// 자동화 룰 BC REST API 클라이언트 — bare DTO CRUD + X-XSRF-TOKEN + errorCode 추출 헬퍼 (FR-AT-01 D6)
import { z } from 'zod'
import { apiGet, apiFetch, ApiError } from './client'
import { readXsrfToken } from './sessions'
import {
  automationRuleResponseSchema,
  createAutomationRuleResponseSchema,
} from './automation-rules.types'
import type {
  AutomationRule,
  CreateAutomationRuleInput,
  CreateAutomationRuleResponse,
  PatchAutomationRuleInput,
} from './automation-rules.types'

export type {
  AutomationRule,
  TriggerType,
  CreateAutomationRuleInput,
  PatchAutomationRuleInput,
  CreateAutomationRuleResponse,
} from './automation-rules.types'

// ─────────────────────────────────────────────────────────────────────────────
// 내부 상수
// ─────────────────────────────────────────────────────────────────────────────

/** 자동화 룰 목록 응답 스키마 — bare 배열(봉투 없음) */
const automationRuleListSchema = z.array(automationRuleResponseSchema)

/** 기본 경로 헬퍼 */
function basePath(projectKey: string): string {
  return `/api/v1/projects/${projectKey}/automation/rules`
}

// ─────────────────────────────────────────────────────────────────────────────
// API 함수
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 프로젝트 자동화 룰 목록을 조회한다.
 *
 * GET /api/v1/projects/{projectKey}/automation/rules → bare 배열(봉투 없음) 그대로 반환.
 *
 * @param projectKey 프로젝트 키
 * @returns AutomationRule 배열 — 룰이 없으면 빈 배열
 * @throws ApiError(403, AUTOMATION_ACCESS_DENIED) 권한 없음 시
 */
export async function fetchAutomationRules(projectKey: string): Promise<AutomationRule[]> {
  return apiGet(basePath(projectKey), automationRuleListSchema)
}

/**
 * 자동화 룰 단건을 조회한다. 웹훅 토큰 원문은 이 응답에 포함되지 않는다
 * (WEBHOOK 트리거 생성 응답에서만 1회 노출, hasWebhookToken 플래그만 남는다).
 *
 * GET /api/v1/projects/{projectKey}/automation/rules/{id} → bare AutomationRuleResponse 반환.
 *
 * @param projectKey 프로젝트 키
 * @param id 자동화 룰 UUID
 * @returns AutomationRule
 * @throws ApiError(404, AUTOMATION_RULE_NOT_FOUND) 룰 미존재 시
 */
export async function fetchAutomationRule(projectKey: string, id: string): Promise<AutomationRule> {
  return apiGet(`${basePath(projectKey)}/${id}`, automationRuleResponseSchema)
}

/**
 * 자동화 룰을 생성한다.
 *
 * POST /api/v1/projects/{projectKey}/automation/rules → 201 { rule, webhookToken } 반환.
 * WEBHOOK 트리거면 webhookToken 원문이 이 응답에서만 1회 동봉되고, 그 외에는 null.
 * CSRF 방어를 위해 X-XSRF-TOKEN 헤더를 포함한다. 권한: MANAGE_AUTOMATION(PROJECT_ADMIN).
 *
 * @param projectKey 프로젝트 키
 * @param input name·triggerType·triggerConfig
 * @returns 생성된 rule + webhookToken(WEBHOOK 이외는 null)
 * @throws ApiError(400, AUTOMATION_RULE_INVALID | AUTOMATION_MALFORMED_REQUEST) 요청 형식 오류 시
 * @throws ApiError(403, AUTOMATION_ACCESS_DENIED) 권한 없음 시
 */
export async function createAutomationRule(
  projectKey: string,
  input: CreateAutomationRuleInput,
): Promise<CreateAutomationRuleResponse> {
  const res = await apiFetch(basePath(projectKey), {
    method: 'POST',
    body: input,
    headers: { 'X-XSRF-TOKEN': readXsrfToken() },
  })
  if (!res.ok) {
    const errorBody: unknown = await res.json().catch(() => ({}))
    throw new ApiError(res.status, errorBody)
  }
  const raw: unknown = await res.json()
  return createAutomationRuleResponseSchema.parse(raw)
}

/**
 * 자동화 룰을 부분 수정한다 — name·enabled·triggerConfig.
 *
 * PATCH /api/v1/projects/{projectKey}/automation/rules/{id} → bare AutomationRuleResponse 반환.
 * version은 OCC(낙관적 동시성 제어) 대조용으로 필수, 나머지는 선택(미지정 시 무변경).
 * CSRF 방어를 위해 X-XSRF-TOKEN 헤더를 포함한다.
 *
 * @param projectKey 프로젝트 키
 * @param id 수정할 자동화 룰 UUID
 * @param input version(필수)·name?·enabled?·triggerConfig?
 * @returns 수정된 AutomationRule
 * @throws ApiError(404, AUTOMATION_RULE_NOT_FOUND) 룰 미존재 시
 * @throws ApiError(409, AUTOMATION_RULE_VERSION_CONFLICT) OCC 버전 불일치 시
 */
export async function patchAutomationRule(
  projectKey: string,
  id: string,
  input: PatchAutomationRuleInput,
): Promise<AutomationRule> {
  const res = await apiFetch(`${basePath(projectKey)}/${id}`, {
    method: 'PATCH',
    body: input,
    headers: { 'X-XSRF-TOKEN': readXsrfToken() },
  })
  if (!res.ok) {
    const errorBody: unknown = await res.json().catch(() => ({}))
    throw new ApiError(res.status, errorBody)
  }
  const raw: unknown = await res.json()
  return automationRuleResponseSchema.parse(raw)
}

/**
 * 자동화 룰을 삭제한다(soft delete).
 *
 * DELETE /api/v1/projects/{projectKey}/automation/rules/{id} → 204 No Content.
 * CSRF 방어를 위해 X-XSRF-TOKEN 헤더를 포함한다.
 *
 * @param projectKey 프로젝트 키
 * @param id 삭제할 자동화 룰 UUID
 * @returns void
 * @throws ApiError(404, AUTOMATION_RULE_NOT_FOUND) 룰 미존재 시
 */
export async function deleteAutomationRule(projectKey: string, id: string): Promise<void> {
  const res = await apiFetch(`${basePath(projectKey)}/${id}`, {
    method: 'DELETE',
    headers: { 'X-XSRF-TOKEN': readXsrfToken() },
  })
  if (!res.ok) {
    const errorBody: unknown = await res.json().catch(() => ({}))
    throw new ApiError(res.status, errorBody)
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// 에러 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 에러에서 자동화 룰 BC errorCode를 추출한다.
 *
 * ApiError이면 body.errorCode를 string으로 반환한다.
 * ApiError가 아니거나 errorCode 필드가 없으면 null을 반환한다.
 *
 * @param error 발생한 에러 (unknown)
 * @returns errorCode string 또는 null
 */
export function extractAutomationRuleErrorCode(error: unknown): string | null {
  if (error instanceof ApiError) {
    const body = error.body as Record<string, unknown> | undefined
    const code = body?.['errorCode']
    return typeof code === 'string' ? code : null
  }
  return null
}
