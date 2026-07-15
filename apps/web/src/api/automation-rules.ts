// 자동화 룰 BC REST API 클라이언트 — bare DTO CRUD + X-XSRF-TOKEN + errorCode 추출 헬퍼 (FR-AT-01 D6)
import { z } from 'zod'
import { apiGet, apiFetch, ApiError } from './client'
import { readXsrfToken } from './sessions'
import {
  automationRuleResponseSchema,
  automationImportResponseSchema,
  createAutomationRuleResponseSchema,
} from './automation-rules.types'
import type {
  AutomationImportResponse,
  AutomationRule,
  CreateAutomationRuleInput,
  CreateAutomationRuleResponse,
  PatchAutomationRuleInput,
} from './automation-rules.types'

export type {
  AutomationImportResponse,
  AutomationRule,
  TriggerType,
  CreateAutomationRuleInput,
  PatchAutomationRuleInput,
  CreateAutomationRuleResponse,
  ImportedWebhookToken,
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

/**
 * mutation 응답이 비-2xx이면 ApiError를 throw한다.
 * create/patch/delete 3개 함수가 공유하는 에러 표면화 로직을 한 곳에 모은다.
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
  await throwIfNotOk(res)
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
  await throwIfNotOk(res)
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
  await throwIfNotOk(res)
}

// ─────────────────────────────────────────────────────────────────────────────
// YAML GitOps export/import (FR-AT-06 D6)
// ─────────────────────────────────────────────────────────────────────────────

/** exportAutomationRulesYaml 반환값 */
export interface ExportAutomationRulesYamlResult {
  /** YAML 본문 Blob */
  blob: Blob
  /** Content-Disposition 헤더에서 파싱한 파일명 */
  filename: string
}

/**
 * GET /api/v1/projects/{projectKey}/automation/rules/export — 전 룰(활성+비활성)을 GitOps YAML로 내보낸다.
 *
 * `exportIssues`(search.ts) 패턴 복제 — apiFetch → non-ok 시 ApiError throw → ok면 res.blob().
 * STATELESS JWT라 `<a href download>` 순수 네비게이션은 401이 된다(Authorization 헤더 미첨부) —
 * 반드시 이 함수로 blob 을 받아 `triggerBlobDownload` 에 넘긴다. CSRF/credentials/401-refresh 는
 * apiFetch 가 처리(raw fetch 금지).
 *
 * @param projectKey 프로젝트 키
 * @returns { blob, filename }
 * @throws ApiError(403, AUTOMATION_ACCESS_DENIED) 권한 없음 시
 */
export async function exportAutomationRulesYaml(projectKey: string): Promise<ExportAutomationRulesYamlResult> {
  const res = await apiFetch(`${basePath(projectKey)}/export`)
  await throwIfNotOk(res)
  // Content-Disposition: attachment; filename="automation-rules-PROJ.yaml"
  const contentDisposition = res.headers.get('content-disposition') ?? ''
  const filename = /filename="([^"]+)"/.exec(contentDisposition)?.[1] ?? `automation-rules-${projectKey}.yaml`
  const blob = await res.blob()
  return { blob, filename }
}

/**
 * POST /api/v1/projects/{projectKey}/automation/rules/import — GitOps YAML을 올려 룰을 일괄 upsert 한다.
 *
 * 백엔드가 `@RequestBody` + `consumes = [application/yaml, application/x-yaml, text/yaml, text/plain]`
 * 이라 **multipart 는 415** 다 — YAML 원문 문자열을 그대로 보낸다(`client.ts` 의 문자열 pass-through 경로).
 * charset 명시는 export 대칭(미명시 시 컨버터 기본 charset 암묵 의존).
 * 원자성은 백엔드가 보장 — 하나라도 실패하면 전량 롤백이라 부분 적용이 없다.
 *
 * @param projectKey 프로젝트 키
 * @param yamlText YAML 원문(파일에서 `File.text()` 로 읽은 문자열)
 * @returns created/updated/total/ruleIds + (있으면) webhookTokens/conflicts
 * @throws ApiError(400, AUTOMATION_IMPORT_INVALID) YAML/스키마버전/projectKey 불일치/커맨드 검증 실패 시
 *   (커맨드 실패면 body 에 0-based `failedIndex` 포함)
 * @throws ApiError(413, AUTOMATION_IMPORT_TOO_LARGE) 본문 1MiB 초과 또는 룰 500개 초과 시
 * @throws ApiError(409, AUTOMATION_RULE_VERSION_CONFLICT) 동시 수정 충돌 시
 * @throws ApiError(403, AUTOMATION_ACCESS_DENIED) 권한 없음 시
 */
export async function importAutomationRulesYaml(
  projectKey: string,
  yamlText: string,
): Promise<AutomationImportResponse> {
  const res = await apiFetch(`${basePath(projectKey)}/import`, {
    method: 'POST',
    body: yamlText,
    headers: { 'Content-Type': 'application/yaml;charset=UTF-8', 'X-XSRF-TOKEN': readXsrfToken() },
  })
  await throwIfNotOk(res)
  const raw: unknown = await res.json()
  return automationImportResponseSchema.parse(raw)
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

/**
 * import 실패 에러에서 0-based `failedIndex`(실패한 룰의 위치)를 안전하게 추출한다.
 *
 * `AUTOMATION_IMPORT_INVALID`(400, C3 커맨드 검증 실패)일 때만 백엔드가 ProblemDetail body에
 * `failedIndex` 를 동봉한다(FR-AT-06 D6). "N번째 룰" 표시는 이 값에 +1 해 소비한다(1-based UI
 * 표시로 변환하는 책임은 소비 측 — 이 함수는 원문 0-based 값만 반환).
 *
 * @param error 발생한 에러 (unknown)
 * @returns failedIndex number 또는 null(ApiError가 아니거나, 필드가 없거나, number가 아닐 시)
 */
export function extractAutomationImportFailedIndex(error: unknown): number | null {
  if (error instanceof ApiError) {
    const body = error.body as Record<string, unknown> | undefined
    const failedIndex = body?.['failedIndex']
    return typeof failedIndex === 'number' ? failedIndex : null
  }
  return null
}
