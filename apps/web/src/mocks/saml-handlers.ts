// SAML IdP MSW mock 핸들러 — GET /api/v1/auth/saml/idps (개발/테스트 환경용)
import { http, HttpResponse } from 'msw'

/**
 * GET /api/v1/auth/saml/idps — 활성 SAML IdP 목록 반환.
 *
 * 기본값으로 Okta SSO 1개를 반환한다.
 * 개발 환경에서 다른 시나리오가 필요하면 server.use()로 핸들러를 덮어쓴다.
 */
const samlIdpListHandler = http.get('/api/v1/auth/saml/idps', () => {
  return HttpResponse.json({
    idps: [{ registrationId: 'okta', displayName: 'Okta SSO' }],
  })
})

export const samlHandlers = [samlIdpListHandler]
