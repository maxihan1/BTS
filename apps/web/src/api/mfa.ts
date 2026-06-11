// MFA(TOTP) API 클라이언트 — setup/status/enable/disable/verify 5개 함수
import { apiFetch, apiGet, ApiError } from './client'
import { readXsrfToken } from './sessions'
import {
  MfaSetupResponseSchema,
  MfaStatusResponseSchema,
  TokenResponseSchema,
  BackupCodesResponseSchema,
  BackupCodesStatusResponseSchema,
  type MfaSetupResponse,
  type MfaStatusResponse,
  type TokenResponse,
  type BackupCodesResponse,
  type BackupCodesStatusResponse,
} from './schemas'

// ─────────────────────────────────────────────────────────────────────────────
// 에러 코드 상수 — 백엔드 MfaErrorCode.kt 와 1:1 (소문자 스네이크, plan §REFACTOR)
// ─────────────────────────────────────────────────────────────────────────────

/** MFA 백엔드 에러 코드 */
export const MfaErrorCode = {
  ALREADY_ENABLED: 'already_enabled',
  INVALID_CODE: 'invalid_code',
  INVALID_METHOD: 'invalid_method',
  NOT_ENABLED: 'not_enabled',
  NO_PENDING_SETUP: 'no_pending_setup',
  TOO_MANY_ATTEMPTS: 'too_many_attempts',
  TOTP_NOT_ACTIVE: 'totp_not_active',
} as const

// ─────────────────────────────────────────────────────────────────────────────
// API 함수
// ─────────────────────────────────────────────────────────────────────────────

/**
 * TOTP setup을 시작한다 — QR PNG data URI와 secret_base32를 반환한다.
 *
 * `POST /api/v1/auth/mfa/totp/setup`
 * - 상태 변경 요청이므로 X-XSRF-TOKEN 헤더를 포함한다 (double submit cookie 패턴).
 * - ACTIVE 상태에서 호출 시 409 already_enabled → ApiError(409) throw.
 *
 * @returns MfaSetupResponse (otpauth_uri, qr_png_data_uri, secret_base32)
 * @throws ApiError(409) 이미 TOTP 활성화된 경우
 * @throws ApiError(401) 미인증
 */
export async function setupMfa(): Promise<MfaSetupResponse> {
  const res = await apiFetch('/api/v1/auth/mfa/totp/setup', {
    method: 'POST',
    headers: {
      'X-XSRF-TOKEN': readXsrfToken(),
    },
  })
  if (!res.ok) {
    const errorBody: unknown = await res.json().catch(() => ({}))
    throw new ApiError(res.status, errorBody)
  }
  return MfaSetupResponseSchema.parse(await res.json())
}

/**
 * 현재 사용자의 TOTP 활성화 여부를 조회한다.
 *
 * `GET /api/v1/auth/mfa/totp`
 * - 읽기 요청이므로 CSRF 헤더 불요 — apiGet 사용.
 *
 * @returns MfaStatusResponse (enabled)
 * @throws ApiError(401) 미인증
 */
export async function getMfaStatus(): Promise<MfaStatusResponse> {
  return apiGet('/api/v1/auth/mfa/totp', MfaStatusResponseSchema)
}

/**
 * TOTP 활성화를 확정한다 — 6자리 코드를 검증해 PENDING → ACTIVE 전환.
 *
 * `POST /api/v1/auth/mfa/totp/enable { code }`
 * - 상태 변경 요청이므로 X-XSRF-TOKEN 헤더를 포함한다.
 * - 204 No Content 성공 — Zod parse 없이 반환.
 *
 * @param code Authenticator 앱의 6자리 TOTP 코드
 * @returns void
 * @throws ApiError(400) invalid_code — 코드 불일치
 * @throws ApiError(409) no_pending_setup — setup 없이 enable 시도
 * @throws ApiError(429) too_many_attempts — rate limit
 * @throws ApiError(401) 미인증
 */
export async function enableMfa(code: string): Promise<void> {
  const res = await apiFetch('/api/v1/auth/mfa/totp/enable', {
    method: 'POST',
    body: { code },
    headers: {
      'X-XSRF-TOKEN': readXsrfToken(),
    },
  })
  if (!res.ok) {
    const errorBody: unknown = await res.json().catch(() => ({}))
    throw new ApiError(res.status, errorBody)
  }
}

/**
 * TOTP를 비활성화한다 — step-up 코드 검증 후 ACTIVE → 비활성 전환.
 *
 * `DELETE /api/v1/auth/mfa/totp { code }`
 * - 상태 변경 요청이므로 X-XSRF-TOKEN 헤더를 포함한다.
 * - 204 No Content 성공 — Zod parse 없이 반환.
 *
 * @param code step-up용 현재 TOTP 코드
 * @returns void
 * @throws ApiError(400) invalid_code
 * @throws ApiError(404) not_enabled — TOTP 미활성 상태
 * @throws ApiError(429) too_many_attempts
 * @throws ApiError(401) 미인증
 */
export async function disableMfa(code: string): Promise<void> {
  const res = await apiFetch('/api/v1/auth/mfa/totp', {
    method: 'DELETE',
    body: { code },
    headers: {
      'X-XSRF-TOKEN': readXsrfToken(),
    },
  })
  if (!res.ok) {
    const errorBody: unknown = await res.json().catch(() => ({}))
    throw new ApiError(res.status, errorBody)
  }
}

/**
 * MFA 챌린지 코드를 검증해 정식 세션 토큰을 발급받는다.
 *
 * `POST /api/v1/auth/mfa/verify { mfa_challenge_token, code, method }`
 * - permitAll + CSRF-ignore 경로이므로 X-XSRF-TOKEN 헤더를 포함하지 않는다.
 *   챌린지 토큰 자체가 인증 증명이다.
 * - apiFetch 대신 fetch 직접 사용 — apiFetch는 401 응답 시 /refresh를 자동 시도하는데,
 *   로그인 MFA 2단계에서는 아직 세션이 없으므로 refresh 대상이 아니다. bypass하지 않으면
 *   잘못된 코드(401 invalid_code) 입력 시 spurious /refresh → refresh 실패 → clearSession()
 *   부수효과 + 에러가 generic으로 변질되어 "코드가 올바르지 않습니다." 메시지가 절대 안 뜬다.
 *   (useLoginMutation.ts:79-81 동일 패턴 참조)
 * - 성공 시 백엔드가 Set-Cookie refresh_token을 발급하므로 credentials:'include' 필수.
 * - method 기본값 'totp' — 기존 호출(LoginForm.tsx) 은 인자 변경 없이 하위호환.
 *   백업코드 로그인 시 'backup_code' 전달.
 *
 * @param challengeToken login 200 mfa_required 응답의 mfa_challenge_token
 * @param code Authenticator 앱의 6자리 TOTP 코드 또는 백업코드
 * @param method 인증 방식. 'totp'(기본) 또는 'backup_code'
 * @returns TokenResponse (access_token, token_type, expires_in)
 * @throws ApiError(400) invalid_method
 * @throws ApiError(401) invalid_code 또는 챌린지 만료
 * @throws ApiError(429) too_many_attempts
 */
export async function verifyMfa(
  challengeToken: string,
  code: string,
  method: 'totp' | 'backup_code' = 'totp',
): Promise<TokenResponse> {
  const res = await fetch('/api/v1/auth/mfa/verify', {
    method: 'POST',
    credentials: 'include',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ mfa_challenge_token: challengeToken, code, method }),
  })
  if (!res.ok) {
    const errorBody: unknown = await res.json().catch(() => ({}))
    throw new ApiError(res.status, errorBody)
  }
  return TokenResponseSchema.parse(await res.json())
}

/**
 * 백업코드를 새로 생성(또는 재생성)한다.
 *
 * `POST /api/v1/auth/mfa/backup-codes` (body 없음)
 * - 상태 변경 요청이므로 X-XSRF-TOKEN 헤더를 포함한다 (double submit cookie 패턴).
 * - 기존 백업코드가 있으면 모두 무효화하고 새 10개를 반환한다.
 *
 * @returns BackupCodesResponse (codes: string[10])
 * @throws ApiError(409) totp_not_active — TOTP 미활성 상태에서 호출
 * @throws ApiError(403) PAT 토큰으로 호출
 * @throws ApiError(401) 미인증
 */
export async function generateBackupCodes(): Promise<BackupCodesResponse> {
  const res = await apiFetch('/api/v1/auth/mfa/backup-codes', {
    method: 'POST',
    headers: {
      'X-XSRF-TOKEN': readXsrfToken(),
    },
  })
  if (!res.ok) {
    const errorBody: unknown = await res.json().catch(() => ({}))
    throw new ApiError(res.status, errorBody)
  }
  return BackupCodesResponseSchema.parse(await res.json())
}

/**
 * 백업코드 생성 여부 및 남은 개수를 조회한다.
 *
 * `GET /api/v1/auth/mfa/backup-codes`
 * - 읽기 요청이므로 CSRF 헤더 불요 — apiGet 사용.
 *
 * @returns BackupCodesStatusResponse (generated, remaining)
 * @throws ApiError(401) 미인증
 */
export async function getBackupCodesStatus(): Promise<BackupCodesStatusResponse> {
  return apiGet('/api/v1/auth/mfa/backup-codes', BackupCodesStatusResponseSchema)
}
