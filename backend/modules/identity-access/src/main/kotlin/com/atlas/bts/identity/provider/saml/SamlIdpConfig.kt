// saml_idp_configs 테이블 매핑 VO — SAML IdP(Identity Provider) 연동 설정

package com.atlas.bts.identity.provider.saml

import java.util.UUID

/**
 * saml_idp_configs 테이블 행 매핑 VO (FR-AU-03 SAML SSO).
 *
 * 외부 SAML IdP 의 메타데이터를 보관한다. 이 VO 는 Spring Security 의
 * RelyingPartyRegistration 으로 변환되어 SP(BTS) ↔ IdP SSO 흐름에 사용된다
 * ([DbRelyingPartyRegistrationRepository]).
 *
 * [idpX509Cert] 는 IdP 의 **공개** 서명 검증 인증서(PEM)다. 공개 인증서이므로
 * 평문 저장이 허용된다(비밀값 아님 — DEVELOPMENT.md §1).
 */
data class SamlIdpConfig(
    val id: UUID,
    /** SP 측 등록 식별자 — SSO URL 경로(/saml2/.../{registrationId})에 사용 (UNIQUE) */
    val registrationId: String,
    /** 관리 UI 표시용 이름 */
    val displayName: String,
    /** IdP EntityID (IdP 메타데이터의 고유 식별자) */
    val idpEntityId: String,
    /** IdP Single Sign-On 엔드포인트 URL */
    val idpSsoUrl: String,
    /** IdP 서명 검증용 공개 X.509 인증서 (PEM 형식) */
    val idpX509Cert: String,
    /** JIT 프로비저닝용 authn_providers.id FK (SAML seed row) */
    val authnProviderId: UUID,
    /** 활성 여부 — false 면 SSO 비노출 (EC5) */
    val enabled: Boolean,
)
