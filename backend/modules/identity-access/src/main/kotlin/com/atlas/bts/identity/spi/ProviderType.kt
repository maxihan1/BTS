// 인증 공급자 유형을 나타내는 열거형 — LOCAL/LDAP/SAML/OIDC/PAT

package com.atlas.bts.identity.spi

/**
 * BTS가 지원하는 인증 공급자 유형.
 * 향후 LDAP/SAML/OIDC Provider 구현체 추가 시 여기에 등록.
 */
enum class ProviderType {
    LOCAL,
    LDAP,
    SAML,
    OIDC,
    PAT,
}
