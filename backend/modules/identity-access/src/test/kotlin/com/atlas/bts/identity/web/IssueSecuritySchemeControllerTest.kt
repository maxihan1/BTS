// IssueSecuritySchemeController 슬라이스 테스트 — 스킴/등급/멤버 관리 라우팅·상태·SYSTEM_ADMIN 가드·에러코드 (FR-PM-06 PR-A Task 7)

package com.atlas.bts.identity.web

import com.atlas.bts.identity.config.CorsConfig
import com.atlas.bts.identity.config.SecurityConfig
import com.atlas.bts.identity.issuesecurity.DefaultLevelConflictException
import com.atlas.bts.identity.issuesecurity.IssueSecurityGroupNotFoundException
import com.atlas.bts.identity.issuesecurity.IssueSecurityLevel
import com.atlas.bts.identity.issuesecurity.IssueSecurityScheme
import com.atlas.bts.identity.issuesecurity.IssueSecuritySchemeDetail
import com.atlas.bts.identity.issuesecurity.IssueSecuritySchemeService
import com.atlas.bts.identity.issuesecurity.IssueSecurityUserNotFoundException
import com.atlas.bts.identity.issuesecurity.LevelNameConflictException
import com.atlas.bts.identity.issuesecurity.LevelNotFoundException
import com.atlas.bts.identity.issuesecurity.MemberType
import com.atlas.bts.identity.issuesecurity.SchemeNameConflictException
import com.atlas.bts.identity.issuesecurity.SchemeNotFoundException
import com.atlas.bts.identity.issuesecurity.SecurityLevelMember
import com.atlas.bts.identity.jwt.SidRevokeJwtConverter
import com.atlas.bts.identity.pat.PersonalAccessToken
import com.atlas.bts.identity.pat.PersonalAccessTokenService
import com.atlas.bts.identity.session.SessionService
import com.bts.shared.permission.SystemPermissionResolver
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.security.oauth2.client.servlet.OAuth2ClientAutoConfiguration
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.MediaType
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.web.cors.CorsConfigurationSource
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * IssueSecuritySchemeController 슬라이스 테스트 (FR-PM-06 PR-A Task 7).
 *
 * 검증 범위.
 * - 스킴/등급/멤버 관리 엔드포인트 HTTP 상태 (201/200/204)
 * - SYSTEM_ADMIN 가드 — isSystemAdmin=false → 403 forbidden
 * - 미인증 → 401 (CSRF 없는 변경요청 → 403)
 * - 비-UUID JWT subject → 401 unauthorized
 * - 도메인 예외 → 404/409/400 + snake_case error 키 매핑
 * - GET members 없는 등급 → 404 level_not_found (빈 등급 200 과 구분, plan T5 갭 보강)
 *
 * UserGroupControllerTest 와 동일한 슬라이스 셋업(SecurityConfig + WebMvcTest + jwt PostProcessor).
 * OAuth2ClientAutoConfiguration 제외 — Keycloak issuer-uri 네트워크 접속 차단.
 */
@WebMvcTest(
    controllers = [IssueSecuritySchemeController::class],
    excludeAutoConfiguration = [OAuth2ClientAutoConfiguration::class],
)
@Import(SecurityConfig::class, IssueSecuritySchemeControllerTest.MockBeans::class)
class IssueSecuritySchemeControllerTest {
    companion object {
        private val ADMIN_ID: UUID = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")
        private val SCHEME_ID: UUID = UUID.fromString("11111111-1111-4111-8111-111111111111")
        private val LEVEL_ID: UUID = UUID.fromString("22222222-2222-4222-8222-222222222222")
        private val MEMBER_ID: UUID = UUID.fromString("33333333-3333-4333-8333-333333333333")
        private val USER_VALUE: UUID = UUID.fromString("44444444-4444-4444-8444-444444444444")

        private val NOW: Instant = Instant.parse("2026-06-06T10:00:00Z")
        private const val RAW_PAT = "pat_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        private val ALLOWED_ORIGINS = listOf("http://localhost:5173")

        private fun scheme(
            id: UUID = SCHEME_ID,
            name: String = "기밀 스킴",
            description: String? = "설명",
        ) = IssueSecurityScheme(
            id = id,
            name = name,
            description = description,
            createdAt = NOW,
            updatedAt = NOW,
        )

        private fun level(
            id: UUID = LEVEL_ID,
            name: String = "임원만",
            isDefault: Boolean = false,
        ) = IssueSecurityLevel(
            id = id,
            schemeId = SCHEME_ID,
            name = name,
            description = "설명",
            isDefault = isDefault,
            createdAt = NOW,
        )

        private fun member(
            id: UUID = MEMBER_ID,
            type: MemberType = MemberType.USER,
            value: String? = USER_VALUE.toString(),
        ) = SecurityLevelMember(
            id = id,
            levelId = LEVEL_ID,
            memberType = type,
            memberValue = value,
            createdAt = NOW,
        )

        private fun activePat(userId: UUID = ADMIN_ID) =
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
        fun issueSecuritySchemeService(): IssueSecuritySchemeService = mockk()

        @Bean
        fun systemPermissionResolver(): SystemPermissionResolver = mockk()
    }

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var service: IssueSecuritySchemeService

    @Autowired
    lateinit var systemPermissionResolver: SystemPermissionResolver

    @Autowired
    lateinit var personalAccessTokenService: PersonalAccessTokenService

    private fun grantAdmin() {
        every { systemPermissionResolver.isSystemAdmin(ADMIN_ID) } returns true
    }

    // ── POST /api/v1/issue-security-schemes — createScheme ───────────────────

    @Test
    fun `POST schemes 관리자 201 SchemeResponse`() {
        grantAdmin()
        every { service.createScheme("기밀 스킴", "설명") } returns scheme()

        mockMvc.perform(
            post("/api/v1/issue-security-schemes")
                .with(jwt().jwt { it.subject(ADMIN_ID.toString()) })
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"기밀 스킴","description":"설명"}"""),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.id").value(SCHEME_ID.toString()))
            .andExpect(jsonPath("$.name").value("기밀 스킴"))
            .andExpect(jsonPath("$.description").value("설명"))
            .andExpect(jsonPath("$.levels").isArray)
            .andExpect(jsonPath("$.levels.length()").value(0))
    }

    @Test
    fun `POST schemes 이름 충돌 409 scheme_name_conflict`() {
        grantAdmin()
        every { service.createScheme(any(), any()) } throws SchemeNameConflictException("기밀 스킴")

        mockMvc.perform(
            post("/api/v1/issue-security-schemes")
                .with(jwt().jwt { it.subject(ADMIN_ID.toString()) })
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"기밀 스킴"}"""),
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.error").value("scheme_name_conflict"))
    }

    @Test
    fun `POST schemes 빈 이름 400 validation_error`() {
        grantAdmin()
        every { service.createScheme(any(), any()) } throws IllegalArgumentException("보안 스킴 이름은 비어 있을 수 없습니다.")

        mockMvc.perform(
            post("/api/v1/issue-security-schemes")
                .with(jwt().jwt { it.subject(ADMIN_ID.toString()) })
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"   "}"""),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value("validation_error"))
    }

    @Test
    fun `POST schemes isSystemAdmin false 403 forbidden`() {
        every { systemPermissionResolver.isSystemAdmin(ADMIN_ID) } returns false

        mockMvc.perform(
            post("/api/v1/issue-security-schemes")
                .with(jwt().jwt { it.subject(ADMIN_ID.toString()) })
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"기밀 스킴"}"""),
        )
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.error").value("forbidden"))
    }

    @Test
    fun `POST schemes 미인증 403 차단 — CSRF 없음`() {
        mockMvc.perform(
            post("/api/v1/issue-security-schemes")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"기밀 스킴"}"""),
        )
            .andExpect(status().isForbidden)
    }

    @Test
    fun `POST schemes 비-UUID subject 401 unauthorized`() {
        mockMvc.perform(
            post("/api/v1/issue-security-schemes")
                .with(jwt().jwt { it.subject("not-a-uuid") })
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"기밀 스킴"}"""),
        )
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.error").value("unauthorized"))
    }

    // ── GET /api/v1/issue-security-schemes — listSchemes ─────────────────────

    @Test
    fun `GET schemes 200 목록 — 등급 동봉`() {
        grantAdmin()
        every { service.listSchemes() } returns
            listOf(IssueSecuritySchemeDetail(scheme(), listOf(level(), level(id = MEMBER_ID, name = "내부용"))))

        mockMvc.perform(
            get("/api/v1/issue-security-schemes")
                .with(jwt().jwt { it.subject(ADMIN_ID.toString()) }),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$").isArray)
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].name").value("기밀 스킴"))
            .andExpect(jsonPath("$[0].levels.length()").value(2))
            .andExpect(jsonPath("$[0].levels[0].name").value("임원만"))
    }

    @Test
    fun `GET schemes 미인증 401`() {
        mockMvc.perform(get("/api/v1/issue-security-schemes"))
            .andExpect(status().isUnauthorized)
    }

    // ── GET /api/v1/issue-security-schemes/{id} — getScheme ──────────────────

    @Test
    fun `GET scheme 단건 200`() {
        grantAdmin()
        every { service.getScheme(SCHEME_ID) } returns IssueSecuritySchemeDetail(scheme(), listOf(level()))

        mockMvc.perform(
            get("/api/v1/issue-security-schemes/$SCHEME_ID")
                .with(jwt().jwt { it.subject(ADMIN_ID.toString()) }),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id").value(SCHEME_ID.toString()))
            .andExpect(jsonPath("$.levels.length()").value(1))
    }

    @Test
    fun `GET scheme 없음 404 scheme_not_found`() {
        grantAdmin()
        every { service.getScheme(SCHEME_ID) } throws SchemeNotFoundException(SCHEME_ID)

        mockMvc.perform(
            get("/api/v1/issue-security-schemes/$SCHEME_ID")
                .with(jwt().jwt { it.subject(ADMIN_ID.toString()) }),
        )
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error").value("scheme_not_found"))
    }

    // ── PATCH /api/v1/issue-security-schemes/{id} — updateScheme ─────────────

    @Test
    fun `PATCH scheme 200`() {
        grantAdmin()
        every { service.updateScheme(SCHEME_ID, "변경", "새 설명") } returns scheme(name = "변경", description = "새 설명")

        mockMvc.perform(
            patch("/api/v1/issue-security-schemes/$SCHEME_ID")
                .with(jwt().jwt { it.subject(ADMIN_ID.toString()) })
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"변경","description":"새 설명"}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.name").value("변경"))
    }

    @Test
    fun `PATCH scheme 없음 404 scheme_not_found`() {
        grantAdmin()
        every { service.updateScheme(any(), any(), any()) } throws SchemeNotFoundException(SCHEME_ID)

        mockMvc.perform(
            patch("/api/v1/issue-security-schemes/$SCHEME_ID")
                .with(jwt().jwt { it.subject(ADMIN_ID.toString()) })
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"변경"}"""),
        )
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error").value("scheme_not_found"))
    }

    // ── DELETE /api/v1/issue-security-schemes/{id} — deleteScheme ────────────

    @Test
    fun `DELETE scheme 204`() {
        grantAdmin()
        every { service.deleteScheme(SCHEME_ID) } returns Unit

        mockMvc.perform(
            delete("/api/v1/issue-security-schemes/$SCHEME_ID")
                .with(jwt().jwt { it.subject(ADMIN_ID.toString()) }),
        )
            .andExpect(status().isNoContent)
    }

    @Test
    fun `DELETE scheme 없음 404 scheme_not_found`() {
        grantAdmin()
        every { service.deleteScheme(SCHEME_ID) } throws SchemeNotFoundException(SCHEME_ID)

        mockMvc.perform(
            delete("/api/v1/issue-security-schemes/$SCHEME_ID")
                .with(jwt().jwt { it.subject(ADMIN_ID.toString()) }),
        )
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error").value("scheme_not_found"))
    }

    @Test
    fun `DELETE scheme 적용 중 409 scheme_in_use`() {
        grantAdmin()
        every { service.deleteScheme(SCHEME_ID) } throws DataIntegrityViolationException("fk restrict")

        mockMvc.perform(
            delete("/api/v1/issue-security-schemes/$SCHEME_ID")
                .with(jwt().jwt { it.subject(ADMIN_ID.toString()) }),
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.error").value("scheme_in_use"))
    }

    // ── POST /api/v1/issue-security-schemes/{schemeId}/levels — addLevel ─────

    @Test
    fun `POST levels 관리자 201 LevelResponse`() {
        grantAdmin()
        every { service.addLevel(SCHEME_ID, "임원만", "설명", true) } returns level(isDefault = true)

        mockMvc.perform(
            post("/api/v1/issue-security-schemes/$SCHEME_ID/levels")
                .with(jwt().jwt { it.subject(ADMIN_ID.toString()) })
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"임원만","description":"설명","isDefault":true}"""),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.id").value(LEVEL_ID.toString()))
            .andExpect(jsonPath("$.schemeId").value(SCHEME_ID.toString()))
            .andExpect(jsonPath("$.name").value("임원만"))
            .andExpect(jsonPath("$.isDefault").value(true))
    }

    @Test
    fun `POST levels 없는 스킴 404 scheme_not_found`() {
        grantAdmin()
        every { service.addLevel(any(), any(), any(), any()) } throws SchemeNotFoundException(SCHEME_ID)

        mockMvc.perform(
            post("/api/v1/issue-security-schemes/$SCHEME_ID/levels")
                .with(jwt().jwt { it.subject(ADMIN_ID.toString()) })
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"임원만"}"""),
        )
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error").value("scheme_not_found"))
    }

    @Test
    fun `POST levels 이름 충돌 409 level_name_conflict`() {
        grantAdmin()
        every { service.addLevel(any(), any(), any(), any()) } throws LevelNameConflictException("임원만")

        mockMvc.perform(
            post("/api/v1/issue-security-schemes/$SCHEME_ID/levels")
                .with(jwt().jwt { it.subject(ADMIN_ID.toString()) })
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"임원만"}"""),
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.error").value("level_name_conflict"))
    }

    @Test
    fun `POST levels 둘째 기본등급 409 default_level_conflict (N1)`() {
        grantAdmin()
        every { service.addLevel(any(), any(), any(), any()) } throws DefaultLevelConflictException(SCHEME_ID)

        mockMvc.perform(
            post("/api/v1/issue-security-schemes/$SCHEME_ID/levels")
                .with(jwt().jwt { it.subject(ADMIN_ID.toString()) })
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"둘째기본","isDefault":true}"""),
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.error").value("default_level_conflict"))
    }

    // ── PATCH /api/v1/issue-security-levels/{levelId} — updateLevel ──────────

    @Test
    fun `PATCH level 200`() {
        grantAdmin()
        every { service.updateLevel(LEVEL_ID, "내부용", null, false) } returns level(name = "내부용")

        mockMvc.perform(
            patch("/api/v1/issue-security-levels/$LEVEL_ID")
                .with(jwt().jwt { it.subject(ADMIN_ID.toString()) })
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"내부용","isDefault":false}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.name").value("내부용"))
    }

    @Test
    fun `PATCH level 없음 404 level_not_found`() {
        grantAdmin()
        every { service.updateLevel(any(), any(), any(), any()) } throws LevelNotFoundException(LEVEL_ID)

        mockMvc.perform(
            patch("/api/v1/issue-security-levels/$LEVEL_ID")
                .with(jwt().jwt { it.subject(ADMIN_ID.toString()) })
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"내부용"}"""),
        )
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error").value("level_not_found"))
    }

    // ── DELETE /api/v1/issue-security-levels/{levelId} — deleteLevel ─────────

    @Test
    fun `DELETE level 204`() {
        grantAdmin()
        every { service.deleteLevel(LEVEL_ID) } returns Unit

        mockMvc.perform(
            delete("/api/v1/issue-security-levels/$LEVEL_ID")
                .with(jwt().jwt { it.subject(ADMIN_ID.toString()) }),
        )
            .andExpect(status().isNoContent)
    }

    @Test
    fun `DELETE level 없음 404 level_not_found`() {
        grantAdmin()
        every { service.deleteLevel(LEVEL_ID) } throws LevelNotFoundException(LEVEL_ID)

        mockMvc.perform(
            delete("/api/v1/issue-security-levels/$LEVEL_ID")
                .with(jwt().jwt { it.subject(ADMIN_ID.toString()) }),
        )
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error").value("level_not_found"))
    }

    // ── POST /api/v1/issue-security-levels/{levelId}/members — addMember ─────

    @Test
    fun `POST members USER 201 MemberResponse`() {
        grantAdmin()
        every { service.addMember(LEVEL_ID, MemberType.USER, USER_VALUE.toString()) } returns member()

        mockMvc.perform(
            post("/api/v1/issue-security-levels/$LEVEL_ID/members")
                .with(jwt().jwt { it.subject(ADMIN_ID.toString()) })
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"memberType":"USER","memberValue":"$USER_VALUE"}"""),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.id").value(MEMBER_ID.toString()))
            .andExpect(jsonPath("$.memberType").value("USER"))
            .andExpect(jsonPath("$.memberValue").value(USER_VALUE.toString()))
    }

    @Test
    fun `POST members REPORTER 201 — memberValue 없음`() {
        grantAdmin()
        every { service.addMember(LEVEL_ID, MemberType.REPORTER, null) } returns
            member(type = MemberType.REPORTER, value = null)

        mockMvc.perform(
            post("/api/v1/issue-security-levels/$LEVEL_ID/members")
                .with(jwt().jwt { it.subject(ADMIN_ID.toString()) })
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"memberType":"REPORTER"}"""),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.memberType").value("REPORTER"))
    }

    @Test
    fun `POST members 잘못된 값 400 member_value_invalid (N2)`() {
        // 멤버 추가 엔드포인트 한정 — 도메인 멤버값 검증 IAE 는 member_value_invalid 로 매핑(스펙 EC1/EC3).
        grantAdmin()
        every {
            service.addMember(any(), any(), any())
        } throws IllegalArgumentException("PROJECT_ROLE 멤버 값은 잘못되었습니다.")

        mockMvc.perform(
            post("/api/v1/issue-security-levels/$LEVEL_ID/members")
                .with(jwt().jwt { it.subject(ADMIN_ID.toString()) })
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"memberType":"PROJECT_ROLE","memberValue":"NOT_A_ROLE"}"""),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value("member_value_invalid"))
    }

    @Test
    fun `POST members 없는 등급 404 level_not_found`() {
        grantAdmin()
        every { service.addMember(any(), any(), any()) } throws LevelNotFoundException(LEVEL_ID)

        mockMvc.perform(
            post("/api/v1/issue-security-levels/$LEVEL_ID/members")
                .with(jwt().jwt { it.subject(ADMIN_ID.toString()) })
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"memberType":"REPORTER"}"""),
        )
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error").value("level_not_found"))
    }

    @Test
    fun `POST members 없는 사용자 404 user_not_found`() {
        grantAdmin()
        every { service.addMember(any(), any(), any()) } throws IssueSecurityUserNotFoundException(USER_VALUE)

        mockMvc.perform(
            post("/api/v1/issue-security-levels/$LEVEL_ID/members")
                .with(jwt().jwt { it.subject(ADMIN_ID.toString()) })
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"memberType":"USER","memberValue":"$USER_VALUE"}"""),
        )
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error").value("user_not_found"))
    }

    @Test
    fun `POST members 없는 그룹 404 group_not_found`() {
        grantAdmin()
        every { service.addMember(any(), any(), any()) } throws IssueSecurityGroupNotFoundException(USER_VALUE)

        mockMvc.perform(
            post("/api/v1/issue-security-levels/$LEVEL_ID/members")
                .with(jwt().jwt { it.subject(ADMIN_ID.toString()) })
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"memberType":"GROUP","memberValue":"$USER_VALUE"}"""),
        )
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error").value("group_not_found"))
    }

    // ── GET /api/v1/issue-security-levels/{levelId}/members — listMembers ────

    @Test
    fun `GET members 200 MemberResponse 배열`() {
        grantAdmin()
        every { service.listMembers(LEVEL_ID) } returns listOf(member())

        mockMvc.perform(
            get("/api/v1/issue-security-levels/$LEVEL_ID/members")
                .with(jwt().jwt { it.subject(ADMIN_ID.toString()) }),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$").isArray)
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].id").value(MEMBER_ID.toString()))
            .andExpect(jsonPath("$[0].memberType").value("USER"))
    }

    @Test
    fun `GET members 빈 등급 200 빈 배열`() {
        grantAdmin()
        every { service.listMembers(LEVEL_ID) } returns emptyList()

        mockMvc.perform(
            get("/api/v1/issue-security-levels/$LEVEL_ID/members")
                .with(jwt().jwt { it.subject(ADMIN_ID.toString()) }),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(0))
    }

    @Test
    fun `GET members 없는 등급 404 level_not_found`() {
        grantAdmin()
        every { service.listMembers(LEVEL_ID) } throws LevelNotFoundException(LEVEL_ID)

        mockMvc.perform(
            get("/api/v1/issue-security-levels/$LEVEL_ID/members")
                .with(jwt().jwt { it.subject(ADMIN_ID.toString()) }),
        )
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error").value("level_not_found"))
    }

    // ── DELETE /api/v1/issue-security-level-members/{memberId} — removeMember ─

    @Test
    fun `DELETE member 204 멱등`() {
        grantAdmin()
        every { service.removeMember(MEMBER_ID) } returns Unit

        mockMvc.perform(
            delete("/api/v1/issue-security-level-members/$MEMBER_ID")
                .with(jwt().jwt { it.subject(ADMIN_ID.toString()) }),
        )
            .andExpect(status().isNoContent)
    }

    @Test
    fun `DELETE member isSystemAdmin false 403 forbidden`() {
        every { systemPermissionResolver.isSystemAdmin(ADMIN_ID) } returns false

        mockMvc.perform(
            delete("/api/v1/issue-security-level-members/$MEMBER_ID")
                .with(jwt().jwt { it.subject(ADMIN_ID.toString()) }),
        )
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.error").value("forbidden"))
    }

    // ── PAT actor 경로 ────────────────────────────────────────────────────────

    @Test
    fun `GET schemes PAT actor 200 — SecurityContext principal 추출`() {
        every { personalAccessTokenService.verify(RAW_PAT) } returns Result.success(activePat())
        every { systemPermissionResolver.isSystemAdmin(ADMIN_ID) } returns true
        every { service.listSchemes() } returns emptyList()

        mockMvc.perform(
            get("/api/v1/issue-security-schemes")
                .header("Authorization", "Bearer $RAW_PAT"),
        )
            .andExpect(status().isOk)
    }
}
