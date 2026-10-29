// SSO 진입 URL 조립 유틸 — SAML/OIDC 경로 분기 + encodeURIComponent 일관 적용

/**
 * SAML 또는 OIDC 인증 진입 URL을 조립한다.
 *
 * registrationId에 슬래시·공백 등 특수문자가 포함될 수 있으므로
 * encodeURIComponent로 안전하게 인코딩한다.
 *
 * - SAML: `/saml2/authenticate/{registrationId}` (Spring Security saml2Login 표준)
 * - OIDC: `/oauth2/authorization/{registrationId}` (Spring Security oauth2Login 표준)
 *
 * @param type - 인증 프로토콜 타입 ('SAML' | 'OIDC')
 * @param registrationId - Spring Security registration identifier
 * @returns 브라우저가 이동해야 할 절대 경로 문자열
 */
export function ssoEntryUrl(type: 'SAML' | 'OIDC', registrationId: string): string {
  const id = encodeURIComponent(registrationId)
  return type === 'SAML' ? `/saml2/authenticate/${id}` : `/oauth2/authorization/${id}`
}

/**
 * OIDC 진입 주소에 로그인 카드의 hint · prompt 와 돌아갈 곳(`returnTo`)을 붙인다 (S22).
 * 백엔드 `LoginCardAuthorizationRequestResolver` 가 앞의 둘을 authorize 로 옮기고 returnTo 를 로그인 뒤 이동에 쓴다.
 *
 * 돌아갈 곳 — `/login` 이면 그 주소의 `returnTo`, 다른 화면(세션 만료 dialog)이면 지금 화면이다.
 * Auth 로는 `replace` 로 가서 방문 기록이 사라지므로 이것이 보던 화면으로 돌아오는 유일한 길이다.
 */
export function withSsoParams(href: string, params: Record<string, string> = {}): string {
  const query = new URLSearchParams(params)
  const { pathname, search } = window.location
  const returnTo = pathname === '/login' ? new URLSearchParams(search).get('returnTo') : pathname + search
  if (returnTo !== null) query.set('returnTo', returnTo)
  const qs = query.toString()
  return qs === '' ? href : `${href}?${qs}`
}
