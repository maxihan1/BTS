// IdentityAccessIssuePermissionResolver 단위테스트 — 멤버 게이트 + BROWSE/VIEW 매트릭스 위임 (FR-PM-05)

package com.atlas.bts.identity.permission

import com.atlas.bts.identity.project.ProjectDirectory
import com.atlas.bts.identity.project.ProjectMembership
import com.atlas.bts.identity.project.ProjectMembershipRepository
import com.atlas.bts.identity.project.ProjectRole
import com.bts.shared.permission.IssuePermission
import com.bts.shared.permission.IssueScope
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * [IdentityAccessIssuePermissionResolver] 단위테스트.
 *
 * ## 검증 시나리오
 * (a) MEMBER는 SOFT_DELETE 거부 — 매트릭스 DELETE_ISSUE 미보유
 * (b) 비멤버는 모든 권한 거부 — 멤버 게이트
 * (c) VIEW는 매트릭스 VIEW_ISSUE 위임 — 멤버여도 매트릭스 false면 거부 (FR-PM-05)
 * (d) 프로젝트 없음(resolveKeyToId null) → CREATE 거부 — EC-2
 * (f) BROWSE는 매트릭스 BROWSE_PROJECT 위임 — 멤버여도 매트릭스 false면 거부 (FR-PM-05)
 *
 * MockK로 의존성을 격리하여 adapter 로직만 검증한다.
 */
class IdentityAccessIssuePermissionResolverTest {
    private val projectDirectory: ProjectDirectory = mockk()
    private val membershipRepo: ProjectMembershipRepository = mockk()
    private val schemeRepo: PermissionSchemeRepository = mockk()

    private val resolver =
        IdentityAccessIssuePermissionResolver(
            projectDirectory = projectDirectory,
            membershipRepo = membershipRepo,
            schemeRepo = schemeRepo,
        )

    private val actor: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private val projectId: UUID = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001")

    // ── 헬퍼 ──────────────────────────────────────────────────────────────────

    private fun membership(role: ProjectRole): ProjectMembership =
        ProjectMembership(
            projectId = projectId,
            userId = actor,
            role = role,
            createdAt = Instant.EPOCH,
            updatedAt = Instant.EPOCH,
        )

    // ── (a) MEMBER + SOFT_DELETE → 거부 ──────────────────────────────────────

    @Test
    fun `MEMBER는 SOFT_DELETE 거부 — 매트릭스 DELETE_ISSUE 미보유`() {
        every { projectDirectory.resolveKeyToId("ATLAS") } returns projectId
        every { membershipRepo.findByProjectAndUser(projectId, actor) } returns membership(ProjectRole.MEMBER)
        every { schemeRepo.roleHasPermission(projectId, "MEMBER", "DELETE_ISSUE") } returns false

        assertThat(
            resolver.hasPermission(actor, IssuePermission.SOFT_DELETE, IssueScope.Issue("ATLAS-1")),
        ).isFalse()
    }

    // ── (b) 비멤버 → 거부 (멤버 게이트) ──────────────────────────────────────

    @Test
    fun `비멤버는 모든 권한 거부 — 멤버 게이트`() {
        every { projectDirectory.resolveKeyToId("ATLAS") } returns projectId
        every { membershipRepo.findByProjectAndUser(projectId, actor) } returns null

        assertThat(
            resolver.hasPermission(actor, IssuePermission.VIEW, IssueScope.Issue("ATLAS-1")),
        ).isFalse()
    }

    // ── (c) VIEW → 매트릭스 VIEW_ISSUE 위임 (멤버여도 false면 거부) ───────────

    @Test
    fun `VIEW는 매트릭스 VIEW_ISSUE 위임 — 멤버여도 매트릭스 false면 거부`() {
        every { projectDirectory.resolveKeyToId("ATLAS") } returns projectId
        every { membershipRepo.findByProjectAndUser(projectId, actor) } returns membership(ProjectRole.MEMBER)
        every { schemeRepo.roleHasPermission(projectId, "MEMBER", "VIEW_ISSUE") } returns false

        assertThat(
            resolver.hasPermission(actor, IssuePermission.VIEW, IssueScope.Issue("ATLAS-1")),
        ).isFalse()
    }

    // ── (f) BROWSE → 매트릭스 BROWSE_PROJECT 위임 (멤버여도 false면 거부) ─────

    @Test
    fun `BROWSE는 매트릭스 BROWSE_PROJECT 위임 — 멤버여도 매트릭스 false면 거부`() {
        every { projectDirectory.resolveKeyToId("ATLAS") } returns projectId
        every { membershipRepo.findByProjectAndUser(projectId, actor) } returns membership(ProjectRole.MEMBER)
        every { schemeRepo.roleHasPermission(projectId, "MEMBER", "BROWSE_PROJECT") } returns false

        assertThat(
            resolver.hasPermission(actor, IssuePermission.BROWSE, IssueScope.Project("ATLAS")),
        ).isFalse()
    }

    // ── (d) 프로젝트 없음 → 거부 (EC-2) ──────────────────────────────────────

    @Test
    fun `프로젝트 없음(resolveKeyToId null)이면 CREATE 거부 — EC-2`() {
        every { projectDirectory.resolveKeyToId("GHOST") } returns null

        assertThat(
            resolver.hasPermission(actor, IssuePermission.CREATE, IssueScope.Project("GHOST")),
        ).isFalse()
    }

    // ── (e) Global scope → 거부 (본 FR 미사용, resolveProjectId null) ─────────

    @Test
    fun `Global scope 는 거부 — 본 FR 미사용`() {
        assertThat(
            resolver.hasPermission(actor, IssuePermission.CREATE, IssueScope.Global),
        ).isFalse()
    }
}
