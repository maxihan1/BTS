// Webhook signing secret 암호화용 SecretEncryptor 빈을 환경변수 키/salt 로 구성하는 설정

package com.bts.search.webhook.config

import com.bts.shared.crypto.SecretEncryptor
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Outbound Webhook signing secret 대칭 암호화용 [SecretEncryptor] 빈 구성 (FR-API-03 PR2 Task 4).
 *
 * ## 키/salt 주입 (DEVELOPMENT.md §6)
 * app encryption key/salt 는 `BTS_WEBHOOK_ENCRYPTION_KEY` / `BTS_WEBHOOK_ENCRYPTION_SALT` 환경변수에서
 * application.yml 의 `bts.webhook-encryption.{key,salt}` 로 바인딩되어 [Value] 로 주입된다.
 * 미설정 시 빈 문자열로 기본값이 바인딩된다. `System.getenv` 직접 호출 없이 Spring 프로퍼티 경유로만 접근한다.
 *
 * identity-access 의 `OidcEncryptionConfig` 와 같은 패턴(BC 마다 서로 다른 키/salt 로 비밀값 도메인을
 * 격리)을 따른다. webhook BC 는 identity-access 와 다른 키를 사용해 키 유출 시 영향 범위를 분리한다.
 *
 * ## 부팅 안전성 — 항상 등록
 * 빈은 키 부재 여부와 무관하게 **항상** 등록된다. [SecretEncryptor] 는 생성자에서 예외를 던지지
 * 않고, 실제 키가 필요한 encrypt/decrypt **호출 시점**에 키 미설정을 검증한다(lazy 검증). 따라서
 * 키 미설정 환경(슬라이스/통합 테스트)에서도 이 빈에 의존하는 다른 컴포넌트가 부팅 실패 없이 로드된다.
 */
@Configuration
class WebhookEncryptionConfig(
    @param:Value("\${bts.webhook-encryption.key:}") private val encryptionKey: String,
    @param:Value("\${bts.webhook-encryption.salt:}") private val encryptionSalt: String,
) {
    /**
     * Webhook signing secret 암호화/복호화에 사용하는 [SecretEncryptor] 빈.
     *
     * 키/salt 미설정 시에도 빈은 생성된다(부팅 안전). 이 경우 실제 암호화 호출에서
     * [IllegalStateException] 이 발생한다([SecretEncryptor] 참고).
     *
     * @return 환경변수 주입 키/salt 로 구성된 [SecretEncryptor]. 미설정 시 사용 시점 검증 모드.
     */
    @Bean
    fun secretEncryptor(): SecretEncryptor = SecretEncryptor(encryptionKey, encryptionSalt)
}
