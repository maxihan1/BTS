// 필드 권한 규칙 BC REST API 클라이언트 — CRUD + X-XSRF-TOKEN + errorCode 추출 헬퍼 (FR-PM-07)
import { z } from 'zod'
import { apiGet, apiFetch, ApiError } from './client'
import { readXsrfToken } from './sessions'
import {
  fieldPermissionResponseSchema,
  type FieldPermissionResponse,
  type CreateFieldPermissionInput,
} from './field-permissions.types'

export { ApiError } from './client'
export type { FieldPermissionResponse, FieldKind, AccessLevel, CreateFieldPermissionInput } from './field-permissions.types'

// ─────────────────────────────────────────────────────────────────────────────
// 내부 상수
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 필드 권한 규칙 목록 응답 스키마 — **맨 배열**.
 *
 * 🛑 `{ data: [...] }` 래퍼로 되돌리지 마라. 정본은 백엔드
 * `FieldPermissionController.listRules` 로, `ResponseEntity.ok(rules)` 가 List 를 그대로 싣는다.
 * 래퍼를 가정하면 200 응답이 매번 ZodError 로 떨어져 화면이 항상 「요청을 처리하지 못했습니다.」가
 * 된다 — MSW mock 도 같은 래퍼를 쓰고 있어 단위 테스트는 전부 초록이었다.
 */
const fieldPermissionListSchema = z.array(fieldPermissionResponseSchema)

/** 기본 경로 헬퍼 */
function basePath(projectKey: string): string {
  return `/api/v1/projects/${projectKey}/field-permissions`
}

// ─────────────────────────────────────────────────────────────────────────────
// API 함수
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 프로젝트 필드 권한 규칙 목록을 조회한다.
 *
 * GET /api/v1/projects/{projectKey}/field-permissions → 맨 배열 그대로 반환.
 *
 * @param projectKey 프로젝트 키 (예: "ATLAS")
 * @returns FieldPermissionResponse 배열 — 규칙이 없으면 빈 배열
 * @throws ApiError(404) 프로젝트 미존재 시
 * @throws ApiError(403) 권한 없음 시
 */
export async function fetchFieldPermissions(projectKey: string): Promise<FieldPermissionResponse[]> {
  return apiGet(basePath(projectKey), fieldPermissionListSchema)
}

/**
 * 필드 권한 규칙을 생성한다.
 *
 * POST /api/v1/projects/{projectKey}/field-permissions → 201 응답, 생성된 규칙 반환.
 * CSRF 방어를 위해 X-XSRF-TOKEN 헤더를 포함한다.
 *
 * @param projectKey 프로젝트 키 (예: "ATLAS")
 * @param input fieldKind·fieldKey·groupId·accessLevel (전부 필수)
 * @returns 생성된 FieldPermissionResponse
 * @throws ApiError(409) 동일 필드/그룹 규칙 중복 시
 * @throws ApiError(403) 권한 없음 시
 */
export async function createFieldPermission(
  projectKey: string,
  input: CreateFieldPermissionInput,
): Promise<FieldPermissionResponse> {
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
  return fieldPermissionResponseSchema.parse(raw)
}

/**
 * 필드 권한 규칙을 삭제한다.
 *
 * DELETE /api/v1/projects/{projectKey}/field-permissions/{id} → 204 No Content.
 * CSRF 방어를 위해 X-XSRF-TOKEN 헤더를 포함한다.
 *
 * @param projectKey 프로젝트 키 (예: "ATLAS")
 * @param id 삭제할 규칙 UUID
 * @returns void
 * @throws ApiError(404) 규칙 미존재 시
 * @throws ApiError(403) 권한 없음 시
 */
export async function deleteFieldPermission(projectKey: string, id: string): Promise<void> {
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
 * 에러에서 필드 권한 BC errorCode 를 추출한다.
 *
 * ApiError 이면 body.errorCode 를 string 으로 반환한다.
 * ApiError 가 아니거나 errorCode 필드가 없으면 null 을 반환한다.
 *
 * @param error 발생한 에러 (unknown)
 * @returns errorCode string 또는 null
 */
export function extractFieldPermissionErrorCode(error: unknown): string | null {
  if (error instanceof ApiError) {
    const body = error.body as Record<string, unknown> | undefined
    const code = body?.['errorCode']
    return typeof code === 'string' ? code : null
  }
  return null
}
