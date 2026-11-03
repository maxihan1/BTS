// back-channel logout 수신 슬라이스 테스트 — 익명 도달(permitAll · CSRF 예외) · logout_token 서명 · claim 규칙 200/400 (S24)

package com.atlas.bts.identity.web

import com.atlas.bts.identity.config.CorsConfig
import com.atlas.bts.identity.config.SecurityConfig
import com.atlas.bts.identity.jwt.SidRevokeJwtConverter
import com.atlas.bts.identity.pat.PersonalAccessTokenService
import com.atlas.bts.identity.provider.oidc.BackChannelLogoutService
import com.atlas.bts.identity.provider.oidc.LogoutClaims
import com.atlas.bts.identity.provider.oidc.LogoutTokenVerifier
import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import com.sun.net.httpserver.HttpServer
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.security.oauth2.client.servlet.OAuth2ClientAutoConfiguration
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.web.cors.CorsConfigurationSource
import java.net.InetSocketAddress
import java.time.Instant
import java.util.Date
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

@WebMvcTest(
    controllers = [BackChannelLogoutController::class],
    excludeAutoConfiguration = [OAuth2ClientAutoConfiguration::class],
)
@Import(SecurityConfig::class, BackChannelLogoutControllerTest.Beans::class)
class BackChannelLogoutControllerTest {
    companion object {
        private const val CLIENT_ID = "bts"
        private val authKey: RSAKey = RSAKeyGenerator(2048).keyID("auth-k1").generate()

        /** JWKS 를 받은 횟수 — 모르는 kid 가 Auth 로 가는 요청을 늘리지 못하는지 본다 */
        private val jwksFetches = AtomicInteger()

        /** 가짜 Auth JWKS — 새 의존성 없이 JDK HttpServer (계약 §3 Bunker test) */
        private val jwks: HttpServer =
            HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                createContext("/oauth2/jwks") { ex ->
                    jwksFetches.incrementAndGet()
                    val body = JWKSet(authKey.toPublicJWK()).toString().toByteArray()
                    ex.responseHeaders.add("Content-Type", "application/json")
                    ex.sendResponseHeaders(200, body.size.toLong())
                    ex.responseBody.use { it.write(body) }
                }
                start()
            }
        private val issuer = "http://127.0.0.1:${jwks.address.port}"

        @JvmStatic
        @AfterAll
        fun stopJwks() = jwks.stop(0)
    }

    @TestConfiguration
    class Beans {
        @Bean
        fun jwtDecoder(): JwtDecoder = mockk(relaxed = true)

        @Bean
        fun sidRevokeJwtConverter(): SidRevokeJwtConverter = SidRevokeJwtConverter(mockk(relaxed = true))

        @Bean
        fun corsConfigurationSource(): CorsConfigurationSource {
            return CorsConfig().corsConfigurationSource(listOf("http://localhost:5173"))
        }

        @Bean
        fun personalAccessTokenService(): PersonalAccessTokenService = mockk(relaxed = true)

        @Bean
        fun logoutTokenVerifier(): LogoutTokenVerifier = LogoutTokenVerifier(issuer, CLIENT_ID)

        @Bean
        fun backChannelLogoutService(): BackChannelLogoutService = mockk()
    }

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var service: BackChannelLogoutService

    @BeforeEach
    fun resetService() {
        clearMocks(service)
        every { service.logout(any()) } returns 1
    }

    private val authUserId = UUID.randomUUID().toString()

    private fun claims(now: Instant = Instant.now()): JWTClaimsSet.Builder =
        JWTClaimsSet.Builder()
            .issuer(issuer)
            .audience(CLIENT_ID)
            .issueTime(Date.from(now))
            .expirationTime(Date.from(now.plusSeconds(120)))
            .jwtID(UUID.randomUUID().toString())
            .claim("events", mapOf("http://schemas.openid.net/event/backchannel-logout" to emptyMap<String, Any>()))
            .subject(authUserId)
            .claim("sid", "auth-sid-hash")

    private fun sign(
        claims: JWTClaimsSet,
        key: RSAKey = authKey,
        typ: String = "logout+jwt",
    ): String =
        SignedJWT(JWSHeader.Builder(JWSAlgorithm.RS256).type(JOSEObjectType(typ)).keyID(key.keyID).build(), claims)
            .apply { sign(RSASSASigner(key)) }
            .serialize()

    /** Auth 서버처럼 — 인증 헤더 · CSRF 토큰 · 쿠키 없이 form 본문만 */
    private fun send(token: String?): ResultActions =
        mockMvc.perform(
            post("/api/v1/auth/backchannel-logout")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .apply { token?.let { param("logout_token", it) } },
        )

    private fun expectRejected(token: String?) {
        send(token)
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value("invalid_request"))
            .andExpect(header().string("Cache-Control", "no-store"))
        verify(exactly = 0) { service.logout(any()) }
    }

    @Test
    fun `Auth 키로 서명한 sid token 은 로그인 없이 도달해 200 이고 그 sid 를 끊는다 (permitAll · CSRF 예외)`() {
        send(sign(claims().build()))
            .andExpect(status().isOk)
            .andExpect(header().string("Cache-Control", "no-store"))

        val got = slot<LogoutClaims>()
        verify(exactly = 1) { service.logout(capture(got)) }
        assertThat(got.captured.sid).isEqualTo("auth-sid-hash")
        assertThat(got.captured.sub).isEqualTo(authUserId)
    }

    @Test
    fun `sid 없는 token 은 sub 와 iat 로 넘긴다 — 계정 끄기 · 초기화`() {
        val iat = Instant.now().minusSeconds(30).truncatedTo(java.time.temporal.ChronoUnit.SECONDS)

        send(sign(claims(iat).claim("sid", null).build())).andExpect(status().isOk)

        val got = slot<LogoutClaims>()
        verify(exactly = 1) { service.logout(capture(got)) }
        assertThat(got.captured.sid).isNull()
        assertThat(got.captured.sub).isEqualTo(authUserId)
        assertThat(got.captured.issuedAt).isEqualTo(iat)
    }

    @Test
    fun `다른 키로 서명한 token 은 400`() {
        expectRejected(sign(claims().build(), key = RSAKeyGenerator(2048).keyID("auth-k1").generate()))
    }

    @Test
    fun `typ 이 logout+jwt 가 아니면 400 — access token 을 logout token 으로 쓰지 못한다`() {
        expectRejected(sign(claims().build(), typ = "JWT"))
    }

    @Test
    fun `다른 앱 aud 는 400`() {
        expectRejected(sign(claims().audience("test-platform").build()))
    }

    @Test
    fun `다른 issuer 는 400`() {
        expectRejected(sign(claims().issuer("https://evil.example.com").build()))
    }

    @Test
    fun `events 가 없으면 400`() {
        expectRejected(sign(claims().claim("events", null).build()))
    }

    @Test
    fun `nonce 가 있으면 400 — id token 을 logout token 으로 쓰지 못한다`() {
        expectRejected(sign(claims().claim("nonce", "n").build()))
    }

    @Test
    fun `sid 도 sub 도 없으면 400`() {
        expectRejected(sign(claims().claim("sid", null).subject(null).build()))
    }

    @Test
    fun `iat 가 5분 넘게 지났으면 exp 가 남아도 400`() {
        val old = Instant.now().minusSeconds(600)
        expectRejected(sign(claims(old).expirationTime(Date.from(Instant.now().plusSeconds(60))).build()))
    }

    @Test
    fun `exp 가 지났거나 없으면 iat 가 5분 안이어도 400`() {
        val now = Instant.now()
        expectRejected(sign(claims(now.minusSeconds(240)).expirationTime(Date.from(now.minusSeconds(120))).build()))
        expectRejected(sign(claims().expirationTime(null).build()))
    }

    @Test
    fun `모르는 kid 를 연달아 보내도 Auth JWKS 는 많아야 한 번 다시 받는다 (로그인 없는 경로의 증폭 차단)`() {
        send(sign(claims().build())).andExpect(status().isOk) // 캐시를 채운다
        resetService()
        val before = jwksFetches.get()

        repeat(3) { i -> expectRejected(sign(claims().build(), key = RSAKeyGenerator(2048).keyID("x-$i").generate())) }

        assertThat(jwksFetches.get() - before).isLessThanOrEqualTo(1)
    }

    @Test
    fun `GET 은 열지 않는다 — 메서드 고정이라 익명이면 401`() {
        mockMvc.perform(get("/api/v1/auth/backchannel-logout")).andExpect(status().isUnauthorized)
    }

    @Test
    fun `Auth issuer 가 설정되지 않은 설치는 모든 token 을 거절한다`() {
        assertThatThrownBy { LogoutTokenVerifier("", CLIENT_ID).verify(sign(claims().build())) }
            .isInstanceOf(org.springframework.security.oauth2.jwt.JwtException::class.java)
    }

    @Test
    fun `logout_token 이 없거나 JWT 가 아니면 400`() {
        expectRejected(null)
        expectRejected("not-a-jwt")
    }
}
