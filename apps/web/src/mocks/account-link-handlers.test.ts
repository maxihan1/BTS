// 계정 연결 MSW 핸들러 단위 테스트 — stateful store + 시나리오 플래그 분기 검증 (FR-AU-08/08b)
import { server } from '@/test/server'
import { afterEach, describe, expect, it } from 'vitest'
import { accountLinkHandlers } from './account-link-handlers'
import {
  resetStore,
  seedLinks,
  SCENARIO_KEY,
  DEFAULT_LDAP_LINK,
  DEFAULT_LINKABLE_PROVIDERS,
} from './account-link-fixtures'

beforeEach(() => {
  server.use(...accountLinkHandlers)
})
afterEach(() => {
  // localStorage 플래그 초기화 + 인메모리 store 초기화
  for (const key of Object.values(SCENARIO_KEY)) {
    localStorage.removeItem(key)
  }
  resetStore()
})

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼 — 응답 타입 (Zod 스키마와 동형, 테스트 내부 편의용)
// ─────────────────────────────────────────────────────────────────────────────

interface AccountLink {
  id: string
  providerId: string
  providerName: string | null
  providerType: string | null
  providerEnabled: boolean
  externalSubjectMasked: string
  linkedAt: string
  lastLoginAt: string | null
}

interface LinksResponse {
  links: AccountLink[]
  hasLocalPassword: boolean
}

interface ErrorResponse {
  error: string
}

interface ReauthResponse {
  stepUpExpiresAt: string
}

interface SsoStartResponse {
  authorizeUrl: string
}

interface LinkableProvidersResponse {
  linkable: unknown[]
}

// ─────────────────────────────────────────────────────────────────────────────
// 공통 fetch 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

async function getLinks(): Promise<Response> {
  return fetch('/api/v1/auth/account/links')
}

async function getLinkableProviders(): Promise<Response> {
  return fetch('/api/v1/auth/account/linkable-providers')
}

async function postReauth(body: Record<string, unknown> = {}): Promise<Response> {
  return fetch('/api/v1/auth/account/reauth', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
}

async function postLinks(body: Record<string, unknown>): Promise<Response> {
  return fetch('/api/v1/auth/account/links', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
}

async function deleteLink(id: string): Promise<Response> {
  return fetch(`/api/v1/auth/account/links/${id}`, { method: 'DELETE' })
}

async function postSsoLinkStart(body: Record<string, unknown>): Promise<Response> {
  return fetch('/api/v1/auth/account/links/sso/start', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
}

async function postSsoReauthStart(body: Record<string, unknown>): Promise<Response> {
  return fetch('/api/v1/auth/account/reauth/sso/start', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
}

// ─────────────────────────────────────────────────────────────────────────────
// GET /api/v1/auth/account/links
// ─────────────────────────────────────────────────────────────────────────────

describe('GET /api/v1/auth/account/links', () => {
  it('빈 store → 200 { links: [], hasLocalPassword: true }', async () => {
    const res = await getLinks()
    expect(res.status).toBe(200)
    const body = await res.json() as LinksResponse
    expect(body.links).toEqual([])
    expect(body.hasLocalPassword).toBe(true)
  })

  it('seedLinks 후 → GET에 반영 (stateful)', async () => {
    seedLinks([DEFAULT_LDAP_LINK])
    const res = await getLinks()
    const body = await res.json() as LinksResponse
    expect(body.links).toHaveLength(1)
    expect(body.links[0]?.id).toBe(DEFAULT_LDAP_LINK.id)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// GET /api/v1/auth/account/linkable-providers
// ─────────────────────────────────────────────────────────────────────────────

describe('GET /api/v1/auth/account/linkable-providers', () => {
  it('200 { linkable: [...] } — kind/providerId/registrationId 포함', async () => {
    const res = await getLinkableProviders()
    expect(res.status).toBe(200)
    const body = await res.json() as LinkableProvidersResponse
    expect(Array.isArray(body.linkable)).toBe(true)
    expect(body.linkable.length).toBeGreaterThan(0)
  })

  it('linkable 항목이 DEFAULT_LINKABLE_PROVIDERS와 동일', async () => {
    const res = await getLinkableProviders()
    const body = await res.json() as LinkableProvidersResponse
    expect(body.linkable).toEqual(DEFAULT_LINKABLE_PROVIDERS)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// POST /api/v1/auth/account/reauth
// ─────────────────────────────────────────────────────────────────────────────

describe('POST /api/v1/auth/account/reauth', () => {
  it('정상 → 200 { stepUpExpiresAt } — 현재 + ~5분 ISO', async () => {
    const before = Date.now()
    const res = await postReauth({ method: 'LOCAL', password: 'password' })
    const after = Date.now()

    expect(res.status).toBe(200)
    const body = await res.json() as ReauthResponse
    const expiresAt = new Date(body.stepUpExpiresAt).getTime()
    // 현재 시각 + 4~6분 범위 (mock는 5분)
    expect(expiresAt).toBeGreaterThanOrEqual(before + 4 * 60 * 1000)
    expect(expiresAt).toBeLessThanOrEqual(after + 6 * 60 * 1000)
  })

  it('SCENARIO_KEY.REAUTH_FAIL 플래그 → 401 { error: "reauth_failed" }', async () => {
    localStorage.setItem(SCENARIO_KEY.REAUTH_FAIL, 'true')
    const res = await postReauth({ method: 'LOCAL', password: 'password' })

    expect(res.status).toBe(401)
    const body = await res.json() as ErrorResponse
    expect(body.error).toBe('reauth_failed')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// POST /api/v1/auth/account/links
// ─────────────────────────────────────────────────────────────────────────────

describe('POST /api/v1/auth/account/links', () => {
  // step-up 없으면 403
  it('step-up 플래그 없음 → 403 { error: "step_up_required" }', async () => {
    const res = await postLinks({
      providerId: DEFAULT_LDAP_LINK.providerId,
      username: 'alice',
      password: 'Test1234!',
    })
    expect(res.status).toBe(403)
    const body = await res.json() as ErrorResponse
    expect(body.error).toBe('step_up_required')
  })

  // step-up 유효 + 정상 → 201 + store에 추가
  it('step-up 유효 + 정상 → 201 AccountLinkResponse + GET에 반영 (stateful)', async () => {
    localStorage.setItem(SCENARIO_KEY.STEP_UP_VALID, 'true')

    const res = await postLinks({
      providerId: DEFAULT_LDAP_LINK.providerId,
      username: 'alice',
      password: 'Test1234!',
    })
    expect(res.status).toBe(201)

    const created = await res.json() as AccountLink
    expect(created.id).toBeDefined()
    expect(created.providerType).toBe('LDAP')

    // GET에 반영 확인 (stateful)
    const listRes = await getLinks()
    const listBody = await listRes.json() as LinksResponse
    expect(listBody.links.some((l) => l.id === created.id)).toBe(true)
  })

  // conflict 플래그 → 409
  it('conflict 플래그 → 409 { error: "account_already_linked" }', async () => {
    localStorage.setItem(SCENARIO_KEY.STEP_UP_VALID, 'true')
    localStorage.setItem(SCENARIO_KEY.CONFLICT, 'true')

    const res = await postLinks({
      providerId: DEFAULT_LDAP_LINK.providerId,
      username: 'alice',
      password: 'Test1234!',
    })
    expect(res.status).toBe(409)
    const body = await res.json() as ErrorResponse
    expect(body.error).toBe('account_already_linked')
  })

  // unavailable 플래그 → 503
  it('unavailable 플래그 → 503 { error: "provider_unavailable" }', async () => {
    localStorage.setItem(SCENARIO_KEY.STEP_UP_VALID, 'true')
    localStorage.setItem(SCENARIO_KEY.PROVIDER_UNAVAILABLE, 'true')

    const res = await postLinks({
      providerId: DEFAULT_LDAP_LINK.providerId,
      username: 'alice',
      password: 'Test1234!',
    })
    expect(res.status).toBe(503)
    const body = await res.json() as ErrorResponse
    expect(body.error).toBe('provider_unavailable')
  })

  // 이미 존재하는 providerId → 멱등 200
  it('같은 providerId 재링크 → 멱등 200 (store 중복 없음)', async () => {
    localStorage.setItem(SCENARIO_KEY.STEP_UP_VALID, 'true')
    seedLinks([DEFAULT_LDAP_LINK])

    const res = await postLinks({
      providerId: DEFAULT_LDAP_LINK.providerId,
      username: 'alice',
      password: 'Test1234!',
    })
    expect(res.status).toBe(200)

    // store에 중복 없음 — 여전히 1개
    const listRes = await getLinks()
    const listBody = await listRes.json() as LinksResponse
    expect(listBody.links.filter((l) => l.providerId === DEFAULT_LDAP_LINK.providerId)).toHaveLength(1)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// DELETE /api/v1/auth/account/links/:id
// ─────────────────────────────────────────────────────────────────────────────

describe('DELETE /api/v1/auth/account/links/:id', () => {
  it('step-up 없음 → 403 { error: "step_up_required" }', async () => {
    seedLinks([DEFAULT_LDAP_LINK])
    const res = await deleteLink(DEFAULT_LDAP_LINK.id)
    expect(res.status).toBe(403)
    const body = await res.json() as ErrorResponse
    expect(body.error).toBe('step_up_required')
  })

  it('step-up + 존재하지 않는 id → 404', async () => {
    localStorage.setItem(SCENARIO_KEY.STEP_UP_VALID, 'true')
    const res = await deleteLink('00000000-0000-4000-8000-000000000099')
    expect(res.status).toBe(404)
  })

  it('step-up + last_method 플래그 → 409 { error: "last_login_method" }', async () => {
    localStorage.setItem(SCENARIO_KEY.STEP_UP_VALID, 'true')
    localStorage.setItem(SCENARIO_KEY.LAST_METHOD, 'true')
    seedLinks([DEFAULT_LDAP_LINK])

    const res = await deleteLink(DEFAULT_LDAP_LINK.id)
    expect(res.status).toBe(409)
    const body = await res.json() as ErrorResponse
    expect(body.error).toBe('last_login_method')
  })

  it('step-up + 정상 → 204 + GET에서 제거됨 (stateful)', async () => {
    localStorage.setItem(SCENARIO_KEY.STEP_UP_VALID, 'true')
    seedLinks([DEFAULT_LDAP_LINK])

    const res = await deleteLink(DEFAULT_LDAP_LINK.id)
    expect(res.status).toBe(204)

    // GET에서 제거 확인 (stateful)
    const listRes = await getLinks()
    const listBody = await listRes.json() as LinksResponse
    expect(listBody.links.find((l) => l.id === DEFAULT_LDAP_LINK.id)).toBeUndefined()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// POST /api/v1/auth/account/links/sso/start
// ─────────────────────────────────────────────────────────────────────────────

describe('POST /api/v1/auth/account/links/sso/start', () => {
  it('step-up 없음 → 403 { error: "step_up_required" }', async () => {
    const res = await postSsoLinkStart({ registrationId: 'saml-corp', providerType: 'SAML' })
    expect(res.status).toBe(403)
    const body = await res.json() as ErrorResponse
    expect(body.error).toBe('step_up_required')
  })

  it('step-up + SAML → 200 { authorizeUrl: /saml2/authenticate/... }', async () => {
    localStorage.setItem(SCENARIO_KEY.STEP_UP_VALID, 'true')
    const res = await postSsoLinkStart({ registrationId: 'saml-corp', providerType: 'SAML' })
    expect(res.status).toBe(200)
    const body = await res.json() as SsoStartResponse
    expect(body.authorizeUrl).toMatch(/^\/saml2\/authenticate\//)
  })

  it('step-up + OIDC → 200 { authorizeUrl: /oauth2/authorization/... }', async () => {
    localStorage.setItem(SCENARIO_KEY.STEP_UP_VALID, 'true')
    const res = await postSsoLinkStart({ registrationId: 'oidc-google', providerType: 'OIDC' })
    expect(res.status).toBe(200)
    const body = await res.json() as SsoStartResponse
    expect(body.authorizeUrl).toMatch(/^\/oauth2\/authorization\//)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// POST /api/v1/auth/account/reauth/sso/start
// ─────────────────────────────────────────────────────────────────────────────

describe('POST /api/v1/auth/account/reauth/sso/start', () => {
  it('SAML → 200 { authorizeUrl }', async () => {
    const res = await postSsoReauthStart({ registrationId: 'saml-corp', providerType: 'SAML' })
    expect(res.status).toBe(200)
    const body = await res.json() as SsoStartResponse
    expect(body.authorizeUrl).toMatch(/^\/saml2\/authenticate\//)
  })

  it('OIDC → 200 { authorizeUrl }', async () => {
    const res = await postSsoReauthStart({ registrationId: 'oidc-google', providerType: 'OIDC' })
    expect(res.status).toBe(200)
    const body = await res.json() as SsoStartResponse
    expect(body.authorizeUrl).toMatch(/^\/oauth2\/authorization\//)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// resetStore / seedLinks 헬퍼 동작 검증
// ─────────────────────────────────────────────────────────────────────────────

describe('resetStore / seedLinks 헬퍼', () => {
  it('seedLinks → resetStore → GET에서 빈 목록', async () => {
    seedLinks([DEFAULT_LDAP_LINK])
    resetStore()

    const res = await getLinks()
    const body = await res.json() as LinksResponse
    expect(body.links).toHaveLength(0)
  })

  it('beforeEach에서 자동 초기화 — 이전 테스트 잔여 없음', async () => {
    // 이전 테스트와 격리되어 있어야 함 (beforeEach resetStore 확인)
    const res = await getLinks()
    const body = await res.json() as LinksResponse
    expect(body.links).toHaveLength(0)
  })
})
