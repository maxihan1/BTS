// OIDC client_secret 암호화용 SecretEncryptor 빈을 환경변수 키/salt 로 구성하는 설정

package com.atlas.bts.identity.config

import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * OIDC client_secret 대칭 암호화용 [SecretEncryptor] 빈 구성 (FR-AU-04 Task 1).
 *
 * ## 키/salt 주입 (DEVELOPMENT.md §6)
 * app encryption key/salt 는 `BTS_OIDC_ENCRYPTION_KEY` / `BTS_OIDC_ENCRYPTION_SALT` 환경변수에서
 * application.yml 의 `bts.oidc.encryption.{key,salt}` 로 바인딩되어 [Value] 로 주입된다.
 * `System.getenv` 직접 호출 없이 Spring 프로퍼티 경유로만 접근한다.
 *
 * ## 조건부 구성 (부팅 안전성 — C2)
 * [ConditionalOnProperty] 로 `bts.oidc.encryption.key` 가 존재할 때만 [SecretEncryptor] 빈을 등록한다.
 * OIDC 미설정 환경(키 부재) 및 슬라이스 테스트(@WebMvcTest)에서는 이 설정을 건너뛰어 부팅을 깨지 않는다.
 */
@Configuration
@ConditionalOnProperty(prefix = "bts.oidc.encryption", name = ["key"])
class OidcEncryptionConfig(
    @param:Value("\${bts.oidc.encryption.key}") private val encryptionKey: String,
    @param:Value("\${bts.oidc.encryption.salt}") private val encryptionSalt: String,
) {
    /**
     * OIDC client_secret 암호화/복호화에 사용하는 [SecretEncryptor] 빈.
     *
     * @return 환경변수 주입 키/salt 로 구성된 [SecretEncryptor].
     */
    @Bean
    fun secretEncryptor(): SecretEncryptor = SecretEncryptor(encryptionKey, encryptionSalt)
}
