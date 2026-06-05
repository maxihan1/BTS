// UserGroupController 슬라이스 테스트 — 8 엔드포인트 라우팅/상태/SYSTEM_ADMIN 가드/에러코드 (FR-PM-09 Task 5)

package com.atlas.bts.identity.web

import com.atlas.bts.identity.config.CorsConfig
import com.atlas.bts.identity.config.SecurityConfig
import com.atlas.bts.identity.group.UserGroup
import com.atlas.bts.identity.group.UserGroupNameConflictException
import com.atlas.bts.identity.group.UserGroupNotFoundException
import com.atlas.bts.identity.group.UserGroupRepository
import com.atlas.bts.identity.group.UserGroupService
import com.atlas.bts.identity.group.UserGroupWithCount
import com.atlas.bts.identity.group.UserNotFoundException
import com.atlas.bts.identity.jwt.SidRevokeJwtConverter
import com.atlas.bts.identity.pat.PersonalAccessToken
import com.atlas.bts.identity.pat.PersonalAccessTokenService
import com.atlas.bts.identity.session.SessionService
import com.atlas.bts.identity.user.User
import com.atlas.bts.identity.user.UserRepository
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
import org.springframework.http.MediaType
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.web.cors.CorsConfigurationSource
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * UserGroupController 슬라이스 테스트 (FR-PM-09 Task 5).
 *
 * 검증 범위.
 * - 8 엔드포인트 HTTP 상태 (201/200/204)
 * - SYSTEM_ADMIN 가드 — isSystemAdmin=false → 403 forbidden
 * - 미인증 → 401 (CSRF 없는 변경요청 → 403)
 * - 도메인 예외 → 404/409/400 + snake_case error 키 매핑
 * - GroupResponse memberCount 동봉, 멤버 목록 UserSummaryResponse 재사용
 *
 * OAuth2ClientAutoConfiguration 제외 — Keycloak issuer-uri 네트워크 접속 차단.
 */
@WebMvcTest(
    controllers = [UserGroupController::class],
    excludeAutoConfiguration = [OAuth2ClientAutoConfiguration::class],
)
@Import(SecurityConfig::class, UserGroupControllerTest.MockBeans::class)
class UserGroupControllerTest {
    companion object {
        private val ADMIN_ID: UUID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
        private val GROUP_ID: UUID = UUID.fromString("11111111-1111-4111-8111-111111111111")
        private val MEMBER_ID: UUID = UUID.fromString("22222222-2222-4222-8222-222222222222")

        private val NOW: Instant = Instant.parse("2026-06-05T10:00:00Z")

        /** "pat_" prefix 포함 PAT raw token */
        private const val RAW_PAT = "pat_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"

        private val ALLOWED_ORIGINS = listOf("http://localhost:5173")

        private fun group(
            id: UUID = GROUP_ID,
            name: String = "Engineering",
            description: String? = "Eng team",
        ) = UserGroup(
            id = id,
            name = name,
            description = description,
            createdAt = NOW,
            updatedAt = NOW,
        )

        private fun member(
            id: UUID = MEMBER_ID,
            username: String = "alice",
            displayName: String = "Alice",
        ) = User(
            id = id,
            username = username,
            email = "alice@example.com",
            displayName = displayName,
            createdAt = NOW,
            updatedAt = NOW,
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
            val clock = Clock.fixed(Instant.parse("2026-06-05T10:00:00Z"), ZoneOffset.UTC)
            return SidRevokeJwtConverter(sessionService, clock)
        }

        @Bean
        fun corsConfigurationSource(): CorsConfigurationSource = CorsConfig().corsConfigurationSource(ALLOWED_ORIGINS)

        @Bean
        fun personalAccessTokenService(): PersonalAccessTokenService = mockk(relaxed = true)

        @Bean
        fun userGroupService(): UserGroupService = mockk()

        @Bean
        fun userRepository(): UserRepository = mockk()

        @Bean
        fun userGroupRepository(): UserGroupRepository = mockk(relaxed = true)

        @Bean
        fun systemPermissionResolver(): SystemPermissionResolver = mockk()
    }

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var userGroupService: UserGroupService

    @Autowired
    lateinit var userRepository: UserRepository

    @Autowired
    lateinit var systemPermissionResolver: SystemPermissionResolver

    @Autowired
    lateinit var personalAccessTokenService: PersonalAccessTokenService

    private fun grantAdmin() {
        every { systemPermissionResolver.isSystemAdmin(ADMIN_ID) } returns true
    }

    // ── POST /api/v1/groups — createGroup ────────────────────────────────────

    @Test
    fun `POST groups 관리자 201 GroupResponse 반환`() {
        grantAdmin()
        every { userGroupService.createGroup("Engineering", "Eng team") } returns group()

        mockMvc.perform(
            post("/api/v1/groups")
                .with(jwt().jwt { it.subject(ADMIN_ID.toString()) })
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"Engineering","description":"Eng team"}"""),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.id").value(GROUP_ID.toString()))
            .andExpect(jsonPath("$.name").value("Engineering"))
            .andExpect(jsonPath("$.description").value("Eng team"))
            .andExpect(jsonPath("$.memberCount").value(0))
    }

    @Test
    fun `POST groups 이름 충돌 409 group_name_conflict`() {
        grantAdmin()
        every { userGroupService.createGroup(any(), any()) } throws UserGroupNameConflictException("Engineering")

        mockMvc.perform(
            post("/api/v1/groups")
                .with(jwt().jwt { it.subject(ADMIN_ID.toString()) })
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"Engineering"}"""),
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.error").value("group_name_conflict"))
    }

    @Test
    fun `POST groups 빈 이름 400 group_name_invalid`() {
        grantAdmin()
        every { userGroupService.createGroup(any(), any()) } throws IllegalArgumentException("그룹 이름은 비어 있을 수 없습니다.")

        mockMvc.perform(
            post("/api/v1/groups")
                .with(jwt().jwt { it.subject(ADMIN_ID.toString()) })
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"   "}"""),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value("group_name_invalid"))
    }

    @Test
    fun `POST groups isSystemAdmin false 403 forbidden`() {
        every { systemPermissionResolver.isSystemAdmin(ADMIN_ID) } returns false

        mockMvc.perform(
            post("/api/v1/groups")
                .with(jwt().jwt { it.subject(ADMIN_ID.toString()) })
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"Engineering"}"""),
        )
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.error").value("forbidden"))
    }

    @Test
    fun `POST groups 미인증 403 차단 — CSRF 없음`() {
        mockMvc.perform(
            post("/api/v1/groups")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"Engineering"}"""),
        )
            .andExpect(status().isForbidden)
    }

    // ── GET /api/v1/groups — listGroups ──────────────────────────────────────

    @Test
    fun `GET groups 200 목록 반환 — memberCount 포함`() {
        grantAdmin()
        val groups =
            listOf(
                UserGroupWithCount(group(), 3),
                UserGroupWithCount(group(id = MEMBER_ID, name = "Design"), 1),
            )
        every { userGroupService.listGroups() } returns groups

        mockMvc.perform(
            get("/api/v1/groups")
                .with(jwt().jwt { it.subject(ADMIN_ID.toString()) }),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$").isArray)
            .andExpect(jsonPath("$.length()").value(2))
            .andExpect(jsonPath("$[0].name").value("Engineering"))
            .andExpect(jsonPath("$[0].memberCount").value(3))
            .andExpect(jsonPath("$[1].memberCount").value(1))
    }

    @Test
    fun `GET groups isSystemAdmin false 403 forbidden`() {
        every { systemPermissionResolver.isSystemAdmin(ADMIN_ID) } returns false

        mockMvc.perform(
            get("/api/v1/groups")
                .with(jwt().jwt { it.subject(ADMIN_ID.toString()) }),
        )
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.error").value("forbidden"))
    }

    @Test
    fun `GET groups 미인증 401`() {
        mockMvc.perform(get("/api/v1/groups"))
            .andExpect(status().isUnauthorized)
    }

    // ── GET /api/v1/groups/{groupId} — getGroup ─────────────────────────────

    @Test
    fun `GET group 단건 200`() {
        grantAdmin()
        every { userGroupService.getGroup(GROUP_ID) } returns group()

        mockMvc.perform(
            get("/api/v1/groups/$GROUP_ID")
                .with(jwt().jwt { it.subject(ADMIN_ID.toString()) }),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id").value(GROUP_ID.toString()))
            .andExpect(jsonPath("$.name").value("Engineering"))
    }

    @Test
    fun `GET group 없음 404 group_not_found`() {
        grantAdmin()
        every { userGroupService.getGroup(GROUP_ID) } throws UserGroupNotFoundException(GROUP_ID)

        mockMvc.perform(
            get("/api/v1/groups/$GROUP_ID")
                .with(jwt().jwt { it.subject(ADMIN_ID.toString()) }),
        )
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error").value("group_not_found"))
    }

    // ── PATCH /api/v1/groups/{groupId} — updateGroup ─────────────────────────

    @Test
    fun `PATCH group 200`() {
        grantAdmin()
        every { userGroupService.updateGroup(GROUP_ID, "Renamed", "new desc") } returns
            group(name = "Renamed", description = "new desc")

        mockMvc.perform(
            patch("/api/v1/groups/$GROUP_ID")
                .with(jwt().jwt { it.subject(ADMIN_ID.toString()) })
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"Renamed","description":"new desc"}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.name").value("Renamed"))
            .andExpect(jsonPath("$.description").value("new desc"))
    }

    @Test
    fun `PATCH group 없음 404 group_not_found`() {
        grantAdmin()
        every { userGroupService.updateGroup(any(), any(), any()) } throws UserGroupNotFoundException(GROUP_ID)

        mockMvc.perform(
            patch("/api/v1/groups/$GROUP_ID")
                .with(jwt().jwt { it.subject(ADMIN_ID.toString()) })
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"Renamed"}"""),
        )
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error").value("group_not_found"))
    }

    @Test
    fun `PATCH group 이름 충돌 409 group_name_conflict`() {
        grantAdmin()
        every { userGroupService.updateGroup(any(), any(), any()) } throws UserGroupNameConflictException("Dup")

        mockMvc.perform(
            patch("/api/v1/groups/$GROUP_ID")
                .with(jwt().jwt { it.subject(ADMIN_ID.toString()) })
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"Dup"}"""),
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.error").value("group_name_conflict"))
    }

    @Test
    fun `PATCH group 빈 이름 400 group_name_invalid`() {
        grantAdmin()
        every { userGroupService.updateGroup(any(), any(), any()) } throws IllegalArgumentException("invalid")

        mockMvc.perform(
            patch("/api/v1/groups/$GROUP_ID")
                .with(jwt().jwt { it.subject(ADMIN_ID.toString()) })
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"  "}"""),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value("group_name_invalid"))
    }

    @Test
    fun `PATCH group isSystemAdmin false 403 forbidden`() {
        every { systemPermissionResolver.isSystemAdmin(ADMIN_ID) } returns false

        mockMvc.perform(
            patch("/api/v1/groups/$GROUP_ID")
                .with(jwt().jwt { it.subject(ADMIN_ID.toString()) })
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"Renamed"}"""),
        )
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.error").value("forbidden"))
    }

    // ── DELETE /api/v1/groups/{groupId} — deleteGroup ────────────────────────

    @Test
    fun `DELETE group 204`() {
        grantAdmin()
        every { userGroupService.deleteGroup(GROUP_ID) } returns Unit

        mockMvc.perform(
            delete("/api/v1/groups/$GROUP_ID")
                .with(jwt().jwt { it.subject(ADMIN_ID.toString()) }),
        )
            .andExpect(status().isNoContent)
    }

    @Test
    fun `DELETE group 없음 404 group_not_found`() {
        grantAdmin()
        every { userGroupService.deleteGroup(GROUP_ID) } throws UserGroupNotFoundException(GROUP_ID)

        mockMvc.perform(
            delete("/api/v1/groups/$GROUP_ID")
                .with(jwt().jwt { it.subject(ADMIN_ID.toString()) }),
        )
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error").value("group_not_found"))
    }

    // ── GET /api/v1/groups/{groupId}/members — listMembers ───────────────────

    @Test
    fun `GET members 200 UserSummaryResponse 배열`() {
        grantAdmin()
        every { userGroupService.listMembers(GROUP_ID) } returns listOf(MEMBER_ID)
        every { userRepository.findByIds(listOf(MEMBER_ID)) } returns listOf(member())

        mockMvc.perform(
            get("/api/v1/groups/$GROUP_ID/members")
                .with(jwt().jwt { it.subject(ADMIN_ID.toString()) }),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$").isArray)
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].id").value(MEMBER_ID.toString()))
            .andExpect(jsonPath("$[0].username").value("alice"))
            .andExpect(jsonPath("$[0].displayName").value("Alice"))
    }

    @Test
    fun `GET members 그룹 없음 404 group_not_found`() {
        grantAdmin()
        every { userGroupService.listMembers(GROUP_ID) } throws UserGroupNotFoundException(GROUP_ID)

        mockMvc.perform(
            get("/api/v1/groups/$GROUP_ID/members")
                .with(jwt().jwt { it.subject(ADMIN_ID.toString()) }),
        )
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error").value("group_not_found"))
    }

    // ── PUT /api/v1/groups/{groupId}/members/{userId} — addMember ────────────

    @Test
    fun `PUT member 204 멱등`() {
        grantAdmin()
        every { userGroupService.addMember(GROUP_ID, MEMBER_ID) } returns Unit

        mockMvc.perform(
            put("/api/v1/groups/$GROUP_ID/members/$MEMBER_ID")
                .with(jwt().jwt { it.subject(ADMIN_ID.toString()) }),
        )
            .andExpect(status().isNoContent)
    }

    @Test
    fun `PUT member 그룹 없음 404 group_not_found`() {
        grantAdmin()
        every { userGroupService.addMember(GROUP_ID, MEMBER_ID) } throws UserGroupNotFoundException(GROUP_ID)

        mockMvc.perform(
            put("/api/v1/groups/$GROUP_ID/members/$MEMBER_ID")
                .with(jwt().jwt { it.subject(ADMIN_ID.toString()) }),
        )
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error").value("group_not_found"))
    }

    @Test
    fun `PUT member 사용자 없음 404 user_not_found`() {
        grantAdmin()
        every { userGroupService.addMember(GROUP_ID, MEMBER_ID) } throws UserNotFoundException(MEMBER_ID)

        mockMvc.perform(
            put("/api/v1/groups/$GROUP_ID/members/$MEMBER_ID")
                .with(jwt().jwt { it.subject(ADMIN_ID.toString()) }),
        )
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error").value("user_not_found"))
    }

    @Test
    fun `PUT member isSystemAdmin false 403 forbidden`() {
        every { systemPermissionResolver.isSystemAdmin(ADMIN_ID) } returns false

        mockMvc.perform(
            put("/api/v1/groups/$GROUP_ID/members/$MEMBER_ID")
                .with(jwt().jwt { it.subject(ADMIN_ID.toString()) }),
        )
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.error").value("forbidden"))
    }

    // ── DELETE /api/v1/groups/{groupId}/members/{userId} — removeMember ──────

    @Test
    fun `DELETE member 204 멱등`() {
        grantAdmin()
        every { userGroupService.removeMember(GROUP_ID, MEMBER_ID) } returns Unit

        mockMvc.perform(
            delete("/api/v1/groups/$GROUP_ID/members/$MEMBER_ID")
                .with(jwt().jwt { it.subject(ADMIN_ID.toString()) }),
        )
            .andExpect(status().isNoContent)
    }

    @Test
    fun `DELETE member 그룹 없음 404 group_not_found`() {
        grantAdmin()
        every { userGroupService.removeMember(GROUP_ID, MEMBER_ID) } throws UserGroupNotFoundException(GROUP_ID)

        mockMvc.perform(
            delete("/api/v1/groups/$GROUP_ID/members/$MEMBER_ID")
                .with(jwt().jwt { it.subject(ADMIN_ID.toString()) }),
        )
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error").value("group_not_found"))
    }

    // ── 비-UUID JWT subject → 401 ────────────────────────────────────────────

    @Test
    fun `POST groups 비-UUID subject 401 unauthorized`() {
        mockMvc.perform(
            post("/api/v1/groups")
                .with(jwt().jwt { it.subject("not-a-uuid") })
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"Engineering"}"""),
        )
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.error").value("unauthorized"))
    }

    // ── PAT actor 경로 ───────────────────────────────────────────────────────

    @Test
    fun `GET groups PAT actor 200 — SecurityContext principal 추출`() {
        every { personalAccessTokenService.verify(RAW_PAT) } returns Result.success(activePat())
        every { systemPermissionResolver.isSystemAdmin(ADMIN_ID) } returns true
        every { userGroupService.listGroups() } returns emptyList()

        mockMvc.perform(
            get("/api/v1/groups")
                .header("Authorization", "Bearer $RAW_PAT"),
        )
            .andExpect(status().isOk)
    }
}
