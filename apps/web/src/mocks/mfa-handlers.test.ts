// TOTP MFA MSW 핸들러 단위 테스트 — stateful store + CSRF 검사 + E2E 토글 분기 검증
import { setupServer } from 'msw/node'
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest'
import { mfaHandlers } from './mfa-handlers'
import {
  resetMfaStore,
  MFA_E2E_ENABLED_KEY,
} from './auth-fixtures'
import { authHandlers } from './auth-handlers'

// ─────────────────────────────────────────────────────────────────────────────
// 서버 — MFA 핸들러 + login 핸들러 (mfa_required 분기 테스트용)
// ─────────────────────────────────────────────────────────────────────────────

const server = setupServer(...mfaHandlers, ...authHandlers)

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }))
afterEach(() => {
  server.resetHandlers()
  resetMfaStore()
  // localStorage E2E 플래그 초기화
  try {
    localStorage.removeItem(MFA_E2E_ENABLED_KEY)
  } catch {
    // node 환경에서 localStorage 없음 — 무시
  }
})
afterAll(() => server.close())

// ─────────────────────────────────────────────────────────────────────────────
// 응답 타입 (테스트 내부 편의용)
// ─────────────────────────────────────────────────────────────────────────────

interface MfaSetupBody {
  otpauth_uri: string
  qr_png_data_uri: string
  secret_base32: string
}

interface MfaStatusBody {
  enabled: boolean
}

interface MfaVerifyBody {
  access_token: string
  token_type: string
  expires_in: number
}

interface MfaErrorBody {
  error: string
}

interface MfaRequiredBody {
  mfa_required: boolean
  mfa_challenge_token: string
  expires_in: number
}

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼 — 공통 요청 함수
// ─────────────────────────────────────────────────────────────────────────────

function postSetup(withCsrf = true): Promise<Response> {
  return fetch('/api/v1/auth/mfa/totp/setup', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      ...(withCsrf ? { 'X-XSRF-TOKEN': 'test-csrf-token' } : {}),
    },
  })
}

function getStatus(): Promise<Response> {
  return fetch('/api/v1/auth/mfa/totp', { method: 'GET' })
}

function postEnable(code: string, withCsrf = true): Promise<Response> {
  return fetch('/api/v1/auth/mfa/totp/enable', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      ...(withCsrf ? { 'X-XSRF-TOKEN': 'test-csrf-token' } : {}),
    },
    body: JSON.stringify({ code }),
  })
}

function deleteDisable(code: string, withCsrf = true): Promise<Response> {
  return fetch('/api/v1/auth/mfa/totp', {
    method: 'DELETE',
    headers: {
      'Content-Type': 'application/json',
      ...(withCsrf ? { 'X-XSRF-TOKEN': 'test-csrf-token' } : {}),
    },
    body: JSON.stringify({ code }),
  })
}

function postVerify(mfa_challenge_token: string, code: string): Promise<Response> {
  return fetch('/api/v1/auth/mfa/verify', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ mfa_challenge_token, code }),
  })
}

function postLogin(username: string): Promise<Response> {
  return fetch('/api/v1/auth/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ provider: 'local', username, password: 'password' }),
  })
}

// ─────────────────────────────────────────────────────────────────────────────
// POST /api/v1/auth/mfa/totp/setup
// ─────────────────────────────────────────────────────────────────────────────

describe('mfaHandlers — POST /api/v1/auth/mfa/totp/setup', () => {
  it('X-XSRF-TOKEN 있음 → 200 + { otpauth_uri, qr_png_data_uri, secret_base32 }', async () => {
    const res = await postSetup(true)

    expect(res.status).toBe(200)
    const body = await res.json() as MfaSetupBody
    expect(body.otpauth_uri).toBeTypeOf('string')
    expect(body.otpauth_uri.length).toBeGreaterThan(0)
    expect(body.qr_png_data_uri).toBeTypeOf('string')
    expect(body.qr_png_data_uri.startsWith('data:image/png;base64,')).toBe(true)
    expect(body.secret_base32).toBeTypeOf('string')
    expect(body.secret_base32.length).toBeGreaterThan(0)
  })

  it('X-XSRF-TOKEN 없음 → 403 (CSRF 가짜그린 차단)', async () => {
    const res = await postSetup(false)

    expect(res.status).toBe(403)
  })

  it('MFA 이미 활성화 상태에서 setup → 409 { error: "already_enabled" }', async () => {
    // 먼저 enable 상태로 만들기
    await postSetup(true)
    await postEnable('123456', true)

    const res = await postSetup(true)

    expect(res.status).toBe(409)
    const body = await res.json() as MfaErrorBody
    expect(body.error).toBe('already_enabled')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// GET /api/v1/auth/mfa/totp (status)
// ─────────────────────────────────────────────────────────────────────────────

describe('mfaHandlers — GET /api/v1/auth/mfa/totp (status)', () => {
  it('초기 상태 → { enabled: false }', async () => {
    const res = await getStatus()

    expect(res.status).toBe(200)
    const body = await res.json() as MfaStatusBody
    expect(body.enabled).toBe(false)
  })

  it('enable 후 → { enabled: true }', async () => {
    await postSetup(true)
    await postEnable('123456', true)

    const res = await getStatus()

    expect(res.status).toBe(200)
    const body = await res.json() as MfaStatusBody
    expect(body.enabled).toBe(true)
  })

  it('status는 CSRF 헤더 없이도 200 (읽기 전용)', async () => {
    const res = await getStatus()

    expect(res.status).toBe(200)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// POST /api/v1/auth/mfa/totp/enable
// ─────────────────────────────────────────────────────────────────────────────

describe('mfaHandlers — POST /api/v1/auth/mfa/totp/enable', () => {
  it('setup 후 유효코드 123456 → 204 + enabled:true', async () => {
    await postSetup(true)
    const res = await postEnable('123456', true)

    expect(res.status).toBe(204)

    const statusRes = await getStatus()
    const statusBody = await statusRes.json() as MfaStatusBody
    expect(statusBody.enabled).toBe(true)
  })

  it('setup 후 잘못된 코드 → 400 { error: "invalid_code" }', async () => {
    await postSetup(true)
    const res = await postEnable('000000', true)

    expect(res.status).toBe(400)
    const body = await res.json() as MfaErrorBody
    expect(body.error).toBe('invalid_code')
  })

  it('X-XSRF-TOKEN 없음 → 403 (CSRF 가짜그린 차단)', async () => {
    await postSetup(true)
    const res = await postEnable('123456', false)

    expect(res.status).toBe(403)
  })

  it('setup 없이 enable → 409 { error: "no_pending_setup" }', async () => {
    const res = await postEnable('123456', true)

    expect(res.status).toBe(409)
    const body = await res.json() as MfaErrorBody
    expect(body.error).toBe('no_pending_setup')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// DELETE /api/v1/auth/mfa/totp (disable)
// ─────────────────────────────────────────────────────────────────────────────

describe('mfaHandlers — DELETE /api/v1/auth/mfa/totp (disable)', () => {
  it('활성 상태에서 유효코드 123456 → 204 + enabled:false', async () => {
    await postSetup(true)
    await postEnable('123456', true)

    const res = await deleteDisable('123456', true)

    expect(res.status).toBe(204)

    const statusRes = await getStatus()
    const statusBody = await statusRes.json() as MfaStatusBody
    expect(statusBody.enabled).toBe(false)
  })

  it('활성 상태에서 잘못된 코드 → 400 { error: "invalid_code" }', async () => {
    await postSetup(true)
    await postEnable('123456', true)

    const res = await deleteDisable('000000', true)

    expect(res.status).toBe(400)
    const body = await res.json() as MfaErrorBody
    expect(body.error).toBe('invalid_code')
  })

  it('X-XSRF-TOKEN 없음 → 403 (CSRF 가짜그린 차단)', async () => {
    await postSetup(true)
    await postEnable('123456', true)

    const res = await deleteDisable('123456', false)

    expect(res.status).toBe(403)
  })

  it('MFA 비활성 상태에서 disable → 404 { error: "not_enabled" }', async () => {
    const res = await deleteDisable('123456', true)

    expect(res.status).toBe(404)
    const body = await res.json() as MfaErrorBody
    expect(body.error).toBe('not_enabled')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// POST /api/v1/auth/mfa/verify
// ─────────────────────────────────────────────────────────────────────────────

describe('mfaHandlers — POST /api/v1/auth/mfa/verify', () => {
  it('유효한 챌린지 토큰 + 코드 123456 → 200 + { access_token, token_type, expires_in }', async () => {
    const res = await postVerify('mock-mfa-challenge-token', '123456')

    expect(res.status).toBe(200)
    const body = await res.json() as MfaVerifyBody
    expect(body.access_token).toBeTypeOf('string')
    expect(body.access_token.length).toBeGreaterThan(0)
    expect(body.token_type).toBe('Bearer')
    expect(body.expires_in).toBeTypeOf('number')
  })

  it('잘못된 코드 → 401 { error: "invalid_code" }', async () => {
    const res = await postVerify('mock-mfa-challenge-token', '000000')

    expect(res.status).toBe(401)
    const body = await res.json() as MfaErrorBody
    expect(body.error).toBe('invalid_code')
  })

  it('verify는 CSRF 헤더 없어도 200 (permitAll 경로)', async () => {
    const res = await postVerify('mock-mfa-challenge-token', '123456')

    expect(res.status).toBe(200)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// login mfa_required 분기 (E2E 토글)
// ─────────────────────────────────────────────────────────────────────────────

describe('authHandlers — login mfa_required 분기', () => {
  it('MFA_E2E_ENABLED_KEY 플래그 없음 → 기존 token 응답 (회귀 없음)', async () => {
    const res = await postLogin('alice')

    expect(res.status).toBe(200)
    const body = await res.json() as { access_token?: string; mfa_required?: boolean }
    expect(body.access_token).toBeTypeOf('string')
    expect(body.mfa_required).toBeUndefined()
  })

  it('MFA_E2E_ENABLED_KEY = "true" → { mfa_required:true, mfa_challenge_token, expires_in:300 }', async () => {
    try {
      localStorage.setItem(MFA_E2E_ENABLED_KEY, 'true')
    } catch {
      // node에 localStorage 없으면 이 테스트는 브라우저 환경 전용
      return
    }

    const res = await postLogin('alice')

    expect(res.status).toBe(200)
    const body = await res.json() as MfaRequiredBody
    expect(body.mfa_required).toBe(true)
    expect(body.mfa_challenge_token).toBeTypeOf('string')
    expect(body.mfa_challenge_token.length).toBeGreaterThan(0)
    expect(body.expires_in).toBe(300)
  })
})
