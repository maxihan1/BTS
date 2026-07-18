// 전역 권한 부여 BC REST API 클라이언트 — 목록/부여/회수 + X-XSRF-TOKEN + error 코드 추출 헬퍼 (FR-PM-10)
import { z } from 'zod'
import { apiGet, apiFetch, ApiError } from './client'
import { readXsrfToken } from './sessions'
import {
  grantResponseSchema,
  type GrantResponse,
  type CreateGlobalPermissionInput,
} from './global-permissions.types'

export { ApiError } from './client'
export type { GrantResponse, GranteeType, CreateGlobalPermissionInput } from './global-permissions.types'

// ─────────────────────────────────────────────────────────────────────────────
// 내부 상수
// ─────────────────────────────────────────────────────────────────────────────

/** 전역 권한 부여 API 기본 경로 */
const BASE_PATH = '/api/v1/admin/global-permissions'

/** 전역 권한 부여 목록 응답 Zod 스키마 — bare 배열(래퍼 없음, field-permissions와 다름) */
const grantListSchema = z.array(grantResponseSchema)

/** 전역 권한 코드 화이트리스트 — 현재 CREATE_PROJECT 단일(백엔드 DB CHECK와 동기화, 확장 시 이 배열만 추가) */
export const GLOBAL_PERMISSION_CODES = ['CREATE_PROJECT'] as const

/** 전역 권한 코드 → 한글 라벨 매핑 */
export const GLOBAL_PERMISSION_LABELS: Record<string, string> = {
  CREATE_PROJECT: '프로젝트 생성',
}

// ─────────────────────────────────────────────────────────────────────────────
// API 함수
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 전역 권한 부여 목록을 조회한다.
 *
 * GET /api/v1/admin/global-permissions → 200 bare 배열(래퍼 없음).
 *
 * @returns GrantResponse 배열 — 부여 내역이 없으면 빈 배열
 * @throws ApiError(401) 미인증
 * @throws ApiError(403) SYSTEM_ADMIN 아님
 */
export async function fetchGlobalPermissions(): Promise<GrantResponse[]> {
  return apiGet(BASE_PATH, grantListSchema)
}

/**
 * 전역 권한을 부여한다.
 *
 * POST /api/v1/admin/global-permissions → 201 bare GrantResponse.
 * CSRF 방어를 위해 X-XSRF-TOKEN 헤더를 포함한다.
 *
 * @param input permission·granteeType·granteeId (전부 필수)
 * @returns 생성된 GrantResponse
 * @throws ApiError(400) unknown_permission
 * @throws ApiError(404) grantee_not_found
 * @throws ApiError(409) grant_already_exists
 */
export async function createGlobalPermission(
  input: CreateGlobalPermissionInput,
): Promise<GrantResponse> {
  const res = await apiFetch(BASE_PATH, {
    method: 'POST',
    body: input,
    headers: { 'X-XSRF-TOKEN': readXsrfToken() },
  })
  if (!res.ok) {
    const errorBody: unknown = await res.json().catch(() => ({}))
    throw new ApiError(res.status, errorBody)
  }
  const raw: unknown = await res.json()
  return grantResponseSchema.parse(raw)
}

/**
 * 전역 권한 부여를 회수한다.
 *
 * DELETE /api/v1/admin/global-permissions/{grantId} → 204 No Content.
 * CSRF 방어를 위해 X-XSRF-TOKEN 헤더를 포함한다.
 *
 * @param grantId 회수할 부여 UUID
 * @returns void
 * @throws ApiError(404) grant_not_found
 */
export async function deleteGlobalPermission(grantId: string): Promise<void> {
  const res = await apiFetch(`${BASE_PATH}/${grantId}`, {
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
 * 에러에서 전역 권한 BC 에러 코드를 추출한다.
 *
 * ApiError이면 body.error를 string으로 반환한다(snake_case, `errorCode` 아님 — NFR-1).
 * ApiError가 아니거나 error 필드가 없으면 null을 반환한다.
 *
 * @param error 발생한 에러 (unknown)
 * @returns 에러 코드 string 또는 null
 */
export function extractGlobalPermissionErrorCode(error: unknown): string | null {
  if (error instanceof ApiError) {
    const body = error.body as Record<string, unknown> | undefined
    const code = body?.['error']
    return typeof code === 'string' ? code : null
  }
  return null
}
