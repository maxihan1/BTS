// 활성 SAML IdP 목록 응답 DTO — UI 로그인 폼 노출용 (민감정보 제외)

package com.atlas.bts.identity.web.dto

/**
 * GET /api/v1/auth/saml/idps 응답 루트 객체 (FR-AU-03).
 *
 * UI 로그인 폼에 표시할 활성 SAML IdP 선택지를 담는다.
 */
data class SamlIdpsResponse(
    val idps: List<SamlIdpResponse>,
)

/**
 * 단일 SAML IdP 항목 — UI 표시에 필요한 최소 필드만 노출한다.
 *
 * 인증서(idp_x509_cert)·SSO URL·entityId·authn_provider_id 등 민감·내부 정보는
 * 절대 포함하지 않는다(DEVELOPMENT.md §1 — 민감정보 미노출).
 */
data class SamlIdpResponse(
    /** SP 측 등록 식별자 — SSO 진입 경로(/saml2/.../{registrationId})에 사용 */
    val registrationId: String,
    /** UI 버튼에 표시할 IdP 이름 */
    val displayName: String,
)
