// OIDC client_secret 대칭 암호화 유틸 (AES-256-GCM, random IV)

package com.atlas.bts.identity.config

import org.springframework.security.crypto.codec.Hex
import org.springframework.security.crypto.encrypt.BytesEncryptor
import org.springframework.security.crypto.encrypt.Encryptors

/**
 * OIDC client_secret 등 외부 비밀값을 DB 에 평문 저장하지 않기 위한 대칭 암호화 유틸
 * (DEVELOPMENT.md §1.1.1 / DATA.md §8 — 외부 OAuth client secret 은 암호화 후 저장).
 *
 * BTS 는 단일 호스트라 KMS 대신 app key 대칭 암호화로 KMS 정신(키 분리)을 구현한다.
 * app key/salt 는 [OidcEncryptionConfig] 가 `BTS_OIDC_ENCRYPTION_*` 환경변수에서 주입한다.
 *
 * ## 부팅 안전성 — 항상 등록 + 사용 시점 검증
 * 본 빈은 키 부재 여부와 무관하게 **항상** 등록된다([OidcEncryptionConfig] 에서 `@ConditionalOnProperty`
 * 제거). 항상 스캔되는 [com.atlas.bts.identity.provider.oidc.DbClientRegistrationRepository] 가 이 빈을
 * 하드 의존하므로, 조건부 등록 시 키 미설정 환경(슬라이스/통합 테스트)에서 `NoSuchBeanDefinitionException`
 * 으로 풀 컨텍스트 부팅이 깨진다. 따라서 생성자는 키 부재여도 예외 없이 성공하고,
 * 실제 암호화 키가 필요한 [encrypt]/[decrypt] **호출 시점**에 키 미설정을 검증해 명확한 예외를 던진다.
 *
 * ## 암호화 알고리즘
 * [Encryptors.stronger] 는 AES-256-GCM + 매 호출 random IV 를 사용한다. 따라서
 * - round-trip 복호화가 항상 원래 평문을 복원하고,
 * - **같은 평문이라도 암호화할 때마다 ciphertext 가 달라진다** (IV 노출 패턴 방어).
 *
 * AES-CBC 고정 IV 기반 `AesBytesEncryptor` 는 같은 평문이 같은 ciphertext 가 되어 사용하지 않는다.
 *
 * ## 보안 주의 (DEVELOPMENT.md §1.1.2)
 * [decrypt] 결과(평문 secret)와 [encrypt] 입력은 **절대 로깅하지 않는다**.
 * 복호화 실패 예외 메시지에도 비밀값이 포함되지 않도록 한다.
 *
 * @param password app encryption key (환경변수 `BTS_OIDC_ENCRYPTION_KEY`). 비어 있으면 미설정 상태.
 * @param hexSalt hex 문자열 salt (환경변수 `BTS_OIDC_ENCRYPTION_SALT`). [Encryptors.stronger] 가 hex 검증한다.
 */
class SecretEncryptor(
    private val password: String,
    private val hexSalt: String,
) {
    /** 키/salt 가 모두 설정됐는지 여부. 둘 중 하나라도 비면 미설정으로 본다. */
    private val configured: Boolean = password.isNotEmpty() && hexSalt.isNotEmpty()

    /**
     * AES-256-GCM(random IV) delegate. 키 설정 시에만 lazy 초기화한다.
     * hex salt 등 [Encryptors.stronger] 의 런타임 검증도 이 시점(첫 사용)에 수행된다.
     */
    private val delegate: BytesEncryptor by lazy { Encryptors.stronger(password, hexSalt) }

    /**
     * 평문 비밀값을 AES-256-GCM(random IV)으로 암호화하여 hex 문자열로 반환한다.
     *
     * 같은 평문을 반복 호출해도 매번 다른 ciphertext 가 나온다(random IV). 평문은 로깅하지 않는다.
     *
     * @param plaintext 암호화할 비밀값(예: OIDC client_secret).
     * @return hex 인코딩된 ciphertext (IV 포함).
     * @throws IllegalStateException 암호화 키가 설정되지 않은 경우.
     */
    fun encrypt(plaintext: String): String {
        requireConfigured()
        return String(Hex.encode(delegate.encrypt(plaintext.toByteArray(Charsets.UTF_8))))
    }

    /**
     * [encrypt] 로 생성한 hex ciphertext 를 복호화하여 원래 평문을 반환한다.
     *
     * 다른 키/salt 로 생성한 [SecretEncryptor] 로 복호화하거나 ciphertext 가 변조되면
     * GCM 인증 태그 검증 실패로 [IllegalStateException] 이 발생한다. 평문은 로깅하지 않는다.
     *
     * @param ciphertext [encrypt] 가 반환한 hex 문자열.
     * @return 복호화된 평문 비밀값.
     * @throws IllegalStateException 암호화 키 미설정 / 키 불일치 / 변조로 복호화에 실패한 경우.
     */
    fun decrypt(ciphertext: String): String {
        requireConfigured()
        return String(delegate.decrypt(Hex.decode(ciphertext)), Charsets.UTF_8)
    }

    /**
     * 암호화 키 미설정 시 명확한 [IllegalStateException] 을 던진다.
     * 예외 메시지에는 비밀값/키를 포함하지 않는다(§1.1.2).
     */
    private fun requireConfigured() {
        check(configured) {
            "OIDC encryption key not configured. Set BTS_OIDC_ENCRYPTION_KEY/SALT"
        }
    }
}
