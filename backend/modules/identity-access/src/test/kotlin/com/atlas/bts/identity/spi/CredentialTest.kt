// Credential sealed interface — UsernamePassword/Pat 봉인 타입 및 equals 검증 테스트

package com.atlas.bts.identity.spi

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class CredentialTest {
    @Test
    fun `sealed Credential when is exhaustive for UsernamePassword and Pat`() {
        val credentials: List<Credential> = listOf(
            Credential.UsernamePassword("alice", "p@ssword".toCharArray()),
            Credential.Pat("token-abc"),
        )
        credentials.forEach { cred ->
            // when 식이 모든 분기를 커버해야 컴파일됨 — exhaustive 검증
            val label: String = when (cred) {
                is Credential.UsernamePassword -> "username-password"
                is Credential.Pat -> "pat"
            }
            assertThat(label).isNotEmpty()
        }
    }

    @Test
    fun `UsernamePassword equals compares CharArray content`() {
        val c1 = Credential.UsernamePassword("alice", charArrayOf('p', 'w', 'd'))
        val c2 = Credential.UsernamePassword("alice", charArrayOf('p', 'w', 'd'))
        assertThat(c1).isEqualTo(c2)
    }

    @Test
    fun `UsernamePassword not equals when password differs`() {
        val c1 = Credential.UsernamePassword("alice", charArrayOf('p', 'w', 'd'))
        val c2 = Credential.UsernamePassword("alice", charArrayOf('x', 'y', 'z'))
        assertThat(c1).isNotEqualTo(c2)
    }

    @Test
    fun `Pat equals by token value`() {
        val p1 = Credential.Pat("tok-123")
        val p2 = Credential.Pat("tok-123")
        assertThat(p1).isEqualTo(p2)
    }
}
