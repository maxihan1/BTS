// FR-AT-05 D6/D7 자동화 룰 실행 이력 MSW 핸들러 — stateful 룰별 목록/단건 trace/replay(재실행 복제)
//
// 교훈 반영.
//   - msw-derived-behavior-shared-store-e2e: 목록 요약은 공유 store(automation-execution-fixtures.ts)에서 파생 계산한다
//   - msw-mutation-stateful-refetch: replay POST 후 목록 GET에 즉시 반영(store에 add — 캐시 통째 덮지 않음)
//   - e2e-msw-scenario-toggle-localstorage-flag: 빈 목록/replay 실패 시나리오는 localStorage 플래그로 분기
//   - frontend-api-convention-per-bc: automation 응답은 `{data}` 봉투 없이 bare DTO 직접 반환
//   - msw-global-handler-registration-gap: handlers.ts 전역 등록 누락 시 E2E에서만 누출 — 반드시 등록
//   - frontend-zod-backend-dto-contract-gap: `ruleExecutionDetailSchema`는 base 스키마 분리로 backend
//     `RuleExecutionDetailResponse`(actionCount/successCount 없음·outcomes만)와 정합한다. 단건/replay 응답은
//     store 레코드를 `toWireDetail`로 방어적 필드 프로젝션해 반환한다(summary 집계는 `toSummary`가 계산).
//
// ✅ 이중 dispatch 방어(단발 캐시)를 **제거했다** (2026-08-12 · PR #375).
//
// 원인이 사라졌기 때문이다. 그 현상은 msw 버그가 아니라 **로컬 `setupServer` 인스턴스가 전역과
// 동시에 listen** 해서 같은 요청이 두 번 배달되던 것이었고(고유 requestId 는 1개), 이 PR 이
// 로컬 인스턴스를 전량 걷어냈다. `src/test/msw-single-setupserver.test.ts` 가 재발을 막는다.
//
// 제거 전 이 파일은 `requestId` 기반 단발 캐시(`lastReplayRequestId`/`lastReplayResponseBody`)로
// 두 번째(phantom) 호출이 store 를 중복 mutate 하지 않도록 방어했다. replay 엔드포인트는 실제
// backend 계약대로 요청 바디가 없어, 바디를 읽는 핸들러들이 얻던 「Body already read」 자연
// 방어를 못 받는 자리였다.
//
// **제거가 안전함을 실측했다** — 관련 18파일 292 테스트가 그대로 통과한다. 원인이 남아 있었다면
// replay 가 store 를 두 번 mutate 해 즉시 red 가 됐을 자리다.
//
import { http, HttpResponse } from 'msw'
import type { RuleExecutionDetail, RuleExecutionSummary } from '@/api/automation-executions.types'
import { generateUuidV4 } from './automation-rule-fixtures'
import { executionStore, SCENARIO_KEY } from './automation-execution-fixtures'

// ─────────────────────────────────────────────────────────────────────────────
// 에러 응답 헬퍼 — RFC 7807 ProblemDetail 형태 (automation-rule-handlers.ts problemDetail 동형)
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

/** backend `RuleExecutionErrorCodes.EXECUTION_NOT_FOUND` 동일 코드 — 미존재/타 프로젝트 소속 모두 존재 숨김(404). */
function executionNotFound(id: string): HttpResponse<ProblemDetail> {
  return problemDetail(
    404,
    'automation-execution-not-found',
    'Not Found',
    'AUTOMATION_EXECUTION_NOT_FOUND',
    `실행 이력을 찾을 수 없습니다: ${id}`,
  )
}

/** backend `RuleExecutionErrorCodes.RULE_UNAVAILABLE` 동일 코드 — replay 대상 룰이 소프트 삭제/부재. */
function ruleUnavailable(id: string): HttpResponse<ProblemDetail> {
  return problemDetail(
    409,
    'automation-execution-rule-unavailable',
    'Conflict',
    'AUTOMATION_RULE_UNAVAILABLE',
    `재실행 대상 자동화 룰을 더 이상 사용할 수 없습니다: ${id}`,
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// 내부 헬퍼 — 상세 → 목록 요약 변환 + limit clamp (backend `AutomationExecutionController` 상수 동형)
// ─────────────────────────────────────────────────────────────────────────────

const DEFAULT_LIMIT = 50
const MIN_LIMIT = 1
const MAX_LIMIT = 200

/**
 * 상세/replay 응답을 store 레코드에서 방어적으로 복사해 반환한다 — backend `RuleExecutionDetailResponse`
 * (`RuleExecutionResponses.kt`) 필드 프로젝션. `RuleExecutionDetail`이 이미 wire 계약과 1:1이라(스키마 base
 * 분리 완료) 별도 Omit 타입은 두지 않고, 명시 필드 복사로 우발적 필드 누출만 막는다.
 */
function toWireDetail(detail: RuleExecutionDetail): RuleExecutionDetail {
  return {
    id: detail.id,
    ruleId: detail.ruleId,
    projectKey: detail.projectKey,
    triggerType: detail.triggerType,
    triggerEvent: detail.triggerEvent,
    issueKey: detail.issueKey,
    status: detail.status,
    outcomes: detail.outcomes,
    replayedFrom: detail.replayedFrom,
    startedAt: detail.startedAt,
    finishedAt: detail.finishedAt,
  }
}

/**
 * 상세 레코드에서 목록 요약 필드만 추출한다(outcomes/triggerEvent/projectKey 제외,
 * actionCount/successCount 는 outcomes 를 집계한 값) — backend `RuleExecutionSummaryResponse.from` 동형.
 */
function toSummary(detail: RuleExecutionDetail): RuleExecutionSummary {
  return {
    id: detail.id,
    ruleId: detail.ruleId,
    triggerType: detail.triggerType,
    issueKey: detail.issueKey,
    status: detail.status,
    actionCount: detail.outcomes.length,
    successCount: detail.outcomes.filter((outcome) => outcome.success).length,
    startedAt: detail.startedAt,
    finishedAt: detail.finishedAt,
    replayedFrom: detail.replayedFrom,
  }
}

/** 쿼리 파라미터 `limit` 을 backend clamp 규칙(1~200, 기본 50)과 동일하게 정규화한다. */
function clampLimit(raw: string | null): number {
  const parsed = raw === null ? DEFAULT_LIMIT : Number.parseInt(raw, 10)
  const value = Number.isNaN(parsed) ? DEFAULT_LIMIT : parsed
  return Math.min(Math.max(value, MIN_LIMIT), MAX_LIMIT)
}

// ─────────────────────────────────────────────────────────────────────────────
// GET /api/v1/projects/:projectKey/automation/rules/:ruleId/executions
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 룰별 실행 이력 목록 — 최신순(`startedAt` desc, `id` desc tiebreaker, backend `ORDER BY started_at DESC,
 * id DESC` 동형) 정렬 후 issueKey/before(keyset 커서, exclusive)/limit 필터를 적용한다.
 * SCENARIO_KEY.EMPTY_EXECUTIONS 플래그가 'true'이면 store 내용과 무관하게 빈 배열을 반환한다
 * (빈 상태 CTA E2E 결정적 재현용).
 * 성공 → 200 RuleExecutionSummary[] (봉투 없음, bare 배열)
 */
const listExecutionsHandler = http.get(
  '/api/v1/projects/:projectKey/automation/rules/:ruleId/executions',
  ({ params, request }) => {
    const projectKey = params['projectKey'] as string
    const ruleId = params['ruleId'] as string

    if (globalThis.localStorage?.getItem(SCENARIO_KEY.EMPTY_EXECUTIONS) === 'true') {
      return HttpResponse.json([])
    }

    const url = new URL(request.url)
    const issueKey = url.searchParams.get('issueKey')
    const before = url.searchParams.get('before')
    const limit = clampLimit(url.searchParams.get('limit'))
    const beforeMs = before !== null ? new Date(before).getTime() : null

    const filtered = Array.from(executionStore.values()).filter((execution) => {
      if (execution.ruleId !== ruleId || execution.projectKey !== projectKey) return false
      if (issueKey !== null && execution.issueKey !== issueKey) return false
      if (beforeMs !== null && new Date(execution.startedAt).getTime() >= beforeMs) return false
      return true
    })

    const sorted = filtered.sort((a, b) => {
      const startedDiff = new Date(b.startedAt).getTime() - new Date(a.startedAt).getTime()
      return startedDiff !== 0 ? startedDiff : b.id.localeCompare(a.id)
    })

    return HttpResponse.json(sorted.slice(0, limit).map(toSummary))
  },
)

// ─────────────────────────────────────────────────────────────────────────────
// GET /api/v1/automation/executions/:id
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 실행 이력 단건 trace 조회. store에 없으면 404(존재 숨김).
 * 성공 → 200 RuleExecutionDetail (봉투 없음, outcomes/triggerEvent 포함)
 */
const getExecutionHandler = http.get('/api/v1/automation/executions/:id', ({ params }) => {
  const id = params['id'] as string
  const found = executionStore.get(id)
  if (found === undefined) {
    return executionNotFound(id)
  }
  return HttpResponse.json(toWireDetail(found))
})

// ─────────────────────────────────────────────────────────────────────────────
// POST /api/v1/automation/executions/:id/replay
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 실행 이력 재실행(replay) — 원본을 복제해 새 id·새(store 내 기존 최신값보다 더 최신) startedAt·
 * replayedFrom=원본id 로 새 실행 이력을 store에 추가한다(stateful — 이후 목록 GET에 즉시 반영,
 * msw-mutation-stateful-refetch. 캐시를 통째 덮지 않고 store 에 add 만 한다).
 *
 * 에러 분기 순서 (backend `RuleExecutionService.replay` 동형 — record 먼저 조회, 그 다음 룰 가용성).
 * 1. 원본 id 미존재 → 404 AUTOMATION_EXECUTION_NOT_FOUND
 * 2. SCENARIO_KEY.RULE_UNAVAILABLE 플래그가 'true' → 409 AUTOMATION_RULE_UNAVAILABLE(store 변경 없음)
 * 성공 → 200 RuleExecutionDetail (신규 자원 생성이어도 backend 컨트롤러가 `ResponseEntity.ok` 를
 * 쓰므로 201 이 아니라 200).
 *
 */
const replayExecutionHandler = http.post(
  '/api/v1/automation/executions/:id/replay',
  ({ params }) => {
    const id = params['id'] as string
    const original = executionStore.get(id)
    if (original === undefined) {
      return executionNotFound(id)
    }

    if (globalThis.localStorage?.getItem(SCENARIO_KEY.RULE_UNAVAILABLE) === 'true') {
      return ruleUnavailable(id)
    }

    const storedTimes = Array.from(executionStore.values()).map((execution) =>
      new Date(execution.startedAt).getTime(),
    )
    const latestMs = storedTimes.length > 0 ? Math.max(...storedTimes) : Date.now()
    const startedAt = new Date(latestMs + 1000).toISOString()
    const finishedAt = new Date(latestMs + 2000).toISOString()

    const replayed: RuleExecutionDetail = {
      ...original,
      id: generateUuidV4(),
      replayedFrom: original.id,
      startedAt,
      finishedAt,
    }

    executionStore.set(replayed.id, replayed)

    const wireBody = toWireDetail(replayed)

    return HttpResponse.json(wireBody)
  },
)

// ─────────────────────────────────────────────────────────────────────────────
// Export
// ─────────────────────────────────────────────────────────────────────────────

/** 자동화 룰 실행 이력 BC MSW 핸들러 배열 */
export const automationExecutionHandlers = [
  listExecutionsHandler,
  getExecutionHandler,
  replayExecutionHandler,
]
