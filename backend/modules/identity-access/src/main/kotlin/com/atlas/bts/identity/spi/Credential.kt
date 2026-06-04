// 인증 시도 입력 VO — sealed interface UsernamePassword/Pat/LdapBind (메모리 전용, DB 저장 금지)

package com.atlas.bts.identity.spi

/**
 * 인증 시도 시 공급자에게 전달되는 자격증명 VO.
 * 메모리에만 존재하며 DB에 저장되지 않는다.
 *
 * 구현체:
 * - [UsernamePassword]: 로컬 인증용. password는 CharArray로 메모리 즉시 초기화 가능.
 * - [Pat]: Personal Access Token 인증용.
 * - [LdapBind]: LDAP/AD 인증용. password는 CharArray로 메모리 즉시 초기화 가능.
 */
sealed interface Credential {
    /**
     * 사용자 이름 + 패스워드 자격증명.
     *
     * Contract: 인증 완료 후 반드시 [password] 배열을 초기화해야 한다.
     * 예: `de.mkammerer.argon2.Argon2Helper.wipeArray(password)` 또는 `password.fill(' ')`.
     * 이 책임은 [com.atlas.bts.identity.spi.AuthenticationProvider] 구현체에 있다.
     *
     * equals/hashCode는 CharArray 내용 기반으로 override됨 (기본 참조 동등 대신).
     */
    data class UsernamePassword(
        val username: String,
        val password: CharArray,
    ) : Credential {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is UsernamePassword) return false
            return username == other.username && password.contentEquals(other.password)
        }

        override fun hashCode(): Int {
            var result = username.hashCode()
            result = 31 * result + password.contentHashCode()
            return result
        }
    }

    /**
     * Personal Access Token 자격증명.
     * token은 raw 값이며, 저장 시 sha256 해시로 변환된다 (DATA.md §8).
     */
    data class Pat(val token: String) : Credential

    /**
     * SAML Assertion 자격증명 (FR-AU-03 SAML SSO).
     *
     * **검증 주체 (게이트1 D4)**:
     * SAML Assertion의 서명·시각·audience 검증은 Spring Security SAML2 필터가 수행한다.
     * 이 VO는 검증 책임을 지지 않으며, 필터가 검증을 완료한 뒤 추출한 식별 정보를
     * BTS SPI 계층으로 표현하기 위한 최소 형태일 뿐이다.
     * 따라서 일반 로그인(POST /login)의 [UsernamePassword]/[Pat] 와 달리
     * [com.atlas.bts.identity.spi.AuthenticationProvider.authenticate] 진입점으로 흐르지 않는다.
     *
     * **PII 주의 (DEVELOPMENT.md §1.2)**:
     * [nameId] 와 [attributes] 값은 PII를 포함할 수 있다. 로그에 직접 출력 금지.
     *
     * @param nameId IdP가 발급한 NameID — user_external_accounts.external_subject 로 매핑
     * @param registrationId SP 측 등록 식별자 — authn_providers.id 해소에 사용
     * @param attributes IdP가 보낸 SAML Attribute 맵 (email/displayName 추출용)
     */
    data class SamlAssertion(
        val nameId: String,
        val registrationId: String,
        val attributes: Map<String, List<String>>,
    ) : Credential

    /**
     * OIDC(OpenID Connect) 토큰 자격증명 (FR-AU-04 OIDC SSO).
     *
     * **검증 주체**:
     * OIDC ID Token 의 서명·issuer·audience·만료 검증과 인증 흐름 자체는
     * Spring Security OAuth2/OIDC 필터가 수행한다. 이 VO 는 검증 책임을 지지 않으며,
     * 필터가 검증을 완료한 뒤 추출한 식별 정보를 BTS SPI 계층으로 표현하기 위한 최소 형태일 뿐이다.
     * 따라서 일반 로그인(POST /login)의 [UsernamePassword]/[Pat] 와 달리
     * [com.atlas.bts.identity.spi.AuthenticationProvider.authenticate] 진입점으로 흐르지 않는 dead-path 다.
     *
     * **PII 주의 (DEVELOPMENT.md §1.2)**:
     * [sub] 와 [claims] 값은 PII 를 포함할 수 있다. 로그에 직접 출력 금지.
     *
     * @param sub OIDC ID Token 의 subject 클레임 — user_external_accounts.external_subject 로 매핑
     * @param registrationId ClientRegistration 식별자 — authn_providers.id 해소에 사용
     * @param claims IdP 가 보낸 OIDC 클레임 맵 (email/name 추출용)
     */
    data class OidcToken(
        val sub: String,
        val registrationId: String,
        val claims: Map<String, Any>,
    ) : Credential

    /**
     * LDAP/AD bind 자격증명 (FR-AU-02).
     *
     * Contract: 인증 완료 후 반드시 [password] 배열을 즉시 초기화해야 한다.
     * 예: `password.fill(' ')`.
     * 이 책임은 [com.atlas.bts.identity.provider.ldap.LdapProvider] 구현체에 있다.
     * 특히 try-finally 블록으로 예외 발생 시에도 반드시 wipe 되어야 한다.
     *
     * equals/hashCode는 CharArray 내용 기반으로 override됨 (기본 참조 동등 대신).
     */
    data class LdapBind(
        val username: String,
        val password: CharArray,
    ) : Credential {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is LdapBind) return false
            return username == other.username && password.contentEquals(other.password)
        }

        override fun hashCode(): Int {
            var result = username.hashCode()
            result = 31 * result + password.contentHashCode()
            return result
        }
    }
}
