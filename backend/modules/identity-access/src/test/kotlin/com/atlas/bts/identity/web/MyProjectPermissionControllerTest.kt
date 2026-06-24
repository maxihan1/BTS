// MyProjectPermissionController 단위 테스트 — MANAGE_CUSTOM_FIELDS 노출 회로 검증 (FR-IS-10 D6 게이팅)

package com.atlas.bts.identity.web

import com.atlas.bts.identity.pat.PersonalAccessTokenService
import com.atlas.bts.identity.permission.PermissionSchemeRepository
import com.atlas.bts.identity.project.ProjectDirectory
import com.atlas.bts.identity.project.ProjectMembership
import com.atlas.bts.identity.project.ProjectMembershipRepository
import com.atlas.bts.identity.project.ProjectRole
import com.bts.shared.permission.ComponentPermission
import com.bts.shared.permission.ComponentPermissionResolver
import com.bts.shared.permission.CustomFieldPermission
import com.bts.shared.permission.CustomFieldPermissionResolver
import com.bts.shared.permission.IssuePermission
import com.bts.shared.permission.IssuePermissionResolver
import com.bts.shared.permission.IssueScope
import com.bts.shared.permission.TemplatePermission
import com.bts.shared.permission.TemplatePermissionResolver
import com.bts.shared.permission.VersionPermission
import com.bts.shared.permission.VersionPermissionResolver
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.security.oauth2.jwt.Jwt
import java.time.Instant
import java.util.UUID

/**
 * [MyProjectPermissionController] 단위 테스트 — MANAGE_CUSTOM_FIELDS 노출 회로 (FR-IS-10 D6 게이팅).
 *
 * 프론트 커스텀 필드 관리 페이지가 "관리 버튼 노출 여부"를 판정하려면 권한 요약 응답에
 * `MANAGE_CUSTOM_FIELDS` 키가 포함되어야 한다. 이 테스트는 [CustomFieldPermissionResolver]
 * 위임 결과가 응답 `permissions` 맵에 정확히 반영되는지 MockK 로 격리 검증한다.
 *
 * 검증 시나리오.
 * - (a) MANAGE_CUSTOM_FIELDS 보유 actor → `permissions["MANAGE_CUSTOM_FIELDS"] == true`
 * - (b) 미보유 actor → `false`
 * - (c) 프로젝트 미존재(resolveKeyToId null) → `false` (404 아님, 기존 미존재 정책 일관)
 * - (d) MANAGE_TEMPLATES 보유/미보유/프로젝트 미존재 (FR-TM-01 D6 게이팅) — (a)~(c)와 동형
 *
 * MANAGE_COMPONENTS 와 완전 동형이므로 컴포넌트 리졸버와 같은 패턴으로 mock 한다.
 *
 * @see MyProjectPermissionController
 * @see MyProjectPermissionIntegrationTest HTTP 레이어 + JWT 인증 흐름 통합테스트
 */
class MyProjectPermissionControllerTest {
    private lateinit var permissionResolver: IssuePermissionResolver
    private lateinit var componentPermissionResolver: ComponentPermissionResolver
    private lateinit var versionPermissionResolver: VersionPermissionResolver
    private lateinit var customFieldPermissionResolver: CustomFieldPermissionResolver
    private lateinit var templatePermissionResolver: TemplatePermissionResolver
    private lateinit var projectDirectory: ProjectDirectory
    private lateinit var membershipRepository: ProjectMembershipRepository
    private lateinit var permissionSchemeRepository: PermissionSchemeRepository
    private lateinit var personalAccessTokenService: PersonalAccessTokenService
    private lateinit var controller: MyProjectPermissionController

    private val actorId: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000aa")
    private val projectId: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000bb")
    private val projectKey = "ATLAS"

    @BeforeEach
    fun setUp() {
        permissionResolver = mockk()
        componentPermissionResolver = mockk()
        versionPermissionResolver = mockk()
        customFieldPermissionResolver = mockk()
        templatePermissionResolver = mockk()
        projectDirectory = mockk()
        membershipRepository = mockk()
        permissionSchemeRepository = mockk()
        personalAccessTokenService = mockk()
        controller =
            MyProjectPermissionController(
                permissionResolver = permissionResolver,
                componentPermissionResolver = componentPermissionResolver,
                versionPermissionResolver = versionPermissionResolver,
                customFieldPermissionResolver = customFieldPermissionResolver,
                templatePermissionResolver = templatePermissionResolver,
                projectDirectory = projectDirectory,
                membershipRepository = membershipRepository,
                permissionSchemeRepository = permissionSchemeRepository,
                personalAccessTokenService = personalAccessTokenService,
            )

        // CREATE(이슈)·MANAGE_COMPONENTS·MANAGE_VERSIONS 는 본 테스트 관심사가 아니므로 false 로 고정.
        every {
            permissionResolver.hasPermission(actorId, IssuePermission.CREATE, IssueScope.Project(projectKey))
        } returns false
        every {
            componentPermissionResolver.hasPermission(actorId, ComponentPermission.CREATE, projectId)
        } returns false
        every {
            versionPermissionResolver.hasPermission(actorId, VersionPermission.CREATE, projectId)
        } returns false
        every {
            customFieldPermissionResolver.hasPermission(actorId, CustomFieldPermission.CREATE, projectId)
        } returns false
        every {
            templatePermissionResolver.hasPermission(actorId, TemplatePermission.CREATE, projectId)
        } returns false
        // MANAGE_FIELD_PERMISSIONS 기본 — 비멤버(membership null). 각 테스트가 필요 시 override.
        every { membershipRepository.findByProjectAndUser(projectId, actorId) } returns null
    }

    /** [actorId] 를 주어진 [role] 의 프로젝트 멤버로 설정한다 (MANAGE_FIELD_PERMISSIONS 판정 경로용). */
    private fun seedMembership(role: ProjectRole) {
        every { membershipRepository.findByProjectAndUser(projectId, actorId) } returns
            ProjectMembership(
                projectId = projectId,
                userId = actorId,
                role = role,
                createdAt = Instant.now(),
                updatedAt = Instant.now(),
            )
    }

    @Test
    fun `MANAGE_CUSTOM_FIELDS 보유 actor — permissions MANAGE_CUSTOM_FIELDS true`() {
        every { projectDirectory.resolveKeyToId(projectKey) } returns projectId
        every {
            customFieldPermissionResolver.hasPermission(actorId, CustomFieldPermission.CREATE, projectId)
        } returns true

        val response = controller.getProjectPermissions(MockHttpServletRequest(), jwtFor(actorId), projectKey)

        assertThat(response.permissions["MANAGE_CUSTOM_FIELDS"]).isTrue()
    }

    @Test
    fun `MANAGE_CUSTOM_FIELDS 미보유 actor — permissions MANAGE_CUSTOM_FIELDS false`() {
        every { projectDirectory.resolveKeyToId(projectKey) } returns projectId
        every {
            customFieldPermissionResolver.hasPermission(actorId, CustomFieldPermission.CREATE, projectId)
        } returns false

        val response = controller.getProjectPermissions(MockHttpServletRequest(), jwtFor(actorId), projectKey)

        assertThat(response.permissions["MANAGE_CUSTOM_FIELDS"]).isFalse()
    }

    @Test
    fun `프로젝트 미존재(resolveKeyToId null) — permissions MANAGE_CUSTOM_FIELDS false`() {
        every { projectDirectory.resolveKeyToId(projectKey) } returns null

        val response = controller.getProjectPermissions(MockHttpServletRequest(), jwtFor(actorId), projectKey)

        assertThat(response.permissions["MANAGE_CUSTOM_FIELDS"]).isFalse()
    }

    @Test
    fun `UPDATE 보유 actor — permissions UPDATE true`() {
        every { projectDirectory.resolveKeyToId(projectKey) } returns projectId
        every {
            permissionResolver.hasPermission(actorId, IssuePermission.UPDATE, IssueScope.Project(projectKey))
        } returns true

        val response = controller.getProjectPermissions(MockHttpServletRequest(), jwtFor(actorId), projectKey)

        assertThat(response.permissions["UPDATE"]).isTrue()
    }

    @Test
    fun `UPDATE 미보유 actor — permissions UPDATE false`() {
        every { projectDirectory.resolveKeyToId(projectKey) } returns projectId
        every {
            permissionResolver.hasPermission(actorId, IssuePermission.UPDATE, IssueScope.Project(projectKey))
        } returns false

        val response = controller.getProjectPermissions(MockHttpServletRequest(), jwtFor(actorId), projectKey)

        assertThat(response.permissions["UPDATE"]).isFalse()
    }

    @Test
    fun `MANAGE_TEMPLATES 보유 actor — permissions MANAGE_TEMPLATES true`() {
        every { projectDirectory.resolveKeyToId(projectKey) } returns projectId
        every {
            templatePermissionResolver.hasPermission(actorId, TemplatePermission.CREATE, projectId)
        } returns true

        val response = controller.getProjectPermissions(MockHttpServletRequest(), jwtFor(actorId), projectKey)

        assertThat(response.permissions["MANAGE_TEMPLATES"]).isTrue()
    }

    @Test
    fun `MANAGE_TEMPLATES 미보유 actor — permissions MANAGE_TEMPLATES false`() {
        every { projectDirectory.resolveKeyToId(projectKey) } returns projectId
        every {
            templatePermissionResolver.hasPermission(actorId, TemplatePermission.CREATE, projectId)
        } returns false

        val response = controller.getProjectPermissions(MockHttpServletRequest(), jwtFor(actorId), projectKey)

        assertThat(response.permissions["MANAGE_TEMPLATES"]).isFalse()
    }

    @Test
    fun `프로젝트 미존재(resolveKeyToId null) — permissions MANAGE_TEMPLATES false`() {
        every { projectDirectory.resolveKeyToId(projectKey) } returns null

        val response = controller.getProjectPermissions(MockHttpServletRequest(), jwtFor(actorId), projectKey)

        assertThat(response.permissions["MANAGE_TEMPLATES"]).isFalse()
    }

    @Test
    fun `PROJECT_ADMIN — permissions MANAGE_FIELD_PERMISSIONS true`() {
        every { projectDirectory.resolveKeyToId(projectKey) } returns projectId
        seedMembership(ProjectRole.PROJECT_ADMIN)
        every {
            permissionSchemeRepository.roleHasPermission(projectId, "PROJECT_ADMIN", "MANAGE_FIELD_PERMISSIONS")
        } returns true

        val response = controller.getProjectPermissions(MockHttpServletRequest(), jwtFor(actorId), projectKey)

        assertThat(response.permissions["MANAGE_FIELD_PERMISSIONS"]).isTrue()
    }

    @Test
    fun `일반 멤버(MEMBER) — permissions MANAGE_FIELD_PERMISSIONS false`() {
        every { projectDirectory.resolveKeyToId(projectKey) } returns projectId
        seedMembership(ProjectRole.MEMBER)
        every {
            permissionSchemeRepository.roleHasPermission(projectId, "MEMBER", "MANAGE_FIELD_PERMISSIONS")
        } returns false

        val response = controller.getProjectPermissions(MockHttpServletRequest(), jwtFor(actorId), projectKey)

        assertThat(response.permissions["MANAGE_FIELD_PERMISSIONS"]).isFalse()
    }

    @Test
    fun `비멤버 — permissions MANAGE_FIELD_PERMISSIONS false`() {
        every { projectDirectory.resolveKeyToId(projectKey) } returns projectId
        // setUp 기본값(membership null)으로 비멤버. schemeRepo 는 호출되지 않아야 한다.

        val response = controller.getProjectPermissions(MockHttpServletRequest(), jwtFor(actorId), projectKey)

        assertThat(response.permissions["MANAGE_FIELD_PERMISSIONS"]).isFalse()
    }

    @Test
    fun `프로젝트 미존재(resolveKeyToId null) — permissions MANAGE_FIELD_PERMISSIONS false`() {
        every { projectDirectory.resolveKeyToId(projectKey) } returns null

        val response = controller.getProjectPermissions(MockHttpServletRequest(), jwtFor(actorId), projectKey)

        assertThat(response.permissions["MANAGE_FIELD_PERMISSIONS"]).isFalse()
    }

    /**
     * 주어진 [userId] 를 subject 로 하는 최소 JWT 를 생성한다.
     *
     * 컨트롤러는 JWT subject 를 UUID 로 파싱해 actorId 로 사용하므로, subject 만 채우면 충분하다.
     * PAT 분기는 Authorization 헤더가 없는 [MockHttpServletRequest] 에서 자동으로 건너뛴다.
     *
     * @param userId JWT subject(actorId)
     * @return subject 만 채운 최소 [Jwt]
     */
    private fun jwtFor(userId: UUID): Jwt =
        Jwt.withTokenValue("test-token")
            .header("alg", "none")
            .subject(userId.toString())
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(3600))
            .build()
}
