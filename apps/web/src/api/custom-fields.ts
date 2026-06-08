// 커스텀 필드 BC REST API 클라이언트 — CRUD + X-XSRF-TOKEN + errorCode 추출 헬퍼 (FR-IS-10)
import { z } from 'zod'
import { apiGet, apiFetch, ApiError } from './client'
import { readXsrfToken } from './sessions'
import {
  customFieldResponseSchema,
  dataResponseSchema,
  type CustomField,
  type CreateCustomFieldInput,
  type UpdateCustomFieldInput,
} from './custom-fields.types'

export type { CustomField, FieldType, CreateCustomFieldInput, UpdateCustomFieldInput } from './custom-fields.types'

// ─────────────────────────────────────────────────────────────────────────────
// 내부 상수
// ─────────────────────────────────────────────────────────────────────────────

/** 커스텀 필드 단건 + 목록 응답 래퍼 스키마 — 내부 전용 */
const customFieldDataSchema = dataResponseSchema(customFieldResponseSchema)
const customFieldListDataSchema = dataResponseSchema(z.array(customFieldResponseSchema))

/** 기본 경로 헬퍼 */
function basePath(projectIdOrKey: string): string {
  return `/api/v1/projects/${projectIdOrKey}/custom-fields`
}

// ─────────────────────────────────────────────────────────────────────────────
// API 함수
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 프로젝트 커스텀 필드 목록을 조회한다 (활성만, displayOrder asc).
 *
 * GET /api/v1/projects/{projectIdOrKey}/custom-fields → `{ data: [...] }` 언래핑 후 반환.
 *
 * @param projectIdOrKey 프로젝트 UUID 또는 키
 * @returns CustomField 배열 — 커스텀 필드가 없으면 빈 배열
 * @throws ApiError(404) 프로젝트 미존재 시
 */
export async function fetchCustomFields(projectIdOrKey: string): Promise<CustomField[]> {
  const wrapped = await apiGet(basePath(projectIdOrKey), customFieldListDataSchema)
  return wrapped.data
}

/**
 * 커스텀 필드 단건을 조회한다.
 *
 * GET /api/v1/projects/{projectIdOrKey}/custom-fields/{fieldId} → `{ data: ... }` 언래핑 후 반환.
 *
 * @param projectIdOrKey 프로젝트 UUID 또는 키
 * @param fieldId 커스텀 필드 UUID
 * @returns CustomField
 * @throws ApiError(404) 커스텀 필드 미존재 시
 */
export async function fetchCustomField(projectIdOrKey: string, fieldId: string): Promise<CustomField> {
  const wrapped = await apiGet(`${basePath(projectIdOrKey)}/${fieldId}`, customFieldDataSchema)
  return wrapped.data
}

/**
 * 커스텀 필드를 생성한다.
 *
 * POST /api/v1/projects/{projectIdOrKey}/custom-fields → 201 `{ data: ... }` 언래핑 후 반환.
 * CSRF 방어를 위해 X-XSRF-TOKEN 헤더를 포함한다.
 * 권한: MANAGE_CUSTOM_FIELDS.
 *
 * @param projectIdOrKey 프로젝트 UUID 또는 키
 * @param input key·name·fieldType(필수), description·required·displayOrder·options(선택)
 * @returns 생성된 CustomField
 * @throws ApiError(409, CUSTOM_FIELD_KEY_DUPLICATE) 키 중복 시
 * @throws ApiError(403) 권한 없음 시
 */
export async function createCustomField(
  projectIdOrKey: string,
  input: CreateCustomFieldInput,
): Promise<CustomField> {
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
  const wrapped = customFieldDataSchema.parse(raw)
  return wrapped.data
}

/**
 * 커스텀 필드를 수정한다 (fieldType·key는 불변).
 *
 * PATCH /api/v1/projects/{projectIdOrKey}/custom-fields/{fieldId} → `{ data: ... }` 언래핑 후 반환.
 * CSRF 방어를 위해 X-XSRF-TOKEN 헤더를 포함한다.
 *
 * @param projectIdOrKey 프로젝트 UUID 또는 키
 * @param fieldId 커스텀 필드 UUID
 * @param input name·description·required·displayOrder·options(전부 선택)
 * @returns 수정된 CustomField
 * @throws ApiError(404) 커스텀 필드 미존재 시
 * @throws ApiError(403) 권한 없음 시
 */
export async function updateCustomField(
  projectIdOrKey: string,
  fieldId: string,
  input: UpdateCustomFieldInput,
): Promise<CustomField> {
  const res = await apiFetch(`${basePath(projectIdOrKey)}/${fieldId}`, {
    method: 'PATCH',
    body: input,
    headers: { 'X-XSRF-TOKEN': readXsrfToken() },
  })
  if (!res.ok) {
    const errorBody: unknown = await res.json().catch(() => ({}))
    throw new ApiError(res.status, errorBody)
  }
  const raw: unknown = await res.json()
  const wrapped = customFieldDataSchema.parse(raw)
  return wrapped.data
}

/**
 * 커스텀 필드를 삭제한다.
 *
 * DELETE /api/v1/projects/{projectIdOrKey}/custom-fields/{fieldId} → 204 No Content.
 * CSRF 방어를 위해 X-XSRF-TOKEN 헤더를 포함한다.
 *
 * @param projectIdOrKey 프로젝트 UUID 또는 키
 * @param fieldId 삭제할 커스텀 필드 UUID
 * @returns void
 * @throws ApiError(404) 커스텀 필드 미존재 시
 * @throws ApiError(403) 권한 없음 시
 */
export async function deleteCustomField(projectIdOrKey: string, fieldId: string): Promise<void> {
  const res = await apiFetch(`${basePath(projectIdOrKey)}/${fieldId}`, {
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
 * 에러에서 커스텀 필드 BC errorCode 를 추출한다.
 *
 * ApiError 이면 body.errorCode 를 string 으로 반환한다.
 * ApiError 가 아니거나 errorCode 필드가 없으면 null 을 반환한다.
 *
 * @param error 발생한 에러 (unknown)
 * @returns errorCode string 또는 null
 */
export function extractCustomFieldErrorCode(error: unknown): string | null {
  if (error instanceof ApiError) {
    const body = error.body as Record<string, unknown> | undefined
    const code = body?.['errorCode']
    return typeof code === 'string' ? code : null
  }
  return null
}
