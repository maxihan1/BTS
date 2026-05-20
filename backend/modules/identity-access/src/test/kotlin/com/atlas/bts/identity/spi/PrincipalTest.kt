// Principal VO — toString PII 마스킹 검증 테스트

package com.atlas.bts.identity.spi

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class PrincipalTest {
    private val userId = UUID.fromString("11111111-1111-1111-1111-111111111111")

    @Test
    fun `toString masks externalSubject`() {
        val principal = Principal(
            userId = userId,
            providerType = ProviderType.LOCAL,
            displayName = "Alice",
            externalSubject = "secret-subject-id-12345",
        )
        val str = principal.toString()
        assertThat(str).doesNotContain("secret-subject-id-12345")
        assertThat(str).contains("<masked>")
    }

    @Test
    fun `toString includes displayName`() {
        val principal = Principal(
            userId = userId,
            providerType = ProviderType.OIDC,
            displayName = "Bob",
            externalSubject = "some-external-id",
        )
        val str = principal.toString()
        assertThat(str).contains("Bob")
    }

    @Test
    fun `toString with null externalSubject shows null masked`() {
        val principal = Principal(
            userId = userId,
            providerType = ProviderType.LOCAL,
            displayName = "Carol",
            externalSubject = null,
        )
        val str = principal.toString()
        assertThat(str).doesNotContain("null")
    }
}
