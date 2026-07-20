// @vitest-environment node
// LoginRequest / TokenResponse / WhoamiResponse / ApiErrorResponse Zod 스키마 검증 테스트
import { describe, it, expect } from 'vitest'
import {
  LoginRequestSchema,
  TokenResponseSchema,
  WhoamiResponseSchema,
  ApiErrorResponseSchema,
  WebauthnKeysResponseSchema,
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
      mfaEnrollmentRequired: false,
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
      mfaEnrollmentRequired: false,
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
      mfaEnrollmentRequired: false,
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
      mfaEnrollmentRequired: false,
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
      mfaEnrollmentRequired: false,
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
      mfaEnrollmentRequired: false,
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
      mfaEnrollmentRequired: false,
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

  it('displayName/avatarUrl 값이 있으면 parse 성공하고 값을 그대로 노출한다', () => {
    const result = WhoamiResponseSchema.parse({
      username: 'alice',
      email: 'alice@example.com',
      authMethod: 'local',
      userId: 'usr-0001',
      mustChangePassword: false,
      isSystemAdmin: false,
      mfaEnrollmentRequired: false,
      displayName: 'Alice Kim',
      avatarUrl: '/api/v1/users/usr-0001/avatar',
    })
    expect(result.displayName).toBe('Alice Kim')
    expect(result.avatarUrl).toBe('/api/v1/users/usr-0001/avatar')
  })

  it('displayName/avatarUrl이 null이어도 parse 성공한다', () => {
    const result = WhoamiResponseSchema.parse({
      username: 'alice',
      email: 'alice@example.com',
      authMethod: 'local',
      userId: 'usr-0001',
      mustChangePassword: false,
      isSystemAdmin: false,
      mfaEnrollmentRequired: false,
      displayName: null,
      avatarUrl: null,
    })
    expect(result.displayName).toBeNull()
    expect(result.avatarUrl).toBeNull()
  })

  it('displayName/avatarUrl 키가 없어도 parse 성공한다 (하위호환 — 기존 인라인 mock)', () => {
    const result = WhoamiResponseSchema.parse({
      username: 'alice',
      email: 'alice@example.com',
      authMethod: 'local',
      userId: 'usr-0001',
      mustChangePassword: false,
      isSystemAdmin: false,
      mfaEnrollmentRequired: false,
    })
    expect(result.displayName).toBeUndefined()
    expect(result.avatarUrl).toBeUndefined()
  })

  it('oooActive/oooUntil 값이 있으면 parse 성공하고 값을 그대로 노출한다 (FR-PR-03)', () => {
    const result = WhoamiResponseSchema.parse({
      username: 'alice',
      email: 'alice@example.com',
      authMethod: 'local',
      userId: 'usr-0001',
      mustChangePassword: false,
      isSystemAdmin: false,
      mfaEnrollmentRequired: false,
      oooActive: true,
      oooUntil: '2026-07-14T00:00:00Z',
    })
    expect(result.oooActive).toBe(true)
    expect(result.oooUntil).toBe('2026-07-14T00:00:00Z')
  })

  it('oooActive:false·oooUntil:null이어도 parse 성공한다 (FR-PR-03 비활성)', () => {
    const result = WhoamiResponseSchema.parse({
      username: 'alice',
      email: 'alice@example.com',
      authMethod: 'local',
      userId: 'usr-0001',
      mustChangePassword: false,
      isSystemAdmin: false,
      mfaEnrollmentRequired: false,
      oooActive: false,
      oooUntil: null,
    })
    expect(result.oooActive).toBe(false)
    expect(result.oooUntil).toBeNull()
  })

  it('oooActive/oooUntil 키가 없어도 parse 성공한다 (하위호환 — 기존 인라인 whoami mock, mock fanout 방어)', () => {
    const result = WhoamiResponseSchema.parse({
      username: 'alice',
      email: 'alice@example.com',
      authMethod: 'local',
      userId: 'usr-0001',
      mustChangePassword: false,
      isSystemAdmin: false,
      mfaEnrollmentRequired: false,
    })
    expect(result.oooActive).toBeUndefined()
    expect(result.oooUntil).toBeUndefined()
  })

  it('canCreateProject 값이 있으면 parse 성공하고 값을 그대로 노출한다 (FR-PJ-01/FR-PM-10)', () => {
    const result = WhoamiResponseSchema.parse({
      username: 'alice',
      email: 'alice@example.com',
      authMethod: 'jwt',
      userId: 'usr-0001',
      mustChangePassword: false,
      isSystemAdmin: false,
      mfaEnrollmentRequired: false,
      canCreateProject: true,
    })
    expect(result.canCreateProject).toBe(true)
  })

  it('canCreateProject:false 값도 parse 성공한다', () => {
    const result = WhoamiResponseSchema.parse({
      username: 'alice',
      email: 'alice@example.com',
      authMethod: 'jwt',
      userId: 'usr-0001',
      mustChangePassword: false,
      isSystemAdmin: false,
      mfaEnrollmentRequired: false,
      canCreateProject: false,
    })
    expect(result.canCreateProject).toBe(false)
  })

  it('canCreateProject 키가 없어도 parse 성공한다 (하위호환 — 기존 인라인 whoami mock, mock fanout 방어)', () => {
    const result = WhoamiResponseSchema.parse({
      username: 'alice',
      email: 'alice@example.com',
      authMethod: 'jwt',
      userId: 'usr-0001',
      mustChangePassword: false,
      isSystemAdmin: false,
      mfaEnrollmentRequired: false,
    })
    expect(result.canCreateProject).toBeUndefined()
  })
})

describe('WhoamiResponseSchema — theme/locale/dateFormat 확장 (FR-PF-01)', () => {
  const BASE_WHOAMI = {
    username: 'alice',
    email: 'alice@example.com',
    authMethod: 'jwt',
    userId: 'usr-0001',
    mustChangePassword: false,
    isSystemAdmin: false,
    mfaEnrollmentRequired: false,
  }

  it('theme/locale/dateFormat 값이 있으면 파싱 성공하고 값을 그대로 노출한다', () => {
    const result = WhoamiResponseSchema.parse({
      ...BASE_WHOAMI,
      theme: 'dark',
      locale: 'en',
      dateFormat: 'us',
    })
    expect(result.theme).toBe('dark')
    expect(result.locale).toBe('en')
    expect(result.dateFormat).toBe('us')
  })

  it('theme/locale/dateFormat 키가 없어도 parse 성공한다 (하위호환 — 기존 인라인 whoami mock, mock fanout 방어)', () => {
    const result = WhoamiResponseSchema.parse(BASE_WHOAMI)
    expect(result.theme).toBeUndefined()
    expect(result.locale).toBeUndefined()
    expect(result.dateFormat).toBeUndefined()
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

describe('WebauthnKeysResponseSchema', () => {
  const validKey = {
    id: '550e8400-e29b-41d4-a716-446655440000',
    name: '회사 노트북',
    createdAt: '2026-06-13T10:00:00Z',
    lastUsedAt: null,
  }

  it('정상 WebauthnKeysResponse → parse 성공', () => {
    const result = WebauthnKeysResponseSchema.parse({ keys: [validKey] })
    expect(result.keys).toHaveLength(1)
    expect(result.keys[0]?.id).toBe('550e8400-e29b-41d4-a716-446655440000')
    expect(result.keys[0]?.name).toBe('회사 노트북')
    expect(result.keys[0]?.lastUsedAt).toBeNull()
  })

  it('name이 null인 키 → parse 성공', () => {
    const result = WebauthnKeysResponseSchema.parse({
      keys: [{ ...validKey, name: null }],
    })
    expect(result.keys[0]?.name).toBeNull()
  })

  it('lastUsedAt에 ISO 문자열이 있는 키 → parse 성공', () => {
    const result = WebauthnKeysResponseSchema.parse({
      keys: [{ ...validKey, lastUsedAt: '2026-06-12T08:30:00Z' }],
    })
    expect(result.keys[0]?.lastUsedAt).toBe('2026-06-12T08:30:00Z')
  })

  it('빈 keys 배열 → parse 성공', () => {
    const result = WebauthnKeysResponseSchema.parse({ keys: [] })
    expect(result.keys).toHaveLength(0)
  })

  it('id가 유효하지 않은 UUID → safeParse fail', () => {
    const result = WebauthnKeysResponseSchema.safeParse({
      keys: [{ ...validKey, id: 'not-a-uuid' }],
    })
    expect(result.success).toBe(false)
  })

  it('id 누락 → safeParse fail', () => {
    // eslint-disable-next-line @typescript-eslint/no-unused-vars
    const { id: _id, ...withoutId } = validKey
    const result = WebauthnKeysResponseSchema.safeParse({ keys: [withoutId] })
    expect(result.success).toBe(false)
  })

  it('createdAt 누락 → safeParse fail', () => {
    // eslint-disable-next-line @typescript-eslint/no-unused-vars
    const { createdAt: _createdAt, ...withoutCreatedAt } = validKey
    const result = WebauthnKeysResponseSchema.safeParse({ keys: [withoutCreatedAt] })
    expect(result.success).toBe(false)
  })

  it('keys 필드 누락 → safeParse fail', () => {
    const result = WebauthnKeysResponseSchema.safeParse({})
    expect(result.success).toBe(false)
  })

  it('createdAt은 string 무변환 — Date 객체로 변환하지 않는다', () => {
    const result = WebauthnKeysResponseSchema.parse({ keys: [validKey] })
    expect(typeof result.keys[0]?.createdAt).toBe('string')
  })
})
