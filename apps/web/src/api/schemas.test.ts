// @vitest-environment node
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

  it('provider ldap → safeParse success', () => {
    const result = LoginRequestSchema.safeParse({
      provider: 'ldap',
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

  it('provider 빈 문자열 → safeParse fail', () => {
    const result = LoginRequestSchema.safeParse({
      provider: '',
      username: 'alice',
      password: 'secret123',
    })
    expect(result.success).toBe(false)
  })

  it('provider 임의 id(동적 목록) → safeParse success', () => {
    // FR-AU-06: provider 목록은 /api/v1/auth/providers 로 동적 조회되므로
    // 클라이언트 스키마는 non-empty string 만 검증하고, 실제 유효성은 백엔드(401)가 판정한다.
    const result = LoginRequestSchema.safeParse({
      provider: 'saml',
      username: 'alice',
      password: 'secret123',
    })
    expect(result.success).toBe(true)
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
      mustChangePassword: false,
      isSystemAdmin: false,
    })
    expect(result.success).toBe(true)
  })

  it('username 누락 → safeParse fail', () => {
    const result = WhoamiResponseSchema.safeParse({
      email: 'alice@example.com',
      authMethod: 'local',
      userId: 'usr-0001',
      mustChangePassword: false,
      isSystemAdmin: false,
    })
    expect(result.success).toBe(false)
  })

  it('userId 누락 → safeParse fail', () => {
    const result = WhoamiResponseSchema.safeParse({
      username: 'alice',
      email: 'alice@example.com',
      authMethod: 'local',
      mustChangePassword: false,
      isSystemAdmin: false,
    })
    expect(result.success).toBe(false)
  })

  it('mustChangePassword 누락 → safeParse fail', () => {
    const result = WhoamiResponseSchema.safeParse({
      username: 'alice',
      email: 'alice@example.com',
      authMethod: 'local',
      userId: 'usr-0001',
      isSystemAdmin: false,
    })
    expect(result.success).toBe(false)
  })

  it('isSystemAdmin 누락 → safeParse fail', () => {
    const result = WhoamiResponseSchema.safeParse({
      username: 'alice',
      email: 'alice@example.com',
      authMethod: 'local',
      userId: 'usr-0001',
      mustChangePassword: false,
    })
    expect(result.success).toBe(false)
  })

  it('mustChangePassword boolean true → safeParse success', () => {
    const result = WhoamiResponseSchema.safeParse({
      username: 'alice',
      email: 'alice@example.com',
      authMethod: 'local',
      userId: 'usr-0001',
      mustChangePassword: true,
      isSystemAdmin: false,
    })
    expect(result.success).toBe(true)
  })

  it('isSystemAdmin boolean true → safeParse success', () => {
    const result = WhoamiResponseSchema.safeParse({
      username: 'alice',
      email: 'alice@example.com',
      authMethod: 'local',
      userId: 'usr-0001',
      mustChangePassword: false,
      isSystemAdmin: true,
    })
    expect(result.success).toBe(true)
  })

  it('whoami 스키마는 mfaEnrollmentRequired(boolean)를 필수로 요구한다', () => {
    const base = {
      username: 'alice',
      email: 'a@bts.local',
      authMethod: 'jwt',
      userId: '00000000-0000-0000-0000-000000000001',
      mustChangePassword: false,
      isSystemAdmin: false,
    }
    expect(() => WhoamiResponseSchema.parse(base)).toThrow()
    expect(
      WhoamiResponseSchema.parse({ ...base, mfaEnrollmentRequired: true }).mfaEnrollmentRequired,
    ).toBe(true)
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
