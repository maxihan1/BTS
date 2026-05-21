// /.well-known/jwks.json 엔드포인트 슬라이스 테스트 — permitAll + RSA 공개키 JWK Set 응답 검증

package com.atlas.bts.identity.web

import com.atlas.bts.identity.config.CorsConfig
import com.atlas.bts.identity.config.SecurityConfig
import com.atlas.bts.identity.jwt.JwtKeyProvider
import com.atlas.bts.identity.jwt.SidRevokeJwtConverter
import com.atlas.bts.identity.pat.PersonalAccessTokenService
import com.atlas.bts.identity.session.SessionService
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.security.oauth2.client.servlet.OAuth2ClientAutoConfiguration
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.web.cors.CorsConfigurationSource
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

// OAuth2ClientAutoConfiguration 제외 — @WebMvcTest 환경에서 Keycloak issuer-uri 네트워크 접속 차단
@WebMvcTest(
    controllers = [JwksController::class],
    excludeAutoConfiguration = [OAuth2ClientAutoConfiguration::class],
)
@Import(SecurityConfig::class, JwksControllerTest.MockBeans::class)
class JwksControllerTest {

    @TestConfiguration
    class MockBeans {
        @Bean
        fun jwtDecoder(): JwtDecoder = mockk(relaxed = true)

        @Bean
        fun sidRevokeJwtConverter(): SidRevokeJwtConverter {
            val sessionService: SessionService = mockk(relaxed = true)
            val clock = Clock.fixed(Instant.parse("2026-05-21T10:00:00Z"), ZoneOffset.UTC)
            return SidRevokeJwtConverter(sessionService, clock)
        }

        @Bean
        fun corsConfigurationSource(): CorsConfigurationSource =
            CorsConfig().corsConfigurationSource(listOf("http://localhost:5173"))

        @Bean
        fun personalAccessTokenService(): PersonalAccessTokenService = mockk(relaxed = true)

        @Bean
        fun jwtKeyProvider(): JwtKeyProvider {
            val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            return mockk<JwtKeyProvider>().also { provider ->
                every { provider.kid } returns "k-01"
                every { provider.publicKey } returns keyPair.public as RSAPublicKey
                every { provider.privateKey } returns keyPair.private as RSAPrivateKey
            }
        }
    }

    @Autowired
    lateinit var mockMvc: MockMvc

    @Test
    fun `jwks endpoint returns 200 without authentication`() {
        // permitAll 검증 — 인증 없이도 200 응답해야 한다
        mockMvc.perform(get("/.well-known/jwks.json"))
            .andExpect(status().isOk)
    }

    @Test
    fun `jwks endpoint returns RSA public key in JWK Set format`() {
        mockMvc.perform(get("/.well-known/jwks.json"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.keys").isArray)
            .andExpect(jsonPath("$.keys[0].kty").value("RSA"))
            .andExpect(jsonPath("$.keys[0].use").value("sig"))
            .andExpect(jsonPath("$.keys[0].kid").value("k-01"))
            .andExpect(jsonPath("$.keys[0].alg").value("RS256"))
            .andExpect(jsonPath("$.keys[0].n").isString)
            .andExpect(jsonPath("$.keys[0].e").value("AQAB"))
    }

    @Test
    fun `jwks endpoint returns Cache-Control header`() {
        // key rotation 후 클라이언트 캐시 — public, max-age=86400 (24h)
        mockMvc.perform(get("/.well-known/jwks.json"))
            .andExpect(status().isOk)
            .andExpect(header().string("Cache-Control", "max-age=86400, public"))
    }

    @Test
    fun `jwks endpoint does not expose private key`() {
        // 회귀 가드 — d (private exponent) 필드가 응답에 포함되면 안 된다
        mockMvc.perform(get("/.well-known/jwks.json"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.keys[0].d").doesNotExist())
    }
}
