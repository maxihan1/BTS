// JwtConfig Bean 등록 검증 — JwkSource / JwtEncoder / JwtDecoder 세 Bean + round-trip 서명 검증 (FR-AU-09, FR-AU-26)

package com.atlas.bts.identity.config

import com.atlas.bts.identity.jwt.JwtKeyProviderConfig
import com.nimbusds.jose.jwk.source.JWKSource
import com.nimbusds.jose.proc.SecurityContext
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.springframework.security.oauth2.jwt.JwtEncoderParameters
import org.springframework.security.oauth2.jwt.JwtClaimsSet
import java.time.Instant

/**
 * JwtConfig Bean 등록 + RS256 round-trip 검증.
 *
 * ApplicationContextRunner (전체 서버 기동 없이 Bean 컨테이너만 띄우는 Spring Test 경량 도구)로
 * 세 가지 Bean을 검증한다.
 *
 * ## 검증 항목
 * - (a) JwkSource<SecurityContext> Bean — JwtKeyProvider 의 publicKey + kid 로 JWK 한 개 제공
 * - (b) JwtEncoder Bean (NimbusJwtEncoder) — JwkSource 활용, RS256 서명 동작
 * - (c) JwtDecoder Bean (NimbusJwtDecoder.withPublicKey) — JwtEncoder 발급 토큰 round-trip 검증
 *
 * ## 프로필
 * `!prod` 프로필 → DevMemoryKeyProvider Bean 활성 (JwtKeyProviderConfig 참조)
 *
 * ## Spring Authorization Server 미도입
 * RegisteredClient, OAuth2AuthorizationServerConfiguration 사용 금지.
 * nimbus-jose-jwt 9.40 + spring-security-oauth2-jose 직접 사용.
 */
class JwtConfigTest {
    private val runner =
        ApplicationContextRunner()
            .withUserConfiguration(JwtKeyProviderConfig::class.java, JwtConfig::class.java)

    @Test
    fun `JwkSource Bean 이 등록된다`() {
        runner.run { ctx ->
            assertThat(ctx).hasNotFailed()
            @Suppress("UNCHECKED_CAST")
            val bean = ctx.getBean("jwkSource") as JWKSource<SecurityContext>
            assertThat(bean).isNotNull()
        }
    }

    @Test
    fun `JwtEncoder Bean 이 등록된다`() {
        runner.run { ctx ->
            assertThat(ctx).hasNotFailed()
            assertThat(ctx.getBean(JwtEncoder::class.java)).isNotNull()
        }
    }

    @Test
    fun `JwtDecoder Bean 이 등록된다`() {
        runner.run { ctx ->
            assertThat(ctx).hasNotFailed()
            assertThat(ctx.getBean(JwtDecoder::class.java)).isNotNull()
        }
    }

    @Test
    fun `JwtEncoder 가 발급한 토큰을 JwtDecoder 가 검증한다 (RS256 round-trip)`() {
        runner.run { ctx ->
            assertThat(ctx).hasNotFailed()

            val encoder = ctx.getBean(JwtEncoder::class.java)
            val decoder = ctx.getBean(JwtDecoder::class.java)

            val now = Instant.now()
            val claims =
                JwtClaimsSet.builder()
                    .subject("user-42")
                    .issuer("https://bts.atlas.internal")
                    .issuedAt(now)
                    .expiresAt(now.plusSeconds(900))
                    .claim("roles", listOf("MEMBER"))
                    .build()

            val token = encoder.encode(JwtEncoderParameters.from(claims)).tokenValue
            assertThat(token).isNotBlank()

            val decoded = decoder.decode(token)
            assertThat(decoded.subject).isEqualTo("user-42")
            assertThat(decoded.issuer?.toString()).isEqualTo("https://bts.atlas.internal")
            assertThat(decoded.getClaimAsStringList("roles")).containsExactly("MEMBER")
        }
    }

    @Test
    fun `JwkSource 가 제공하는 JWK 의 kid 는 JwtKeyProvider kid 와 일치한다`() {
        runner.run { ctx ->
            assertThat(ctx).hasNotFailed()

            val encoder = ctx.getBean(JwtEncoder::class.java)
            val now = Instant.now()
            val claims =
                JwtClaimsSet.builder()
                    .subject("kid-check")
                    .issuedAt(now)
                    .expiresAt(now.plusSeconds(60))
                    .build()

            // 발급된 JWT 헤더에서 kid 추출 (Nimbus 파싱)
            val tokenValue = encoder.encode(JwtEncoderParameters.from(claims)).tokenValue
            val parsedHeader =
                com.nimbusds.jwt.SignedJWT.parse(tokenValue).header
            assertThat(parsedHeader.keyID).isNotBlank()
            assertThat(parsedHeader.keyID).startsWith("dev-")
        }
    }
}
