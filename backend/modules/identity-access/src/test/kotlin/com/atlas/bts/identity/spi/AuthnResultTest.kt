// AuthnResult sealed interface — Success/Failure/RequiresMfa 패턴 매칭 검증 테스트

package com.atlas.bts.identity.spi

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class AuthnResultTest {
    private val samplePrincipal = Principal(
        userId = UUID.randomUUID(),
        providerType = ProviderType.LOCAL,
        displayName = "Alice",
        externalSubject = null,
    )

    @Test
    fun `Success carries Principal`() {
        val result: AuthnResult = AuthnResult.Success(samplePrincipal)
        assertThat(result).isInstanceOf(AuthnResult.Success::class.java)
        assertThat((result as AuthnResult.Success).principal).isEqualTo(samplePrincipal)
    }

    @Test
    fun `Failure carries FailureReason`() {
        val result: AuthnResult = AuthnResult.Failure(FailureReason.INVALID_CREDENTIALS)
        assertThat(result).isInstanceOf(AuthnResult.Failure::class.java)
        assertThat((result as AuthnResult.Failure).reason).isEqualTo(FailureReason.INVALID_CREDENTIALS)
    }

    @Test
    fun `RequiresMfa carries MfaChallenge`() {
        val result: AuthnResult = AuthnResult.RequiresMfa(MfaChallenge.NOT_IMPLEMENTED_YET)
        assertThat(result).isInstanceOf(AuthnResult.RequiresMfa::class.java)
        assertThat((result as AuthnResult.RequiresMfa).challenge).isEqualTo(MfaChallenge.NOT_IMPLEMENTED_YET)
    }

    @Test
    fun `sealed AuthnResult when is exhaustive`() {
        val results: List<AuthnResult> = listOf(
            AuthnResult.Success(samplePrincipal),
            AuthnResult.Failure(FailureReason.ACCOUNT_LOCKED),
            AuthnResult.RequiresMfa(MfaChallenge.NOT_IMPLEMENTED_YET),
        )
        results.forEach { result ->
            val label: String = when (result) {
                is AuthnResult.Success -> "success"
                is AuthnResult.Failure -> "failure"
                is AuthnResult.RequiresMfa -> "requires-mfa"
            }
            assertThat(label).isNotEmpty()
        }
    }
}
