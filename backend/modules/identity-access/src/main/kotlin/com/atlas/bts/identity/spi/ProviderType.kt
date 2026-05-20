// 인증 공급자 유형을 나타내는 열거형 — LOCAL/LDAP/SAML/OIDC/PAT/OAUTH

package com.atlas.bts.identity.spi

/**
 * BTS가 지원하는 인증 공급자 유형.
 *
 * @property priority ProviderRegistry 정렬 기준 기본 우선순위 (높을수록 먼저 시도).
 *   LDAP=80 > LOCAL=70 > PAT=60 > OIDC=50 > SAML=40 > OAUTH=30 (EC-25, FR-AU-09 §19.2).
 *   동률 시 fallback: [ProviderRegistry]가 type.ordinal 오름차순으로 결정.
 *
 * 구현체 추가 시 여기에 등록하고 priority 값을 명시적으로 지정할 것.
 */
enum class ProviderType(val priority: Int) {
    LOCAL(70),
    LDAP(80),
    SAML(40),
    OIDC(50),
    PAT(60),
    OAUTH(30),
}
