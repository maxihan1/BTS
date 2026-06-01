// ProjectMemberController 슬라이스 테스트 — 엔드포인트 매핑/상태/actor 추출/에러코드 검증 (FR-PM-01 Task 6)

package com.atlas.bts.identity.web

import com.atlas.bts.identity.config.CorsConfig
import com.atlas.bts.identity.config.SecurityConfig
import com.atlas.bts.identity.jwt.SidRevokeJwtConverter
import com.atlas.bts.identity.pat.PersonalAccessToken
import com.atlas.bts.identity.pat.PersonalAccessTokenService
import com.atlas.bts.identity.project.AlreadyMember
import com.atlas.bts.identity.project.BootstrapRequiresJwt
import com.atlas.bts.identity.project.LastAdminProtected
import com.atlas.bts.identity.project.MemberNotFound
import com.atlas.bts.identity.project.NotProjectAdmin
import com.atlas.bts.identity.project.ProjectMembership
import com.atlas.bts.identity.project.ProjectMembershipService
import com.atlas.bts.identity.project.ProjectNotFound
import com.atlas.bts.identity.project.ProjectRole
import com.atlas.bts.identity.project.UserNotFound
import com.atlas.bts.identity.session.SessionService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
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
 * ProjectMemberController 슬라이스 테스트 (FR-PM-01 Task 6).
 *
 * 검증 범위.
 * - 4 엔드포인트 HTTP 상태 (201/200/204)
 * - JWT actor 추출 경로
 * - PAT actor 추출 경로 (jwt=null → SecurityContext UsernamePasswordAuthenticationToken)
 * - 부트스트랩 PAT 거부 (BootstrapRequiresJwt → 403)
 * - 각 service 예외 → 에러코드 + HTTP 매핑
 * - 비멤버 ProjectNotFound → 404
 * - 잘못된 role 문자열 → 422 invalid_role
 */
// OAuth2ClientAutoConfiguration 제외 — Keycloak issuer-uri 네트워크 접속 차단
@WebMvcTest(
    controllers = [ProjectMemberController::class],
    excludeAutoConfiguration = [OAuth2ClientAutoConfiguration::class],
)
@Import(SecurityConfig::class, ProjectMemberControllerTest.MockBeans::class)
class ProjectMemberControllerTest {

    companion object {
        private val ACTOR_ID: UUID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
        private val TARGET_ID: UUID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb")
        private val PROJECT_ID: UUID = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc")

        private val NOW: Instant = Instant.parse("2026-06-01T10:00:00Z")

        /** EC-26: "pat_" prefix 포함 PAT raw token */
        private const val RAW_PAT = "pat_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"

        private fun membership(userId: UUID = TARGET_ID, role: ProjectRole = ProjectRole.MEMBER) = ProjectMembership(
            projectId = PROJECT_ID,
            userId = userId,
            role = role,
            createdAt = NOW,
            updatedAt = NOW,
        )

        /** 성공한 PAT 검증 결과를 반환하는 mock PAT 객체 */
        private fun activePat(userId: UUID = ACTOR_ID) = PersonalAccessToken(
            id = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd"),
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
            val clock = Clock.fixed(Instant.parse("2026-06-01T10:00:00Z"), ZoneOffset.UTC)
            return SidRevokeJwtConverter(sessionService, clock)
        }

        @Bean
        fun corsConfigurationSource(): CorsConfigurationSource =
            CorsConfig().corsConfigurationSource(listOf("http://localhost:5173"))

        @Bean
        fun personalAccessTokenService(): PersonalAccessTokenService = mockk(relaxed = true)

        @Bean
        fun projectMembershipService(): ProjectMembershipService = mockk()
    }

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var projectMembershipService: ProjectMembershipService

    @Autowired
    lateinit var personalAccessTokenService: PersonalAccessTokenService

    // ── POST / — addMember ────────────────────────────────────────────────────

    @Test
    fun `POST members JWT actor 201 반환`() {
        every {
            projectMembershipService.addMember(ACTOR_ID, false, PROJECT_ID, TARGET_ID, ProjectRole.MEMBER)
        } returns membership()

        mockMvc.perform(
            post("/api/v1/projects/$PROJECT_ID/members")
                .with(jwt().jwt { it.subject(ACTOR_ID.toString()) })
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"userId":"$TARGET_ID","role":"MEMBER"}"""),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.userId").value(TARGET_ID.toString()))
            .andExpect(jsonPath("$.role").value("MEMBER"))
            .andExpect(jsonPath("$.projectId").value(PROJECT_ID.toString()))
    }

    @Test
    fun `POST members PAT actor 201 반환 — SecurityContext principal 추출`() {
        every { personalAccessTokenService.verify(RAW_PAT) } returns Result.success(activePat())
        every {
            projectMembershipService.addMember(ACTOR_ID, true, PROJECT_ID, TARGET_ID, ProjectRole.MEMBER)
        } returns membership()

        mockMvc.perform(
            post("/api/v1/projects/$PROJECT_ID/members")
                .header("Authorization", "Bearer $RAW_PAT")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"userId":"$TARGET_ID","role":"MEMBER"}"""),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.userId").value(TARGET_ID.toString()))
    }

    @Test
    fun `POST members BootstrapRequiresJwt 403 bootstrap_requires_jwt`() {
        every { personalAccessTokenService.verify(RAW_PAT) } returns Result.success(activePat())
        every {
            projectMembershipService.addMember(any(), true, PROJECT_ID, any(), any())
        } throws BootstrapRequiresJwt(PROJECT_ID)

        mockMvc.perform(
            post("/api/v1/projects/$PROJECT_ID/members")
                .header("Authorization", "Bearer $RAW_PAT")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"userId":"$ACTOR_ID","role":"MEMBER"}"""),
        )
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.error").value("bootstrap_requires_jwt"))
    }

    @Test
    fun `POST members ProjectNotFound 404 project_not_found`() {
        every {
            projectMembershipService.addMember(any(), any(), PROJECT_ID, any(), any())
        } throws ProjectNotFound(PROJECT_ID)

        mockMvc.perform(
            post("/api/v1/projects/$PROJECT_ID/members")
                .with(jwt().jwt { it.subject(ACTOR_ID.toString()) })
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"userId":"$TARGET_ID","role":"MEMBER"}"""),
        )
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error").value("project_not_found"))
    }

    @Test
    fun `POST members UserNotFound 404 user_not_found`() {
        every {
            projectMembershipService.addMember(any(), any(), PROJECT_ID, TARGET_ID, any())
        } throws UserNotFound(TARGET_ID)

        mockMvc.perform(
            post("/api/v1/projects/$PROJECT_ID/members")
                .with(jwt().jwt { it.subject(ACTOR_ID.toString()) })
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"userId":"$TARGET_ID","role":"MEMBER"}"""),
        )
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error").value("user_not_found"))
    }

    @Test
    fun `POST members AlreadyMember 409 membership_already_exists`() {
        every {
            projectMembershipService.addMember(any(), any(), PROJECT_ID, any(), any())
        } throws AlreadyMember(PROJECT_ID, TARGET_ID)

        mockMvc.perform(
            post("/api/v1/projects/$PROJECT_ID/members")
                .with(jwt().jwt { it.subject(ACTOR_ID.toString()) })
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"userId":"$TARGET_ID","role":"MEMBER"}"""),
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.error").value("membership_already_exists"))
    }

    @Test
    fun `POST members NotProjectAdmin 403 not_project_admin`() {
        every {
            projectMembershipService.addMember(any(), any(), PROJECT_ID, any(), any())
        } throws NotProjectAdmin(PROJECT_ID, ACTOR_ID)

        mockMvc.perform(
            post("/api/v1/projects/$PROJECT_ID/members")
                .with(jwt().jwt { it.subject(ACTOR_ID.toString()) })
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"userId":"$TARGET_ID","role":"MEMBER"}"""),
        )
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.error").value("not_project_admin"))
    }

    @Test
    fun `POST members invalid role 422 invalid_role`() {
        mockMvc.perform(
            post("/api/v1/projects/$PROJECT_ID/members")
                .with(jwt().jwt { it.subject(ACTOR_ID.toString()) })
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"userId":"$TARGET_ID","role":"UNKNOWN_ROLE"}"""),
        )
            .andExpect(status().isUnprocessableEntity)
            .andExpect(jsonPath("$.error").value("invalid_role"))
    }

    // ── GET / — listMembers ───────────────────────────────────────────────────

    @Test
    fun `GET members 200 members 배열 반환`() {
        every {
            projectMembershipService.listMembers(ACTOR_ID, PROJECT_ID)
        } returns listOf(membership(TARGET_ID, ProjectRole.MEMBER), membership(ACTOR_ID, ProjectRole.PROJECT_ADMIN))

        mockMvc.perform(
            get("/api/v1/projects/$PROJECT_ID/members")
                .with(jwt().jwt { it.subject(ACTOR_ID.toString()) }),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.members").isArray)
            .andExpect(jsonPath("$.members.length()").value(2))
    }

    @Test
    fun `GET members ProjectNotFound 404 project_not_found`() {
        every {
            projectMembershipService.listMembers(any(), PROJECT_ID)
        } throws ProjectNotFound(PROJECT_ID)

        mockMvc.perform(
            get("/api/v1/projects/$PROJECT_ID/members")
                .with(jwt().jwt { it.subject(ACTOR_ID.toString()) }),
        )
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error").value("project_not_found"))
    }

    // ── PATCH /{userId} — changeRole ─────────────────────────────────────────

    @Test
    fun `PATCH members userId JWT actor 200 반환`() {
        every {
            projectMembershipService.changeRole(ACTOR_ID, PROJECT_ID, TARGET_ID, ProjectRole.PROJECT_ADMIN)
        } returns membership(TARGET_ID, ProjectRole.PROJECT_ADMIN)

        mockMvc.perform(
            patch("/api/v1/projects/$PROJECT_ID/members/$TARGET_ID")
                .with(jwt().jwt { it.subject(ACTOR_ID.toString()) })
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"role":"PROJECT_ADMIN"}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.role").value("PROJECT_ADMIN"))
    }

    @Test
    fun `PATCH members MemberNotFound 404 member_not_found`() {
        every {
            projectMembershipService.changeRole(any(), PROJECT_ID, TARGET_ID, any())
        } throws MemberNotFound(PROJECT_ID, TARGET_ID)

        mockMvc.perform(
            patch("/api/v1/projects/$PROJECT_ID/members/$TARGET_ID")
                .with(jwt().jwt { it.subject(ACTOR_ID.toString()) })
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"role":"MEMBER"}"""),
        )
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error").value("member_not_found"))
    }

    @Test
    fun `PATCH members LastAdminProtected 409 last_admin_protected`() {
        every {
            projectMembershipService.changeRole(any(), PROJECT_ID, TARGET_ID, any())
        } throws LastAdminProtected(PROJECT_ID)

        mockMvc.perform(
            patch("/api/v1/projects/$PROJECT_ID/members/$TARGET_ID")
                .with(jwt().jwt { it.subject(ACTOR_ID.toString()) })
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"role":"MEMBER"}"""),
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.error").value("last_admin_protected"))
    }

    @Test
    fun `PATCH members invalid role 422 invalid_role`() {
        mockMvc.perform(
            patch("/api/v1/projects/$PROJECT_ID/members/$TARGET_ID")
                .with(jwt().jwt { it.subject(ACTOR_ID.toString()) })
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"role":"bad"}"""),
        )
            .andExpect(status().isUnprocessableEntity)
            .andExpect(jsonPath("$.error").value("invalid_role"))
    }

    // ── DELETE /{userId} — removeMember ──────────────────────────────────────

    @Test
    fun `DELETE members userId JWT actor 204 반환`() {
        every {
            projectMembershipService.removeMember(ACTOR_ID, PROJECT_ID, TARGET_ID)
        } returns Unit

        mockMvc.perform(
            delete("/api/v1/projects/$PROJECT_ID/members/$TARGET_ID")
                .with(jwt().jwt { it.subject(ACTOR_ID.toString()) }),
        )
            .andExpect(status().isNoContent)
    }

    @Test
    fun `DELETE members PAT actor 204 반환`() {
        every { personalAccessTokenService.verify(RAW_PAT) } returns Result.success(activePat())
        every {
            projectMembershipService.removeMember(ACTOR_ID, PROJECT_ID, TARGET_ID)
        } returns Unit

        mockMvc.perform(
            delete("/api/v1/projects/$PROJECT_ID/members/$TARGET_ID")
                .header("Authorization", "Bearer $RAW_PAT"),
        )
            .andExpect(status().isNoContent)
    }

    @Test
    fun `DELETE members LastAdminProtected 409 last_admin_protected`() {
        every {
            projectMembershipService.removeMember(any(), PROJECT_ID, TARGET_ID)
        } throws LastAdminProtected(PROJECT_ID)

        mockMvc.perform(
            delete("/api/v1/projects/$PROJECT_ID/members/$TARGET_ID")
                .with(jwt().jwt { it.subject(ACTOR_ID.toString()) }),
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.error").value("last_admin_protected"))
    }

    @Test
    fun `DELETE members MemberNotFound 404 member_not_found`() {
        every {
            projectMembershipService.removeMember(any(), PROJECT_ID, TARGET_ID)
        } throws MemberNotFound(PROJECT_ID, TARGET_ID)

        mockMvc.perform(
            delete("/api/v1/projects/$PROJECT_ID/members/$TARGET_ID")
                .with(jwt().jwt { it.subject(ACTOR_ID.toString()) }),
        )
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error").value("member_not_found"))
    }

    // ── 미인증 ────────────────────────────────────────────────────────────────

    @Test
    fun `GET members 미인증 401 반환`() {
        mockMvc.perform(
            get("/api/v1/projects/$PROJECT_ID/members"),
        )
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `POST members 미인증 CSRF 없음 403 차단`() {
        mockMvc.perform(
            post("/api/v1/projects/$PROJECT_ID/members")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"userId":"$TARGET_ID","role":"MEMBER"}"""),
        )
            .andExpect(status().isForbidden)
    }

    // ── actor 추출 서비스 전달 검증 ────────────────────────────────────────────

    @Test
    fun `JWT actor isPat=false 로 서비스 호출`() {
        every {
            projectMembershipService.addMember(ACTOR_ID, false, PROJECT_ID, TARGET_ID, ProjectRole.MEMBER)
        } returns membership()

        mockMvc.perform(
            post("/api/v1/projects/$PROJECT_ID/members")
                .with(jwt().jwt { it.subject(ACTOR_ID.toString()) })
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"userId":"$TARGET_ID","role":"MEMBER"}"""),
        ).andExpect(status().isCreated)

        verify(exactly = 1) {
            projectMembershipService.addMember(ACTOR_ID, false, PROJECT_ID, TARGET_ID, ProjectRole.MEMBER)
        }
    }

    @Test
    fun `PAT actor isPat=true 로 서비스 호출`() {
        every { personalAccessTokenService.verify(RAW_PAT) } returns Result.success(activePat())
        every {
            projectMembershipService.addMember(ACTOR_ID, true, PROJECT_ID, TARGET_ID, ProjectRole.MEMBER)
        } returns membership()

        mockMvc.perform(
            post("/api/v1/projects/$PROJECT_ID/members")
                .header("Authorization", "Bearer $RAW_PAT")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"userId":"$TARGET_ID","role":"MEMBER"}"""),
        ).andExpect(status().isCreated)

        verify(exactly = 1) {
            projectMembershipService.addMember(ACTOR_ID, true, PROJECT_ID, TARGET_ID, ProjectRole.MEMBER)
        }
    }
}
