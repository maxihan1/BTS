// OIDC provider MSW mock 핸들러 — GET /api/v1/auth/oidc/providers (개발/테스트 환경용)
import { http, HttpResponse } from 'msw'

/**
 * E2E 테스트 전용 localStorage 플래그 키 — 이 키가 'true'이면 provider 0개 빈 목록 반환.
 *
 * MSW 핸들러는 페이지 메인 스레드에서 실행되므로 localStorage 접근이 가능하다.
 * Playwright addInitScript 로 goto 전에 플래그를 설정하면 첫 fetch 시점부터 적용된다.
 * dev/test 빌드 전용 (production 미포함).
 */
export const E2E_OIDC_NO_PROVIDERS_KEY = '__bts_e2e_oidc_no_providers'

/**
 * GET /api/v1/auth/oidc/providers — 활성 OIDC provider 목록 반환.
 *
 * 기본값으로 Google 1개를 반환한다.
 * E2E 테스트에서 provider 0개 시나리오가 필요하면 addInitScript로
 * localStorage '__bts_e2e_oidc_no_providers' = 'true' 를 설정한다.
 */
const oidcProviderListHandler = http.get('/api/v1/auth/oidc/providers', () => {
  if (globalThis.localStorage?.getItem(E2E_OIDC_NO_PROVIDERS_KEY) === 'true') {
    return HttpResponse.json({ providers: [] })
  }
  return HttpResponse.json({
    providers: [{ registrationId: 'google', displayName: 'Google' }],
  })
})

export const oidcHandlers = [oidcProviderListHandler]
