// WebhookEncryptionConfig — 환경변수 키/salt 로 shared SecretEncryptor 빈이 구성되는지 검증하는 테스트

package com.bts.search.webhook.config

import com.bts.shared.crypto.SecretEncryptor
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner

/**
 * [WebhookEncryptionConfig] 검증.
 *
 * ApplicationContextRunner(Spring Test 유틸리티 — 전체 서버 기동 없이 ApplicationContext만 띄워
 * Bean 등록/실패를 검증하는 경량 도구)로 `bts.webhook-encryption.key`/`salt` 프로퍼티를 주입해
 * shared [SecretEncryptor] 빈이 정상 구성되는지 확인한다.
 */
class WebhookEncryptionConfigTest {
    private val runner =
        ApplicationContextRunner()
            .withUserConfiguration(WebhookEncryptionConfig::class.java)

    @Test
    fun `key와 salt가 설정되면 SecretEncryptor 빈이 등록되고 round-trip 암복호화가 성공한다`() {
        runner
            .withPropertyValues(
                "bts.webhook-encryption.key=$TEST_KEY",
                "bts.webhook-encryption.salt=$TEST_SALT",
            ).run { ctx ->
                assertThat(ctx).hasNotFailed()
                val bean = ctx.getBean(SecretEncryptor::class.java)

                val plaintext = "webhook-signing-secret-value"
                val ciphertext = bean.encrypt(plaintext)
                val decrypted = bean.decrypt(ciphertext)

                assertThat(decrypted).isEqualTo(plaintext)
            }
    }

    @Test
    fun `key와 salt가 미설정이어도 빈은 정상 등록된다 (부팅 안전 — lazy 검증)`() {
        runner.run { ctx ->
            assertThat(ctx).hasNotFailed()
            assertThat(ctx.getBean(SecretEncryptor::class.java)).isNotNull()
        }
    }

    private companion object {
        const val TEST_KEY = "test-webhook-encryption-key-value"

        // Encryptors.stronger 는 salt 가 hex 문자열일 것을 런타임에 요구한다.
        const val TEST_SALT = "deadbeefcafef00d"
    }
}
