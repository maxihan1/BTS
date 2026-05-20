// test-integration 프로필 전용 JwtDecoder 가짜 Bean — SecurityFilterChain 부팅 허용용

package com.atlas.bts.identity.config

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Profile
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtDecoder

/**
 * test-integration 프로필 전용 가짜 JwtDecoder.
 * SecurityConfig 의 .oauth2ResourceServer().jwt() 가 JwtDecoder Bean 을 요구하므로,
 * LDAP 통합 테스트 컨텍스트에서 가짜 Bean 으로 부팅을 허용한다.
 *
 * 실제 JWT 검증은 이 테스트의 관심사가 아님 — LdapProvider SPI 단독 동작 검증 목적.
 */
@TestConfiguration
@Profile("test-integration")
class TestIntegrationSecurityConfig {
    @Bean
    fun jwtDecoder(): JwtDecoder =
        JwtDecoder { token ->
            // 테스트 전용 가짜 디코더 — 모든 토큰을 빈 Jwt 로 반환
            Jwt.withTokenValue(token)
                .header("alg", "none")
                .claim("sub", "test-user")
                .build()
        }
}
