// SamlProvider 단위 테스트 — type/메타 + supports false + authenticate dead-path (FR-AU-03)

package com.atlas.bts.identity.provider.saml

import com.atlas.bts.identity.spi.AuthnResult
import com.atlas.bts.identity.spi.Credential
import com.atlas.bts.identity.spi.FailureReason
import com.atlas.bts.identity.spi.ProviderType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * [SamlProvider] 단위 테스트.
 *
 * SAML 인증은 Spring SAML2 필터가 수행하므로 이 공급자는 type/메타만 제공한다.
 * supports 는 항상 false, authenticate 는 호출되지 않는 dead-path 임을 검증한다.
 */
class SamlProviderUnitTest {
    private val provider = SamlProvider()

    @Test
    fun `type 은 SAML 이다`() {
        assertThat(provider.type).isEqualTo(ProviderType.SAML)
    }

    @Test
    fun `priority 는 ProviderType_SAML 의 기본값 40 을 위임한다`() {
        assertThat(provider.priority).isEqualTo(ProviderType.SAML.priority)
        assertThat(provider.priority).isEqualTo(EXPECTED_SAML_PRIORITY)
    }

    @Test
    fun `available 기본값은 true 다`() {
        assertThat(provider.available).isTrue()
    }

    @Test
    fun `supports 는 어떤 자격증명이든 false 를 반환한다 (필터가 처리)`() {
        val saml = Credential.SamlAssertion(
            nameId = "alice@corp.example.com",
            registrationId = "corp-saml",
            attributes = emptyMap(),
        )
        assertThat(provider.supports(saml)).isFalse()
        assertThat(provider.supports(Credential.Pat("pat_x"))).isFalse()
    }

    @Test
    fun `authenticate 는 dead-path 이므로 Failure 를 반환한다 (예외 throw 금지)`() {
        val saml = Credential.SamlAssertion(
            nameId = "alice@corp.example.com",
            registrationId = "corp-saml",
            attributes = emptyMap(),
        )
        val result = provider.authenticate(saml)
        assertThat(result).isInstanceOf(AuthnResult.Failure::class.java)
        assertThat((result as AuthnResult.Failure).reason).isEqualTo(FailureReason.PROVIDER_UNAVAILABLE)
    }

    private companion object {
        const val EXPECTED_SAML_PRIORITY = 40
    }
}
