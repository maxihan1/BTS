// TOTP MFA MSW 핸들러 — stateful store 기반 setup/status/enable/disable/verify (FR-MF-01)
import { http, HttpResponse } from 'msw'
import { mfaStore, MFA_VALID_CODE } from './auth-fixtures'

// ─────────────────────────────────────────────────────────────────────────────
// 내부 헬퍼 — CSRF 검사
// 존재 여부만 확인 (실제 값 검증은 백엔드 몫 — field-permission-handlers 선례)
// ─────────────────────────────────────────────────────────────────────────────

function checkCsrf(request: Request): boolean {
  return request.headers.get('X-XSRF-TOKEN') !== null
}

function csrfMissingResponse(): Response {
  return HttpResponse.json({ error: 'csrf_token_missing' }, { status: 403 })
}

// ─────────────────────────────────────────────────────────────────────────────
// 내부 헬퍼 — 코드 검증
// 고정 유효코드 MFA_VALID_CODE 만 성공
// ─────────────────────────────────────────────────────────────────────────────

function isValidCode(code: unknown): boolean {
  return code === MFA_VALID_CODE
}

// ─────────────────────────────────────────────────────────────────────────────
// POST /api/v1/auth/mfa/totp/setup
// CSRF 필수. MFA 이미 활성화 → 409. 성공 → pending 상태로 전환 + setup 응답 반환.
// ─────────────────────────────────────────────────────────────────────────────

/**
 * TOTP setup — QR code, secret, otpauth URI 반환.
 * 성공 시 hasPendingSetup = true 로 전환한다 (enable 호출 대기 상태).
 */
const setupHandler = http.post('/api/v1/auth/mfa/totp/setup', ({ request }) => {
  if (!checkCsrf(request)) return csrfMissingResponse()

  if (mfaStore.enabled) {
    return HttpResponse.json({ error: 'already_enabled' }, { status: 409 })
  }

  mfaStore.hasPendingSetup = true

  return HttpResponse.json({
    otpauth_uri: 'otpauth://totp/BTS:alice@bts.local?secret=JBSWY3DPEHPK3PXP&issuer=BTS',
    // 1x1 투명 PNG data URI — QR 렌더링 테스트용 최소 더미
    qr_png_data_uri:
      'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==',
    secret_base32: 'JBSWY3DPEHPK3PXP',
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// GET /api/v1/auth/mfa/totp (status)
// CSRF 불요 (읽기 전용). 현재 store 상태 반환.
// ─────────────────────────────────────────────────────────────────────────────

/**
 * TOTP 상태 조회 — { enabled } 반환.
 * CSRF 헤더 불요 (GET 읽기 전용).
 */
const statusHandler = http.get('/api/v1/auth/mfa/totp', () => {
  return HttpResponse.json({ enabled: mfaStore.enabled })
})

// ─────────────────────────────────────────────────────────────────────────────
// POST /api/v1/auth/mfa/totp/enable
// CSRF 필수. setup pending 없음 → 409. 코드 불일치 → 400. 성공 → enabled:true.
// ─────────────────────────────────────────────────────────────────────────────

/**
 * TOTP 활성화 — 유효코드 검증 후 enabled = true 로 전환.
 * setup pending 상태가 없으면 409 no_pending_setup 반환.
 */
const enableHandler = http.post('/api/v1/auth/mfa/totp/enable', async ({ request }) => {
  if (!checkCsrf(request)) return csrfMissingResponse()

  if (!mfaStore.hasPendingSetup) {
    return HttpResponse.json({ error: 'no_pending_setup' }, { status: 409 })
  }

  const body = await request.json() as { code?: unknown }
  if (!isValidCode(body.code)) {
    return HttpResponse.json({ error: 'invalid_code' }, { status: 400 })
  }

  mfaStore.enabled = true
  mfaStore.hasPendingSetup = false

  return new HttpResponse(null, { status: 204 })
})

// ─────────────────────────────────────────────────────────────────────────────
// DELETE /api/v1/auth/mfa/totp (disable)
// CSRF 필수. 비활성 상태 → 404. 코드 불일치 → 400. 성공 → enabled:false.
// ─────────────────────────────────────────────────────────────────────────────

/**
 * TOTP 비활성화 — step-up 코드 검증 후 enabled = false 로 전환.
 * MFA가 활성 상태가 아니면 404 not_enabled 반환.
 */
const disableHandler = http.delete('/api/v1/auth/mfa/totp', async ({ request }) => {
  if (!checkCsrf(request)) return csrfMissingResponse()

  if (!mfaStore.enabled) {
    return HttpResponse.json({ error: 'not_enabled' }, { status: 404 })
  }

  const body = await request.json() as { code?: unknown }
  if (!isValidCode(body.code)) {
    return HttpResponse.json({ error: 'invalid_code' }, { status: 400 })
  }

  mfaStore.enabled = false
  mfaStore.hasPendingSetup = false

  return new HttpResponse(null, { status: 204 })
})

// ─────────────────────────────────────────────────────────────────────────────
// POST /api/v1/auth/mfa/verify
// CSRF 불요 (permitAll + 챌린지 토큰이 인증 증명). 코드 불일치 → 401.
// 성공 → 정식 세션 토큰 반환 (alice 고정 — mock 단순화).
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MFA 챌린지 검증 — 유효코드 확인 후 정식 access_token 반환.
 * CSRF 헤더 불요 (permitAll 경로, 챌린지 토큰이 인증 증명).
 * mfa_challenge_token 값 자체는 mock에서 검증하지 않는다 (단명 JWT 서명 불필요).
 */
const verifyHandler = http.post('/api/v1/auth/mfa/verify', async ({ request }) => {
  const body = await request.json() as { mfa_challenge_token?: unknown; code?: unknown }

  if (!isValidCode(body.code)) {
    return HttpResponse.json({ error: 'invalid_code' }, { status: 401 })
  }

  return HttpResponse.json({
    access_token: 'mock-access-token-alice',
    token_type: 'Bearer',
    expires_in: 900,
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 핸들러 배열 export
// ─────────────────────────────────────────────────────────────────────────────

export const mfaHandlers = [
  setupHandler,
  statusHandler,
  enableHandler,
  disableHandler,
  verifyHandler,
]
