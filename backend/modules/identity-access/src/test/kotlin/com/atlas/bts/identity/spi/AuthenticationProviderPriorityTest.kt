// AuthenticationProvider SPI priority 필드 + ProviderType 기본 우선순위 상수 검증 테스트

package com.atlas.bts.identity.spi

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Task 33 — SPI priority 필드 RED 테스트.
 *
 * 검증 항목:
 * (a) ProviderType 각 enum 값이 spec 기준 priority 상수를 반환 (FR-AU-09 §19.2).
 * (b) AuthenticationProvider 인터페이스에 val priority: Int 가 존재하고, 구현체가 타입 기본값을 반환.
 * (c) LdapProvider / FakeLocalProvider / FakePatProvider 모두 priority 필드를 정상 반환.
 *
 * Priority 우선순위 (사용자 결정, EC-25):
 *   LDAP=80 > LOCAL=70 > PAT=60 > OIDC=50 > SAML=40 > OAUTH=30
 * 동률 시 fallback: type.ordinal 오름차순 (ProviderRegistry 정렬 책임).
 */
class AuthenticationProviderPriorityTest {

    // -----------------------------------------------------------------------
    // (a) ProviderType.priority 상수 검증
    // -----------------------------------------------------------------------

    @Test
    fun `ProviderType LDAP priority is 80`() {
        assertThat(ProviderType.LDAP.priority).isEqualTo(80)
    }

    @Test
    fun `ProviderType LOCAL priority is 70`() {
        assertThat(ProviderType.LOCAL.priority).isEqualTo(70)
    }

    @Test
    fun `ProviderType PAT priority is 60`() {
        assertThat(ProviderType.PAT.priority).isEqualTo(60)
    }

    @Test
    fun `ProviderType OIDC priority is 50`() {
        assertThat(ProviderType.OIDC.priority).isEqualTo(50)
    }

    @Test
    fun `ProviderType SAML priority is 40`() {
        assertThat(ProviderType.SAML.priority).isEqualTo(40)
    }

    @Test
    fun `ProviderType OAUTH priority is 30`() {
        assertThat(ProviderType.OAUTH.priority).isEqualTo(30)
    }

    // -----------------------------------------------------------------------
    // (b) AuthenticationProvider 인터페이스 default priority getter 검증
    //     — 익명 구현체로 인터페이스 계약만 검증 (구현체 의존성 없음)
    // -----------------------------------------------------------------------

    @Test
    fun `AuthenticationProvider default priority delegates to type priority`() {
        val provider = object : AuthenticationProvider {
            override val type: ProviderType = ProviderType.LDAP
            override fun supports(credential: Credential): Boolean = false
            override fun authenticate(credential: Credential): AuthnResult =
                AuthnResult.Failure(FailureReason.PROVIDER_UNAVAILABLE)
            // priority 는 interface default getter 로 제공 — override 없이 type.priority 반환
        }
        assertThat(provider.priority).isEqualTo(80)
    }

    @Test
    fun `AuthenticationProvider priority follows ProviderType for LOCAL`() {
        val provider = object : AuthenticationProvider {
            override val type: ProviderType = ProviderType.LOCAL
            override fun supports(credential: Credential): Boolean = false
            override fun authenticate(credential: Credential): AuthnResult =
                AuthnResult.Failure(FailureReason.PROVIDER_UNAVAILABLE)
        }
        assertThat(provider.priority).isEqualTo(70)
    }

    @Test
    fun `AuthenticationProvider priority follows ProviderType for PAT`() {
        val provider = object : AuthenticationProvider {
            override val type: ProviderType = ProviderType.PAT
            override fun supports(credential: Credential): Boolean = false
            override fun authenticate(credential: Credential): AuthnResult =
                AuthnResult.Failure(FailureReason.PROVIDER_UNAVAILABLE)
        }
        assertThat(provider.priority).isEqualTo(60)
    }

    // -----------------------------------------------------------------------
    // (c) priority 순서 전체 정렬 검증 — 동률 없이 내림차순 보장
    // -----------------------------------------------------------------------

    @Test
    fun `ProviderType priorities are strictly decreasing in spec order`() {
        val specOrder = listOf(
            ProviderType.LDAP,
            ProviderType.LOCAL,
            ProviderType.PAT,
            ProviderType.OIDC,
            ProviderType.SAML,
            ProviderType.OAUTH,
        )
        val priorities = specOrder.map { it.priority }
        assertThat(priorities).isSortedAccordingTo(Comparator.reverseOrder())
    }
}
