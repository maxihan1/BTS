// identity-access BC auth MSW 핸들러 단위 테스트 — 응답 schema 및 에러 분기 검증
import { setupServer } from 'msw/node'
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest'
import { authHandlers } from './auth-handlers'

const server = setupServer(...authHandlers)

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }))
afterEach(() => server.resetHandlers())
afterAll(() => server.close())

describe('authHandlers — POST /api/v1/auth/login', () => {
  it('alice/password → 200 + access_token + token_type Bearer', async () => {
    const res = await fetch('/api/v1/auth/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ provider: 'local', username: 'alice', password: 'password' }),
    })

    expect(res.status).toBe(200)
    const body = await res.json() as { access_token: string; token_type: string; expires_in: number }
    expect(body.access_token).toBeTypeOf('string')
    expect(body.access_token.length).toBeGreaterThan(0)
    expect(body.token_type).toBe('Bearer')
    expect(body.expires_in).toBeTypeOf('number')
  })

  it('alice/wrong → 401 + { error: "invalid_credentials" }', async () => {
    const res = await fetch('/api/v1/auth/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ provider: 'local', username: 'alice', password: 'wrong' }),
    })

    expect(res.status).toBe(401)
    const body = await res.json() as { error: string }
    expect(body.error).toBe('invalid_credentials')
  })
})

describe('authHandlers — POST /api/v1/auth/login (ldap-corp)', () => {
  it('S3-ldap happy: provider=ldap-corp + alice/Test1234! → 200 + access_token + token_type Bearer', async () => {
    const res = await fetch('/api/v1/auth/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ provider: 'ldap-corp', username: 'alice', password: 'Test1234!' }),
    })

    expect(res.status).toBe(200)
    const body = await res.json() as { access_token: string; token_type: string; expires_in: number }
    expect(body.access_token).toBeTypeOf('string')
    expect(body.access_token.length).toBeGreaterThan(0)
    expect(body.token_type).toBe('Bearer')
  })

  it('S4-ldap invalid: provider=ldap-corp + alice/wrong → 401 + { error: "invalid_credentials" }', async () => {
    const res = await fetch('/api/v1/auth/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ provider: 'ldap-corp', username: 'alice', password: 'wrong' }),
    })

    expect(res.status).toBe(401)
    const body = await res.json() as { error: string }
    expect(body.error).toBe('invalid_credentials')
  })

  it('S5-ldap-iso: provider=ldap-corp + alice + local password("password") → 401 (provider isolation)', async () => {
    const res = await fetch('/api/v1/auth/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ provider: 'ldap-corp', username: 'alice', password: 'password' }),
    })

    expect(res.status).toBe(401)
  })

  it('S6-unknown-provider-missing: provider 누락 → 401 + { error: "unknown_provider" } (silent local fallback 차단)', async () => {
    const res = await fetch('/api/v1/auth/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username: 'alice', password: 'password' }),
    })

    expect(res.status).toBe(401)
    expect(await res.json()).toEqual({ error: 'unknown_provider' })
  })

  it('S7-unknown-provider-value: provider="saml" (미지원) → 401 + { error: "unknown_provider" }', async () => {
    const res = await fetch('/api/v1/auth/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ provider: 'saml', username: 'alice', password: 'anything' }),
    })

    expect(res.status).toBe(401)
    expect(await res.json()).toEqual({ error: 'unknown_provider' })
  })
})

describe('authHandlers — GET /api/v1/users/me/whoami', () => {
  it('Authorization: Bearer alice-token → 200 + alice user info', async () => {
    const res = await fetch('/api/v1/users/me/whoami', {
      headers: { Authorization: 'Bearer mock-access-token-alice' },
    })

    expect(res.status).toBe(200)
    const body = await res.json() as {
      username: string
      email: string
      authMethod: string
      userId: string
      mustChangePassword: boolean
      isSystemAdmin: boolean
      mfaEnrollmentRequired: boolean
    }
    expect(body.username).toBe('alice')
    expect(body.email).toBeTypeOf('string')
    expect(body.authMethod).toBe('jwt')
    expect(body.userId).toBeTypeOf('string')
    expect(body.mustChangePassword).toBeTypeOf('boolean')
    expect(body.isSystemAdmin).toBeTypeOf('boolean')
    expect(body.mfaEnrollmentRequired).toBeTypeOf('boolean')
  })

  it('Authorization 헤더 없음 → 401', async () => {
    const res = await fetch('/api/v1/users/me/whoami')

    expect(res.status).toBe(401)
  })
})
