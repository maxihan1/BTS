// FR-AT-07 PR-D Git 웹훅 등록 REST 3매핑 MSW 핸들러 — stateful CRUD + secret 검증 400 + 권한 403
//
// 교훈 반영.
//   - msw-derived-behavior-shared-store-e2e: 파생 응답은 공유 store(git-webhook-fixtures.ts)에서 읽는다
//   - msw-mutation-stateful-refetch: POST/DELETE 후 GET에 즉시 반영
//   - e2e-msw-scenario-toggle-localstorage-flag: 권한 거부 시나리오는 localStorage 플래그로 분기
//   - frontend-api-convention-per-bc: automation 응답은 `{data}` 봉투 없이 bare DTO 직접 반환
//
// backend 계약 정본 — GitWebhookRegistrationController.kt(automation 모듈, FR-AT-07 PR-C Task 11).
//   - POST   /api/v1/projects/{projectKey}/automation/git-webhooks      → 201 CreateGitWebhookResponse
//   - GET    /api/v1/projects/{projectKey}/automation/git-webhooks      → 200 GitWebhookSummaryResponse[]
//   - DELETE /api/v1/projects/{projectKey}/automation/git-webhooks/{id} → 204
//   - secret 검증 실패 → 400 AUTOMATION_GIT_WEBHOOK_SECRET_INVALID (16~4096자·비공백,
//     GitWebhookRegistrationService.kt MIN_SECRET_LENGTH/MAX_SECRET_LENGTH와 1:1 대응)
//   - 권한 없음 → 403 AUTOMATION_ACCESS_DENIED
import { http, HttpResponse } from 'msw'
import type { CreateGitWebhookInput } from '@/api/automation-git-webhooks.types'
import { DEFAULT_GIT_WEBHOOK_CREATOR_ID, generateUuidV4, gitWebhookStore, SCENARIO_KEY } from './git-webhook-fixtures'

// ─────────────────────────────────────────────────────────────────────────────
// 에러 응답 헬퍼 — RFC 7807 ProblemDetail 형태 (automation-rule-handlers.ts 동형 복제)
// `message` 필드 절대 금지 — 백엔드는 `detail` 필드를 사용한다
// ─────────────────────────────────────────────────────────────────────────────

interface ProblemDetail {
  type: string
  title: string
  status: number
  detail: string
  errorCode: string
  timestamp: string
}

function problemDetail(
  status: number,
  type: string,
  title: string,
  errorCode: string,
  detail: string,
): HttpResponse<ProblemDetail> {
  return HttpResponse.json<ProblemDetail>(
    {
      type: `https://bts.example.com/problems/${type}`,
      title,
      status,
      detail,
      errorCode,
      timestamp: new Date().toISOString(),
    },
    { status },
  )
}

/**
 * secret 검증 실패(400) 응답 — detail은 backend 실물 정책 문구를 그대로 미러한다
 * (`GitWebhookRegistrationExceptionHandler.handleSecretInvalid` — 검증 실패 사유와 무관하게
 * 항상 같은 고정 문구를 반환한다. 요청자가 방금 보낸 secret 값을 절대 되돌려 싣지 않는다).
 */
function secretInvalid(): HttpResponse<ProblemDetail> {
  return problemDetail(
    400,
    'automation-git-webhook-secret-invalid',
    'Bad Request',
    'AUTOMATION_GIT_WEBHOOK_SECRET_INVALID',
    'secret 은 공백이 아닌 16자 이상 4096자 이하 문자열이어야 합니다.',
  )
}

/** 권한 거부(403) 응답 — detail은 backend 실물 정책 문구를 그대로 미러한다(일반 메시지, 사유 비노출). */
function accessDenied(): HttpResponse<ProblemDetail> {
  return problemDetail(
    403,
    'automation-git-webhook-forbidden',
    'Forbidden',
    'AUTOMATION_ACCESS_DENIED',
    '이 작업을 수행할 권한이 없습니다.',
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// 내부 헬퍼 — secret 길이/공백 검증 + 원문 토큰/URL 조립
// ─────────────────────────────────────────────────────────────────────────────

/** GitWebhookRegistrationService.kt MIN_SECRET_LENGTH와 1:1 대응 */
const MIN_SECRET_LENGTH = 16
/** GitWebhookRegistrationService.kt MAX_SECRET_LENGTH와 1:1 대응 */
const MAX_SECRET_LENGTH = 4096
/** CreateGitWebhookResponse.kt INBOUND_PATH_PREFIX와 1:1 대응 — origin 없는 절대 경로 */
const INBOUND_PATH_PREFIX = '/api/v1/webhooks/git'

/**
 * secret이 서명 검증 방어선으로 쓸 수 있는 값인지 판정한다(backend validateSecret 동형).
 * trim한 결과로 공백만 판정하고(isBlank 동형), 길이 검사는 원문 길이 그대로 쓴다 —
 * backend가 secret을 trim하지 않는 이유(HMAC 바이트열 그대로 보관)와 같은 이유로 여기서도
 * 원문 길이를 그대로 검사한다.
 */
function isSecretInvalid(secret: string): boolean {
  if (secret.trim().length === 0) {
    return true
  }
  return secret.length < MIN_SECRET_LENGTH || secret.length > MAX_SECRET_LENGTH
}

/** WEBHOOK 원문 URL 토큰을 발급한다(automation-rule-handlers.ts generateWebhookToken 동형). */
function generateRawToken(): string {
  return `whk_${generateUuidV4().replace(/-/g, '')}`
}

/**
 * SCENARIO_KEY.FORBIDDEN 플래그가 켜져 있는지 판정한다
 * (automation-rule-fixtures.ts withConflictsFlag 판정 동형).
 */
function isForbiddenFlagOn(): boolean {
  return globalThis.localStorage?.getItem(SCENARIO_KEY.FORBIDDEN) === 'true'
}

// ─────────────────────────────────────────────────────────────────────────────
// POST /api/v1/projects/:projectKey/automation/git-webhooks
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Git 웹훅 등록.
 * secret이 16~4096자 범위를 벗어나거나 공백뿐이면 400 AUTOMATION_GIT_WEBHOOK_SECRET_INVALID.
 * 성공 → 201 { id, provider, webhookUrl, token } — token은 이 응답에서만 노출되는 1회성 원문.
 * store에는 token·secret을 저장하지 않는다(GitWebhookSummary 계약에 그 필드가 없다).
 */
const createGitWebhookHandler = http.post(
  '/api/v1/projects/:projectKey/automation/git-webhooks',
  async ({ params, request }) => {
    const projectKey = params['projectKey'] as string
    const body = (await request.json()) as CreateGitWebhookInput

    if (isSecretInvalid(body.secret)) {
      return secretInvalid()
    }

    const id = generateUuidV4()
    const token = generateRawToken()

    gitWebhookStore.set(id, {
      id,
      provider: body.provider,
      createdAt: new Date().toISOString(),
      createdBy: DEFAULT_GIT_WEBHOOK_CREATOR_ID,
      projectKey,
    })

    return HttpResponse.json(
      { id, provider: body.provider, webhookUrl: `${INBOUND_PATH_PREFIX}/${token}`, token },
      { status: 201 },
    )
  },
)

// ─────────────────────────────────────────────────────────────────────────────
// GET /api/v1/projects/:projectKey/automation/git-webhooks
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 프로젝트 Git 웹훅 목록 조회(token·secret 미포함).
 * SCENARIO_KEY.FORBIDDEN 플래그가 'true'이면 store 내용과 무관하게 403을 반환한다
 * (FR12 error 분기 재현용).
 * 성공 → 200 GitWebhookSummary[] (봉투 없음, bare 배열)
 */
const listGitWebhooksHandler = http.get(
  '/api/v1/projects/:projectKey/automation/git-webhooks',
  ({ params }) => {
    if (isForbiddenFlagOn()) {
      return accessDenied()
    }

    const projectKey = params['projectKey'] as string
    const items = Array.from(gitWebhookStore.values())
      .filter((webhook) => webhook.projectKey === projectKey)
      .map(({ id, provider, createdAt, createdBy }) => ({ id, provider, createdAt, createdBy }))

    return HttpResponse.json(items)
  },
)

// ─────────────────────────────────────────────────────────────────────────────
// DELETE /api/v1/projects/:projectKey/automation/git-webhooks/:id
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Git 웹훅 삭제(soft delete — mock에서는 store에서 즉시 제거).
 * 존재하지 않거나 다른 프로젝트 소속이면(경로 projectKey와 불일치) 조용히 무시하지 않고도
 * 결과적으로 store에서 사라진 상태와 동일하므로 별도 404 분기 없이 204를 반환한다
 * (등록 자체가 없는 id를 지우는 호출은 이미 원하는 최종 상태에 도달했다는 관점).
 * 성공 → 204 No Content
 */
const deleteGitWebhookHandler = http.delete(
  '/api/v1/projects/:projectKey/automation/git-webhooks/:id',
  ({ params }) => {
    const id = params['id'] as string
    gitWebhookStore.delete(id)
    return new HttpResponse(null, { status: 204 })
  },
)

// ─────────────────────────────────────────────────────────────────────────────
// Export
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Git 웹훅 등록 BC MSW 핸들러 배열(FR-AT-07 PR-D).
 *
 * ★ 순서 주의 — `DELETE .../git-webhooks/:id`가 `GET .../git-webhooks`(목록)를 가리지 않도록
 * 구체 경로(`listGitWebhooksHandler`)를 `:id` 와일드카드 경로보다 먼저 등록한다
 * (automation-rule-handlers.ts export/import vs :id 배치 관례와 동형 — msw는 배열 순서대로
 * 첫 매칭 핸들러를 채택한다). GET과 DELETE는 서로 다른 HTTP 메서드라 실질적으로 경로가
 * 충돌하지는 않지만, 향후 GET 단건(`:id`) 핸들러가 추가될 가능성을 고려해 목록 GET을 앞에 둔다.
 */
export const gitWebhookHandlers = [createGitWebhookHandler, listGitWebhooksHandler, deleteGitWebhookHandler]
