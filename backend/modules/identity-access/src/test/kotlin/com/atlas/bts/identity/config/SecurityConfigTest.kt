// SecurityConfig 단일 필터 체인 검증 — permitAll 4경로 / 인증 가드 / sid revoke / CORS preflight (FR-09-30, FR-09-11)

package com.atlas.bts.identity.config

import com.atlas.bts.identity.jwt.SidRevokeJwtConverter
import com.atlas.bts.identity.pat.PersonalAccessTokenService
import com.atlas.bts.identity.session.Session
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
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.cors.CorsConfigurationSource
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * SecurityConfig 단일 필터 체인 통합 검증 (Task 19 / FR-09-30 / FR-09-11).
 *
 * 검증 항목.
 * - (a) permitAll 4경로 인증 없이 200 반환
 * - (b) /api/v1/protected 등 그 외 경로 → JWT 없을 때 401
 * - (c) 유효한 JWT Bearer + 활성 sid → 200
 * - (d) revoke된 sid JWT → 401
 * - (e) CORS preflight OPTIONS → 200
 *
 * 설계 노트 (BLOCKER #4/#5 해소).
 * - Spring Authorization Server 미도입. /oauth2/token 비활성.
 * - 단일 SecurityFilterChain Bean — Order 분리 없음.
 *
 * @see SecurityConfig 검증 대상 설정 클래스
 */
// controllers = [ProbeController::class] 로 한정 — 미지정 시 모든 @RestController (AuthController/ProvidersController 등) 가
// 컨텍스트에 등록되어 ProviderRegistry 등 추가 의존성을 요구하므로 SecurityFilterChain 단독 검증 의도와 어긋난다.
@WebMvcTest(
    controllers = [SecurityConfigTest.ProbeController::class],
    excludeAutoConfiguration = [OAuth2ClientAutoConfiguration::class],
)
@Import(
    SecurityConfig::class,
    SecurityConfigTest.TestSecurityBeans::class,
    SecurityConfigTest.ProbeController::class,
)
class SecurityConfigTest {

    // --- 테스트 전용 더미 컨트롤러 --------------------------------------------------

    /**
     * 필터 체인 동작 탐침용 컨트롤러.
     * 실제 비즈니스 로직 없이 HTTP 200 만 반환한다.
     */
    @RestController
    class ProbeController {
        @GetMapping("/api/v1/auth/login")
        fun login() = "ok"

        @GetMapping("/api/v1/auth/providers")
        fun providers() = "ok"

        @GetMapping("/.well-known/jwks.json")
        fun jwks() = "ok"

        @GetMapping("/actuator/health")
        fun health() = "ok"

        @GetMapping("/api/v1/protected")
        fun protected() = "ok"
    }

    // --- 테스트 전용 Bean 공급 ------------------------------------------------------

    /**
     * SecurityConfig가 요구하는 JwtDecoder, SidRevokeJwtConverter, CorsConfigurationSource Bean 공급.
     */
    @TestConfiguration
    class TestSecurityBeans {

        private val sessionService: SessionService = mockk()
        private val now: Instant = Instant.parse("2026-05-21T10:00:00Z")
        private val clock: Clock = Clock.fixed(now, ZoneOffset.UTC)

        /**
         * JwtDecoder mock — Bearer 헤더 토큰을 받으면 sid claim 이 포함된 Jwt 를 반환한다.
         * "active-token" → ACTIVE_SID / "revoked-token" → REVOKED_SID.
         * jwt() post-processor 는 JwtDecoder 를 우회하므로, sid revoke 검증은 Bearer 헤더 방식으로만 가능.
         */
        @Bean
        fun jwtDecoder(): JwtDecoder = JwtDecoder { token ->
            val sid = when (token) {
                "active-token" -> ACTIVE_SID.toString()
                "revoked-token" -> REVOKED_SID.toString()
                else -> null
            }
            Jwt.withTokenValue(token)
                .header("alg", "RS256")
                .subject("user-test")
                .issuedAt(now)
                .expiresAt(now.plusSeconds(900))
                .apply { if (sid != null) claim("sid", sid) }
                .build()
        }

        @Bean
        fun sidRevokeJwtConverter(): SidRevokeJwtConverter {
            every { sessionService.lookup(ACTIVE_SID) } returns Session(
                id = ACTIVE_SID,
                userId = UUID.randomUUID(),
                providerId = "local",
                deviceFingerprint = null,
                ipAddress = null,
                userAgent = null,
                createdAt = now,
                expiresAt = now.plus(14, ChronoUnit.DAYS),
                lastSeenAt = now,
                revokedAt = null,
                revokeReason = null,
            )
            every { sessionService.lookup(REVOKED_SID) } returns Session(
                id = REVOKED_SID,
                userId = UUID.randomUUID(),
                providerId = "local",
                deviceFingerprint = null,
                ipAddress = null,
                userAgent = null,
                createdAt = now.minusSeconds(3600),
                expiresAt = now.plus(14, ChronoUnit.DAYS),
                lastSeenAt = now,
                revokedAt = now.minusSeconds(60),
                revokeReason = "logout",
            )
            return SidRevokeJwtConverter(sessionService, clock)
        }

        @Bean
        fun corsConfigurationSource(): CorsConfigurationSource =
            CorsConfig().corsConfigurationSource(listOf("http://localhost:5173"))

        // SecurityConfig 가 PatAuthenticationFilter 생성을 위해 요구하는 Bean.
        // 본 테스트는 PAT 흐름을 검증하지 않으므로 relaxed mock 으로 충분하다.
        @Bean
        fun personalAccessTokenService(): PersonalAccessTokenService = mockk(relaxed = true)
    }

    @Autowired
    lateinit var mockMvc: MockMvc

    // --- (a) permitAll 4경로 — 인증 없이 200 ----------------------------------------

    @Test
    fun `login 경로는 인증 없이 200 을 반환한다 (FR-09-30 permitAll)`() {
        mockMvc.perform(get("/api/v1/auth/login"))
            .andExpect(status().isOk)
    }

    @Test
    fun `providers 경로는 인증 없이 200 을 반환한다 (FR-09-30 permitAll)`() {
        mockMvc.perform(get("/api/v1/auth/providers"))
            .andExpect(status().isOk)
    }

    @Test
    fun `jwks 경로는 인증 없이 200 을 반환한다 (FR-09-30 permitAll)`() {
        mockMvc.perform(get("/.well-known/jwks.json"))
            .andExpect(status().isOk)
    }

    @Test
    fun `actuator health 경로는 인증 없이 200 을 반환한다 (FR-09-30 permitAll)`() {
        mockMvc.perform(get("/actuator/health"))
            .andExpect(status().isOk)
    }

    // --- (b) 그 외 api 경로 → JWT 없을 때 401 ----------------------------------------

    @Test
    fun `보호된 api 경로에 JWT 없이 접근하면 401 을 반환한다`() {
        mockMvc.perform(get("/api/v1/protected"))
            .andExpect(status().isUnauthorized)
    }

    // --- (c) 유효한 JWT Bearer + 활성 sid → 200 --------------------------------------

    /**
     * Bearer 헤더로 실제 JwtDecoder → SidRevokeJwtConverter 흐름을 통과하여 200 검증.
     * jwt() post-processor 는 JwtDecoder/SidRevokeJwtConverter 를 우회하므로 Bearer 헤더 방식을 사용한다.
     */
    @Test
    fun `유효한 JWT Bearer 와 활성 sid 로 보호 경로에 접근하면 200 을 반환한다 (FR-09-11)`() {
        mockMvc.perform(
            get("/api/v1/protected")
                .header(HttpHeaders.AUTHORIZATION, "Bearer active-token"),
        )
            .andExpect(status().isOk)
    }

    // --- (d) revoke된 sid JWT → 401 -------------------------------------------------

    /**
     * Bearer 헤더로 revoke된 sid를 가진 JWT 를 전달 — SidRevokeJwtConverter 가 401 로 거부.
     * jwt() post-processor 는 SidRevokeJwtConverter 를 우회하므로 Bearer 헤더 방식을 사용한다.
     */
    @Test
    fun `revoke 된 세션 sid 를 가진 JWT 로 접근하면 401 을 반환한다 (FR-09-11 sid revoke)`() {
        mockMvc.perform(
            get("/api/v1/protected")
                .header(HttpHeaders.AUTHORIZATION, "Bearer revoked-token"),
        )
            .andExpect(status().isUnauthorized)
    }

    // --- (e) CORS preflight OPTIONS → 200 -------------------------------------------

    @Test
    fun `CORS preflight OPTIONS 요청에 대해 적절한 응답을 반환한다`() {
        mockMvc.perform(
            options("/api/v1/protected")
                .header(HttpHeaders.ORIGIN, "http://localhost:5173")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, HttpMethod.GET.name()),
        )
            .andExpect(status().isOk)
    }

    companion object {
        val ACTIVE_SID: UUID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
        val REVOKED_SID: UUID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb")
    }
}
