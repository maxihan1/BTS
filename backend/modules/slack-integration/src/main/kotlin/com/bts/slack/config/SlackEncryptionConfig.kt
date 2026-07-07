// Slack bot token 암호화용 SecretEncryptor 빈을 환경변수 키/salt 로 구성하는 설정

package com.bts.slack.config

import com.bts.shared.crypto.SecretEncryptor
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Slack bot token 대칭 암호화용 [SecretEncryptor] 빈 구성 (FR-SL-01 Task 5).
 */
@Configuration
class SlackEncryptionConfig(
    @param:Value("\${$PROPERTY_KEY:}") private val encryptionKey: String,
    @param:Value("\${$PROPERTY_SALT:}") private val encryptionSalt: String,
) {
    @Bean("slackSecretEncryptor")
    fun slackSecretEncryptor(): SecretEncryptor = SecretEncryptor(encryptionKey, encryptionSalt)

    companion object {
        const val PROPERTY_KEY = "bts.slack-encryption.key"
        const val PROPERTY_SALT = "bts.slack-encryption.salt"
    }
}
