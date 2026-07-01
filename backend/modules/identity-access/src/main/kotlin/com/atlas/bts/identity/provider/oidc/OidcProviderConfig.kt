// oidc_provider_configs 테이블 매핑 VO — OIDC(OpenID Connect) IdP 연동 설정

package com.atlas.bts.identity.provider.oidc

import java.util.UUID

/**
 * oidc_provider_configs 테이블 행 매핑 VO (FR-AU-04 OIDC SSO).
 *
 * 외부 OIDC Provider(IdP)의 등록 메타데이터를 보관한다. 이 VO 는 Spring Security 의
 * ClientRegistration 으로 변환되어 BTS(RP) ↔ IdP OIDC 흐름에 사용된다
 * ([DbClientRegistrationRepository]).
 *
 * [clientSecretEncrypted] 는 app key 로 **암호화된** client_secret 이다(평문 저장 금지 — §1.1.1).
 * 복호화는 본 VO 가 아니라 [DbClientRegistrationRepository] 가 [com.bts.shared.crypto.SecretEncryptor]
 * 로 수행하며, 복호화 결과(평문 secret)는 메모리에만 두고 **절대 로깅하지 않는다**(§1.1.2).
 */
data class OidcProviderConfig(
    val id: UUID,
    /** ClientRegistration 식별자 — OAuth2 콜백 URL 경로(/login/oauth2/code/{registrationId})에 사용 (UNIQUE) */
    val registrationId: String,
    /** 로그인 화면 SSO 버튼 라벨 */
    val displayName: String,
    /** IdP issuer URI — OIDC .well-known discovery 기준 */
    val issuerUri: String,
    /** OAuth2 client_id (공개값) */
    val clientId: String,
    /** app key 로 암호화된 client_secret — 평문 금지. 복호화 결과는 로깅 금지 (§1.1.2) */
    val clientSecretEncrypted: String,
    /** 요청 OAuth2/OIDC 스코프 (CSV, 예: openid,profile,email) */
    val scopes: String,
    /** JIT 프로비저닝용 authn_providers.id FK (OIDC seed row) */
    val authnProviderId: UUID,
    /** 활성 여부 — false 면 SSO 비노출 (EC5) */
    val enabled: Boolean,
)
