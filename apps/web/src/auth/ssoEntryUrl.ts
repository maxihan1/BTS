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
