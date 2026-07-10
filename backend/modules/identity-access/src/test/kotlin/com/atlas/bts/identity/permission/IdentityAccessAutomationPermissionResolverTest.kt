// IdentityAccessAutomationPermissionResolver 단위테스트 — 키 해석 + 멤버 게이트 + MANAGE_AUTOMATION 매트릭스 (FR-AT-01 D5)

package com.atlas.bts.identity.permission

import com.atlas.bts.identity.project.ProjectDirectory
import com.atlas.bts.identity.project.ProjectMembership
import com.atlas.bts.identity.project.ProjectMembershipRepository
import com.atlas.bts.identity.project.ProjectRole
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * [IdentityAccessAutomationPermissionResolver] 단위테스트 (FR-AT-01 D5).
 *
 * ## 검증 시나리오 (plan Task 5 RED)
 * (a) PROJECT_ADMIN 멤버 + 매트릭스 true → true. "MANAGE_AUTOMATION" 코드로 roleHasPermission 호출 verify.
 * (b) 비멤버(findByProjectAndUser null) → false (멤버 게이트). schemeRepo 미호출 verify.
 * (c) 미해석 키(resolveKeyToId null) → false (fail-closed). membership/scheme 미호출 verify.
 * (d) MEMBER 멤버 + 매트릭스 false → false (매트릭스 미보유, fail-closed).
 *
 * fail-closed 하한: 미해석 키·비멤버·매트릭스 미보유는 모두 거부(false)로 수렴한다
 * (cross-BC resolver fail-open 회귀 방지 — nullable+?:return 이 아니라 non-null 주입 + 명시 거부).
 *
 * MockK 로 의존성을 격리하여 adapter 판정 로직만 검증한다.
 * 선례: [IdentityAccessComponentPermissionResolverTest] (Boolean·멤버 게이트+매트릭스) +
 * [IdentityAccessWorkflowSchemePermissionResolverIntegrationTest] S3~S6 (키 해석 fail-closed).
 */
class IdentityAccessAutomationPermissionResolverTest {
    private val projectDirectory: ProjectDirectory = mockk()
    private val membershipRepo: ProjectMembershipRepository = mockk()
    private val schemeRepo: PermissionSchemeRepository = mockk()

    private val resolver =
        IdentityAccessAutomationPermissionResolver(
            projectDirectory = projectDirectory,
            membershipRepo = membershipRepo,
            permissionSchemeRepo = schemeRepo,
        )

    private val actor: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private val projectId: UUID = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001")
    private val projectKey: String = "ATLAS"

    // ── 헬퍼 ──────────────────────────────────────────────────────────────────

    private fun membership(role: ProjectRole): ProjectMembership =
        ProjectMembership(
            projectId = projectId,
            userId = actor,
            role = role,
            createdAt = Instant.EPOCH,
            updatedAt = Instant.EPOCH,
        )

    // ── (a) PROJECT_ADMIN + 매트릭스 true → 허용 + 코드 매핑 검증 ──────────────

    @Test
    fun `PROJECT_ADMIN 멤버는 허용 — MANAGE_AUTOMATION 코드로 판정`() {
        every { projectDirectory.resolveKeyToId(projectKey) } returns projectId
        every { membershipRepo.findByProjectAndUser(projectId, actor) } returns membership(ProjectRole.PROJECT_ADMIN)
        every {
            schemeRepo.roleHasPermission(projectId, "PROJECT_ADMIN", "MANAGE_AUTOMATION")
        } returns true

        assertThat(resolver.hasManageAutomation(actor, projectKey)).isTrue()

        verify(exactly = 1) {
            schemeRepo.roleHasPermission(projectId, "PROJECT_ADMIN", "MANAGE_AUTOMATION")
        }
    }

    // ── (b) 비멤버 → 거부 (멤버 게이트) ─────────────────────────────────────

    @Test
    fun `비멤버는 거부 — 멤버 게이트, 매트릭스 미호출`() {
        every { projectDirectory.resolveKeyToId(projectKey) } returns projectId
        every { membershipRepo.findByProjectAndUser(projectId, actor) } returns null

        assertThat(resolver.hasManageAutomation(actor, projectKey)).isFalse()

        verify(exactly = 0) { schemeRepo.roleHasPermission(any(), any(), any()) }
    }

    // ── (c) 미해석 키 → 거부 (fail-closed, 멤버십/매트릭스 미호출) ─────────────

    @Test
    fun `미해석 프로젝트 키는 거부 — fail-closed`() {
        every { projectDirectory.resolveKeyToId("NOPE") } returns null

        assertThat(resolver.hasManageAutomation(actor, "NOPE")).isFalse()

        verify(exactly = 0) { membershipRepo.findByProjectAndUser(any(), any()) }
        verify(exactly = 0) { schemeRepo.roleHasPermission(any(), any(), any()) }
    }

    // ── (d) MEMBER + 매트릭스 false → 거부 (fail-closed) ──────────────────────

    @Test
    fun `MEMBER 멤버는 거부 — 매트릭스 미보유`() {
        every { projectDirectory.resolveKeyToId(projectKey) } returns projectId
        every { membershipRepo.findByProjectAndUser(projectId, actor) } returns membership(ProjectRole.MEMBER)
        every {
            schemeRepo.roleHasPermission(projectId, "MEMBER", "MANAGE_AUTOMATION")
        } returns false

        assertThat(resolver.hasManageAutomation(actor, projectKey)).isFalse()
    }
}
