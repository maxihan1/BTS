// OIDC client_secret 암호화용 SecretEncryptor 빈을 환경변수 키/salt 로 구성하는 설정

package com.atlas.bts.identity.config

import com.bts.shared.crypto.SecretEncryptor
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * OIDC client_secret 대칭 암호화용 [SecretEncryptor] 빈 구성 (FR-AU-04 Task 1).
 *
 * ## 키/salt 주입 (DEVELOPMENT.md §6)
 * app encryption key/salt 는 `BTS_OIDC_ENCRYPTION_KEY` / `BTS_OIDC_ENCRYPTION_SALT` 환경변수에서
 * application.yml 의 `bts.oidc.encryption.{key,salt}` 로 바인딩되어 [Value] 로 주입된다.
 * 미설정 시 빈 문자열로 기본값이 바인딩된다. `System.getenv` 직접 호출 없이 Spring 프로퍼티 경유로만 접근한다.
 *
 * ## 부팅 안전성 — 항상 등록 (C2 정정)
 * 과거 `@ConditionalOnProperty` 로 키가 있을 때만 빈을 등록했으나, 항상 스캔되는
 * [com.atlas.bts.identity.provider.oidc.DbClientRegistrationRepository] 가 [SecretEncryptor] 를
 * 하드 의존해 키 미설정 환경(테스트)에서 `NoSuchBeanDefinitionException` 으로 부팅이 깨졌다
 * (메모리 `profile-scoped-bean-boot-failure` 와 동일 패턴). 따라서 빈은 **항상** 등록하고,
 * 키 미설정 검증은 [SecretEncryptor.encrypt]/[SecretEncryptor.decrypt] 호출 시점으로 미룬다.
 */
@Configuration
class OidcEncryptionConfig(
    @param:Value("\${bts.oidc.encryption.key:}") private val encryptionKey: String,
    @param:Value("\${bts.oidc.encryption.salt:}") private val encryptionSalt: String,
) {
    /**
     * OIDC client_secret 암호화/복호화에 사용하는 [SecretEncryptor] 빈.
     *
     * 키/salt 미설정 시에도 빈은 생성된다(부팅 안전). 이 경우 실제 암호화 호출에서
     * [IllegalStateException] 이 발생한다([SecretEncryptor] 참고).
     *
     * @return 환경변수 주입 키/salt 로 구성된 [SecretEncryptor]. 미설정 시 사용 시점 검증 모드.
     */
    @Bean
    fun secretEncryptor(): SecretEncryptor = SecretEncryptor(encryptionKey, encryptionSalt)
}
