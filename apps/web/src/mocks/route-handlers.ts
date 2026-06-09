// 도메인 라우팅 MSW mock 핸들러 — GET /api/v1/auth/route?domain={domain} (FR-AU-07)
import { http, HttpResponse } from 'msw'

// ─────────────────────────────────────────────────────────────────────────────
// 브라우저 시드 가능 공유 store
// (msw-derived-behavior-shared-store-e2e: E2E Task 6에서 시드할 수 있도록
//  핸들러 클로저 내부 지역 맵이 아닌 전역 참조로 노출한다)
// ─────────────────────────────────────────────────────────────────────────────

/** 라우팅 결과 타입 — 핸들러 store에서 사용하는 내부 표현 */
interface RouteStoreEntry {
  type: 'SAML' | 'OIDC'
  registrationId: string
  displayName: string
}

/**
 * 도메인 → 라우팅 결과 맵.
 *
 * E2E 테스트에서 `E2E_ROUTE_STORE_KEY` localStorage JSON 시드 패턴으로 오버라이드한다.
 * Playwright `addInitScript`로 goto 전에 설정하면 첫 fetch 시점부터 적용된다.
 *
 * 기본 시드: partner.com → SAML, acme.com → OIDC.
 */
export const routeStore: Map<string, RouteStoreEntry> = new Map([
  [
    'partner.com',
    { type: 'SAML', registrationId: 'partner-saml', displayName: 'Partner SSO' },
  ],
  [
    'acme.com',
    { type: 'OIDC', registrationId: 'acme-oidc', displayName: 'Acme Google SSO' },
  ],
])

/**
 * E2E 테스트 전용 localStorage 키 — JSON 직렬화된 도메인 오버라이드 맵.
 *
 * 형식: `'{"corp.com":{"type":"SAML","registrationId":"corp","displayName":"Corp SSO"}}'`
 *
 * Playwright `addInitScript`로 goto 전에 설정하면 첫 fetch 시점부터 적용된다.
 * E2E Task 6에서 사용.
 */
export const E2E_ROUTE_STORE_KEY = '__bts_e2e_route_store'

/**
 * GET /api/v1/auth/route — 도메인별 인증 provider 라우팅 결과 반환.
 *
 * 1. localStorage `E2E_ROUTE_STORE_KEY`에 JSON이 있으면 파싱해 기본 store를 오버라이드.
 * 2. `domain` 쿼리스트링을 routeStore에서 조회.
 * 3. 매칭되면 `{matched:true, type, registrationId, displayName}` 반환.
 * 4. 미매칭이면 `{matched:false}` 반환.
 */
const routeHandler = http.get('/api/v1/auth/route', ({ request }) => {
  const url = new URL(request.url)
  const domain = url.searchParams.get('domain') ?? ''

  // E2E localStorage 시드 오버라이드 적용
  const e2eJson = globalThis.localStorage?.getItem(E2E_ROUTE_STORE_KEY)
  if (e2eJson !== null && e2eJson !== undefined) {
    try {
      const overrides = JSON.parse(e2eJson) as Record<
        string,
        { type: 'SAML' | 'OIDC'; registrationId: string; displayName: string }
      >
      for (const [key, value] of Object.entries(overrides)) {
        routeStore.set(key, value)
      }
    } catch {
      // 파싱 실패 시 기본 store 유지 (E2E 시드 오류는 무시)
    }
  }

  const entry = routeStore.get(domain)
  if (entry !== undefined) {
    return HttpResponse.json({
      matched: true,
      type: entry.type,
      registrationId: entry.registrationId,
      displayName: entry.displayName,
    })
  }

  return HttpResponse.json({ matched: false })
})

export const routeHandlers = [routeHandler]
