// AutomationEncryptionConfig — 환경변수 키/salt 로 shared SecretEncryptor 빈(automationSecretEncryptor)이 구성되는지 검증하는 테스트

package com.bts.automation

import com.bts.shared.crypto.SecretEncryptor
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner

/**
 * [AutomationEncryptionConfig] 검증.
 *
 * ApplicationContextRunner(Spring Test 유틸리티 — 전체 서버 기동 없이 ApplicationContext만 띄워
 * Bean 등록/실패를 검증하는 경량 도구)로 `bts.automation-encryption.key`/`salt` 프로퍼티를 주입해
 * shared [SecretEncryptor] 빈이 `automationSecretEncryptor` 이름으로 정상 구성되는지 확인한다.
 *
 * 빈 이름을 by-name 으로 검증하는 이유: 소비처가 `@Qualifier("automationSecretEncryptor")` 로 주입하므로
 * 이름이 바뀌면 조립이 깨진다. 동일 타입 SecretEncryptor 빈(oidc/webhook/slack — prod 조립 시 4개)과의
 * 충돌도 이름으로 격리된다(이름 미지정 시 `NoUniqueBeanDefinitionException`).
 */
class AutomationEncryptionConfigTest {
    private val runner =
        ApplicationContextRunner()
            .withUserConfiguration(AutomationEncryptionConfig::class.java)

    @Test
    fun `key와 salt가 설정되면 automationSecretEncryptor 빈이 등록되고 round-trip 암복호화가 성공한다`() {
        runner
            .withPropertyValues(
                "${AutomationEncryptionConfig.PROPERTY_KEY}=$TEST_KEY",
                "${AutomationEncryptionConfig.PROPERTY_SALT}=$TEST_SALT",
            ).run { ctx ->
                assertThat(ctx).hasNotFailed()
                // by-name 주입 계약(@Qualifier) 검증 — 이름이 반드시 automationSecretEncryptor 여야 한다.
                assertThat(ctx).hasBean("automationSecretEncryptor")
                val bean = ctx.getBean("automationSecretEncryptor", SecretEncryptor::class.java)

                val plaintext = "git-webhook-signing-secret-value"
                val ciphertext = bean.encrypt(plaintext)
                val decrypted = bean.decrypt(ciphertext)

                assertThat(decrypted).isEqualTo(plaintext)
            }
    }

    @Test
    fun `key와 salt가 미설정이어도 automationSecretEncryptor 빈은 정상 등록된다 (부팅 안전 — lazy 검증)`() {
        runner.run { ctx ->
            assertThat(ctx).hasNotFailed()
            assertThat(ctx).hasBean("automationSecretEncryptor")
            assertThat(ctx.getBean("automationSecretEncryptor", SecretEncryptor::class.java)).isNotNull()
        }
    }

    @Test
    fun `키 미설정 빈으로 암호화하면 사용 시점에 예외를 던진다`() {
        runner.run { ctx ->
            assertThat(ctx).hasNotFailed()
            val bean = ctx.getBean("automationSecretEncryptor", SecretEncryptor::class.java)

            assertThatThrownBy { bean.encrypt("any-webhook-secret") }
                .isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("encryption key not configured")
        }
    }

    private companion object {
        const val TEST_KEY = "test-automation-encryption-key-value"

        // Encryptors.stronger 는 salt 가 hex 문자열일 것을 런타임에 요구한다.
        const val TEST_SALT = "deadbeefcafef00d"
    }
}
