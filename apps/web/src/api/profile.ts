// identity-access 사용자 프로필 REST API 클라이언트 + Zod 스키마
import { z } from 'zod'
import { apiFetch, apiGet, ApiError } from './client'
import { readXsrfToken } from './sessions'

// ─────────────────────────────────────────────────────────────────────────────
// Zod 스키마 정의
// backend ProfileResponse/AvatarUploadResponse DTO 직렬화 형태와 1:1 대응
// (UserProfileController.kt, ProfileResponse.kt, AvatarUploadResponse.kt).
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 사용자 프로필 응답 Zod 스키마.
 * `GET`/`PATCH /api/v1/users/me/profile` 응답 형태 — 래퍼 없음.
 */
export const profileResponseSchema = z.object({
  userId: z.string().uuid(),
  username: z.string().min(1),
  /** 이메일 — 없으면 null */
  email: z.string().nullable(),
  displayName: z.string(),
  /** 아바타 다운로드 경로(`/api/v1/users/{userId}/avatar`) — 미설정 시 null */
  avatarUrl: z.string().nullable(),
  timezone: z.string().min(1),
  /** 부서 — 없으면 null */
  department: z.string().nullable(),
  /**
   * 표시 이름 필드의 출처(FR-PR-04) — `LDAP`(로그인 시 cn 동기화 대상) 또는
   * `USER`(사용자가 직접 편집해 동기화가 중단된 상태).
   */
  displayNameSource: z.enum(['LDAP', 'USER']),
  /** 외부 IdP(LDAP 등) 계정 연결 여부(FR-PR-04) — false면 출처 배지/재설정 UI 자체가 무의미(로컬 전용 사용자) */
  ldapLinked: z.boolean(),
})

/** 사용자 프로필 응답 타입 — Zod 스키마에서 추론 */
export type ProfileResponse = z.infer<typeof profileResponseSchema>

/**
 * 아바타 업로드 응답 Zod 스키마.
 * `POST /api/v1/users/me/profile/avatar` 응답 형태.
 */
export const avatarUploadResponseSchema = z.object({
  avatarUrl: z.string().min(1),
})

/** 아바타 업로드 응답 타입 */
export type AvatarUploadResponse = z.infer<typeof avatarUploadResponseSchema>

/**
 * 비-2xx 응답이면 body를 파싱해 {@link ApiError}를 throw한다.
 * PATCH/POST/DELETE/blob GET 등 응답 형태가 제각각인 함수들이 공통으로 쓰는
 * 에러 처리 — 각 함수는 자기 응답 body 파싱(json/blob)을 이 호출 다음에 이어간다.
 */
async function throwIfNotOk(res: Response): Promise<void> {
  if (!res.ok) {
    const errorBody: unknown = await res.json().catch(() => ({}))
    throw new ApiError(res.status, errorBody)
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// PATCH 요청 바디 타입 — 3-state (부재/명시 null/명시 값)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 프로필 PATCH 요청 바디.
 * 백엔드 `ProfilePatchRequest`(JsonNode 기반 3-state)와 정합 — 키 부재=미변경,
 * displayName/timezone은 부재/값(2-state), department는 부재/null/값(3-state).
 */
export interface ProfilePatchBody {
  displayName?: string
  timezone?: string
  department?: string | null
}

// ─────────────────────────────────────────────────────────────────────────────
// API 함수
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 본인 프로필을 조회한다.
 *
 * `GET /api/v1/users/me/profile` → 200 {@link ProfileResponse}.
 *
 * @returns 사용자 프로필
 * @throws ApiError(401) 미인증
 */
export async function getProfile(): Promise<ProfileResponse> {
  return apiGet('/api/v1/users/me/profile', profileResponseSchema)
}

/**
 * 본인 프로필을 3-state로 부분 수정한다.
 *
 * `PATCH /api/v1/users/me/profile` → 200 갱신 후 {@link ProfileResponse}.
 * - X-XSRF-TOKEN 헤더를 포함해 CSRF 공격을 방어한다 (double submit cookie 패턴).
 * - body에 없는 키는 미변경(부재)으로 취급되므로, 호출측이 변경된 필드만 담아야 한다
 *   ({@link buildPatchBody} 사용 권장).
 *
 * @param body 변경할 필드만 담은 3-state 요청 바디
 * @returns 갱신 후 사용자 프로필
 * @throws ApiError(400) PROFILE_VALIDATION_FAILED
 * @throws ApiError(401) 미인증
 */
export async function patchProfile(body: ProfilePatchBody): Promise<ProfileResponse> {
  const res = await apiFetch('/api/v1/users/me/profile', {
    method: 'PATCH',
    body,
    headers: {
      'X-XSRF-TOKEN': readXsrfToken(),
    },
  })
  await throwIfNotOk(res)
  return profileResponseSchema.parse(await res.json())
}

/**
 * 표시 이름 필드 출처를 LDAP로 되돌린다(source: USER → LDAP, FR-PR-04).
 *
 * `POST /api/v1/users/me/profile/display-name/resync` → 200 갱신 후 {@link ProfileResponse}
 * (`displayNameSource` = `"LDAP"`).
 * - 지연(delayed) semantics — 이 호출 자체는 표시 이름 **값**을 바꾸지 않는다. 다음 로그인
 *   (LDAP JIT 재동기화) 시 cn 값으로 실제 반영된다(ADR 2026-07-07 D4).
 * - X-XSRF-TOKEN 헤더를 포함해 CSRF 공격을 방어한다.
 *
 * @returns 갱신 후 사용자 프로필(`displayNameSource: "LDAP"`)
 * @throws ApiError(409) DISPLAY_NAME_NOT_LDAP_LINKED — 연결된 외부 IdP 계정이 없음(`ldapLinked: false`)
 * @throws ApiError(401) 미인증
 */
export async function resyncDisplayName(): Promise<ProfileResponse> {
  const res = await apiFetch('/api/v1/users/me/profile/display-name/resync', {
    method: 'POST',
    headers: {
      'X-XSRF-TOKEN': readXsrfToken(),
    },
  })
  await throwIfNotOk(res)
  return profileResponseSchema.parse(await res.json())
}

/**
 * 아바타 이미지를 업로드한다.
 *
 * `POST /api/v1/users/me/profile/avatar` (multipart/form-data, part명 `file`)
 * → 200 {@link AvatarUploadResponse}.
 * - Content-Type은 수동 설정하지 않는다 — FormData면 브라우저가 boundary를 자동 설정한다
 *   (attachments.ts의 uploadAttachment 선례).
 * - X-XSRF-TOKEN 헤더를 포함해 CSRF 공격을 방어한다.
 *
 * @param file 업로드할 이미지 File 객체
 * @returns 업로드된 아바타의 다운로드 경로
 * @throws ApiError(400) AVATAR_VALIDATION_FAILED (MIME/5MB 초과)
 * @throws ApiError(401) 미인증
 */
export async function uploadAvatar(file: File): Promise<AvatarUploadResponse> {
  const formData = new FormData()
  // 백엔드 API 계약: part명은 반드시 'file'
  formData.append('file', file)

  const res = await apiFetch('/api/v1/users/me/profile/avatar', {
    method: 'POST',
    body: formData,
    headers: {
      'X-XSRF-TOKEN': readXsrfToken(),
    },
  })
  await throwIfNotOk(res)
  return avatarUploadResponseSchema.parse(await res.json())
}

/**
 * 본인 아바타를 삭제한다 (멱등 — 아바타가 없어도 204).
 *
 * `DELETE /api/v1/users/me/profile/avatar` → 204 No Content.
 * X-XSRF-TOKEN 헤더를 포함해 CSRF 공격을 방어한다.
 *
 * @throws ApiError(401) 미인증
 */
export async function deleteAvatar(): Promise<void> {
  const res = await apiFetch('/api/v1/users/me/profile/avatar', {
    method: 'DELETE',
    headers: {
      'X-XSRF-TOKEN': readXsrfToken(),
    },
  })
  await throwIfNotOk(res)
}

/**
 * 인증된 아바타 이미지를 blob으로 조회한다.
 *
 * 아바타 GET(`/api/v1/users/{userId}/avatar`)은 `@PreAuthorize("isAuthenticated()")`로
 * 보호되는 STATELESS JWT 엔드포인트라 `<img src>`로 직접 로드하면 Authorization 헤더가
 * 실리지 않아 401로 깨진다. 따라서 `apiFetch`로 JWT Bearer를 실어 조회한 뒤
 * `res.blob()`으로 반환하고, 호출측이 `URL.createObjectURL`로 `<img>`에 표시한다
 * (attachments.ts의 downloadAttachment 선례).
 *
 * @param avatarUrl 프로필/whoami 응답의 avatarUrl (다운로드 경로) — 존재 여부 판정에도 쓰인다
 * @returns 이미지 바이트를 담은 Blob
 * @throws ApiError(404) AVATAR_NOT_FOUND — 호출측이 이니셜 폴백으로 처리
 * @throws ApiError(401) 미인증
 */
export async function fetchAvatarBlob(avatarUrl: string): Promise<Blob> {
  const res = await apiFetch(avatarUrl, { method: 'GET' })
  await throwIfNotOk(res)
  return res.blob()
}

// ─────────────────────────────────────────────────────────────────────────────
// 3-state 매핑 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

/** 폼에서 입력받는 프로필 편집 필드 (department는 빈 문자열로 "값 없음"을 표현) */
export interface ProfileFormFields {
  displayName: string
  timezone: string
  department: string
}

/**
 * 서버 원본 값(`original`)과 폼 입력값(`next`)을 비교해 변경된 필드만 담은
 * {@link ProfilePatchBody}를 만든다.
 *
 * - displayName: `next`를 trim한 값으로 비교·전송한다(department와 동일하게 앞뒤 공백
 *   비대칭 방지 — 트림 후 원본과 같으면 키를 생략한다).
 * - timezone: 값이 다르면 그대로 포함(2-state, 부재=미변경, select 값이라 trim 불필요).
 * - department: `next`를 trim한 뒤 빈 문자열이면 `null`(삭제 의도)로, 비어있지 않으면
 *   trim된 값으로 비교한다. 비교 결과가 `original.department`와 같으면 키를 생략한다
 *   (미변경 시 부재 — EC2).
 *
 * @param original 서버가 응답한 현재 프로필 값 중 displayName/timezone/department
 * @param next 폼에 입력된 값
 * @returns 변경된 필드만 담은 3-state PATCH 바디 — 변경 없으면 빈 객체
 */
export function buildPatchBody(
  original: Pick<ProfileResponse, 'displayName' | 'timezone' | 'department'>,
  next: ProfileFormFields,
): ProfilePatchBody {
  const body: ProfilePatchBody = {}

  const trimmedDisplayName = next.displayName.trim()
  if (trimmedDisplayName !== original.displayName) {
    body.displayName = trimmedDisplayName
  }

  if (next.timezone !== original.timezone) {
    body.timezone = next.timezone
  }

  const trimmedDepartment = next.department.trim()
  const nextDepartment = trimmedDepartment === '' ? null : trimmedDepartment
  if (nextDepartment !== original.department) {
    body.department = nextDepartment
  }

  return body
}
