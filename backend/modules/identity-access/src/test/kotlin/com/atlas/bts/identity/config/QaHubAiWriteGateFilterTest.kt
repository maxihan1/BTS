// 「QA Hub AI」 쓰기 게이트를 실제 SecurityConfig 체인으로 본다 — 여섯 곳만 통과 · 그 밖 쓰기 403 · 읽기 · 다른 사용자 · JWT (BTS-F2-02)

package com.atlas.bts.identity.config

import com.atlas.bts.identity.jwt.SidRevokeJwtConverter
import com.atlas.bts.identity.pat.PatVerificationException
import com.atlas.bts.identity.pat.PersonalAccessToken
import com.atlas.bts.identity.pat.PersonalAccessTokenService
import com.atlas.bts.identity.session.Session
import com.atlas.bts.identity.session.SessionService
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.security.oauth2.client.servlet.OAuth2ClientAutoConfiguration
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.ResponseEntity
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.cors.CorsConfigurationSource
import java.time.Instant
import java.util.UUID

@WebMvcTest(
    controllers = [QaHubAiWriteGateFilterTest.ProbeController::class],
    excludeAutoConfiguration = [OAuth2ClientAutoConfiguration::class],
    properties = ["bts.qahub-ai.user-id=11111111-1111-4111-8111-111111111111"],
)
@Import(
    SecurityConfig::class,
    QaHubAiWriteGateFilterTest.TestSecurityBeans::class,
    QaHubAiWriteGateFilterTest.ProbeController::class,
)
class QaHubAiWriteGateFilterTest {
    /** 게이트를 지나면 어느 메서드 · 경로든 200 — 403 이면 게이트가 막은 것이다. */
    @RestController
    class ProbeController {
        @RequestMapping("/api/v1/**")
        fun any(): ResponseEntity<Void> = ResponseEntity.ok().build()
    }

    @TestConfiguration
    class TestSecurityBeans {
        private val now: Instant = Instant.now()

        @Bean
        fun personalAccessTokenService(): PersonalAccessTokenService =
            mockk {
                every { verify(any()) } answers {
                    val owner = PAT_OWNERS[firstArg<String>()]
                    if (owner == null) {
                        Result.failure(PatVerificationException("PAT not found"))
                    } else {
                        Result.success(
                            PersonalAccessToken(UUID.randomUUID(), owner, "t", "h", listOf("*"), null, null, null, now),
                        )
                    }
                }
            }

        /** "qahub-jwt" 는 QA Hub AI 계정의 브라우저 로그인 JWT 다. */
        @Bean
        fun jwtDecoder(): JwtDecoder =
            JwtDecoder { token ->
                Jwt.withTokenValue(token)
                    .header("alg", "RS256")
                    .subject(QAHUB_AI.toString())
                    .claim("sid", SID.toString())
                    .issuedAt(now)
                    .expiresAt(now.plusSeconds(900))
                    .build()
            }

        @Bean
        fun sidRevokeJwtConverter(): SidRevokeJwtConverter {
            val sessions: SessionService = mockk()
            every { sessions.lookup(SID) } returns
                Session(SID, QAHUB_AI, "oidc", null, null, null, now, now.plusSeconds(3600), now, null, null)
            return SidRevokeJwtConverter(sessions)
        }

        @Bean
        fun corsConfigurationSource(): CorsConfigurationSource = CorsConfig().corsConfigurationSource(listOf(ORIGIN))
    }

    @Autowired
    lateinit var mockMvc: MockMvc

    @ParameterizedTest
    @CsvSource(
        "POST, /api/v1/issues",
        "POST, /api/v1/issues/DM-12/comments",
        "POST, /api/v1/issues/DM-12/transition",
        "PATCH, /api/v1/issues/DM-12/assignee",
        "POST, /api/v1/issues/DM-12/attachments",
        "POST, /api/v1/search/aql",
        "GET, /api/v1/issues/DM-12",
        "GET, /api/v1/users/me/whoami",
    )
    fun `QA Hub AI 열쇠는 QA Hub 가 부르는 쓰기 · 읽기가 통과한다`(
        method: String,
        path: String,
    ) {
        call(method, path, QAHUB_PAT).andExpect(status().isOk)
    }

    @ParameterizedTest
    @CsvSource(
        "PATCH, /api/v1/issues/DM-12",
        "DELETE, /api/v1/issues/DM-12/comments/c1",
        "PUT, /api/v1/issues/DM-12/assignee",
        "POST, /api/v1/issues/DM-12/links",
        "POST, /api/v1/issues/DM-12/comments/c1",
        "PATCH, /api/v1/users/me/preferences",
    )
    fun `QA Hub AI 열쇠의 그 밖 쓰기는 403 이다`(
        method: String,
        path: String,
    ) {
        call(method, path, QAHUB_PAT)
            .andExpect(status().isForbidden)
            .andExpect(content().json("""{"error":"forbidden"}"""))
    }

    @Test
    fun `다른 사용자의 열쇠는 막지 않는다`() {
        call("PATCH", "/api/v1/issues/DM-12", OTHER_PAT).andExpect(status().isOk)
    }

    @Test
    fun `같은 계정의 브라우저 로그인 JWT 도 막는다`() {
        call("PATCH", "/api/v1/issues/DM-12", "qahub-jwt").andExpect(status().isForbidden)
    }

    private fun call(
        method: String,
        path: String,
        token: String,
    ) = mockMvc.perform(request(HttpMethod.valueOf(method), path).header(HttpHeaders.AUTHORIZATION, "Bearer $token"))

    private companion object {
        val QAHUB_AI: UUID = UUID.fromString("11111111-1111-4111-8111-111111111111")
        val SID: UUID = UUID.fromString("22222222-2222-4222-8222-222222222222")
        const val ORIGIN = "http://localhost:5173"
        const val QAHUB_PAT = "pat_qahub"
        const val OTHER_PAT = "pat_other"
        val OTHER_USER: UUID = UUID.fromString("33333333-3333-4333-8333-333333333333")
        val PAT_OWNERS = mapOf(QAHUB_PAT to QAHUB_AI, OTHER_PAT to OTHER_USER)
    }
}
