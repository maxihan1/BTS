// 계정 연결 API 클라이언트 단위 테스트 — MSW로 HTTP 가로채기 + Zod 파싱 + CSRF 헤더 검증
import { describe, it, expect, beforeEach } from 'vitest'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import {
  accountLinkSchema,
  accountLinksResponseSchema,
  reauthResponseSchema,
  ssoLinkStartResponseSchema,
  linkableProviderSchema,
  fetchAccountLinks,
  fetchLinkableProviders,
  reauth,
  linkAccount,
  unlinkAccount,
  ssoLinkStart,
  ssoReauthStart,
} from './account-links'
import { ApiError } from './client'

// ─────────────────────────────────────────────────────────────────────────────
// Fixture — 백엔드 DTO와 1:1 정합
// ─────────────────────────────────────────────────────────────────────────────
const linkFixture = {
  id: 'a1b2c3d4-e5f6-4890-abcd-ef1234567890',
  providerId: 'b2c3d4e5-f6a7-5901-bcde-f01234567891',
  providerName: 'LDAP 사내',
  providerType: 'LDAP',
  providerEnabled: true,
  externalSubjectMasked: 'joh***@example.com',
  linkedAt: '2026-05-01T09:00:00Z',
  lastLoginAt: '2026-06-01T08:00:00Z',
}

const linkFixtureNullable = {
  id: 'c3d4e5f6-a7b8-6012-cdef-012345678902',
  providerId: 'd4e5f6a7-b8c9-7123-def0-123456789013',
  providerName: null,
  providerType: null,
  providerEnabled: false,
  externalSubjectMasked: 'use***',
  linkedAt: '2026-04-01T07:00:00Z',
  lastLoginAt: null,
}

const accountLinksFixture = {
  links: [linkFixture, linkFixtureNullable],
  hasLocalPassword: true,
}

const reauthResponseFixture = {
  stepUpExpiresAt: '2026-06-10T10:30:00Z',
}

const ssoLinkStartResponseFixture = {
  authorizeUrl: 'https://idp.example.com/sso/saml?SAMLRequest=xxx',
}

const ldapLinkableProvider = {
  kind: 'LDAP',
  providerId: 'e5f6a7b8-c9d0-8234-ef01-234567890124',
  displayName: 'LDAP 사내',
}

const samlLinkableProvider = {
  kind: 'SAML',
  registrationId: 'company-saml',
  displayName: 'Okta SAML',
}

const oidcLinkableProvider = {
  kind: 'OIDC',
  registrationId: 'google-oidc',
  displayName: 'Google OIDC',
}

const linkableProvidersFixture = {
  linkable: [ldapLinkableProvider, samlLinkableProvider, oidcLinkableProvider],
}

// ─────────────────────────────────────────────────────────────────────────────
// MSW 핸들러 기본 설정
// ─────────────────────────────────────────────────────────────────────────────
beforeEach(() => {
  document.cookie = 'XSRF-TOKEN=test-csrf-token'

  server.use(
    http.get('/api/v1/auth/account/links', () => {
      return HttpResponse.json(accountLinksFixture)
    }),
    http.get('/api/v1/auth/account/linkable-providers', () => {
      return HttpResponse.json(linkableProvidersFixture)
    }),
    http.post('/api/v1/auth/account/reauth', ({ request }) => {
      const xsrf = request.headers.get('X-XSRF-TOKEN')
      if (xsrf === null || xsrf === '') {
        return HttpResponse.json({ message: 'Missing CSRF' }, { status: 403 })
      }
      return HttpResponse.json(reauthResponseFixture)
    }),
    http.post('/api/v1/auth/account/links', ({ request }) => {
      const xsrf = request.headers.get('X-XSRF-TOKEN')
      if (xsrf === null || xsrf === '') {
        return HttpResponse.json({ message: 'Missing CSRF' }, { status: 403 })
      }
      return HttpResponse.json(linkFixture)
    }),
    http.delete('/api/v1/auth/account/links/:id', ({ request }) => {
      const xsrf = request.headers.get('X-XSRF-TOKEN')
      if (xsrf === null || xsrf === '') {
        return HttpResponse.json({ message: 'Missing CSRF' }, { status: 403 })
      }
      return new HttpResponse(null, { status: 204 })
    }),
    http.post('/api/v1/auth/account/links/sso/start', ({ request }) => {
      const xsrf = request.headers.get('X-XSRF-TOKEN')
      if (xsrf === null || xsrf === '') {
        return HttpResponse.json({ message: 'Missing CSRF' }, { status: 403 })
      }
      return HttpResponse.json(ssoLinkStartResponseFixture)
    }),
    http.post('/api/v1/auth/account/reauth/sso/start', ({ request }) => {
      const xsrf = request.headers.get('X-XSRF-TOKEN')
      if (xsrf === null || xsrf === '') {
        return HttpResponse.json({ message: 'Missing CSRF' }, { status: 403 })
      }
      return HttpResponse.json(ssoLinkStartResponseFixture)
    }),
  )
})

// ─────────────────────────────────────────────────────────────────────────────
// T1. accountLinkSchema — DTO 필드 파싱
// ─────────────────────────────────────────────────────────────────────────────
describe('accountLinkSchema', () => {
  it('T1-a: 모든 필드가 있는 링크를 파싱한다', () => {
    const result = accountLinkSchema.parse(linkFixture)
    expect(result.id).toBe('a1b2c3d4-e5f6-4890-abcd-ef1234567890')
    expect(result.providerId).toBe('b2c3d4e5-f6a7-5901-bcde-f01234567891')
    expect(result.providerName).toBe('LDAP 사내')
    expect(result.providerType).toBe('LDAP')
    expect(result.providerEnabled).toBe(true)
    expect(result.externalSubjectMasked).toBe('joh***@example.com')
    expect(result.linkedAt).toBe('2026-05-01T09:00:00Z')
    expect(result.lastLoginAt).toBe('2026-06-01T08:00:00Z')
  })

  it('T1-b: providerName/providerType/lastLoginAt이 null인 링크도 파싱 성공', () => {
    const result = accountLinkSchema.parse(linkFixtureNullable)
    expect(result.providerName).toBeNull()
    expect(result.providerType).toBeNull()
    expect(result.lastLoginAt).toBeNull()
  })

  it('T1-c: providerType 6값 enum — LOCAL/LDAP/SAML/OIDC/PAT/OAUTH 모두 파싱', () => {
    const types = ['LOCAL', 'LDAP', 'SAML', 'OIDC', 'PAT', 'OAUTH'] as const
    for (const t of types) {
      const result = accountLinkSchema.parse({ ...linkFixture, providerType: t })
      expect(result.providerType).toBe(t)
    }
  })

  it('T1-d: providerType에 알 수 없는 값 시 ZodError throw', () => {
    expect(() =>
      accountLinkSchema.parse({ ...linkFixture, providerType: 'GITHUB' }),
    ).toThrow()
  })

  it('T1-e: 필수 필드(id) 누락 시 ZodError throw', () => {
    // eslint-disable-next-line @typescript-eslint/no-unused-vars
    const { id: _linkId, ...rest } = linkFixture
    expect(() => accountLinkSchema.parse(rest)).toThrow()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T2. accountLinksResponseSchema — 래퍼 파싱
// ─────────────────────────────────────────────────────────────────────────────
describe('accountLinksResponseSchema', () => {
  it('T2-a: links 배열과 hasLocalPassword를 파싱한다', () => {
    const result = accountLinksResponseSchema.parse(accountLinksFixture)
    expect(result.links).toHaveLength(2)
    expect(result.hasLocalPassword).toBe(true)
  })

  it('T2-b: links가 빈 배열이어도 파싱 성공', () => {
    const result = accountLinksResponseSchema.parse({ links: [], hasLocalPassword: false })
    expect(result.links).toHaveLength(0)
    expect(result.hasLocalPassword).toBe(false)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T3. reauthResponseSchema — stepUpExpiresAt 파싱
// ─────────────────────────────────────────────────────────────────────────────
describe('reauthResponseSchema', () => {
  it('T3-a: stepUpExpiresAt ISO 문자열을 파싱한다', () => {
    const result = reauthResponseSchema.parse(reauthResponseFixture)
    expect(result.stepUpExpiresAt).toBe('2026-06-10T10:30:00Z')
  })

  it('T3-b: stepUpExpiresAt 누락 시 ZodError throw', () => {
    expect(() => reauthResponseSchema.parse({})).toThrow()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T4. ssoLinkStartResponseSchema — authorizeUrl 파싱
// ─────────────────────────────────────────────────────────────────────────────
describe('ssoLinkStartResponseSchema', () => {
  it('T4-a: authorizeUrl 문자열을 파싱한다', () => {
    const result = ssoLinkStartResponseSchema.parse(ssoLinkStartResponseFixture)
    expect(result.authorizeUrl).toBe('https://idp.example.com/sso/saml?SAMLRequest=xxx')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T5. linkableProviderSchema — discriminated union 파싱
// ─────────────────────────────────────────────────────────────────────────────
describe('linkableProviderSchema', () => {
  it('T5-a: LDAP provider — providerId 있고 registrationId 키 없어도 파싱 성공', () => {
    const result = linkableProviderSchema.parse(ldapLinkableProvider)
    expect(result.kind).toBe('LDAP')
    if (result.kind === 'LDAP') {
      expect(result.providerId).toBe('e5f6a7b8-c9d0-8234-ef01-234567890124')
      expect(result.displayName).toBe('LDAP 사내')
      // LDAP엔 registrationId 필드가 없어야 함
      expect('registrationId' in result).toBe(false)
    }
  })

  it('T5-b: SAML provider — registrationId 있고 providerId 키 없어도 파싱 성공', () => {
    const result = linkableProviderSchema.parse(samlLinkableProvider)
    expect(result.kind).toBe('SAML')
    if (result.kind === 'SAML') {
      expect(result.registrationId).toBe('company-saml')
      expect(result.displayName).toBe('Okta SAML')
      expect('providerId' in result).toBe(false)
    }
  })

  it('T5-c: OIDC provider 파싱 성공', () => {
    const result = linkableProviderSchema.parse(oidcLinkableProvider)
    expect(result.kind).toBe('OIDC')
    if (result.kind === 'OIDC') {
      expect(result.registrationId).toBe('google-oidc')
    }
  })

  it('T5-d: 알 수 없는 kind 값 시 ZodError throw', () => {
    expect(() =>
      linkableProviderSchema.parse({ kind: 'GITHUB', registrationId: 'x', displayName: 'y' }),
    ).toThrow()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T6. fetchAccountLinks — GET /api/v1/auth/account/links
// ─────────────────────────────────────────────────────────────────────────────
describe('fetchAccountLinks', () => {
  it('T6-a: 계정 링크 목록과 hasLocalPassword를 반환한다', async () => {
    const result = await fetchAccountLinks()
    expect(result.links).toHaveLength(2)
    expect(result.hasLocalPassword).toBe(true)
    expect(result.links[0]?.id).toBe('a1b2c3d4-e5f6-4890-abcd-ef1234567890')
  })

  it('T6-b: 서버 401 응답 시 ApiError(401)를 throw한다', async () => {
    server.use(
      http.get('/api/v1/auth/account/links', () => {
        return HttpResponse.json({ message: 'Unauthorized' }, { status: 401 })
      }),
    )
    const err = await fetchAccountLinks().catch((e: unknown) => e)
    expect(err).toBeInstanceOf(ApiError)
    expect((err as ApiError).status).toBe(401)
  })

  it('T6-c: GET 요청이므로 X-XSRF-TOKEN 헤더가 없어도 성공한다', async () => {
    let capturedXsrf: string | null | undefined
    server.use(
      http.get('/api/v1/auth/account/links', ({ request }) => {
        capturedXsrf = request.headers.get('X-XSRF-TOKEN')
        return HttpResponse.json(accountLinksFixture)
      }),
    )
    await fetchAccountLinks()
    expect(capturedXsrf).toBeNull()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T7. fetchLinkableProviders — GET /api/v1/auth/account/linkable-providers
// ─────────────────────────────────────────────────────────────────────────────
describe('fetchLinkableProviders', () => {
  it('T7-a: .linkable 언래핑 후 LinkableProvider[] 반환한다', async () => {
    const result = await fetchLinkableProviders()
    expect(result).toHaveLength(3)
    expect(result[0]?.kind).toBe('LDAP')
    expect(result[1]?.kind).toBe('SAML')
    expect(result[2]?.kind).toBe('OIDC')
  })

  it('T7-b: 빈 linkable 배열도 빈 배열 반환한다', async () => {
    server.use(
      http.get('/api/v1/auth/account/linkable-providers', () => {
        return HttpResponse.json({ linkable: [] })
      }),
    )
    const result = await fetchLinkableProviders()
    expect(result).toHaveLength(0)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T8. reauth — POST /api/v1/auth/account/reauth + X-XSRF-TOKEN
// ─────────────────────────────────────────────────────────────────────────────
describe('reauth', () => {
  it('T8-a: LOCAL method로 재인증 후 stepUpExpiresAt을 반환한다', async () => {
    const result = await reauth({ method: 'LOCAL', password: 'secret123' })
    expect(result.stepUpExpiresAt).toBe('2026-06-10T10:30:00Z')
  })

  it('T8-b: LDAP method + providerId/username 포함 재인증', async () => {
    const result = await reauth({
      method: 'LDAP',
      password: 'secret123',
      providerId: 'e5f6a7b8-c9d0-8234-ef01-234567890124',
      username: 'johndoe',
    })
    expect(result.stepUpExpiresAt).toBe('2026-06-10T10:30:00Z')
  })

  it('T8-c: X-XSRF-TOKEN 헤더가 포함된다', async () => {
    let capturedXsrf: string | null = null
    server.use(
      http.post('/api/v1/auth/account/reauth', ({ request }) => {
        capturedXsrf = request.headers.get('X-XSRF-TOKEN')
        return HttpResponse.json(reauthResponseFixture)
      }),
    )
    await reauth({ method: 'LOCAL', password: 'pw' })
    expect(capturedXsrf).toBe('test-csrf-token')
  })

  it('T8-d: 서버 401 응답 시 ApiError(401)를 throw한다', async () => {
    server.use(
      http.post('/api/v1/auth/account/reauth', () => {
        return HttpResponse.json({ message: 'Unauthorized' }, { status: 401 })
      }),
    )
    const err = await reauth({ method: 'LOCAL', password: 'pw' }).catch((e: unknown) => e)
    expect(err).toBeInstanceOf(ApiError)
    expect((err as ApiError).status).toBe(401)
  })

  it('T8-e: 서버 403 응답 시 ApiError(403)를 throw한다', async () => {
    server.use(
      http.post('/api/v1/auth/account/reauth', () => {
        return HttpResponse.json({ message: 'Forbidden' }, { status: 403 })
      }),
    )
    const err = await reauth({ method: 'LOCAL', password: 'pw' }).catch((e: unknown) => e)
    expect(err).toBeInstanceOf(ApiError)
    expect((err as ApiError).status).toBe(403)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T9. linkAccount — POST /api/v1/auth/account/links + X-XSRF-TOKEN
// ─────────────────────────────────────────────────────────────────────────────
describe('linkAccount', () => {
  it('T9-a: LDAP 계정을 연결하고 AccountLinkResponse를 반환한다', async () => {
    const result = await linkAccount({
      providerId: 'b2c3d4e5-f6a7-5901-bcde-f01234567891',
      username: 'johndoe',
      password: 'secret123',
    })
    expect(result.id).toBe('a1b2c3d4-e5f6-4890-abcd-ef1234567890')
    expect(result.providerType).toBe('LDAP')
  })

  it('T9-b: X-XSRF-TOKEN 헤더가 포함된다', async () => {
    let capturedXsrf: string | null = null
    server.use(
      http.post('/api/v1/auth/account/links', ({ request }) => {
        capturedXsrf = request.headers.get('X-XSRF-TOKEN')
        return HttpResponse.json(linkFixture)
      }),
    )
    await linkAccount({
      providerId: 'b2c3d4e5-f6a7-5901-bcde-f01234567891',
      username: 'johndoe',
      password: 'secret123',
    })
    expect(capturedXsrf).toBe('test-csrf-token')
  })

  it('T9-c: 서버 409 응답(이미 연결됨) 시 ApiError(409)를 throw한다', async () => {
    server.use(
      http.post('/api/v1/auth/account/links', () => {
        return HttpResponse.json({ message: 'Already linked' }, { status: 409 })
      }),
    )
    const err = await linkAccount({
      providerId: 'b2c3d4e5-f6a7-5901-bcde-f01234567891',
      username: 'johndoe',
      password: 'secret123',
    }).catch((e: unknown) => e)
    expect(err).toBeInstanceOf(ApiError)
    expect((err as ApiError).status).toBe(409)
  })

  it('T9-d: 서버 503 응답(LDAP 연결 불가) 시 ApiError(503)를 throw한다', async () => {
    server.use(
      http.post('/api/v1/auth/account/links', () => {
        return HttpResponse.json({ message: 'Service Unavailable' }, { status: 503 })
      }),
    )
    const err = await linkAccount({
      providerId: 'b2c3d4e5-f6a7-5901-bcde-f01234567891',
      username: 'johndoe',
      password: 'secret123',
    }).catch((e: unknown) => e)
    expect(err).toBeInstanceOf(ApiError)
    expect((err as ApiError).status).toBe(503)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T10. unlinkAccount — DELETE /api/v1/auth/account/links/:id + X-XSRF-TOKEN
// ─────────────────────────────────────────────────────────────────────────────
describe('unlinkAccount', () => {
  it('T10-a: 유효한 id로 DELETE 호출 시 204로 완료(void 반환)된다', async () => {
    await expect(
      unlinkAccount('a1b2c3d4-e5f6-4890-abcd-ef1234567890'),
    ).resolves.toBeUndefined()
  })

  it('T10-b: X-XSRF-TOKEN 헤더가 포함된다', async () => {
    let capturedXsrf: string | null = null
    server.use(
      http.delete('/api/v1/auth/account/links/:id', ({ request }) => {
        capturedXsrf = request.headers.get('X-XSRF-TOKEN')
        return new HttpResponse(null, { status: 204 })
      }),
    )
    await unlinkAccount('a1b2c3d4-e5f6-4890-abcd-ef1234567890')
    expect(capturedXsrf).toBe('test-csrf-token')
  })

  it('T10-c: 서버 409 응답(마지막 연결 해제 불가) 시 ApiError(409)를 throw한다', async () => {
    server.use(
      http.delete('/api/v1/auth/account/links/:id', () => {
        return HttpResponse.json({ message: 'Cannot unlink last' }, { status: 409 })
      }),
    )
    const err = await unlinkAccount('a1b2c3d4-e5f6-4890-abcd-ef1234567890').catch(
      (e: unknown) => e,
    )
    expect(err).toBeInstanceOf(ApiError)
    expect((err as ApiError).status).toBe(409)
  })

  it('T10-d: 서버 401 응답 시 ApiError(401)를 throw한다', async () => {
    server.use(
      http.delete('/api/v1/auth/account/links/:id', () => {
        return HttpResponse.json({ message: 'Unauthorized' }, { status: 401 })
      }),
    )
    const err = await unlinkAccount('a1b2c3d4-e5f6-4890-abcd-ef1234567890').catch(
      (e: unknown) => e,
    )
    expect(err).toBeInstanceOf(ApiError)
    expect((err as ApiError).status).toBe(401)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T11. ssoLinkStart — POST /api/v1/auth/account/links/sso/start + X-XSRF-TOKEN
// ─────────────────────────────────────────────────────────────────────────────
describe('ssoLinkStart', () => {
  it('T11-a: SAML 연결 시작 후 authorizeUrl을 반환한다', async () => {
    const result = await ssoLinkStart({ registrationId: 'company-saml', providerType: 'SAML' })
    expect(result.authorizeUrl).toBe('https://idp.example.com/sso/saml?SAMLRequest=xxx')
  })

  it('T11-b: OIDC 연결 시작 후 authorizeUrl을 반환한다', async () => {
    const result = await ssoLinkStart({ registrationId: 'google-oidc', providerType: 'OIDC' })
    expect(result.authorizeUrl).toBe('https://idp.example.com/sso/saml?SAMLRequest=xxx')
  })

  it('T11-c: X-XSRF-TOKEN 헤더가 포함된다', async () => {
    let capturedXsrf: string | null = null
    server.use(
      http.post('/api/v1/auth/account/links/sso/start', ({ request }) => {
        capturedXsrf = request.headers.get('X-XSRF-TOKEN')
        return HttpResponse.json(ssoLinkStartResponseFixture)
      }),
    )
    await ssoLinkStart({ registrationId: 'company-saml', providerType: 'SAML' })
    expect(capturedXsrf).toBe('test-csrf-token')
  })

  it('T11-d: 서버 401 응답 시 ApiError(401)를 throw한다', async () => {
    server.use(
      http.post('/api/v1/auth/account/links/sso/start', () => {
        return HttpResponse.json({ message: 'Unauthorized' }, { status: 401 })
      }),
    )
    const err = await ssoLinkStart({
      registrationId: 'company-saml',
      providerType: 'SAML',
    }).catch((e: unknown) => e)
    expect(err).toBeInstanceOf(ApiError)
    expect((err as ApiError).status).toBe(401)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T12. ssoReauthStart — POST /api/v1/auth/account/reauth/sso/start + X-XSRF-TOKEN
// ─────────────────────────────────────────────────────────────────────────────
describe('ssoReauthStart', () => {
  it('T12-a: SSO 재인증 시작 후 authorizeUrl을 반환한다', async () => {
    const result = await ssoReauthStart({ registrationId: 'company-saml', providerType: 'SAML' })
    expect(result.authorizeUrl).toBe('https://idp.example.com/sso/saml?SAMLRequest=xxx')
  })

  it('T12-b: X-XSRF-TOKEN 헤더가 포함된다 (mutation이므로 CSRF 면제 없음)', async () => {
    let capturedXsrf: string | null = null
    server.use(
      http.post('/api/v1/auth/account/reauth/sso/start', ({ request }) => {
        capturedXsrf = request.headers.get('X-XSRF-TOKEN')
        return HttpResponse.json(ssoLinkStartResponseFixture)
      }),
    )
    await ssoReauthStart({ registrationId: 'google-oidc', providerType: 'OIDC' })
    expect(capturedXsrf).toBe('test-csrf-token')
  })

  it('T12-c: 서버 401 응답 시 ApiError(401)를 throw한다', async () => {
    server.use(
      http.post('/api/v1/auth/account/reauth/sso/start', () => {
        return HttpResponse.json({ message: 'Unauthorized' }, { status: 401 })
      }),
    )
    const err = await ssoReauthStart({
      registrationId: 'google-oidc',
      providerType: 'OIDC',
    }).catch((e: unknown) => e)
    expect(err).toBeInstanceOf(ApiError)
    expect((err as ApiError).status).toBe(401)
  })

  it('T12-d: 서버 403 응답 시 ApiError(403)를 throw한다', async () => {
    server.use(
      http.post('/api/v1/auth/account/reauth/sso/start', () => {
        return HttpResponse.json({ message: 'Forbidden' }, { status: 403 })
      }),
    )
    const err = await ssoReauthStart({
      registrationId: 'google-oidc',
      providerType: 'OIDC',
    }).catch((e: unknown) => e)
    expect(err).toBeInstanceOf(ApiError)
    expect((err as ApiError).status).toBe(403)
  })
})
