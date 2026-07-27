// IdentityAccessIssuePermissionResolver 단위테스트 — 멤버 게이트 + BROWSE/VIEW 매트릭스 위임 (FR-PM-05)

package com.atlas.bts.identity.permission

import com.atlas.bts.identity.group.UserGroupRepository
import com.atlas.bts.identity.issuesecurity.IssueSecurityContext
import com.atlas.bts.identity.issuesecurity.IssueSecurityLookup
import com.atlas.bts.identity.issuesecurity.IssueSecuritySchemeRepository
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
 * (g) SET_SECURITY는 매트릭스 SET_ISSUE_SECURITY 위임 — 멤버여도 매트릭스 false면 거부 (FR-PM-06)
 *
 * MockK로 의존성을 격리하여 adapter 로직만 검증한다.
 */
class IdentityAccessIssuePermissionResolverTest {
    private val projectDirectory: ProjectDirectory = mockk()
    private val membershipRepo: ProjectMembershipRepository = mockk()
    private val schemeRepo: PermissionSchemeRepository = mockk()
    private val securityLookup: IssueSecurityLookup = mockk()
    private val securitySchemeRepo: IssueSecuritySchemeRepository = mockk()
    private val userGroupRepo: UserGroupRepository = mockk()

    private val resolver =
        IdentityAccessIssuePermissionResolver(
            projectDirectory = projectDirectory,
            membershipRepo = membershipRepo,
            schemeRepo = schemeRepo,
            securityLookup = securityLookup,
            securitySchemeRepo = securitySchemeRepo,
            userGroupRepo = userGroupRepo,
        )

    private val actor: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private val projectId: UUID = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001")
    private val levelId: UUID = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000001")
    private val otherUser: UUID = UUID.fromString("cccccccc-0000-0000-0000-000000000001")

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

    // ── 보안 등급 게이트 확대 (2026-07-27 — 이전에는 VIEW 에만 걸렸다) ──────────

    /**
     * **볼 수 없는 이슈를 바꿀 수는 더더욱 없어야 한다.**
     *
     * 2026-07-27 이전 등급 게이트는 `VIEW` 에만 걸렸다. 그래서 기밀 이슈를 **볼 수 없는** 멤버가
     * 매트릭스에 `EDIT_ISSUE` 만 있으면 그 이슈를 수정·전이·삭제할 수 있었다
     * (댓글 수정·삭제 · 이슈 PATCH 포함). `IssuePermission` 8종을 전수 판정해 확대 범위를 정했다.
     *
     * | 권한 | 게이트 | 근거 |
     * |---|---|---|
     * | VIEW·UPDATE·TRANSITION·SOFT_DELETE·HARD_DELETE | ✅ | 이슈 내용 접근을 전제하는 조작 |
     * | BROWSE·CREATE | ❌ | 프로젝트 스코프 — 특정 이슈의 등급과 무관 |
     * | SET_SECURITY | ❌ | 아래 별도 테스트 참조 (락아웃 방지) |
     */
    @Test
    fun `UPDATE 도 보안 등급 게이트를 통과해야 한다 — 등급 멤버가 아니면 거부`() {
        every { projectDirectory.resolveKeyToId("ATLAS") } returns projectId
        every { membershipRepo.findByProjectAndUser(projectId, actor) } returns membership(ProjectRole.MEMBER)
        every { schemeRepo.roleHasPermission(projectId, "MEMBER", "EDIT_ISSUE") } returns true
        // 등급이 지정돼 있고 actor 는 그 등급의 멤버가 아니다.
        every { securityLookup.lookup("ATLAS-1") } returns
            IssueSecurityContext(securityLevelId = levelId, reporterId = otherUser, assigneeId = null)
        every { securitySchemeRepo.listMembers(levelId) } returns emptyList()

        assertThat(
            resolver.hasPermission(actor, IssuePermission.UPDATE, IssueScope.Issue("ATLAS-1")),
        ).isFalse()
    }

    /** TRANSITION 은 매트릭스 매핑이 없어 `code == null` 로 빠지는데, 그래도 등급 게이트는 타야 한다. */
    @Test
    fun `TRANSITION 도 보안 등급 게이트를 통과해야 한다 — 매트릭스 미매핑이어도`() {
        every { projectDirectory.resolveKeyToId("ATLAS") } returns projectId
        every { membershipRepo.findByProjectAndUser(projectId, actor) } returns membership(ProjectRole.MEMBER)
        every { securityLookup.lookup("ATLAS-1") } returns
            IssueSecurityContext(securityLevelId = levelId, reporterId = otherUser, assigneeId = null)
        every { securitySchemeRepo.listMembers(levelId) } returns emptyList()

        assertThat(
            resolver.hasPermission(actor, IssuePermission.TRANSITION, IssueScope.Issue("ATLAS-1")),
        ).isFalse()
    }

    /**
     * ★`SET_SECURITY` 는 **의도적으로 게이트에서 제외**한다 — 누락이 아니라 결정이다.
     *
     * 등급 변경 권한까지 게이트에 넣으면 **잘못 설정된 등급을 아무도 되돌릴 수 없다**
     * (등급 멤버가 아니라서 못 보고, 못 보니 못 고친다). 우회로 없는 락아웃이라 운영상 치명적이다.
     * 반대급부로 `SET_SECURITY` 보유자에게는 「등급을 자기가 볼 수 있는 것으로 바꾼 뒤 열람」 경로가
     * 남지만, 그 권한은 이미 높은 권한이고 현행도 동일하다.
     *
     * **이 테스트가 그 결정을 고정한다** — 없으면 다음 사람이 "빠뜨렸네" 하고 넣어 락아웃을 만든다.
     */
    @Test
    fun `SET_SECURITY 는 등급 게이트를 타지 않는다 — 락아웃 방지 의도적 제외`() {
        every { projectDirectory.resolveKeyToId("ATLAS") } returns projectId
        every { membershipRepo.findByProjectAndUser(projectId, actor) } returns membership(ProjectRole.MEMBER)
        every { schemeRepo.roleHasPermission(projectId, "MEMBER", "SET_ISSUE_SECURITY") } returns true

        // securityLookup 을 스텁하지 않는다 — 게이트를 탄다면 strict mock 이 터진다.
        // 즉 이 테스트는 "호출되지 않음" 을 mock 엄격성으로 증명한다.
        assertThat(
            resolver.hasPermission(actor, IssuePermission.SET_SECURITY, IssueScope.Issue("ATLAS-1")),
        ).isTrue()
    }

    /** 대조군 — 등급 멤버라면 UPDATE 가 통과한다. "전부 거부" 로 무너지지 않았음을 확인한다. */
    @Test
    fun `대조군 — 등급이 없는 이슈면 UPDATE 가 통과한다`() {
        every { projectDirectory.resolveKeyToId("ATLAS") } returns projectId
        every { membershipRepo.findByProjectAndUser(projectId, actor) } returns membership(ProjectRole.MEMBER)
        every { schemeRepo.roleHasPermission(projectId, "MEMBER", "EDIT_ISSUE") } returns true
        // securityLevelId == null → 공개 이슈, 게이트가 곧장 통과시킨다.
        every { securityLookup.lookup("ATLAS-1") } returns
            IssueSecurityContext(securityLevelId = null, reporterId = otherUser, assigneeId = null)

        assertThat(
            resolver.hasPermission(actor, IssuePermission.UPDATE, IssueScope.Issue("ATLAS-1")),
        ).isTrue()
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

    // ── (g) SET_SECURITY → 매트릭스 SET_ISSUE_SECURITY 위임 (멤버여도 false면 거부) ──

    @Test
    fun `SET_SECURITY는 매트릭스 SET_ISSUE_SECURITY 위임 — 멤버여도 매트릭스 false면 거부`() {
        every { projectDirectory.resolveKeyToId("ATLAS") } returns projectId
        every { membershipRepo.findByProjectAndUser(projectId, actor) } returns membership(ProjectRole.MEMBER)
        every { schemeRepo.roleHasPermission(projectId, "MEMBER", "SET_ISSUE_SECURITY") } returns false

        assertThat(
            resolver.hasPermission(actor, IssuePermission.SET_SECURITY, IssueScope.Issue("ATLAS-1")),
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
