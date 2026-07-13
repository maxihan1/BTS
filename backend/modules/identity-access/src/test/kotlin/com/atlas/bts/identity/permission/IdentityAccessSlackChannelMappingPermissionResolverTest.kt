// IdentityAccessSlackChannelMappingPermissionResolver 단위테스트 — 키 해석 + PROJECT_ADMIN 역할 직접 확인 (FR-SL-06 Task 8)

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
 * [IdentityAccessSlackChannelMappingPermissionResolver] 단위테스트 (FR-SL-06 Task 8).
 *
 * ## 검증 시나리오 (plan Task 8 RED)
 * (a) PROJECT_ADMIN 멤버 → true (채널 매핑 관리 허용).
 * (b) MEMBER 멤버 → false (일반 멤버는 관리 불가, fail-closed).
 * (c) 비멤버(findByProjectAndUser null) → false (멤버 게이트, fail-closed).
 * (d) 미해석 키(resolveKeyToId null) → false (fail-closed). 멤버십 조회 미호출 verify.
 *
 * fail-closed 하한: 미해석 키·비멤버·비관리자는 모두 거부(false)로 수렴한다
 * (cross-BC resolver fail-open 회귀 방지 — nullable+?:return 이 아니라 non-null 주입 + 명시 거부).
 *
 * 판정 로직은 [com.atlas.bts.identity.issuesecurity.ProjectSecuritySchemeService] `requireProjectAdmin`
 * 패턴 미러 — `role == PROJECT_ADMIN` 직접 확인(신규 권한 코드 시드 없음). 어댑터 구조는
 * [IdentityAccessAutomationPermissionResolver] 미러(@Component @Profile prod). MockK 로 협력자를 격리한다.
 */
class IdentityAccessSlackChannelMappingPermissionResolverTest {
    private val projectDirectory: ProjectDirectory = mockk()
    private val membershipRepo: ProjectMembershipRepository = mockk()

    private val resolver =
        IdentityAccessSlackChannelMappingPermissionResolver(
            projectDirectory = projectDirectory,
            membershipRepo = membershipRepo,
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

    // ── (a) PROJECT_ADMIN → 허용 ──────────────────────────────────────────────

    @Test
    fun `PROJECT_ADMIN 멤버는 채널 매핑 관리 허용`() {
        every { projectDirectory.resolveKeyToId(projectKey) } returns projectId
        every { membershipRepo.findByProjectAndUser(projectId, actor) } returns membership(ProjectRole.PROJECT_ADMIN)

        assertThat(resolver.hasManageChannelMapping(actor, projectKey)).isTrue()
    }

    // ── (b) MEMBER → 거부 (fail-closed) ───────────────────────────────────────

    @Test
    fun `MEMBER 멤버는 거부 — PROJECT_ADMIN 아님`() {
        every { projectDirectory.resolveKeyToId(projectKey) } returns projectId
        every { membershipRepo.findByProjectAndUser(projectId, actor) } returns membership(ProjectRole.MEMBER)

        assertThat(resolver.hasManageChannelMapping(actor, projectKey)).isFalse()
    }

    // ── (c) 비멤버 → 거부 (멤버 게이트) ────────────────────────────────────────

    @Test
    fun `비멤버는 거부 — 멤버십 없음`() {
        every { projectDirectory.resolveKeyToId(projectKey) } returns projectId
        every { membershipRepo.findByProjectAndUser(projectId, actor) } returns null

        assertThat(resolver.hasManageChannelMapping(actor, projectKey)).isFalse()
    }

    // ── (d) 미해석 키 → 거부 (fail-closed, 멤버십 미호출) ───────────────────────

    @Test
    fun `미해석 프로젝트 키는 거부 — fail-closed, 멤버십 조회 미호출`() {
        every { projectDirectory.resolveKeyToId("NOPE") } returns null

        assertThat(resolver.hasManageChannelMapping(actor, "NOPE")).isFalse()

        verify(exactly = 0) { membershipRepo.findByProjectAndUser(any(), any()) }
    }
}
