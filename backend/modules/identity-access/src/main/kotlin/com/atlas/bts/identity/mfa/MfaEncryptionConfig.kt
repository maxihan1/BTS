// TOTP secret 암호화용 MfaSecretEncryptor 빈을 MFA 전용 환경변수 키/salt 로 구성하는 설정

package com.atlas.bts.identity.mfa

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * TOTP secret 대칭 암호화용 [MfaSecretEncryptor] 빈 구성 (FR-MF-01 Task 2).
 *
 * ## 키/salt 주입 + OIDC 키와 격리 (DEVELOPMENT.md §6)
 * app encryption key/salt 는 `BTS_MFA_ENCRYPTION_KEY` / `BTS_MFA_ENCRYPTION_SALT` 환경변수에서
 * `bts.mfa.encryption.{key,salt}` 프로퍼티로 바인딩되어 [Value] 로 주입된다(Spring relaxed binding).
 * OIDC client_secret 암호화 키(`BTS_OIDC_ENCRYPTION_*`)와 **별개의 키**를 사용해, 한 키 유출 시
 * 다른 비밀값까지 함께 노출되는 폭발 반경을 차단한다([MfaSecretEncryptor] 의 키 격리 사유 참고).
 * 미설정 시 빈 문자열로 기본값이 바인딩된다. `System.getenv` 직접 호출 없이 Spring 프로퍼티 경유로만 접근한다.
 *
 * ## 부팅 안전성 — 항상 등록
 * 키가 있을 때만 등록하는 `@ConditionalOnProperty` 를 쓰지 않고 빈을 **항상** 등록한다. MFA 컴포넌트가
 * [MfaSecretEncryptor] 를 하드 의존하므로, 조건부 등록 시 키 미설정 환경(테스트)에서
 * `NoSuchBeanDefinitionException` 으로 부팅이 깨진다(메모리 `profile-scoped-bean-boot-failure`).
 * 따라서 빈은 항상 등록하고, 키 미설정 검증은
 * [MfaSecretEncryptor.encrypt]/[MfaSecretEncryptor.decrypt] 호출 시점으로 미룬다.
 */
@Configuration
class MfaEncryptionConfig(
    @param:Value("\${bts.mfa.encryption.key:}") private val encryptionKey: String,
    @param:Value("\${bts.mfa.encryption.salt:}") private val encryptionSalt: String,
) {
    /**
     * TOTP secret 암호화/복호화에 사용하는 [MfaSecretEncryptor] 빈.
     *
     * 키/salt 미설정 시에도 빈은 생성된다(부팅 안전). 이 경우 실제 암호화 호출에서
     * [IllegalStateException] 이 발생한다([MfaSecretEncryptor] 참고).
     *
     * @return 환경변수 주입 키/salt 로 구성된 [MfaSecretEncryptor]. 미설정 시 사용 시점 검증 모드.
     */
    @Bean
    fun mfaSecretEncryptor(): MfaSecretEncryptor = MfaSecretEncryptor(encryptionKey, encryptionSalt)
}
