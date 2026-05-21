// JWT 서명/검증 키를 제공하는 SPI 인터페이스 — nimbus-jose-jwt 기반 자체 발급 (FR-AU-09)

package com.atlas.bts.identity.jwt

import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey

/**
 * JWT 서명/검증 키 제공자 인터페이스.
 *
 * BTS는 Spring Authorization Server 미도입 결정에 따라 nimbus-jose-jwt 9.x 기반으로
 * JWT를 자체 발급한다. 이 인터페이스는 환경별 키 소스를 추상화한다.
 *
 * - dev/staging: [DevMemoryKeyProvider] — 메모리 내 RSA 2048 키 (재시작 시 갱신)
 * - prod: [PemFileKeyProvider] — 파일시스템 PEM 파일 (BouncyCastle PEMParser)
 *
 * ## 키 교체 (Key Rotation)
 * [kid]를 JWS 헤더에 포함하여 다중 키 공존을 지원한다.
 * 키 교체 시 새 [JwtKeyProvider] 인스턴스를 등록하고 기존 kid 로 발급된 토큰은
 * 만료될 때까지 이전 인스턴스로 검증한다.
 *
 * ## 참조
 * - FR-AU-09, FR-AU-19 (JWT 발급), FR-AU-32 (토큰 검증)
 * - EC-19 (PEM 경로 누락 fail-fast), EC-30 (prod silent dev fallback 방지)
 */
interface JwtKeyProvider {
    /** JWS 헤더 kid 값 — 키 교체 시 토큰-키 매핑에 사용 */
    val kid: String

    /** JWT 서명에 사용할 RSA 개인키 */
    val privateKey: RSAPrivateKey

    /** JWT 검증에 사용할 RSA 공개키 */
    val publicKey: RSAPublicKey
}
