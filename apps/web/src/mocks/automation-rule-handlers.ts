// FR-AT-01 D6 자동화 룰 MSW 핸들러 — stateful CRUD + OCC 409 + WEBHOOK 토큰 1회 발급
//
// 교훈 반영.
//   - msw-derived-behavior-shared-store-e2e: 파생 응답은 공유 store(automation-rule-fixtures.ts)에서 읽는다
//   - msw-mutation-stateful-refetch: POST/PATCH/DELETE 후 GET에 즉시 반영
//   - e2e-msw-scenario-toggle-localstorage-flag: 빈 목록 시나리오는 localStorage 플래그로 분기
//   - frontend-api-convention-per-bc: automation 응답은 `{data}` 봉투 없이 bare DTO 직접 반환
//
import { http, HttpResponse } from 'msw'
import type {
  ActionRequestInput,
  ActionResponse,
  AutomationRule,
  CreateAutomationRuleInput,
  PatchAutomationRuleInput,
  TriggerType,
} from '@/api/automation-rules.types'
import { DEFAULT_AUTOMATION_ACTOR_ID, generateUuidV4, ruleStore, SCENARIO_KEY } from './automation-rule-fixtures'

// ─────────────────────────────────────────────────────────────────────────────
// 에러 응답 헬퍼 — RFC 7807 ProblemDetail 형태 (version-handlers.ts 동형)
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

function automationRuleNotFound(id: string): HttpResponse<ProblemDetail> {
  return problemDetail(
    404,
    'automation-rule-not-found',
    'Automation Rule Not Found',
    'AUTOMATION_RULE_NOT_FOUND',
    `자동화 룰을 찾을 수 없습니다: ${id}`,
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// 소유권 조회 헬퍼 — get/patch/delete 3개 핸들러가 공유하는 404 분기를 한 곳에 모은다
// ─────────────────────────────────────────────────────────────────────────────

/** findOwnedRule 결과 — 조회 성공 또는 404 응답 중 하나 */
type OwnedRuleLookup =
  | { readonly ok: true; readonly rule: AutomationRule }
  | { readonly ok: false; readonly response: HttpResponse<ProblemDetail> }

/**
 * store에서 지정한 프로젝트에 소속된 룰을 조회한다.
 * 룰이 없거나 다른 프로젝트 소속이면(경로의 projectKey와 불일치) 404 응답을 담아 반환한다.
 *
 * @param id 조회할 룰 UUID
 * @param projectKey 요청 경로의 프로젝트 키
 */
function findOwnedRule(id: string, projectKey: string): OwnedRuleLookup {
  const stored = ruleStore.get(id)
  if (stored === undefined || stored.projectKey !== projectKey) {
    return { ok: false, response: automationRuleNotFound(id) }
  }
  return { ok: true, rule: stored }
}

// ─────────────────────────────────────────────────────────────────────────────
// 내부 헬퍼 — WEBHOOK 토큰 원문 생성 + SCHEDULED 다음 발화 시각 계산
// ─────────────────────────────────────────────────────────────────────────────

/**
 * WEBHOOK 트리거 생성 시 1회 노출용 원문 토큰을 발급한다.
 * store에는 저장하지 않는다 — hasWebhookToken 플래그만 보관하고 원문은 응답에만 실린다.
 */
function generateWebhookToken(): string {
  return `whk_${generateUuidV4().replace(/-/g, '')}`
}

/**
 * SCHEDULED 트리거의 다음 발화 예정 시각을 계산한다.
 * 실제 cron 파싱은 하지 않는 모의 값 — 기준 시각(from) 24시간 뒤 ISO Instant.
 * SCHEDULED가 아니면 항상 null(백엔드 nextFireAt 정책과 동일).
 *
 * @param triggerType 룰의 트리거 타입
 * @param from 계산 기준 시각
 */
function computeNextFireAt(triggerType: TriggerType, from: Date): string | null {
  if (triggerType !== 'SCHEDULED') {
    return null
  }
  return new Date(from.getTime() + 24 * 60 * 60 * 1000).toISOString()
}

// ─────────────────────────────────────────────────────────────────────────────
// 내부 헬퍼 — 액션 config 비대칭 변환 (FR-AT-02, EC1)
//
// 요청 ActionRequest.config는 JSON 문자열, 응답 ActionResponse.config는 객체다
// (automation-rules.types.ts parseActionConfig/serializeActionConfig 선례와 동일 비대칭).
// mock은 실제 백엔드 저장소를 흉내내므로 요청을 저장 시점에 파싱해 응답 객체로 echo한다.
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 요청 액션의 config JSON 문자열을 객체로 파싱한다.
 * 파싱 실패이거나 객체가 아니면(배열·원시값 포함) 빈 객체로 안전하게 폴백한다
 * (§1.13 빈 catch 금지 — 최소한 콘솔 로그를 남긴다, automation-rules.types.ts parseBaseConfig 선례 동형).
 */
function parseActionRequestConfig(configJson: string): Record<string, unknown> {
  try {
    const parsed: unknown = JSON.parse(configJson)
    if (typeof parsed !== 'object' || parsed === null || Array.isArray(parsed)) {
      return {}
    }
    return parsed as Record<string, unknown>
  } catch (error) {
    console.error('automation action config 파싱 실패 — 빈 객체로 폴백', error)
    return {}
  }
}

/** 요청 액션 목록(config=JSON 문자열)을 응답 액션 목록(config=객체)으로 변환한다. */
function toActionResponses(actions: ActionRequestInput[]): ActionResponse[] {
  return actions.map((action) => ({
    type: action.type,
    config: parseActionRequestConfig(action.config),
  }))
}

// ─────────────────────────────────────────────────────────────────────────────
// GET /api/v1/projects/:projectKey/automation/rules
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 프로젝트 자동화 룰 목록 조회.
 * SCENARIO_KEY.EMPTY_LIST 플래그가 'true'이면 store 내용과 무관하게 빈 배열을 반환한다
 * (빈 상태 CTA E2E 결정적 재현용).
 * 성공 → 200 AutomationRuleResponse[] (봉투 없음, bare 배열)
 */
const listRulesHandler = http.get('/api/v1/projects/:projectKey/automation/rules', ({ params }) => {
  const projectKey = params['projectKey'] as string

  if (globalThis.localStorage?.getItem(SCENARIO_KEY.EMPTY_LIST) === 'true') {
    return HttpResponse.json([])
  }

  const items = Array.from(ruleStore.values()).filter((rule) => rule.projectKey === projectKey)
  return HttpResponse.json(items)
})

// ─────────────────────────────────────────────────────────────────────────────
// POST /api/v1/projects/:projectKey/automation/rules
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 자동화 룰 생성.
 * triggerType이 WEBHOOK이면 원문 토큰을 이 응답에서만 1회 동봉하고, 그 외에는 null.
 * 생성된 룰은 항상 enabled=true, version=1로 시작한다(백엔드 고정값).
 * actions 미지정 시 빈 배열(EC5 — 트리거만 있는 룰도 유효), actorUserId 미지정 시
 * 생성자(DEFAULT_AUTOMATION_ACTOR_ID)로 폴백한다(FR8 — 백엔드 생성자 폴백 규약과 동형).
 * 성공 → 201 { rule: AutomationRuleResponse, webhookToken: string | null }
 */
const createRuleHandler = http.post(
  '/api/v1/projects/:projectKey/automation/rules',
  async ({ params, request }) => {
    const projectKey = params['projectKey'] as string
    const body = (await request.json()) as CreateAutomationRuleInput

    const now = new Date()
    const isWebhook = body.triggerType === 'WEBHOOK'

    const newRule: AutomationRule = {
      id: generateUuidV4(),
      projectKey,
      name: body.name,
      enabled: true,
      triggerType: body.triggerType,
      triggerConfig: body.triggerConfig,
      actions: body.actions !== undefined ? toActionResponses(body.actions) : [],
      actorUserId: body.actorUserId ?? DEFAULT_AUTOMATION_ACTOR_ID,
      hasWebhookToken: isWebhook,
      nextFireAt: computeNextFireAt(body.triggerType, now),
      createdBy: DEFAULT_AUTOMATION_ACTOR_ID,
      createdAt: now.toISOString(),
      updatedAt: now.toISOString(),
      version: 1,
    }

    ruleStore.set(newRule.id, newRule)

    const webhookToken = isWebhook ? generateWebhookToken() : null

    return HttpResponse.json({ rule: newRule, webhookToken }, { status: 201 })
  },
)

// ─────────────────────────────────────────────────────────────────────────────
// GET /api/v1/projects/:projectKey/automation/rules/:id
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 자동화 룰 단건 조회. store에 없거나 다른 프로젝트 소속이면 404.
 * 성공 → 200 AutomationRuleResponse (봉투 없음)
 */
const getRuleHandler = http.get(
  '/api/v1/projects/:projectKey/automation/rules/:id',
  ({ params }) => {
    const projectKey = params['projectKey'] as string
    const id = params['id'] as string

    const found = findOwnedRule(id, projectKey)
    if (!found.ok) {
      return found.response
    }

    return HttpResponse.json(found.rule)
  },
)

// ─────────────────────────────────────────────────────────────────────────────
// PATCH /api/v1/projects/:projectKey/automation/rules/:id
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 자동화 룰 부분 수정 — name·enabled·triggerConfig·actions·actorUserId.
 *
 * 에러 분기 순서 (백엔드와 동일).
 * 1. 룰 미존재 또는 다른 프로젝트 소속 → 404 AUTOMATION_RULE_NOT_FOUND
 * 2. body.version이 현재 저장된 version과 다름(OCC 충돌) → 409 AUTOMATION_RULE_VERSION_CONFLICT
 * 성공 → 200 AutomationRuleResponse (version +1, updatedAt 갱신)
 *
 * triggerConfig가 실제로 변경되고 트리거 타입이 SCHEDULED이면 nextFireAt을 재계산한다.
 * actions는 지정 시 **전체 교체**(부분 병합 아님, backend PatchAutomationRuleRequest 동일 컨벤션) —
 * 미지정이면 기존 액션을 그대로 유지한다. actorUserId도 미지정이면 기존 값을 유지한다.
 */
const patchRuleHandler = http.patch(
  '/api/v1/projects/:projectKey/automation/rules/:id',
  async ({ params, request }) => {
    const projectKey = params['projectKey'] as string
    const id = params['id'] as string

    const found = findOwnedRule(id, projectKey)
    if (!found.ok) {
      return found.response
    }
    const stored = found.rule

    const body = (await request.json()) as PatchAutomationRuleInput

    if (body.version !== stored.version) {
      return problemDetail(
        409,
        'automation-rule-version-conflict',
        'Automation Rule Version Conflict',
        'AUTOMATION_RULE_VERSION_CONFLICT',
        `버전이 일치하지 않습니다. 최신 정보를 다시 불러온 뒤 시도하세요: ${id}`,
      )
    }

    const now = new Date()
    const triggerConfig = body.triggerConfig ?? stored.triggerConfig
    const triggerConfigChanged = triggerConfig !== stored.triggerConfig

    const updated: AutomationRule = {
      ...stored,
      name: body.name ?? stored.name,
      enabled: body.enabled ?? stored.enabled,
      triggerConfig,
      actions: body.actions !== undefined ? toActionResponses(body.actions) : stored.actions,
      actorUserId: body.actorUserId ?? stored.actorUserId,
      nextFireAt: triggerConfigChanged
        ? computeNextFireAt(stored.triggerType, now)
        : stored.nextFireAt,
      updatedAt: now.toISOString(),
      version: stored.version + 1,
    }

    ruleStore.set(id, updated)

    return HttpResponse.json(updated)
  },
)

// ─────────────────────────────────────────────────────────────────────────────
// DELETE /api/v1/projects/:projectKey/automation/rules/:id
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 자동화 룰 삭제(soft delete — mock에서는 store에서 즉시 제거).
 * 룰 미존재 또는 다른 프로젝트 소속이면 404.
 * 성공 → 204 No Content
 */
const deleteRuleHandler = http.delete(
  '/api/v1/projects/:projectKey/automation/rules/:id',
  ({ params }) => {
    const projectKey = params['projectKey'] as string
    const id = params['id'] as string

    const found = findOwnedRule(id, projectKey)
    if (!found.ok) {
      return found.response
    }

    ruleStore.delete(id)
    return new HttpResponse(null, { status: 204 })
  },
)

// ─────────────────────────────────────────────────────────────────────────────
// Export
// ─────────────────────────────────────────────────────────────────────────────

/** 자동화 룰 BC MSW 핸들러 배열 */
export const automationRuleHandlers = [
  listRulesHandler,
  createRuleHandler,
  getRuleHandler,
  patchRuleHandler,
  deleteRuleHandler,
]
