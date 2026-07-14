// 자동화 룰 실행 이력 BC REST API 클라이언트 — bare DTO 조회/재실행 + X-XSRF-TOKEN + errorCode 추출 헬퍼 (FR-AT-05 D6/D7)
import { z } from 'zod'
import { apiGet, apiFetch, ApiError } from './client'
import { readXsrfToken } from './sessions'
import { ruleExecutionSummarySchema, ruleExecutionDetailSchema } from './automation-executions.types'
import type { RuleExecutionSummary, RuleExecutionDetail } from './automation-executions.types'

export type {
  RuleExecutionStatus,
  ActionOutcome,
  RuleExecutionSummary,
  RuleExecutionDetail,
} from './automation-executions.types'

/** `fetchRuleExecutions` 목록 조회 옵션 — 전부 선택, 미지정 필드는 쿼리스트링에서 생략 */
export interface FetchRuleExecutionsOptions {
  /** 특정 이슈로 좁힌 실행 이력만 조회 */
  issueKey?: string
  /** 조회 최대 건수 */
  limit?: number
  /** 이 시각(ISO Instant 문자열) 이전 실행만 조회 — 커서 기반 페이지네이션용 */
  before?: string
}

// ─────────────────────────────────────────────────────────────────────────────
// 내부 상수/헬퍼
// ─────────────────────────────────────────────────────────────────────────────

/** 룰별 실행 이력 목록 응답 스키마 — bare 배열(봉투 없음) */
const ruleExecutionSummaryListSchema = z.array(ruleExecutionSummarySchema)

/** 룰별 실행 이력 목록 경로 헬퍼 */
function executionsPath(projectKey: string, ruleId: string): string {
  return `/api/v1/projects/${projectKey}/automation/rules/${ruleId}/executions`
}

/** 실행 이력 단건(trace) 경로 헬퍼 */
function executionPath(id: string): string {
  return `/api/v1/automation/executions/${id}`
}

/**
 * `FetchRuleExecutionsOptions`를 쿼리스트링으로 조립한다.
 * undefined인 필드는 생략하고, 필드가 하나도 없으면 빈 문자열을 반환한다.
 */
function buildExecutionsQuery(opts?: FetchRuleExecutionsOptions): string {
  if (opts === undefined) {
    return ''
  }
  const params = new URLSearchParams()
  if (opts.issueKey !== undefined) {
    params.set('issueKey', opts.issueKey)
  }
  if (opts.limit !== undefined) {
    params.set('limit', String(opts.limit))
  }
  if (opts.before !== undefined) {
    params.set('before', opts.before)
  }
  const query = params.toString()
  return query === '' ? '' : `?${query}`
}

/**
 * mutation 응답이 비-2xx이면 ApiError를 throw한다.
 * replayRuleExecution이 사용하는 에러 표면화 로직을 한 곳에 모은다 (automation-rules.ts 관례 미러).
 */
async function throwIfNotOk(res: Response): Promise<void> {
  if (!res.ok) {
    const errorBody: unknown = await res.json().catch(() => ({}))
    throw new ApiError(res.status, errorBody)
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// API 함수
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 자동화 룰의 실행 이력 목록을 조회한다.
 *
 * GET /api/v1/projects/{projectKey}/automation/rules/{ruleId}/executions → bare 배열(봉투 없음) 그대로 반환.
 * `opts`의 각 필드는 미지정 시 쿼리스트링에서 생략된다.
 *
 * @param projectKey 프로젝트 키
 * @param ruleId 자동화 룰 UUID
 * @param opts issueKey(이슈별 필터) · limit(최대 건수) · before(커서, ISO Instant)
 * @returns RuleExecutionSummary 배열 — 실행 이력이 없으면 빈 배열
 * @throws ApiError(403, AUTOMATION_ACCESS_DENIED) 권한 없음 시
 * @throws ApiError(409, AUTOMATION_RULE_UNAVAILABLE) 룰 미존재/비활성 시
 */
export async function fetchRuleExecutions(
  projectKey: string,
  ruleId: string,
  opts?: FetchRuleExecutionsOptions,
): Promise<RuleExecutionSummary[]> {
  const path = `${executionsPath(projectKey, ruleId)}${buildExecutionsQuery(opts)}`
  return apiGet(path, ruleExecutionSummaryListSchema)
}

/**
 * 실행 이력 단건의 전체 trace를 조회한다 — replay 재료인 triggerEvent 원문과 액션별 결과(outcomes) 포함.
 *
 * GET /api/v1/automation/executions/{id} → bare RuleExecutionDetailResponse 반환.
 *
 * @param id 실행 이력 UUID
 * @returns RuleExecutionDetail
 * @throws ApiError(404, AUTOMATION_EXECUTION_NOT_FOUND) 실행 이력 미존재 시
 * @throws ApiError(403, AUTOMATION_ACCESS_DENIED) 권한 없음 시
 */
export async function fetchRuleExecution(id: string): Promise<RuleExecutionDetail> {
  return apiGet(executionPath(id), ruleExecutionDetailSchema)
}

/**
 * 실행 이력을 재실행(replay)한다 — 저장된 triggerEvent를 룰의 현재 정의로 다시 평가한다.
 *
 * POST /api/v1/automation/executions/{id}/replay, 바디 없음 → 새로 생성된 RuleExecutionDetail 반환.
 * CSRF 방어를 위해 X-XSRF-TOKEN 헤더를 포함한다. 권한: MANAGE_AUTOMATION(PROJECT_ADMIN).
 *
 * @param id 재실행할 실행 이력 UUID
 * @returns 재실행 결과 RuleExecutionDetail (replayedFrom에 원본 id가 채워짐)
 * @throws ApiError(404, AUTOMATION_EXECUTION_NOT_FOUND) 실행 이력 미존재 시
 * @throws ApiError(409, AUTOMATION_RULE_UNAVAILABLE) 원본 룰이 삭제/비활성 상태라 재실행 불가 시
 * @throws ApiError(403, AUTOMATION_ACCESS_DENIED) 권한 없음 시
 */
export async function replayRuleExecution(id: string): Promise<RuleExecutionDetail> {
  const res = await apiFetch(`${executionPath(id)}/replay`, {
    method: 'POST',
    headers: { 'X-XSRF-TOKEN': readXsrfToken() },
  })
  await throwIfNotOk(res)
  const raw: unknown = await res.json()
  return ruleExecutionDetailSchema.parse(raw)
}

// ─────────────────────────────────────────────────────────────────────────────
// 에러 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 에러에서 자동화 실행 이력 BC errorCode를 추출한다.
 *
 * ApiError이면 body.errorCode를 string으로 반환한다.
 * ApiError가 아니거나 errorCode 필드가 없으면 null을 반환한다.
 *
 * @param error 발생한 에러 (unknown)
 * @returns errorCode string 또는 null (예: AUTOMATION_ACCESS_DENIED · AUTOMATION_EXECUTION_NOT_FOUND ·
 *   AUTOMATION_RULE_UNAVAILABLE · AUTOMATION_UNAUTHENTICATED · AUTOMATION_MALFORMED_REQUEST)
 */
export function extractAutomationExecutionErrorCode(error: unknown): string | null {
  if (error instanceof ApiError) {
    const body = error.body as Record<string, unknown> | undefined
    const code = body?.['errorCode']
    return typeof code === 'string' ? code : null
  }
  return null
}
