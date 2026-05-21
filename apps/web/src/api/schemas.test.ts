// LoginRequest / TokenResponse / WhoamiResponse / ApiErrorResponse Zod 스키마 검증 테스트
import { describe, it, expect } from 'vitest'
import {
  LoginRequestSchema,
  TokenResponseSchema,
  WhoamiResponseSchema,
  ApiErrorResponseSchema,
} from './schemas'

describe('LoginRequestSchema', () => {
  it('정상 LoginRequest → safeParse success', () => {
    const result = LoginRequestSchema.safeParse({
      provider: 'local',
      username: 'alice',
      password: 'secret123',
    })
    expect(result.success).toBe(true)
  })

  it('provider ldap-corp → safeParse success', () => {
    const result = LoginRequestSchema.safeParse({
      provider: 'ldap-corp',
      username: 'alice',
      password: 'secret123',
    })
    expect(result.success).toBe(true)
  })

  it('provider 누락 → safeParse fail', () => {
    const result = LoginRequestSchema.safeParse({
      username: 'alice',
      password: 'secret123',
    })
    expect(result.success).toBe(false)
  })

  it('provider 잘못된 값(oidc) → safeParse fail', () => {
    const result = LoginRequestSchema.safeParse({
      provider: 'oidc',
      username: 'alice',
      password: 'secret123',
    })
    expect(result.success).toBe(false)
  })

  it('username 빈 문자열 → safeParse fail', () => {
    const result = LoginRequestSchema.safeParse({
      provider: 'local',
      username: '',
      password: 'secret123',
    })
    expect(result.success).toBe(false)
  })

  it('password 빈 문자열 → safeParse fail', () => {
    const result = LoginRequestSchema.safeParse({
      provider: 'local',
      username: 'alice',
      password: '',
    })
    expect(result.success).toBe(false)
  })
})

describe('TokenResponseSchema', () => {
  it('정상 TokenResponse → safeParse success', () => {
    const result = TokenResponseSchema.safeParse({
      access_token: 'eyJhbGciOiJSUzI1NiJ9.payload.sig',
      token_type: 'Bearer',
      expires_in: 900,
    })
    expect(result.success).toBe(true)
  })

  it('token_type Bearer 이외 값 → safeParse fail', () => {
    const result = TokenResponseSchema.safeParse({
      access_token: 'eyJhbGciOiJSUzI1NiJ9.payload.sig',
      token_type: 'Basic',
      expires_in: 900,
    })
    expect(result.success).toBe(false)
  })

  it('access_token 누락 → safeParse fail', () => {
    const result = TokenResponseSchema.safeParse({
      token_type: 'Bearer',
      expires_in: 900,
    })
    expect(result.success).toBe(false)
  })

  it('access_token 빈 문자열 → safeParse fail', () => {
    const result = TokenResponseSchema.safeParse({
      access_token: '',
      token_type: 'Bearer',
      expires_in: 900,
    })
    expect(result.success).toBe(false)
  })

  it('expires_in 누락 → safeParse fail', () => {
    const result = TokenResponseSchema.safeParse({
      access_token: 'eyJhbGciOiJSUzI1NiJ9.payload.sig',
      token_type: 'Bearer',
    })
    expect(result.success).toBe(false)
  })
})

describe('WhoamiResponseSchema', () => {
  it('정상 WhoamiResponse → safeParse success', () => {
    const result = WhoamiResponseSchema.safeParse({
      username: 'alice',
      email: 'alice@example.com',
      authMethod: 'local',
      userId: 'usr-0001',
    })
    expect(result.success).toBe(true)
  })

  it('username 누락 → safeParse fail', () => {
    const result = WhoamiResponseSchema.safeParse({
      email: 'alice@example.com',
      authMethod: 'local',
      userId: 'usr-0001',
    })
    expect(result.success).toBe(false)
  })

  it('userId 누락 → safeParse fail', () => {
    const result = WhoamiResponseSchema.safeParse({
      username: 'alice',
      email: 'alice@example.com',
      authMethod: 'local',
    })
    expect(result.success).toBe(false)
  })
})

describe('ApiErrorResponseSchema', () => {
  it('정상 ApiErrorResponse → safeParse success', () => {
    const result = ApiErrorResponseSchema.safeParse({
      error: 'invalid_credentials',
    })
    expect(result.success).toBe(true)
  })

  it('알려진 백엔드 에러 코드들 → safeParse success', () => {
    const codes = [
      'mfa_required',
      'refresh_token_expired',
      'refresh_token_reused',
      'refresh_token_invalid',
    ]
    for (const code of codes) {
      const result = ApiErrorResponseSchema.safeParse({ error: code })
      expect(result.success).toBe(true)
    }
  })

  it('error 필드 누락 → safeParse fail', () => {
    const result = ApiErrorResponseSchema.safeParse({})
    expect(result.success).toBe(false)
  })
})
