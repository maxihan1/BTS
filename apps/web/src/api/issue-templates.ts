// 이슈 템플릿 BC REST API 클라이언트 — CRUD + X-XSRF-TOKEN + errorCode 추출 헬퍼 (FR-TM-01)
import { z } from 'zod'
import { apiGet, apiFetch, ApiError } from './client'
import { readXsrfToken } from './sessions'
import {
  issueTemplateResponseSchema,
  dataResponseSchema,
  type IssueTemplate,
  type CreateIssueTemplateInput,
  type UpdateIssueTemplateInput,
} from './issue-templates.types'

export type { IssueTemplate, CreateIssueTemplateInput, UpdateIssueTemplateInput } from './issue-templates.types'

// ─────────────────────────────────────────────────────────────────────────────
// 내부 상수
// ─────────────────────────────────────────────────────────────────────────────

/** 이슈 템플릿 단건 + 목록 응답 래퍼 스키마 — 내부 전용 */
const templateDataSchema = dataResponseSchema(issueTemplateResponseSchema)
const templateListDataSchema = dataResponseSchema(z.array(issueTemplateResponseSchema))

/** 기본 경로 헬퍼 */
function basePath(projectIdOrKey: string): string {
  return `/api/v1/projects/${projectIdOrKey}/issue-templates`
}

// ─────────────────────────────────────────────────────────────────────────────
// API 함수
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 프로젝트 이슈 템플릿 목록을 조회한다.
 *
 * GET /api/v1/projects/{projectIdOrKey}/issue-templates → `{ data: [...] }` 언래핑 후 반환.
 * 권한 게이트 없음 — 프로젝트 조회 권한으로 충족.
 *
 * @param projectIdOrKey 프로젝트 UUID 또는 키
 * @returns IssueTemplate 배열 — 템플릿이 없으면 빈 배열
 * @throws ApiError(404) 프로젝트 미존재 시
 */
export async function fetchIssueTemplates(projectIdOrKey: string): Promise<IssueTemplate[]> {
  const wrapped = await apiGet(basePath(projectIdOrKey), templateListDataSchema)
  return wrapped.data
}

/**
 * 이슈 템플릿 단건을 조회한다.
 *
 * GET /api/v1/projects/{projectIdOrKey}/issue-templates/{templateId} → `{ data: ... }` 언래핑 후 반환.
 *
 * @param projectIdOrKey 프로젝트 UUID 또는 키
 * @param templateId 이슈 템플릿 UUID
 * @returns IssueTemplate
 * @throws ApiError(404) 템플릿 미존재 시
 */
export async function fetchIssueTemplate(projectIdOrKey: string, templateId: string): Promise<IssueTemplate> {
  const wrapped = await apiGet(`${basePath(projectIdOrKey)}/${templateId}`, templateDataSchema)
  return wrapped.data
}

/**
 * 이슈 템플릿을 생성한다.
 *
 * POST /api/v1/projects/{projectIdOrKey}/issue-templates → 201 `{ data: ... }` 언래핑 후 반환.
 * CSRF 방어를 위해 X-XSRF-TOKEN 헤더를 포함한다.
 * 권한: MANAGE_TEMPLATES.
 *
 * @param projectIdOrKey 프로젝트 UUID 또는 키
 * @param input issueTypeId·name·content 필수
 * @returns 생성된 IssueTemplate
 * @throws ApiError(409, ISSUE_TEMPLATE_DUPLICATE) 동일 issueTypeId 중복 시
 * @throws ApiError(403, ISSUE_TEMPLATE_ACCESS_DENIED) 권한 없음 시
 */
export async function createIssueTemplate(
  projectIdOrKey: string,
  input: CreateIssueTemplateInput,
): Promise<IssueTemplate> {
  const res = await apiFetch(basePath(projectIdOrKey), {
    method: 'POST',
    body: input,
    headers: { 'X-XSRF-TOKEN': readXsrfToken() },
  })
  if (!res.ok) {
    const errorBody: unknown = await res.json().catch(() => ({}))
    throw new ApiError(res.status, errorBody)
  }
  const raw: unknown = await res.json()
  const wrapped = templateDataSchema.parse(raw)
  return wrapped.data
}

/**
 * 이슈 템플릿을 수정한다 (issueTypeId는 불변).
 *
 * PATCH /api/v1/projects/{projectIdOrKey}/issue-templates/{templateId} → `{ data: ... }` 언래핑 후 반환.
 * CSRF 방어를 위해 X-XSRF-TOKEN 헤더를 포함한다.
 *
 * @param projectIdOrKey 프로젝트 UUID 또는 키
 * @param templateId 이슈 템플릿 UUID
 * @param input name·content(전부 선택, null/생략=무변경)
 * @returns 수정된 IssueTemplate
 * @throws ApiError(404, ISSUE_TEMPLATE_NOT_FOUND) 템플릿 미존재 시
 * @throws ApiError(403, ISSUE_TEMPLATE_ACCESS_DENIED) 권한 없음 시
 */
export async function updateIssueTemplate(
  projectIdOrKey: string,
  templateId: string,
  input: UpdateIssueTemplateInput,
): Promise<IssueTemplate> {
  const res = await apiFetch(`${basePath(projectIdOrKey)}/${templateId}`, {
    method: 'PATCH',
    body: input,
    headers: { 'X-XSRF-TOKEN': readXsrfToken() },
  })
  if (!res.ok) {
    const errorBody: unknown = await res.json().catch(() => ({}))
    throw new ApiError(res.status, errorBody)
  }
  const raw: unknown = await res.json()
  const wrapped = templateDataSchema.parse(raw)
  return wrapped.data
}

/**
 * 이슈 템플릿을 삭제한다.
 *
 * DELETE /api/v1/projects/{projectIdOrKey}/issue-templates/{templateId} → 204 No Content.
 * CSRF 방어를 위해 X-XSRF-TOKEN 헤더를 포함한다.
 *
 * @param projectIdOrKey 프로젝트 UUID 또는 키
 * @param templateId 삭제할 이슈 템플릿 UUID
 * @returns void
 * @throws ApiError(404, ISSUE_TEMPLATE_NOT_FOUND) 템플릿 미존재 시
 * @throws ApiError(403, ISSUE_TEMPLATE_ACCESS_DENIED) 권한 없음 시
 */
export async function deleteIssueTemplate(projectIdOrKey: string, templateId: string): Promise<void> {
  const res = await apiFetch(`${basePath(projectIdOrKey)}/${templateId}`, {
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
 * 에러에서 이슈 템플릿 BC errorCode 를 추출한다.
 *
 * ApiError 이면 body.errorCode 를 string 으로 반환한다.
 * ApiError 가 아니거나 errorCode 필드가 없으면 null 을 반환한다.
 *
 * wire 값 목록 (backend ProblemDetail.errorCode):
 * - ISSUE_TEMPLATE_PROJECT_NOT_FOUND (404)
 * - ISSUE_TEMPLATE_NOT_FOUND (404)
 * - ISSUE_TEMPLATE_ISSUE_TYPE_NOT_FOUND (404)
 * - ISSUE_TEMPLATE_DUPLICATE (409)
 * - ISSUE_TEMPLATE_ACCESS_DENIED (403)
 * - ISSUE_TEMPLATE_INVALID (422)
 * - VALIDATION_FAILED (400)
 *
 * @param error 발생한 에러 (unknown)
 * @returns errorCode string 또는 null
 */
export function extractIssueTemplateErrorCode(error: unknown): string | null {
  if (error instanceof ApiError) {
    const body = error.body as Record<string, unknown> | undefined
    const code = body?.['errorCode']
    return typeof code === 'string' ? code : null
  }
  return null
}
