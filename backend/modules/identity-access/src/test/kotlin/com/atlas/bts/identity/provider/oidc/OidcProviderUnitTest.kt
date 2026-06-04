// OidcProvider 단위 테스트 — type/메타 + supports false + authenticate dead-path (FR-AU-04)

package com.atlas.bts.identity.provider.oidc

import com.atlas.bts.identity.spi.AuthnResult
import com.atlas.bts.identity.spi.Credential
import com.atlas.bts.identity.spi.FailureReason
import com.atlas.bts.identity.spi.ProviderType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * [OidcProvider] 단위 테스트.
 *
 * OIDC 인증은 Spring Security OAuth2/OIDC 필터가 수행하므로 이 공급자는 type/메타만 제공한다.
 * supports 는 항상 false, authenticate 는 호출되지 않는 dead-path 임을 검증한다 (SamlProvider 동형).
 */
class OidcProviderUnitTest {
    private val provider = OidcProvider()

    @Test
    fun `type 은 OIDC 이다`() {
        assertThat(provider.type).isEqualTo(ProviderType.OIDC)
    }

    @Test
    fun `priority 는 ProviderType_OIDC 의 기본값 50 을 위임한다`() {
        assertThat(provider.priority).isEqualTo(ProviderType.OIDC.priority)
        assertThat(provider.priority).isEqualTo(EXPECTED_OIDC_PRIORITY)
    }

    @Test
    fun `available 기본값은 true 다`() {
        assertThat(provider.available).isTrue()
    }

    @Test
    fun `supports 는 어떤 자격증명이든 false 를 반환한다 (필터가 처리)`() {
        val oidc =
            Credential.OidcToken(
                sub = "oidc-subject-123",
                registrationId = "corp-oidc",
                claims = emptyMap(),
            )
        assertThat(provider.supports(oidc)).isFalse()
        assertThat(provider.supports(Credential.Pat("pat_x"))).isFalse()
    }

    @Test
    fun `authenticate 는 dead-path 이므로 Failure 를 반환한다 (예외 throw 금지)`() {
        val oidc =
            Credential.OidcToken(
                sub = "oidc-subject-123",
                registrationId = "corp-oidc",
                claims = emptyMap(),
            )
        val result = provider.authenticate(oidc)
        assertThat(result).isInstanceOf(AuthnResult.Failure::class.java)
        assertThat((result as AuthnResult.Failure).reason).isEqualTo(FailureReason.PROVIDER_UNAVAILABLE)
    }

    private companion object {
        const val EXPECTED_OIDC_PRIORITY = 50
    }
}
