// prod 환경 전용 PEM 파일 기반 RSA 키 제공자 — BouncyCastle PEMParser, 경로 누락 시 fail-fast

package com.atlas.bts.identity.jwt

import org.bouncycastle.asn1.pkcs.PrivateKeyInfo
import org.bouncycastle.openssl.PEMKeyPair
import org.bouncycastle.openssl.PEMParser
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter
import org.slf4j.LoggerFactory
import java.io.FileReader
import java.nio.file.Files
import java.nio.file.Paths
import java.security.KeyFactory
import java.security.interfaces.RSAPrivateCrtKey
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.RSAPublicKeySpec

/**
 * prod 환경 전용 PEM 파일 기반 RSA 키 제공자.
 *
 * [pemPath] 경로의 RSA 개인키 PEM 파일을 BouncyCastle PEMParser 로 파싱한다.
 * 파일이 존재하지 않거나 파싱에 실패하면 즉시 [IllegalStateException]을 던진다 (EC-19).
 *
 * ## fail-fast 원칙 (EC-19, EC-30)
 * 경로 미설정 또는 파일 불존재 시 애플리케이션 기동을 막는다.
 * Silent dev fallback은 절대 허용하지 않는다.
 *
 * ## kid 형식
 * PEM 파일명 기반: `pem-{파일명 확장자 제거}`.
 * 파일명이 없으면 `pem-default` 사용.
 *
 * @param pemPath RSA 개인키 PEM 파일 절대 경로 (bts.auth.jwt.private-key-pem-path)
 */
class PemFileKeyProvider(
    private val pemPath: String,
) : JwtKeyProvider {
    private val log = LoggerFactory.getLogger(PemFileKeyProvider::class.java)

    private lateinit var _privateKey: RSAPrivateKey
    private lateinit var _publicKey: RSAPublicKey
    private lateinit var _kid: String

    override val kid: String
        get() = _kid

    override val privateKey: RSAPrivateKey
        get() = _privateKey

    override val publicKey: RSAPublicKey
        get() = _publicKey

    /**
     * PEM 파일을 읽어 RSA 키 페어를 초기화한다.
     *
     * [JwtKeyProviderConfig]에서 Bean 생성 직후 호출된다.
     * 실패 시 [IllegalStateException]을 던져 Spring context 기동을 중단시킨다.
     *
     * @throws IllegalStateException PEM 경로가 비어있거나 파일이 존재하지 않거나 파싱에 실패한 경우
     */
    fun load() {
        check(pemPath.isNotBlank()) {
            "bts.auth.jwt.private-key-pem-path 가 비어있습니다. " +
                "prod 환경에서는 RSA PEM 파일 경로를 반드시 설정해야 합니다 (EC-19)."
        }

        val path = Paths.get(pemPath)
        check(Files.exists(path)) {
            "PEM 파일을 찾을 수 없습니다: $pemPath. " +
                "bts.auth.jwt.private-key-pem-path 설정을 확인하세요 (EC-19)."
        }

        val privateKey =
            runCatching {
                PEMParser(FileReader(path.toFile())).use { parser ->
                    val obj = parser.readObject()
                    val converter = JcaPEMKeyConverter().setProvider("BC")
                    when (obj) {
                        is PEMKeyPair -> converter.getKeyPair(obj).private as RSAPrivateKey
                        is PrivateKeyInfo -> converter.getPrivateKey(obj) as RSAPrivateKey
                        else -> throw IllegalStateException(
                            "지원하지 않는 PEM 형식입니다: ${obj?.javaClass?.simpleName}. " +
                                "RSA PRIVATE KEY 또는 PRIVATE KEY 형식을 사용하세요.",
                        )
                    }
                }
            }.getOrElse { ex ->
                throw IllegalStateException(
                    "PEM 파일 파싱 실패: $pemPath. 오류: ${ex.message} (EC-19)",
                    ex,
                )
            }

        // 개인키에서 공개키 파생 — RSAPrivateCrtKey 에서 공개 지수(publicExponent)를 올바르게 읽는다.
        // RSA CRT 키는 공개 지수를 포함한다. 65537 하드코딩은 다른 exponent 값 시 오동작하므로 사용 금지.
        val crtKey =
            privateKey as? RSAPrivateCrtKey
                ?: throw IllegalStateException(
                    "PEM 파일이 RSA CRT 키 형식이 아닙니다: $pemPath. RSA CRT 형식(PKCS#1)을 사용하세요.",
                )
        val publicKeySpec = RSAPublicKeySpec(crtKey.modulus, crtKey.publicExponent)
        val publicKey = KeyFactory.getInstance("RSA").generatePublic(publicKeySpec) as RSAPublicKey

        _privateKey = privateKey
        _publicKey = publicKey
        _kid = "pem-${path.fileName.toString().substringBeforeLast('.')}"

        log.info("PemFileKeyProvider 로드 완료 — kid={}, path={}", _kid, pemPath)
    }
}
