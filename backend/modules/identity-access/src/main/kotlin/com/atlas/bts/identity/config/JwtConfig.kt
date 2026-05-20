// JWT Bean 설정 — JwkSource / NimbusJwtEncoder / NimbusJwtDecoder (FR-AU-09, FR-AU-26, BLOCKER #4 해소)

package com.atlas.bts.identity.config

import com.atlas.bts.identity.jwt.JwtKeyProvider
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.source.ImmutableJWKSet
import com.nimbusds.jose.jwk.source.JWKSource
import com.nimbusds.jose.proc.SecurityContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder

/**
 * JWT 인프라 Bean — JwkSource, JwtEncoder, JwtDecoder.
 *
 * ## BLOCKER #4 해소 — Spring Authorization Server 미도입 결정 (2026-05-21)
 * Spring Authorization Server(RegisteredClient, OAuth2AuthorizationServerConfiguration 등)는
 * BTS 자체 JWT 발급 범위에 비해 과잉 의존성이다. 3rd-party OAuth2 클라이언트 통합이 필요한
 * 후속 PR까지 도입을 보류한다.
 *
 * **결정 근거.**
 * - BTS는 자체 발급 JWT만 검증하면 된다 — Keycloak 위임 없이 nimbus-jose-jwt 직접 사용으로 충분.
 * - Spring Authorization Server는 OAuth2 Authorization Code Flow, PKCE, Token Introspection 등을
 *   전제한다. BTS 현재 범위(세션 기반 + PAT)에는 불필요하다.
 * - 후속 PR에서 3rd-party OAuth2 클라이언트 통합 시 재검토한다.
 *
 * **금지 항목 (회귀 방지).** `RegisteredClient`, `OAuth2AuthorizationServerConfiguration`,
 * `@EnableAuthorizationServer` import 절대 금지.
 *
 * ## Bean 구성
 * - [jwkSource] — [JwtKeyProvider] 의 RSA 공개키 + kid 로 단일 JWK Set 구성.
 *   SecurityFilterChain 의 `oauth2ResourceServer` 가 JWK 공개키를 여기서 조회한다.
 * - [jwtEncoder] — [NimbusJwtEncoder] + [jwkSource]. [com.atlas.bts.identity.jwt.JwtIssuer] 가 사용.
 * - [jwtDecoder] — [NimbusJwtDecoder.withPublicKey]. RS256 서명 검증.
 *   SecurityFilterChain 의 `.oauth2ResourceServer { it.jwt { jwt -> jwt.decoder(jwtDecoder()) } }` 가 사용.
 *
 * ## FR 참조
 * - **FR-09-2 / FR-AU-09** — JWT 자체 발급 결정 (nimbus-jose-jwt, Spring Auth Server 미도입)
 * - **FR-09-18 / FR-AU-18** — 토큰 클레임 구조 (sub/iss/aud/exp/iat/jti + BTS 확장 클레임)
 * - **FR-09-20 / FR-AU-20** — kid 헤더 — 다중 키 공존 지원 (키 교체 시 이전 토큰 호환성 보장)
 * - **FR-09-26 / FR-AU-26** — 토큰 검증 (RS256 공개키 검증, JwtDecoder 책임)
 */
@Configuration
class JwtConfig {
    /**
     * RS256 JWK Set — [JwtKeyProvider] 의 공개키 한 개를 ImmutableJWKSet 으로 래핑한다.
     *
     * kid 는 [JwtKeyProvider.kid] 를 그대로 사용해 JWS 헤더와 JWK Set 간 매핑을 보장한다.
     * private key 도 포함해야 NimbusJwtEncoder 가 서명에 사용할 수 있다.
     */
    @Bean
    fun jwkSource(provider: JwtKeyProvider): JWKSource<SecurityContext> {
        val rsaKey =
            RSAKey.Builder(provider.publicKey)
                .privateKey(provider.privateKey)
                .keyID(provider.kid)
                .build()
        return ImmutableJWKSet(JWKSet(rsaKey))
    }

    /**
     * NimbusJwtEncoder — [jwkSource] 를 주입받아 RS256 JWT 서명 발급에 사용한다.
     * [com.atlas.bts.identity.jwt.JwtIssuer] 가 의존한다.
     */
    @Bean
    fun jwtEncoder(jwkSource: JWKSource<SecurityContext>): JwtEncoder = NimbusJwtEncoder(jwkSource)

    /**
     * NimbusJwtDecoder — RSA 공개키 직접 주입 방식.
     *
     * Spring Authorization Server 미도입으로 JWK Set URI 방식 대신 공개키를 직접 주입한다.
     * SecurityFilterChain 의 `.oauth2ResourceServer { it.jwt {} }` 가 이 Bean 을 자동 감지한다.
     */
    @Bean
    fun jwtDecoder(provider: JwtKeyProvider): JwtDecoder =
        NimbusJwtDecoder
            .withPublicKey(provider.publicKey)
            .signatureAlgorithm(SignatureAlgorithm.RS256)
            .build()
}
