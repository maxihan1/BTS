// Git 웹훅 secret 등 automation 외부 비밀값 암호화용 SecretEncryptor 빈을 환경변수 키/salt 로 구성하는 설정

package com.bts.automation

import com.bts.shared.crypto.SecretEncryptor
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Git 웹훅 secret(및 기타 automation 외부 비밀값) 대칭 암호화용 [SecretEncryptor] 빈 구성 (FR-AT-07 PR-C).
 *
 * ## 키/salt 주입 (DEVELOPMENT.md §1.1.1 / DATA.md §8 — 외부 비밀값은 암호화 후 저장)
 * app encryption key/salt 는 `BTS_AUTOMATION_ENCRYPTION_KEY` / `BTS_AUTOMATION_ENCRYPTION_SALT`
 * 환경변수에서 `bts.automation-encryption.{key,salt}` 프로퍼티로 바인딩되어 [Value] 로 주입된다
 * (Spring relaxed binding). 미설정 시 빈 문자열로 기본값이 바인딩된다.
 * `System.getenv` 직접 호출 없이 Spring 프로퍼티 경유로만 접근한다(DEVELOPMENT.md §6).
 *
 * identity-access 의 `OidcEncryptionConfig`, search-export-import 의 `WebhookEncryptionConfig`,
 * slack-integration 의 `SlackEncryptionConfig` 와 같은 패턴(BC 마다 서로 다른 키/salt 로 비밀값 도메인을
 * 격리)을 따른다. automation BC 는 다른 BC 와 다른 키를 사용해 키 유출 시 영향 범위를 분리한다
 * (키 위생 — 한 도메인 키 유출이 다른 도메인으로 전파되지 않음).
 *
 * ## 부팅 안전성 — 항상 등록
 * 빈은 키 부재 여부와 무관하게 **항상** 등록된다. [SecretEncryptor] 는 생성자에서 예외를 던지지
 * 않고, 실제 키가 필요한 encrypt/decrypt **호출 시점**에 키 미설정을 검증한다(lazy 검증). 따라서
 * 키 미설정 환경(슬라이스/통합 테스트)에서도 이 빈에 의존하는 다른 컴포넌트가 부팅 실패 없이 로드된다.
 * `@ConditionalOnProperty` 로 조건부 등록하면 키 미설정 환경에서 `NoSuchBeanDefinitionException` 으로
 * 풀 컨텍스트 부팅이 깨지므로 사용하지 않는다.
 *
 * ## 배포 주의 — health 는 green 이지만 기능은 500
 * 키 미설정은 부팅/`/actuator/health` 로 드러나지 않고 Git 웹훅 secret 저장/검증 첫 호출에서 500 이 된다.
 * 배포 후 스모크 점검은 health 가 아니라 실기능 경로로 해야 한다(MFA·OIDC 에서 실제 발생한 사고).
 * salt 는 반드시 hex 문자열이어야 한다(`Encryptors.stronger` 가 hex 로 디코드).
 */
@Configuration
class AutomationEncryptionConfig(
    @param:Value("\${$PROPERTY_KEY:}") private val encryptionKey: String,
    @param:Value("\${$PROPERTY_SALT:}") private val encryptionSalt: String,
) {
    /**
     * Git 웹훅 secret 암호화/복호화에 사용하는 [SecretEncryptor] 빈.
     *
     * 키/salt 미설정 시에도 빈은 생성된다(부팅 안전). 이 경우 실제 암호화 호출에서
     * [IllegalStateException] 이 발생한다([SecretEncryptor] 참고).
     *
     * ## 빈 이름 명시 — 조립 충돌 차단
     * identity-access 의 `OidcEncryptionConfig`, search-export-import 의 `WebhookEncryptionConfig`,
     * slack-integration 의 `SlackEncryptionConfig` 도 같은 타입 [SecretEncryptor] 빈을 등록한다
     * (prod 전조립 시 이 빈 포함 4개). 여러 config 를 한 Spring 컨텍스트로 조립하면 기본 빈 이름(메서드명)이
     * 충돌해 부팅 실패(`NoUniqueBeanDefinitionException`) 또는 오버라이딩으로 BC 별 키 격리가 무력화된다.
     * 이를 막기 위해 빈 이름을 `automationSecretEncryptor` 로 명시하고 소비처는
     * `@Qualifier("automationSecretEncryptor")` 로 by-name 주입한다.
     *
     * @return 환경변수 주입 키/salt 로 구성된 [SecretEncryptor]. 미설정 시 사용 시점 검증 모드.
     */
    @Bean("automationSecretEncryptor")
    fun automationSecretEncryptor(): SecretEncryptor = SecretEncryptor(encryptionKey, encryptionSalt)

    companion object {
        /** `BTS_AUTOMATION_ENCRYPTION_KEY` 환경변수가 바인딩되는 프로퍼티 키. */
        const val PROPERTY_KEY = "bts.automation-encryption.key"

        /** `BTS_AUTOMATION_ENCRYPTION_SALT` 환경변수가 바인딩되는 프로퍼티 키. */
        const val PROPERTY_SALT = "bts.automation-encryption.salt"
    }
}
