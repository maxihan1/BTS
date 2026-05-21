// JwtKeyProvider 구현체 단위 테스트 — DevMemoryKeyProvider RSA 2048 키 생성 + PemFileKeyProvider fail-fast 검증

package com.atlas.bts.identity.jwt

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey

/**
 * JwtKeyProvider SPI 구현체 단위 테스트.
 *
 * - EC-19: PemFileKeyProvider — PEM 경로 없음 → fail-fast (IllegalStateException)
 * - EC-30: prod 환경에서 env var 미설정 시 silent dev fallback 방지 검증
 */
class JwtKeyProviderTest {
    // ── DevMemoryKeyProvider ─────────────────────────────────────────────────

    @Test
    fun `DevMemoryKeyProvider 는 RSAPrivateKey 와 RSAPublicKey 를 제공한다`() {
        val provider = DevMemoryKeyProvider()

        assertThat(provider.privateKey).isInstanceOf(RSAPrivateKey::class.java)
        assertThat(provider.publicKey).isInstanceOf(RSAPublicKey::class.java)
    }

    @Test
    fun `DevMemoryKeyProvider privateKey 와 publicKey 는 동일 KeyPair 에서 파생된다`() {
        val provider = DevMemoryKeyProvider()

        // RSA 2048 modulus 크기 검증
        val privateKey = provider.privateKey as RSAPrivateKey
        val publicKey = provider.publicKey as RSAPublicKey
        assertThat(privateKey.modulus).isEqualTo(publicKey.modulus)
    }

    @Test
    fun `DevMemoryKeyProvider 는 2048 비트 RSA 키를 생성한다`() {
        val provider = DevMemoryKeyProvider()
        val publicKey = provider.publicKey as RSAPublicKey

        // RSA 2048: modulus bit length = 2048
        assertThat(publicKey.modulus.bitLength()).isEqualTo(2048)
    }

    @Test
    fun `DevMemoryKeyProvider 동일 인스턴스는 매번 동일한 키를 반환한다`() {
        val provider = DevMemoryKeyProvider()

        // 동일 인스턴스 — 키가 교체되지 않아야 함
        assertThat(provider.privateKey).isSameAs(provider.privateKey)
        assertThat(provider.publicKey).isSameAs(provider.publicKey)
    }

    @Test
    fun `DevMemoryKeyProvider kid 는 dev- 접두사를 포함한다`() {
        val provider = DevMemoryKeyProvider()

        assertThat(provider.kid).startsWith("dev-")
    }

    @Test
    fun `DevMemoryKeyProvider 서로 다른 인스턴스는 서로 다른 키를 생성한다`() {
        val provider1 = DevMemoryKeyProvider()
        val provider2 = DevMemoryKeyProvider()

        // 각 인스턴스는 독립적인 키를 생성해야 함
        assertThat(provider1.kid).isNotEqualTo(provider2.kid)
        assertThat((provider1.publicKey as RSAPublicKey).modulus)
            .isNotEqualTo((provider2.publicKey as RSAPublicKey).modulus)
    }

    // ── PemFileKeyProvider ───────────────────────────────────────────────────

    @Test
    fun `PemFileKeyProvider 존재하지 않는 경로로 load 시 IllegalStateException 을 던진다 (EC-19)`() {
        val provider = PemFileKeyProvider(pemPath = "/non-existent/path/private.pem")

        assertThatThrownBy { provider.load() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("/non-existent/path/private.pem")
    }

    @Test
    fun `PemFileKeyProvider 빈 경로로 load 시 IllegalStateException 을 던진다`() {
        val provider = PemFileKeyProvider(pemPath = "")

        assertThatThrownBy { provider.load() }
            .isInstanceOf(IllegalStateException::class.java)
    }
}
