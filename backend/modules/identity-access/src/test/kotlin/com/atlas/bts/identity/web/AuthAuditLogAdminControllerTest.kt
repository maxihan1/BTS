// AuthAuditLogAdminController 슬라이스 테스트 — 관리자 전역 감사 로그 조회 권한/검증/응답형태 (FR-AU-10 D6/D7)

package com.atlas.bts.identity.web

import com.atlas.bts.identity.audit.AuthAuditLogAdminEntry
import com.atlas.bts.identity.audit.AuthAuditLogAdminPage
import com.atlas.bts.identity.audit.AuthAuditLogAdminQueryRepository
import com.atlas.bts.identity.audit.AuthAuditLogSearchCriteria
import com.atlas.bts.identity.audit.AuthEventType
import com.atlas.bts.identity.config.CorsConfig
import com.atlas.bts.identity.config.SecurityConfig
import com.atlas.bts.identity.jwt.SidRevokeJwtConverter
import com.atlas.bts.identity.pat.PersonalAccessTokenService
import com.atlas.bts.identity.session.SessionService
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.security.oauth2.client.servlet.OAuth2ClientAutoConfiguration
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.web.cors.CorsConfigurationSource
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * AuthAuditLogAdminController GET /api/v1/admin/auth-audit-logs 슬라이스 테스트 (FR-AU-10 D6/D7).
 *
 * 검증 범위.
 * - SYSTEM_ADMIN(ROLE_SYSTEM_ADMIN): 200 + 응답형태(username/displayName null 포함, metadata 직렬화,
 *   page/size/totalElements/totalPages).
 * - 비 admin JWT: 403 + 권한 상세 미노출.
 * - PAT(ROLE_PAT): 403.
 * - 미인증: 401(필터 체인).
 * - eventType 미정의 / userId 잘못된 UUID / from·to 파싱 실패 / page 음수 / size 0 / size 101: 400.
 * - 기본값(page0, size50) + criteria 매핑 정확(slot capture).
 *
 * OAuth2ClientAutoConfiguration 제외 — @WebMvcTest 환경에서 Keycloak issuer-uri 네트워크 접속 차단.
 * SecurityConfig 가 SidRevokeJwtConverter + CorsConfigurationSource + PersonalAccessTokenService Bean 을
 * 요구하므로 MockBeans 로 공급.
 */
@WebMvcTest(
    controllers = [AuthAuditLogAdminController::class],
    excludeAutoConfiguration = [OAuth2ClientAutoConfiguration::class],
)
@Import(SecurityConfig::class, AuthAuditLogAdminControllerTest.MockBeans::class)
class AuthAuditLogAdminControllerTest {
    companion object {
        private val ADMIN_ID: UUID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
        private val ALICE_ID: UUID = UUID.fromString("a1111111-1111-1111-1111-111111111111")
        private val NOW: Instant = Instant.parse("2026-06-01T10:00:00Z")
        private const val ALLOWED_ORIGIN = "http://localhost:5173"
        private const val PATH = "/api/v1/admin/auth-audit-logs"
    }

    @TestConfiguration
    class MockBeans {
        @Bean
        fun jwtDecoder(): JwtDecoder = mockk(relaxed = true)

        @Bean
        fun sidRevokeJwtConverter(): SidRevokeJwtConverter {
            val sessionService: SessionService = mockk(relaxed = true)
            val clock = Clock.fixed(Instant.parse("2026-06-01T10:00:00Z"), ZoneOffset.UTC)
            return SidRevokeJwtConverter(sessionService, clock)
        }

        @Bean
        fun corsConfigurationSource(): CorsConfigurationSource {
            return CorsConfig().corsConfigurationSource(listOf(ALLOWED_ORIGIN))
        }

        @Bean
        fun personalAccessTokenService(): PersonalAccessTokenService = mockk(relaxed = true)

        @Bean
        fun authAuditLogAdminQueryRepository(): AuthAuditLogAdminQueryRepository = mockk()
    }

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var repository: AuthAuditLogAdminQueryRepository

    // ── 인증/권한 헬퍼 (UsersControllerTest 패턴) ──────────────────────────────────

    private fun adminJwt() =
        jwt()
            .jwt { it.subject(ADMIN_ID.toString()).claim("roles", listOf("SYSTEM_ADMIN")) }
            .authorities(SimpleGrantedAuthority("ROLE_SYSTEM_ADMIN"))

    private fun userJwt() = jwt().jwt { it.subject(ADMIN_ID.toString()) }

    private fun patAuth() =
        jwt()
            .jwt { it.subject(ADMIN_ID.toString()) }
            .authorities(SimpleGrantedAuthority("ROLE_PAT"))

    private fun samplePage() =
        AuthAuditLogAdminPage(
            items =
                listOf(
                    AuthAuditLogAdminEntry(
                        id = 42L,
                        userId = ALICE_ID,
                        username = "alice",
                        displayName = "Alice Anderson",
                        eventType = AuthEventType.LOGIN_SUCCESS,
                        providerId = "local",
                        ipAddress = "203.0.113.1",
                        userAgent = "agent-a",
                        metadata = mapOf("sid" to "s1"),
                        createdAt = NOW,
                    ),
                    AuthAuditLogAdminEntry(
                        id = 7L,
                        userId = null,
                        username = null,
                        displayName = null,
                        eventType = AuthEventType.LOGIN_FAILURE,
                        providerId = "local",
                        ipAddress = null,
                        userAgent = null,
                        metadata = mapOf("username" to "ghost"),
                        createdAt = NOW,
                    ),
                ),
            totalElements = 5,
        )

    // ── 200 + 응답형태 ─────────────────────────────────────────────────────────────

    @Test
    fun `admin 200 — 응답형태(items page size totalElements totalPages, null 필드 포함)`() {
        every { repository.search(any()) } returns samplePage()

        mockMvc.perform(get(PATH).with(adminJwt()))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.page").value(0))
            .andExpect(jsonPath("$.size").value(50))
            .andExpect(jsonPath("$.totalElements").value(5))
            // totalPages = ceil(5/50) = 1
            .andExpect(jsonPath("$.totalPages").value(1))
            .andExpect(jsonPath("$.items.length()").value(2))
            // 첫 행: alice (id number, username/displayName 채움, metadata 직렬화)
            .andExpect(jsonPath("$.items[0].id").value(42))
            .andExpect(jsonPath("$.items[0].userId").value(ALICE_ID.toString()))
            .andExpect(jsonPath("$.items[0].username").value("alice"))
            .andExpect(jsonPath("$.items[0].displayName").value("Alice Anderson"))
            .andExpect(jsonPath("$.items[0].eventType").value("LOGIN_SUCCESS"))
            .andExpect(jsonPath("$.items[0].providerId").value("local"))
            .andExpect(jsonPath("$.items[0].ipAddress").value("203.0.113.1"))
            .andExpect(jsonPath("$.items[0].userAgent").value("agent-a"))
            .andExpect(jsonPath("$.items[0].metadata.sid").value("s1"))
            // 둘째 행: 사용자 미상 (userId/username/displayName/ip/userAgent null)
            .andExpect(jsonPath("$.items[1].id").value(7))
            .andExpect(jsonPath("$.items[1].userId").doesNotExist())
            .andExpect(jsonPath("$.items[1].username").doesNotExist())
            .andExpect(jsonPath("$.items[1].displayName").doesNotExist())
            .andExpect(jsonPath("$.items[1].eventType").value("LOGIN_FAILURE"))
    }

    @Test
    fun `admin 200 — totalPages 는 totalElements size 로 올림 계산`() {
        // totalElements=5, size=2 → ceil(5/2)=3
        every { repository.search(any()) } returns samplePage().copy(totalElements = 5)

        mockMvc.perform(get(PATH).param("size", "2").with(adminJwt()))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.size").value(2))
            .andExpect(jsonPath("$.totalPages").value(3))
    }

    // ── criteria 매핑 (기본값 + 명시값) ───────────────────────────────────────────

    @Test
    fun `파라미터 없으면 기본 criteria(page0 size50, 필터 null)로 조회한다`() {
        val captured = slot<AuthAuditLogSearchCriteria>()
        every { repository.search(capture(captured)) } returns samplePage()

        mockMvc.perform(get(PATH).with(adminJwt())).andExpect(status().isOk)

        verify { repository.search(any()) }
        assertThat(captured.captured.page).isEqualTo(0)
        assertThat(captured.captured.size).isEqualTo(50)
        assertThat(captured.captured.eventType).isNull()
        assertThat(captured.captured.userId).isNull()
        assertThat(captured.captured.from).isNull()
        assertThat(captured.captured.to).isNull()
    }

    @Test
    fun `명시 파라미터를 criteria 로 정확히 매핑한다`() {
        val captured = slot<AuthAuditLogSearchCriteria>()
        every { repository.search(capture(captured)) } returns samplePage()

        mockMvc.perform(
            get(PATH)
                .param("eventType", "LOGIN_FAILURE")
                .param("userId", ALICE_ID.toString())
                .param("from", "2026-06-01T00:00:00Z")
                .param("to", "2026-06-02T00:00:00Z")
                .param("page", "1")
                .param("size", "10")
                .with(adminJwt()),
        ).andExpect(status().isOk)

        assertThat(captured.captured.eventType).isEqualTo(AuthEventType.LOGIN_FAILURE)
        assertThat(captured.captured.userId).isEqualTo(ALICE_ID)
        assertThat(captured.captured.from).isEqualTo(Instant.parse("2026-06-01T00:00:00Z"))
        assertThat(captured.captured.to).isEqualTo(Instant.parse("2026-06-02T00:00:00Z"))
        assertThat(captured.captured.page).isEqualTo(1)
        assertThat(captured.captured.size).isEqualTo(10)
    }

    // ── 권한 (403/401) ─────────────────────────────────────────────────────────────

    @Test
    fun `비 admin JWT 403 — 권한 상세 미노출`() {
        mockMvc.perform(get(PATH).with(userJwt()))
            .andExpect(status().isForbidden)
            .andExpect(content().string(not(containsString("SYSTEM_ADMIN"))))
    }

    @Test
    fun `PAT 403 — ROLE_PAT 는 SYSTEM_ADMIN 아님`() {
        mockMvc.perform(get(PATH).with(patAuth()))
            .andExpect(status().isForbidden)
            .andExpect(content().string(not(containsString("SYSTEM_ADMIN"))))
    }

    @Test
    fun `미인증 401`() {
        mockMvc.perform(get(PATH)).andExpect(status().isUnauthorized)
    }

    // ── 검증 위반 (400) ────────────────────────────────────────────────────────────

    @Test
    fun `eventType 미정의 400`() {
        mockMvc.perform(get(PATH).param("eventType", "NOT_A_REAL_EVENT").with(adminJwt()))
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `userId 잘못된 UUID 400`() {
        mockMvc.perform(get(PATH).param("userId", "not-a-uuid").with(adminJwt()))
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `from 파싱 실패 400`() {
        mockMvc.perform(get(PATH).param("from", "yesterday").with(adminJwt()))
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `to 파싱 실패 400`() {
        mockMvc.perform(get(PATH).param("to", "2026-13-99").with(adminJwt()))
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `page 음수 400`() {
        mockMvc.perform(get(PATH).param("page", "-1").with(adminJwt()))
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `size 0 400`() {
        mockMvc.perform(get(PATH).param("size", "0").with(adminJwt()))
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `size 101 400`() {
        mockMvc.perform(get(PATH).param("size", "101").with(adminJwt()))
            .andExpect(status().isBadRequest)
    }
}
