// dev/staging 환경 전용 메모리 RSA 2048 키 제공자 — 재시작 시 키 갱신, prod 절대 사용 금지

package com.atlas.bts.identity.jwt

import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.util.UUID

/**
 * dev/staging 환경 전용 메모리 RSA 2048 키 제공자.
 *
 * 애플리케이션 시작 시 RSA 2048 키 페어를 한 번 생성하여 메모리에 보관한다.
 * 재시작 시 새 키가 생성되므로 이전에 발급된 JWT는 무효가 된다.
 *
 * **prod 환경에서 절대 사용 금지.** prod는 [PemFileKeyProvider]를 사용한다.
 * [JwtKeyProviderConfig]의 `@Profile("!prod")` 분기에 의해 강제된다.
 *
 * ## kid 형식
 * `dev-{uuid}` — 인스턴스마다 고유. 다중 인스턴스 배포 시 kid 충돌 방지.
 */
class DevMemoryKeyProvider : JwtKeyProvider {
    private val log = LoggerFactory.getLogger(DevMemoryKeyProvider::class.java)

    private val keyPair =
        KeyPairGenerator.getInstance("RSA").apply {
            initialize(2048)
        }.generateKeyPair()

    override val kid: String = "dev-${UUID.randomUUID()}"

    override val privateKey: RSAPrivateKey
        get() = keyPair.private as RSAPrivateKey

    override val publicKey: RSAPublicKey
        get() = keyPair.public as RSAPublicKey

    /**
     * 애플리케이션 시작 시 dev 키 사용 중임을 경고 로그로 알린다.
     * prod 환경에서 이 클래스가 실수로 활성화되는 경우 감지를 돕기 위함이다.
     */
    @PostConstruct
    fun warnDevMode() {
        log.warn(
            "[DEV] DevMemoryKeyProvider 활성 — 메모리 RSA 2048 키 사용 중 (kid={}). " +
                "prod 환경에서는 PemFileKeyProvider 를 사용해야 합니다.",
            kid,
        )
    }
}
