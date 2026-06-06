// ProjectSecuritySchemeController 슬라이스 테스트 — 프로젝트 스킴 적용/해제/조회 라우팅·상태·actor 선추출·예외 매핑 (FR-PM-06 PR-A Task 8)

package com.atlas.bts.identity.web

import com.atlas.bts.identity.config.CorsConfig
import com.atlas.bts.identity.config.SecurityConfig
import com.atlas.bts.identity.issuesecurity.IssueSecurityLevel
import com.atlas.bts.identity.issuesecurity.ProjectNotFoundException
import com.atlas.bts.identity.issuesecurity.ProjectSchemeAccessDeniedException
import com.atlas.bts.identity.issuesecurity.ProjectSecuritySchemeService
import com.atlas.bts.identity.issuesecurity.SchemeNotFoundException
import com.atlas.bts.identity.jwt.SidRevokeJwtConverter
import com.atlas.bts.identity.pat.PersonalAccessToken
import com.atlas.bts.identity.pat.PersonalAccessTokenService
import com.atlas.bts.identity.session.SessionService
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.hamcrest.core.IsNull
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
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.web.cors.CorsConfigurationSource
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * ProjectSecuritySchemeController 슬라이스 테스트 (FR-PM-06 PR-A Task 8).
 *
 * 검증 범위.
 * - 프로젝트 스킴 적용/해제/조회 엔드포인트 HTTP 상태 (PUT/DELETE 204, GET 200)
 * - 미인증(비-UUID subject) → 401 unauthorized, **프로젝트 조회보다 actor 추출이 먼저**
 *   (미인증자가 404 vs 401 로 리소스 존재를 probe 하지 못하게, auth-extraction-before-resource-lookup 교훈)
 * - 비-PROJECT_ADMIN → 403 forbidden (서비스의 ProjectSchemeAccessDeniedException)
 * - 없는 프로젝트 → 404 project_not_found, 없는 스킴 → 404 scheme_not_found
 *
 * IssueSecuritySchemeControllerTest 와 동일한 슬라이스 셋업(SecurityConfig + WebMvcTest + jwt PostProcessor).
 * OAuth2ClientAutoConfiguration 제외 — Keycloak issuer-uri 네트워크 접속 차단.
 */
@WebMvcTest(
    controllers = [ProjectSecuritySchemeController::class],
    excludeAutoConfiguration = [OAuth2ClientAutoConfiguration::class],
)
@Import(SecurityConfig::class, ProjectSecuritySchemeControllerTest.MockBeans::class)
class ProjectSecuritySchemeControllerTest {
    companion object {
        private val ACTOR_ID: UUID = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")
        private val SCHEME_ID: UUID = UUID.fromString("11111111-1111-4111-8111-111111111111")
        private const val PROJECT_KEY = "ATLAS"

        private val NOW: Instant = Instant.parse("2026-06-06T10:00:00Z")
        private const val RAW_PAT = "pat_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        private val ALLOWED_ORIGINS = listOf("http://localhost:5173")

        private val LEVEL_ID: UUID = UUID.fromString("22222222-2222-4222-8222-222222222222")

        private fun level(
            id: UUID = LEVEL_ID,
            name: String = "임원만",
            description: String? = "임원 전용 등급",
            isDefault: Boolean = true,
        ) = IssueSecurityLevel(
            id = id,
            schemeId = SCHEME_ID,
            name = name,
            description = description,
            isDefault = isDefault,
            createdAt = NOW,
        )

        private fun activePat(userId: UUID = ACTOR_ID) =
            PersonalAccessToken(
                id = UUID.fromString("dddddddd-dddd-4ddd-8ddd-dddddddddddd"),
                userId = userId,
                name = "ci-token",
                tokenHash = "irrelevant-hash",
                scopes = listOf("*"),
                expiresAt = null,
                lastUsedAt = null,
                revokedAt = null,
                createdAt = NOW,
            )
    }

    @TestConfiguration
    class MockBeans {
        @Bean
        fun jwtDecoder(): JwtDecoder = mockk(relaxed = true)

        @Bean
        fun sidRevokeJwtConverter(): SidRevokeJwtConverter {
            val sessionService: SessionService = mockk(relaxed = true)
            val clock = Clock.fixed(Instant.parse("2026-06-06T10:00:00Z"), ZoneOffset.UTC)
            return SidRevokeJwtConverter(sessionService, clock)
        }

        @Bean
        fun corsConfigurationSource(): CorsConfigurationSource = CorsConfig().corsConfigurationSource(ALLOWED_ORIGINS)

        @Bean
        fun personalAccessTokenService(): PersonalAccessTokenService = mockk(relaxed = true)

        @Bean
        fun projectSecuritySchemeService(): ProjectSecuritySchemeService = mockk()
    }

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var service: ProjectSecuritySchemeService

    @Autowired
    lateinit var personalAccessTokenService: PersonalAccessTokenService

    @BeforeEach
    fun resetMocks() {
        // WebMvcTest 슬라이스의 mockk 빈은 테스트 간 공유되므로 호출 기록을 초기화한다
        // (verify(exactly = 0) 가 다른 테스트의 호출을 세지 않게).
        clearMocks(service)
    }

    // ── PUT /api/v1/projects/{key}/issue-security-scheme — assign ─────────────

    @Test
    fun `PUT scheme 적용 204`() {
        every { service.assign(PROJECT_KEY, SCHEME_ID, ACTOR_ID) } returns Unit

        mockMvc.perform(
            put("/api/v1/projects/$PROJECT_KEY/issue-security-scheme")
                .with(jwt().jwt { it.subject(ACTOR_ID.toString()) })
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"schemeId":"$SCHEME_ID"}"""),
        )
            .andExpect(status().isNoContent)

        verify { service.assign(PROJECT_KEY, SCHEME_ID, ACTOR_ID) }
    }

    @Test
    fun `PUT scheme 비-PROJECT_ADMIN 403 forbidden`() {
        every { service.assign(PROJECT_KEY, SCHEME_ID, ACTOR_ID) } throws ProjectSchemeAccessDeniedException()

        mockMvc.perform(
            put("/api/v1/projects/$PROJECT_KEY/issue-security-scheme")
                .with(jwt().jwt { it.subject(ACTOR_ID.toString()) })
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"schemeId":"$SCHEME_ID"}"""),
        )
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.error").value("forbidden"))
    }

    @Test
    fun `PUT scheme 없는 프로젝트 404 project_not_found`() {
        every { service.assign(PROJECT_KEY, SCHEME_ID, ACTOR_ID) } throws ProjectNotFoundException(PROJECT_KEY)

        mockMvc.perform(
            put("/api/v1/projects/$PROJECT_KEY/issue-security-scheme")
                .with(jwt().jwt { it.subject(ACTOR_ID.toString()) })
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"schemeId":"$SCHEME_ID"}"""),
        )
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error").value("project_not_found"))
    }

    @Test
    fun `PUT scheme 없는 스킴 404 scheme_not_found`() {
        every { service.assign(PROJECT_KEY, SCHEME_ID, ACTOR_ID) } throws SchemeNotFoundException(SCHEME_ID)

        mockMvc.perform(
            put("/api/v1/projects/$PROJECT_KEY/issue-security-scheme")
                .with(jwt().jwt { it.subject(ACTOR_ID.toString()) })
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"schemeId":"$SCHEME_ID"}"""),
        )
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error").value("scheme_not_found"))
    }

    @Test
    fun `PUT scheme 미인증 403 차단 — CSRF 없음`() {
        mockMvc.perform(
            put("/api/v1/projects/$PROJECT_KEY/issue-security-scheme")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"schemeId":"$SCHEME_ID"}"""),
        )
            .andExpect(status().isForbidden)
    }

    @Test
    fun `PUT scheme 비-UUID subject 401 unauthorized — 서비스 미호출`() {
        mockMvc.perform(
            put("/api/v1/projects/$PROJECT_KEY/issue-security-scheme")
                .with(jwt().jwt { it.subject("not-a-uuid") })
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"schemeId":"$SCHEME_ID"}"""),
        )
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.error").value("unauthorized"))

        verify(exactly = 0) { service.assign(any(), any(), any()) }
    }

    // ── DELETE /api/v1/projects/{key}/issue-security-scheme — unassign ────────

    @Test
    fun `DELETE scheme 해제 204`() {
        every { service.unassign(PROJECT_KEY, ACTOR_ID) } returns Unit

        mockMvc.perform(
            delete("/api/v1/projects/$PROJECT_KEY/issue-security-scheme")
                .with(jwt().jwt { it.subject(ACTOR_ID.toString()) }),
        )
            .andExpect(status().isNoContent)

        verify { service.unassign(PROJECT_KEY, ACTOR_ID) }
    }

    @Test
    fun `DELETE scheme 비-PROJECT_ADMIN 403 forbidden`() {
        every { service.unassign(PROJECT_KEY, ACTOR_ID) } throws ProjectSchemeAccessDeniedException()

        mockMvc.perform(
            delete("/api/v1/projects/$PROJECT_KEY/issue-security-scheme")
                .with(jwt().jwt { it.subject(ACTOR_ID.toString()) }),
        )
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.error").value("forbidden"))
    }

    @Test
    fun `DELETE scheme 없는 프로젝트 404 project_not_found`() {
        every { service.unassign(PROJECT_KEY, ACTOR_ID) } throws ProjectNotFoundException(PROJECT_KEY)

        mockMvc.perform(
            delete("/api/v1/projects/$PROJECT_KEY/issue-security-scheme")
                .with(jwt().jwt { it.subject(ACTOR_ID.toString()) }),
        )
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error").value("project_not_found"))
    }

    @Test
    fun `DELETE scheme 미인증 401 unauthorized — 서비스 미호출`() {
        mockMvc.perform(
            delete("/api/v1/projects/$PROJECT_KEY/issue-security-scheme")
                .with(jwt().jwt { it.subject("not-a-uuid") }),
        )
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.error").value("unauthorized"))

        verify(exactly = 0) { service.unassign(any(), any()) }
    }

    // ── GET /api/v1/projects/{key}/issue-security-scheme — findByProject ──────

    @Test
    fun `GET scheme 적용된 스킴 200`() {
        every { service.findByProject(PROJECT_KEY, ACTOR_ID) } returns SCHEME_ID

        mockMvc.perform(
            get("/api/v1/projects/$PROJECT_KEY/issue-security-scheme")
                .with(jwt().jwt { it.subject(ACTOR_ID.toString()) }),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.schemeId").value(SCHEME_ID.toString()))
    }

    @Test
    fun `GET scheme 미적용 200 schemeId null`() {
        every { service.findByProject(PROJECT_KEY, ACTOR_ID) } returns null

        mockMvc.perform(
            get("/api/v1/projects/$PROJECT_KEY/issue-security-scheme")
                .with(jwt().jwt { it.subject(ACTOR_ID.toString()) }),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.schemeId").value(IsNull.nullValue())) // schemeId=null 로 직렬화
    }

    @Test
    fun `GET scheme 비-PROJECT_ADMIN 403 forbidden`() {
        every { service.findByProject(PROJECT_KEY, ACTOR_ID) } throws ProjectSchemeAccessDeniedException()

        mockMvc.perform(
            get("/api/v1/projects/$PROJECT_KEY/issue-security-scheme")
                .with(jwt().jwt { it.subject(ACTOR_ID.toString()) }),
        )
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.error").value("forbidden"))
    }

    @Test
    fun `GET scheme 없는 프로젝트 404 project_not_found`() {
        every { service.findByProject(PROJECT_KEY, ACTOR_ID) } throws ProjectNotFoundException(PROJECT_KEY)

        mockMvc.perform(
            get("/api/v1/projects/$PROJECT_KEY/issue-security-scheme")
                .with(jwt().jwt { it.subject(ACTOR_ID.toString()) }),
        )
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error").value("project_not_found"))
    }

    @Test
    fun `GET scheme 미인증 401 unauthorized — 프로젝트 조회보다 actor 추출 먼저`() {
        mockMvc.perform(
            get("/api/v1/projects/$PROJECT_KEY/issue-security-scheme")
                .with(jwt().jwt { it.subject("not-a-uuid") }),
        )
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.error").value("unauthorized"))

        verify(exactly = 0) { service.findByProject(any(), any()) }
    }

    // ── PAT actor 경로 ────────────────────────────────────────────────────────

    @Test
    fun `GET scheme PAT actor 200 — SecurityContext principal 추출`() {
        every { personalAccessTokenService.verify(RAW_PAT) } returns Result.success(activePat())
        every { service.findByProject(PROJECT_KEY, ACTOR_ID) } returns SCHEME_ID

        mockMvc.perform(
            get("/api/v1/projects/$PROJECT_KEY/issue-security-scheme")
                .header("Authorization", "Bearer $RAW_PAT"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.schemeId").value(SCHEME_ID.toString()))
    }

    // ── GET /api/v1/projects/{key}/issue-security-scheme/levels — listLevelsByProject ──
    // BE-2: 이슈 편집/생성 드롭다운 옵션용. 인증만 요구(PROJECT_ADMIN 불요), 멤버 데이터 비노출.

    @Test
    fun `GET levels 적용 스킴 등급 목록 200`() {
        every { service.listLevelsByProject(PROJECT_KEY) } returns listOf(level())

        mockMvc.perform(
            get("/api/v1/projects/$PROJECT_KEY/issue-security-scheme/levels")
                .with(jwt().jwt { it.subject(ACTOR_ID.toString()) }),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.levels[0].id").value(LEVEL_ID.toString()))
            .andExpect(jsonPath("$.levels[0].name").value("임원만"))
            .andExpect(jsonPath("$.levels[0].description").value("임원 전용 등급"))
            .andExpect(jsonPath("$.levels[0].isDefault").value(true))
            // 멤버 데이터(누가 볼 수 있나)는 노출하지 않는다 — 스킴 구조만.
            .andExpect(jsonPath("$.levels[0].members").doesNotExist())
    }

    @Test
    fun `GET levels 미적용 프로젝트 200 빈 배열`() {
        every { service.listLevelsByProject(PROJECT_KEY) } returns emptyList()

        mockMvc.perform(
            get("/api/v1/projects/$PROJECT_KEY/issue-security-scheme/levels")
                .with(jwt().jwt { it.subject(ACTOR_ID.toString()) }),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.levels").isArray)
            .andExpect(jsonPath("$.levels").isEmpty)
    }

    @Test
    fun `GET levels 없는 프로젝트 404 project_not_found`() {
        every { service.listLevelsByProject(PROJECT_KEY) } throws ProjectNotFoundException(PROJECT_KEY)

        mockMvc.perform(
            get("/api/v1/projects/$PROJECT_KEY/issue-security-scheme/levels")
                .with(jwt().jwt { it.subject(ACTOR_ID.toString()) }),
        )
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error").value("project_not_found"))
    }

    @Test
    fun `GET levels 미인증 401 unauthorized — 프로젝트 조회보다 actor 추출 먼저`() {
        mockMvc.perform(
            get("/api/v1/projects/$PROJECT_KEY/issue-security-scheme/levels")
                .with(jwt().jwt { it.subject("not-a-uuid") }),
        )
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.error").value("unauthorized"))

        verify(exactly = 0) { service.listLevelsByProject(any()) }
    }
}
