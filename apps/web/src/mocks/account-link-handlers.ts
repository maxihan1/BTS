// 계정 연결 MSW 핸들러 — stateful CRUD + 시나리오 플래그 분기 (FR-AU-08/08b)
import { http, HttpResponse } from 'msw'
import type { AccountLinkResponse } from '../api/account-links'
import {
  linkStore,
  DEFAULT_LINKABLE_PROVIDERS,
  SCENARIO_KEY,
} from './account-link-fixtures'

// ─────────────────────────────────────────────────────────────────────────────
// 내부 헬퍼 — UUID v4 생성
// ─────────────────────────────────────────────────────────────────────────────

/**
 * RFC4122 v4 UUID를 생성한다.
 * crypto.randomUUID()가 있으면 사용하고, 없으면 Math.random 기반 폴백.
 */
function generateUuidV4(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID()
  }
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = (Math.random() * 16) | 0
    const v = c === 'x' ? r : (r & 0x3) | 0x8
    return v.toString(16)
  })
}

// ─────────────────────────────────────────────────────────────────────────────
// 내부 헬퍼 — 시나리오 플래그 읽기 (localStorage)
// ─────────────────────────────────────────────────────────────────────────────

function flag(key: string): boolean {
  try {
    return localStorage.getItem(key) === 'true'
  } catch {
    return false
  }
}

function isStepUpValid(): boolean {
  return flag(SCENARIO_KEY.STEP_UP_VALID)
}

// ─────────────────────────────────────────────────────────────────────────────
// GET /api/v1/auth/account/links
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 현재 계정 연결 목록 조회.
 * linkStore에서 전체 반환. hasLocalPassword는 항상 true (mock 단순화).
 */
const listAccountLinksHandler = http.get('/api/v1/auth/account/links', () => {
  const links = Array.from(linkStore.values())
  return HttpResponse.json({ links, hasLocalPassword: true })
})

// ─────────────────────────────────────────────────────────────────────────────
// GET /api/v1/auth/account/linkable-providers
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 연결 가능한 공급자 목록 조회.
 * DEFAULT_LINKABLE_PROVIDERS를 고정 반환.
 */
const listLinkableProvidersHandler = http.get('/api/v1/auth/account/linkable-providers', () => {
  return HttpResponse.json({ linkable: DEFAULT_LINKABLE_PROVIDERS })
})

// ─────────────────────────────────────────────────────────────────────────────
// POST /api/v1/auth/account/reauth
// ─────────────────────────────────────────────────────────────────────────────

/**
 * step-up 재인증.
 * REAUTH_FAIL 플래그 → 401.
 * 정상 → 200 { stepUpExpiresAt: 현재+5분 ISO }.
 * 성공 시 STEP_UP_VALID 플래그를 localStorage에 자동 세팅 (이후 mutation 허용).
 */
const reauthHandler = http.post('/api/v1/auth/account/reauth', () => {
  if (flag(SCENARIO_KEY.REAUTH_FAIL)) {
    return HttpResponse.json({ error: 'reauth_failed' }, { status: 401 })
  }

  const stepUpExpiresAt = new Date(Date.now() + 5 * 60 * 1000).toISOString()
  // 재인증 성공 시 step-up 유효 플래그 자동 세팅
  try {
    localStorage.setItem(SCENARIO_KEY.STEP_UP_VALID, 'true')
  } catch {
    // 테스트 환경(node)에서 localStorage 없을 수 있음 — 무시
  }
  return HttpResponse.json({ stepUpExpiresAt })
})

// ─────────────────────────────────────────────────────────────────────────────
// POST /api/v1/auth/account/links
// ─────────────────────────────────────────────────────────────────────────────

/**
 * LDAP 계정 연결.
 *
 * 분기 순서 (백엔드와 동일).
 * 1. step-up 무효 → 403 step_up_required
 * 2. conflict 플래그 → 409 account_already_linked
 * 3. unavailable 플래그 → 503 provider_unavailable
 * 4. 같은 providerId 이미 존재 → 200 멱등
 * 5. 정상 → 201 AccountLinkResponse + store 추가
 */
const linkAccountHandler = http.post('/api/v1/auth/account/links', async ({ request }) => {
  if (!isStepUpValid()) {
    return HttpResponse.json({ error: 'step_up_required' }, { status: 403 })
  }

  if (flag(SCENARIO_KEY.CONFLICT)) {
    return HttpResponse.json({ error: 'account_already_linked' }, { status: 409 })
  }

  if (flag(SCENARIO_KEY.PROVIDER_UNAVAILABLE)) {
    return HttpResponse.json({ error: 'provider_unavailable' }, { status: 503 })
  }

  const body = (await request.json()) as { providerId: string; username?: string }

  // 멱등 처리 — 같은 providerId가 이미 있으면 기존 항목 반환 (200)
  const existing = Array.from(linkStore.values()).find((l) => l.providerId === body.providerId)
  if (existing !== undefined) {
    return HttpResponse.json(existing, { status: 200 })
  }

  const newLink: AccountLinkResponse = {
    id: generateUuidV4(),
    providerId: body.providerId,
    providerName: 'BTS LDAP',
    providerType: 'LDAP',
    providerEnabled: true,
    externalSubjectMasked: body.username !== undefined ? `${body.username.slice(0, 3)}***` : '***',
    linkedAt: new Date().toISOString(),
    lastLoginAt: null,
  }

  linkStore.set(newLink.id, newLink)
  return HttpResponse.json(newLink, { status: 201 })
})

// ─────────────────────────────────────────────────────────────────────────────
// DELETE /api/v1/auth/account/links/:id
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 계정 연결 해제.
 *
 * 분기 순서 (백엔드와 동일).
 * 1. step-up 무효 → 403 step_up_required
 * 2. last_method 플래그 → 409 last_login_method
 * 3. store에 없음 → 404
 * 4. 정상 → 204 + store 제거
 */
const unlinkAccountHandler = http.delete('/api/v1/auth/account/links/:id', ({ params }) => {
  if (!isStepUpValid()) {
    return HttpResponse.json({ error: 'step_up_required' }, { status: 403 })
  }

  if (flag(SCENARIO_KEY.LAST_METHOD)) {
    return HttpResponse.json({ error: 'last_login_method' }, { status: 409 })
  }

  const id = params['id'] as string
  if (!linkStore.has(id)) {
    return HttpResponse.json({ error: 'not_found' }, { status: 404 })
  }

  linkStore.delete(id)
  return new HttpResponse(null, { status: 204 })
})

// ─────────────────────────────────────────────────────────────────────────────
// POST /api/v1/auth/account/links/sso/start
// ─────────────────────────────────────────────────────────────────────────────

/**
 * SSO 공급자 계정 연결 흐름 시작.
 *
 * step-up 무효 → 403.
 * providerType SAML → /saml2/authenticate/:registrationId.
 * providerType OIDC → /oauth2/authorization/:registrationId.
 */
const ssoLinkStartHandler = http.post('/api/v1/auth/account/links/sso/start', async ({ request }) => {
  if (!isStepUpValid()) {
    return HttpResponse.json({ error: 'step_up_required' }, { status: 403 })
  }

  const body = (await request.json()) as { registrationId: string; providerType: string }
  const authorizeUrl =
    body.providerType === 'SAML'
      ? `/saml2/authenticate/${body.registrationId}`
      : `/oauth2/authorization/${body.registrationId}`

  return HttpResponse.json({ authorizeUrl })
})

// ─────────────────────────────────────────────────────────────────────────────
// POST /api/v1/auth/account/reauth/sso/start
// ─────────────────────────────────────────────────────────────────────────────

/**
 * SSO step-up 재인증 흐름 시작.
 * step-up 체크 없음 — reauth 자체가 step-up을 획득하는 단계이므로.
 */
const ssoReauthStartHandler = http.post(
  '/api/v1/auth/account/reauth/sso/start',
  async ({ request }) => {
    const body = (await request.json()) as { registrationId: string; providerType: string }
    const authorizeUrl =
      body.providerType === 'SAML'
        ? `/saml2/authenticate/${body.registrationId}`
        : `/oauth2/authorization/${body.registrationId}`

    return HttpResponse.json({ authorizeUrl })
  },
)

// ─────────────────────────────────────────────────────────────────────────────
// Export
// ─────────────────────────────────────────────────────────────────────────────

/** 계정 연결 BC MSW 핸들러 배열 */
export const accountLinkHandlers = [
  listAccountLinksHandler,
  listLinkableProvidersHandler,
  reauthHandler,
  linkAccountHandler,
  unlinkAccountHandler,
  ssoLinkStartHandler,
  ssoReauthStartHandler,
]
