// WebAuthn(보안 키) API 클라이언트 — 등록(register)·인증(authenticate)·목록·삭제 + 오케스트레이션 헬퍼
import { startRegistration, startAuthentication } from '@simplewebauthn/browser'
import type {
  RegistrationResponseJSON,
  AuthenticationResponseJSON,
} from '@simplewebauthn/browser'
import { apiFetch, apiGet, ApiError } from './client'
import { readXsrfToken } from './sessions'
import {
  WebauthnKeysResponseSchema,
  TokenResponseSchema,
  type WebauthnKeysResponse,
  type TokenResponse,
} from './schemas'

// ─────────────────────────────────────────────────────────────────────────────
// API 함수 — 6개
// ─────────────────────────────────────────────────────────────────────────────

/**
 * WebAuthn 보안 키 등록을 시작한다 — 백엔드에서 registration options를 받아온다.
 *
 * `POST /api/v1/auth/mfa/webauthn/register/start`
 * - 상태 변경 요청이므로 X-XSRF-TOKEN 헤더를 포함한다 (double submit cookie 패턴).
 * - 백엔드가 JSON 객체를 application/json으로 내려주므로 res.json()으로 객체화한다 (B-2).
 *   res.text()를 쓰면 문자열이 반환돼 startRegistration에 그대로 전달 불가.
 *
 * @returns PublicKeyCredentialCreationOptionsJSON 형태의 registration options 객체
 * @throws ApiError 비-2xx 응답
 */
export async function webauthnRegisterStart(): Promise<unknown> {
  const res = await apiFetch('/api/v1/auth/mfa/webauthn/register/start', {
    method: 'POST',
    headers: {
      'X-XSRF-TOKEN': readXsrfToken(),
    },
  })
  if (!res.ok) {
    const errorBody: unknown = await res.json().catch(() => ({}))
    throw new ApiError(res.status, errorBody)
  }
  return res.json()
}

/**
 * WebAuthn 보안 키 등록을 완료한다 — 브라우저 credential과 별칭을 백엔드에 전송한다.
 *
 * `POST /api/v1/auth/mfa/webauthn/register/finish { credential, name }`
 * - 상태 변경 요청이므로 X-XSRF-TOKEN 헤더를 포함한다.
 * - 백엔드 응답은 201 No Content — res.ok만 확인하고 void를 반환한다 (B-1).
 *   Zod parse 금지: 빈 바디를 파싱하면 ZodError.
 *
 * @param credential startRegistration()이 반환한 RegistrationResponseJSON 객체
 * @param name 사용자가 지정한 보안 키 별칭 (빈 문자열 허용)
 * @returns void
 * @throws ApiError 비-2xx 응답
 */
export async function webauthnRegisterFinish(
  credential: RegistrationResponseJSON,
  name: string,
): Promise<void> {
  const res = await apiFetch('/api/v1/auth/mfa/webauthn/register/finish', {
    method: 'POST',
    body: { credential, name },
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
 * 현재 사용자의 등록된 WebAuthn 보안 키 목록을 조회한다.
 *
 * `GET /api/v1/auth/mfa/webauthn`
 * - 읽기 요청이므로 CSRF 헤더 불요 — apiGet 사용.
 *
 * @returns WebauthnKeysResponse (keys: WebauthnKey[])
 * @throws ApiError(401) 미인증
 */
export async function listWebauthnKeys(): Promise<WebauthnKeysResponse> {
  return apiGet('/api/v1/auth/mfa/webauthn', WebauthnKeysResponseSchema)
}

/**
 * 지정한 WebAuthn 보안 키를 삭제한다.
 *
 * `DELETE /api/v1/auth/mfa/webauthn/{id}` → 204 No Content
 * - 상태 변경 요청이므로 X-XSRF-TOKEN 헤더를 포함한다.
 *
 * @param id 삭제할 보안 키 UUID
 * @returns void
 * @throws ApiError(404) not_found
 * @throws ApiError(401) 미인증
 */
export async function deleteWebauthnKey(id: string): Promise<void> {
  const res = await apiFetch(`/api/v1/auth/mfa/webauthn/${id}`, {
    method: 'DELETE',
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
 * WebAuthn 인증(authentication) 챌린지를 시작한다 — 백엔드에서 authentication options를 받아온다.
 *
 * `POST /api/v1/auth/mfa/webauthn/authenticate/start { mfa_challenge_token }`
 * - raw fetch 사용 이유: 아직 세션이 없는 2단계 인증 경로이므로 apiFetch의 401→refresh
 *   자동 재시도가 spurious /refresh를 발생시킨다. raw fetch로 우회해 NFR-2를 보장한다.
 * - credentials와 CSRF 헤더 불요: 챌린지 토큰 자체가 인증 증명이다.
 * - 백엔드가 JSON 객체를 application/json으로 내려주므로 res.json()으로 객체화한다 (B-2).
 *
 * @param challengeToken 로그인 응답의 mfa_challenge_token
 * @returns PublicKeyCredentialRequestOptionsJSON 형태의 authentication options 객체
 * @throws ApiError 비-2xx 응답
 */
export async function webauthnAuthenticateStart(challengeToken: string): Promise<unknown> {
  const res = await fetch('/api/v1/auth/mfa/webauthn/authenticate/start', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ mfa_challenge_token: challengeToken }),
  })
  if (!res.ok) {
    const errorBody: unknown = await res.json().catch(() => ({}))
    throw new ApiError(res.status, errorBody)
  }
  return res.json()
}

/**
 * WebAuthn assertion을 검증해 정식 세션 토큰을 발급받는다.
 *
 * `POST /api/v1/auth/mfa/verify { mfa_challenge_token, code, method, credential }`
 * - raw fetch 사용 이유: 아직 세션이 없는 2단계 인증 경로. apiFetch의 401→/refresh
 *   자동 재시도가 invalid_code(401)를 spurious refresh 실패로 변질시킨다 (B-3, NFR-2).
 * - credentials:'include' 필수: 성공 시 백엔드가 Set-Cookie refresh_token을 발급한다.
 *   누락 시 이후 모든 refresh 호출이 실패한다 (치명적).
 * - body의 credential은 객체 그대로 JSON.stringify에 포함 (B-4).
 *   credential을 먼저 JSON.stringify하면 이중 직렬화 — 백엔드 JsonNode 파싱 실패(401).
 *
 * @param challengeToken 로그인 응답의 mfa_challenge_token
 * @param credential startAuthentication()이 반환한 AuthenticationResponseJSON 객체
 * @param trustDevice 이 디바이스를 30일간 신뢰할지 여부 (기본 false). FR-MF-05 신뢰 디바이스.
 * @returns TokenResponse (access_token, token_type, expires_in)
 * @throws ApiError(401) invalid_code 또는 챌린지 만료
 * @throws ApiError(429) too_many_attempts
 */
export async function verifyWebauthn(
  challengeToken: string,
  credential: AuthenticationResponseJSON,
  trustDevice = false,
): Promise<TokenResponse> {
  const res = await fetch('/api/v1/auth/mfa/verify', {
    method: 'POST',
    credentials: 'include',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      mfa_challenge_token: challengeToken,
      code: '',
      method: 'webauthn',
      credential,
      trust_device: trustDevice,
    }),
  })
  if (!res.ok) {
    const errorBody: unknown = await res.json().catch(() => ({}))
    throw new ApiError(res.status, errorBody)
  }
  return TokenResponseSchema.parse(await res.json())
}

// ─────────────────────────────────────────────────────────────────────────────
// 오케스트레이션 헬퍼 — 2개
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 보안 키를 등록한다 — webauthnRegisterStart → startRegistration → webauthnRegisterFinish 순서.
 *
 * NotAllowedError 등 @simplewebauthn/browser가 throw하는 예외는 그대로 전파한다.
 * 컴포넌트에서 catch해 사용자 친화적 메시지로 매핑할 것.
 *
 * @param name 사용자가 지정할 보안 키 별칭
 * @returns void
 * @throws Error (NotAllowedError 등) 브라우저 WebAuthn 거부
 * @throws ApiError 백엔드 비-2xx 응답
 */
export async function registerSecurityKey(name: string): Promise<void> {
  const optionsJSON = await webauthnRegisterStart()
  const credential = await startRegistration({
    optionsJSON: optionsJSON as Parameters<typeof startRegistration>[0]['optionsJSON'],
  })
  await webauthnRegisterFinish(credential, name)
}

/**
 * 보안 키로 MFA 인증을 완료한다 — webauthnAuthenticateStart → startAuthentication → verifyWebauthn 순서.
 *
 * NotAllowedError 등 @simplewebauthn/browser가 throw하는 예외는 그대로 전파한다.
 * 컴포넌트에서 catch해 사용자 친화적 메시지로 매핑할 것.
 *
 * @param challengeToken 로그인 응답의 mfa_challenge_token
 * @param trustDevice 이 디바이스를 30일간 신뢰할지 여부 (기본 false). FR-MF-05 신뢰 디바이스.
 * @returns TokenResponse (access_token, token_type, expires_in)
 * @throws Error (NotAllowedError 등) 브라우저 WebAuthn 거부
 * @throws ApiError 백엔드 비-2xx 응답
 */
export async function authenticateWithSecurityKey(
  challengeToken: string,
  trustDevice = false,
): Promise<TokenResponse> {
  const optionsJSON = await webauthnAuthenticateStart(challengeToken)
  const credential = await startAuthentication({
    optionsJSON: optionsJSON as Parameters<typeof startAuthentication>[0]['optionsJSON'],
  })
  return verifyWebauthn(challengeToken, credential, trustDevice)
}
