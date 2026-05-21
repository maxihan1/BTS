// JwtKeyProvider @Profile 분기 @Configuration — prod → PemFileKeyProvider, !prod → DevMemoryKeyProvider

package com.atlas.bts.identity.jwt

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile

/**
 * JwtKeyProvider Bean 등록 — 환경별 @Profile 분기.
 *
 * ## 분기 규칙
 * - `@Profile("prod")` → [PemFileKeyProvider]. `bts.auth.jwt.private-key-pem-path` 미설정 시
 *   Spring @Value PlaceholderResolutionException → 애플리케이션 기동 즉시 실패 (EC-30).
 * - `@Profile("!prod")` → [DevMemoryKeyProvider]. 메모리 RSA 2048 키. dev/staging/test 전용.
 *
 * ## BLOCKER #6 해소
 * prod 환경에서 PEM 경로 env var 누락 시 DevMemoryKeyProvider 로 silent fallback 되는
 * 보안 취약점을 @Profile 강제 분리로 차단한다.
 *
 * ## 참조
 * - EC-19: PEM 경로 누락 fail-fast
 * - EC-30: prod 환경 silent dev fallback 방지
 * - FR-AU-09, FR-AU-19, FR-AU-32
 */
@Configuration
class JwtKeyProviderConfig {
    /**
     * prod 프로필 전용 PemFileKeyProvider Bean.
     *
     * `bts.auth.jwt.private-key-pem-path` 환경변수가 미설정된 경우
     * @Value placeholder 해소 실패로 Spring이 기동을 중단한다 (EC-30).
     */
    @Profile("prod")
    @Bean
    fun pemKeyProvider(
        @Value("\${bts.auth.jwt.private-key-pem-path}") pemPath: String,
    ): JwtKeyProvider =
        PemFileKeyProvider(pemPath).also { it.load() }

    /**
     * !prod 프로필 (dev/staging/test) 전용 DevMemoryKeyProvider Bean.
     *
     * prod 환경에서는 절대 등록되지 않는다.
     */
    @Profile("!prod")
    @Bean
    fun memKeyProvider(): JwtKeyProvider = DevMemoryKeyProvider()
}
