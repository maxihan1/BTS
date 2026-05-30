// 비밀번호 변경 API 클라이언트 — POST /api/v1/users/me/password + X-XSRF-TOKEN CSRF 방어
import { z } from 'zod'
import { apiFetch, ApiError } from './client'
import { readXsrfToken } from './sessions'

// ─────────────────────────────────────────────────────────────────────────────
// 에러 코드 상수 — 백엔드 enum.name 과 1:1 (대문자 스네이크, 소문자 금지)
// 참조: PasswordErrorCode.kt (PR #44), frontend-zod-backend-dto-contract-gap 선례
// ─────────────────────────────────────────────────────────────────────────────

/** 비밀번호 변경 백엔드 에러 코드 — 대문자 스네이크 고정 */
export const PasswordChangeErrorCode = {
  POLICY_VIOLATION: 'POLICY_VIOLATION',
  CURRENT_PASSWORD_MISMATCH: 'CURRENT_PASSWORD_MISMATCH',
  SAME_AS_CURRENT: 'SAME_AS_CURRENT',
} as const

/** 비밀번호 정책 위반 세부 항목 — violations 배열 값 */
export const PasswordViolation = {
  MIN_LENGTH: 'MIN_LENGTH',
  COMPLEXITY: 'COMPLEXITY',
} as const

// ─────────────────────────────────────────────────────────────────────────────
// Zod 스키마 — 백엔드 응답 DTO와 1:1 정합 (NFR-1, Zod↔DTO drift 차단)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 비밀번호 변경 성공 응답 스키마.
 * 백엔드: 200 `{ "changed": true }` (PasswordController.kt PR #44)
 */
const successSchema = z.object({
  changed: z.literal(true),
})

/** 비밀번호 변경 성공 응답 타입 — z.infer로 자동 추론 */
export type ChangePasswordSuccess = z.infer<typeof successSchema>

// ─────────────────────────────────────────────────────────────────────────────
// 요청 파라미터 타입
// ─────────────────────────────────────────────────────────────────────────────

/** changePassword 함수 입력 파라미터 */
export interface ChangePasswordParams {
  /** 현재 비밀번호 — 백엔드 @NotBlank 검증 */
  currentPassword: string
  /** 새 비밀번호 — 백엔드 @NotBlank + 정책(12자/3종) 검증 */
  newPassword: string
}

// ─────────────────────────────────────────────────────────────────────────────
// API 함수
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 현재 인증 사용자의 비밀번호를 변경한다.
 *
 * `POST /api/v1/users/me/password`
 * - X-XSRF-TOKEN 헤더를 포함해 CSRF 공격을 방어한다 (double submit cookie 패턴).
 *   `readXsrfToken()`은 sessions.ts에서 공유 — 중복 구현 금지.
 * - 성공 시 Zod로 `{ changed: true }` 파싱 후 반환.
 * - 비-2xx 시 `ApiError(status, body)` throw — body.code/violations 보존.
 *
 * @param params currentPassword / newPassword (평문 — 네트워크 탭 외 로그/URL 노출 금지)
 * @returns 성공 응답 `{ changed: true }`
 * @throws ApiError(400) POLICY_VIOLATION / CURRENT_PASSWORD_MISMATCH / SAME_AS_CURRENT
 * @throws ApiError(401) 미인증 (apiFetch가 refresh 1회 retry 후 throw)
 * @throws ApiError(403) CSRF 누락
 */
export async function changePassword(params: ChangePasswordParams): Promise<ChangePasswordSuccess> {
  const { currentPassword, newPassword } = params

  const res = await apiFetch('/api/v1/users/me/password', {
    method: 'POST',
    body: { currentPassword, newPassword },
    headers: {
      // double submit cookie 패턴: 쿠키의 XSRF-TOKEN 값을 헤더로 재전송
      'X-XSRF-TOKEN': readXsrfToken(),
    },
  })

  if (!res.ok) {
    const errorBody: unknown = await res.json().catch(() => ({}))
    throw new ApiError(res.status, errorBody)
  }

  // 성공 응답 Zod 파싱 — 백엔드 계약 드리프트 조기 발견
  return successSchema.parse(await res.json())
}
