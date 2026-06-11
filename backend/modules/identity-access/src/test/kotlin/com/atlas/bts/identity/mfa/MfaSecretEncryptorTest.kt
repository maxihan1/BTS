// TOTP secret 대칭 암호화 유틸(MfaSecretEncryptor)의 round-trip·랜덤 IV·키 미설정 검증 테스트

package com.atlas.bts.identity.mfa

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * [MfaSecretEncryptor] 단위 테스트 (FR-MF-01 Task 2).
 *
 * MockK 불필요 — 순수 암호화 유틸이므로 고정 테스트 키/salt 리터럴로 self-consistent 하게 검증한다.
 * 테스트 salt 는 `Encryptors.stronger` 런타임 검증을 통과하는 유효 hex 문자열이어야 한다.
 *
 * OIDC 의 `SecretEncryptor` 와 알고리즘은 같지만 **키 격리**를 위해 별도 유틸로 분리됐다.
 * 본 테스트는 MFA 전용 키로 round-trip·random IV·키 미설정 시 사용 시점 예외를 검증한다.
 */
class MfaSecretEncryptorTest {
    @Test
    fun `평문 secret 을 암호화 후 복호화하면 원래 평문과 일치한다 (round-trip)`() {
        val encryptor = MfaSecretEncryptor(TEST_KEY, TEST_SALT)
        val plaintext = "JBSWY3DPEHPK3PXP"

        val ciphertext = encryptor.encrypt(plaintext)
        val decrypted = encryptor.decrypt(ciphertext)

        assertThat(decrypted).isEqualTo(plaintext)
    }

    @Test
    fun `같은 평문을 두 번 암호화하면 서로 다른 ciphertext 가 나온다 (random IV)`() {
        val encryptor = MfaSecretEncryptor(TEST_KEY, TEST_SALT)
        val plaintext = "JBSWY3DPEHPK3PXP"

        val first = encryptor.encrypt(plaintext)
        val second = encryptor.encrypt(plaintext)

        // random IV — 같은 평문이라도 매 호출 ciphertext 가 달라야 한다.
        assertThat(first).isNotEqualTo(second)
        // 단, 둘 다 복호화하면 원래 평문으로 돌아와야 한다.
        assertThat(encryptor.decrypt(first)).isEqualTo(plaintext)
        assertThat(encryptor.decrypt(second)).isEqualTo(plaintext)
    }

    @Test
    fun `키 미설정으로 생성해도 생성 자체는 성공한다 (부팅 안전 — 빈 항상 등록)`() {
        // 키/salt 가 비어도 생성자에서 예외를 던지지 않아야 빈이 항상 등록되어 부팅이 안전하다.
        MfaSecretEncryptor("", "")
    }

    @Test
    fun `키 미설정 encryptor 로 암호화하면 사용 시점에 예외를 던진다`() {
        val encryptor = MfaSecretEncryptor("", "")

        assertThatThrownBy { encryptor.encrypt("any-secret") }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("encryption key not configured")
    }

    @Test
    fun `키 미설정 encryptor 로 복호화하면 사용 시점에 예외를 던진다`() {
        val encryptor = MfaSecretEncryptor("", "")

        assertThatThrownBy { encryptor.decrypt("deadbeef") }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("encryption key not configured")
    }

    private companion object {
        const val TEST_KEY = "test-app-encryption-key-for-mfa-totp"

        // Encryptors.stronger 는 salt 가 hex 문자열일 것을 런타임에 요구한다.
        const val TEST_SALT = "deadbeefcafef00d"
    }
}
