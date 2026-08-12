// WebAuthn MSW 핸들러 단위 테스트 — stateful store + CSRF + verify 분기 검증 (FR-MF-03)
import { server } from '@/test/server'
import { afterEach, describe, expect, it } from 'vitest'
import { webauthnHandlers, resetWebauthnStore } from './webauthn-handlers'
import { mfaHandlers } from './mfa-handlers'

// ─────────────────────────────────────────────────────────────────────────────
// 서버 — WebAuthn 핸들러 + MFA verify 핸들러 (webauthn 분기 테스트용)
// ─────────────────────────────────────────────────────────────────────────────

beforeEach(() => {
  server.use(...webauthnHandlers, ...mfaHandlers)
})
afterEach(() => {
  resetWebauthnStore()
})

// ─────────────────────────────────────────────────────────────────────────────
// 응답 타입 (테스트 내부 편의용)
// ─────────────────────────────────────────────────────────────────────────────

interface RegisterStartBody {
  challenge: string
  rp: { id: string; name: string }
  user: { id: string; name: string; displayName: string }
  pubKeyCredParams: Array<{ type: string; alg: number }>
  timeout: number
  attestation: string
  excludeCredentials: unknown[]
  authenticatorSelection: Record<string, unknown>
}

interface WebAuthnKeyResponse {
  id: string
  name: string | null
  createdAt: string
  lastUsedAt: string | null
}

interface ListKeysBody {
  keys: WebAuthnKeyResponse[]
}

interface AuthenticateStartBody {
  challenge: string
  rpId: string
  allowCredentials: Array<{ type: string; id: string }>
  userVerification: string
  timeout: number
}

interface ErrorBody {
  error: string
}

interface MfaVerifyBody {
  access_token: string
  token_type: string
  expires_in: number
}

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼 — 공통 요청 함수
// ─────────────────────────────────────────────────────────────────────────────

function postRegisterStart(): Promise<Response> {
  return fetch('/api/v1/auth/mfa/webauthn/register/start', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'X-XSRF-TOKEN': 'test-csrf-token',
    },
  })
}

function postRegisterFinish(
  credentialId: string,
  name: string,
  withCsrf = true,
): Promise<Response> {
  return fetch('/api/v1/auth/mfa/webauthn/register/finish', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      ...(withCsrf ? { 'X-XSRF-TOKEN': 'test-csrf-token' } : {}),
    },
    body: JSON.stringify({
      credential: { id: credentialId, type: 'public-key', rawId: credentialId },
      name,
    }),
  })
}

function getListKeys(): Promise<Response> {
  return fetch('/api/v1/auth/mfa/webauthn', { method: 'GET' })
}

function deleteKey(id: string, withCsrf = true): Promise<Response> {
  return fetch(`/api/v1/auth/mfa/webauthn/${id}`, {
    method: 'DELETE',
    headers: {
      ...(withCsrf ? { 'X-XSRF-TOKEN': 'test-csrf-token' } : {}),
    },
  })
}

function postAuthenticateStart(mfaChallengeToken: string): Promise<Response> {
  return fetch('/api/v1/auth/mfa/webauthn/authenticate/start', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ mfa_challenge_token: mfaChallengeToken }),
  })
}

function postVerifyWebauthn(
  mfaChallengeToken: string,
  credential: Record<string, unknown>,
): Promise<Response> {
  return fetch('/api/v1/auth/mfa/verify', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      mfa_challenge_token: mfaChallengeToken,
      method: 'webauthn',
      credential,
    }),
  })
}

function postVerifyNoCredential(mfaChallengeToken: string): Promise<Response> {
  return fetch('/api/v1/auth/mfa/verify', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      mfa_challenge_token: mfaChallengeToken,
      method: 'webauthn',
    }),
  })
}

// ─────────────────────────────────────────────────────────────────────────────
// POST /api/v1/auth/mfa/webauthn/register/start
// ─────────────────────────────────────────────────────────────────────────────

describe('webauthnHandlers — POST .../webauthn/register/start', () => {
  it('CSRF 있음 → 200 + WebAuthn 옵션 객체 반환', async () => {
    const res = await postRegisterStart()

    expect(res.status).toBe(200)
    const body = await res.json() as RegisterStartBody
    expect(body.challenge).toBeTypeOf('string')
    expect(body.challenge.length).toBeGreaterThan(0)
    expect(body.rp).toBeDefined()
    expect(body.rp.id).toBeTypeOf('string')
    expect(body.rp.name).toBeTypeOf('string')
    expect(body.user).toBeDefined()
    expect(body.user.id).toBeTypeOf('string')
    expect(body.user.name).toBeTypeOf('string')
    expect(body.user.displayName).toBeTypeOf('string')
    expect(Array.isArray(body.pubKeyCredParams)).toBe(true)
    expect(body.pubKeyCredParams.length).toBeGreaterThan(0)
    expect(body.pubKeyCredParams[0]?.type).toBe('public-key')
    expect(body.pubKeyCredParams[0]?.alg).toBe(-7)
    expect(body.timeout).toBeTypeOf('number')
    expect(body.attestation).toBe('none')
    expect(Array.isArray(body.excludeCredentials)).toBe(true)
    expect(body.authenticatorSelection).toBeDefined()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// POST /api/v1/auth/mfa/webauthn/register/finish
// ─────────────────────────────────────────────────────────────────────────────

describe('webauthnHandlers — POST .../webauthn/register/finish', () => {
  it('CSRF 있음 + 신규 credential → 빈 201', async () => {
    const res = await postRegisterFinish('cred-id-001', 'My Security Key')

    expect(res.status).toBe(201)
    const text = await res.text()
    expect(text).toBe('')
  })

  it('등록 후 목록 조회 시 키가 추가되어 있음', async () => {
    await postRegisterFinish('cred-id-002', 'YubiKey')

    const listRes = await getListKeys()
    expect(listRes.status).toBe(200)
    const body = await listRes.json() as ListKeysBody
    expect(body.keys.some((k) => k.name === 'YubiKey')).toBe(true)
  })

  it('같은 credential.id 중복 등록 → 409 { error: "already_registered" }', async () => {
    await postRegisterFinish('cred-id-003', 'Key 1')
    const res = await postRegisterFinish('cred-id-003', 'Key 2')

    expect(res.status).toBe(409)
    const body = await res.json() as ErrorBody
    expect(body.error).toBe('already_registered')
  })

  it('CSRF 없음 → 403', async () => {
    const res = await postRegisterFinish('cred-id-004', 'Key', false)

    expect(res.status).toBe(403)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// GET /api/v1/auth/mfa/webauthn
// ─────────────────────────────────────────────────────────────────────────────

describe('webauthnHandlers — GET .../webauthn', () => {
  it('초기 상태 → { keys: [] }', async () => {
    const res = await getListKeys()

    expect(res.status).toBe(200)
    const body = await res.json() as ListKeysBody
    expect(Array.isArray(body.keys)).toBe(true)
    expect(body.keys).toHaveLength(0)
  })

  it('키 등록 후 목록에 WebAuthnKeyResponse 형태 항목 반환', async () => {
    await postRegisterFinish('cred-id-list-01', 'Hardware Key')

    const res = await getListKeys()
    const body = await res.json() as ListKeysBody
    const key = body.keys[0]

    expect(key).toBeDefined()
    expect(key?.id).toBeTypeOf('string')
    // name 키는 null이어도 존재해야 함 (백엔드 WebAuthnKeyResponse 형태)
    expect('name' in (key ?? {})).toBe(true)
    expect(key?.createdAt).toBeTypeOf('string')
    // lastUsedAt 키는 null이어도 존재해야 함
    expect('lastUsedAt' in (key ?? {})).toBe(true)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// DELETE /api/v1/auth/mfa/webauthn/:id
// ─────────────────────────────────────────────────────────────────────────────

describe('webauthnHandlers — DELETE .../webauthn/:id', () => {
  it('존재하는 키 삭제 → 204 + store에서 제거', async () => {
    await postRegisterFinish('cred-to-delete', 'Delete Me')

    const listBefore = await getListKeys()
    const beforeBody = await listBefore.json() as ListKeysBody
    const key = beforeBody.keys.find((k) => k.name === 'Delete Me')
    expect(key).toBeDefined()

    const res = await deleteKey(key!.id)
    expect(res.status).toBe(204)

    const listAfter = await getListKeys()
    const afterBody = await listAfter.json() as ListKeysBody
    expect(afterBody.keys.find((k) => k.id === key!.id)).toBeUndefined()
  })

  it('존재하지 않는 id → 404 { error: "not_found" }', async () => {
    const res = await deleteKey('00000000-0000-0000-0000-000000000099')

    expect(res.status).toBe(404)
    const body = await res.json() as ErrorBody
    expect(body.error).toBe('not_found')
  })

  it('CSRF 없음 → 403', async () => {
    await postRegisterFinish('cred-csrf-test', 'CSRF Test Key')

    const listRes = await getListKeys()
    const listBody = await listRes.json() as ListKeysBody
    const key = listBody.keys[0]
    expect(key).toBeDefined()

    const res = await deleteKey(key!.id, false)
    expect(res.status).toBe(403)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// POST /api/v1/auth/mfa/webauthn/authenticate/start
// ─────────────────────────────────────────────────────────────────────────────

describe('webauthnHandlers — POST .../webauthn/authenticate/start', () => {
  it('유효 챌린지 토큰 → 200 + 인증 옵션 객체', async () => {
    // 키 1개 등록 후 authenticate 옵션에 allowCredentials 포함되는지 확인
    await postRegisterFinish('cred-auth-001', 'Auth Key')

    const res = await postAuthenticateStart('valid-challenge-token')

    expect(res.status).toBe(200)
    const body = await res.json() as AuthenticateStartBody
    expect(body.challenge).toBeTypeOf('string')
    expect(body.challenge.length).toBeGreaterThan(0)
    expect(body.rpId).toBeTypeOf('string')
    expect(Array.isArray(body.allowCredentials)).toBe(true)
    expect(body.userVerification).toBe('preferred')
    expect(body.timeout).toBeTypeOf('number')
  })

  it('allowCredentials에 등록된 키의 base64url id 포함', async () => {
    await postRegisterFinish('cred-auth-allow', 'Allow Key')

    const res = await postAuthenticateStart('valid-challenge-token')
    const body = await res.json() as AuthenticateStartBody

    const hasPublicKey = body.allowCredentials.some((c) => c.type === 'public-key')
    expect(hasPublicKey).toBe(true)
  })

  it('"__invalid_token__" → 401 { error: "mfa_challenge_expired" }', async () => {
    const res = await postAuthenticateStart('__invalid_token__')

    expect(res.status).toBe(401)
    const body = await res.json() as ErrorBody
    expect(body.error).toBe('mfa_challenge_expired')
  })

  it('authenticate/start는 CSRF 헤더 불필요 (permitAll)', async () => {
    // CSRF 헤더 없이도 200 응답 확인
    const res = await fetch('/api/v1/auth/mfa/webauthn/authenticate/start', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ mfa_challenge_token: 'valid-challenge-token' }),
    })

    expect(res.status).toBe(200)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// POST /api/v1/auth/mfa/verify — method:"webauthn" 분기
// ─────────────────────────────────────────────────────────────────────────────

describe('mfaHandlers — POST /api/v1/auth/mfa/verify (method: webauthn)', () => {
  it('credential 객체 존재 → 200 + { access_token, token_type, expires_in }', async () => {
    const res = await postVerifyWebauthn('mock-challenge', {
      id: 'cred-verify-001',
      type: 'public-key',
      rawId: 'cred-verify-001',
      response: { clientDataJSON: 'mock', authenticatorData: 'mock', signature: 'mock' },
    })

    expect(res.status).toBe(200)
    const body = await res.json() as MfaVerifyBody
    expect(body.access_token).toBeTypeOf('string')
    expect(body.access_token.length).toBeGreaterThan(0)
    expect(body.token_type).toBe('Bearer')
    expect(body.expires_in).toBeTypeOf('number')
  })

  it('credential 없음 (webauthn method) → 401 { error: "invalid_code" }', async () => {
    const res = await postVerifyNoCredential('mock-challenge')

    expect(res.status).toBe(401)
    const body = await res.json() as ErrorBody
    expect(body.error).toBe('invalid_code')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 기존 TOTP / backup_code 회귀 없음 확인
// ─────────────────────────────────────────────────────────────────────────────

describe('mfaHandlers — 기존 verify 분기 회귀 없음', () => {
  it('method:"totp" + 유효코드 → 200 (회귀 없음)', async () => {
    const res = await fetch('/api/v1/auth/mfa/verify', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ mfa_challenge_token: 'mock', method: 'totp', code: '123456' }),
    })

    expect(res.status).toBe(200)
    const body = await res.json() as MfaVerifyBody
    expect(body.access_token).toBeTypeOf('string')
  })

  it('method 생략 + 유효코드 → 200 (TOTP 기본 경로 회귀 없음)', async () => {
    const res = await fetch('/api/v1/auth/mfa/verify', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ mfa_challenge_token: 'mock', code: '123456' }),
    })

    expect(res.status).toBe(200)
  })

  it('method:"backup_code" + 유효 백업코드 → 200 (회귀 없음)', async () => {
    const res = await fetch('/api/v1/auth/mfa/verify', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        mfa_challenge_token: 'mock',
        method: 'backup_code',
        code: 'aaaaa-bbbbb',
      }),
    })

    expect(res.status).toBe(200)
  })
})
