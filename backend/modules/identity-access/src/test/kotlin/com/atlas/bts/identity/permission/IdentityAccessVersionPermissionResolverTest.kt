// IdentityAccessVersionPermissionResolver 단위테스트 — 멤버 게이트 + 매트릭스 판정 (FR-PM-03 Task 3)

package com.atlas.bts.identity.permission

import com.atlas.bts.identity.project.ProjectMembership
import com.atlas.bts.identity.project.ProjectMembershipRepository
import com.atlas.bts.identity.project.ProjectRole
import com.bts.shared.permission.VersionPermission
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * [IdentityAccessVersionPermissionResolver] 단위테스트.
 *
 * ## 검증 시나리오 (plan Task 3 RED)
 * (a) 비멤버(findByProjectAndUser null) → CREATE/UPDATE/DELETE 모두 false (멤버 게이트).
 *     schemeRepo 미호출 검증(verify exactly=0).
 * (b) PROJECT_ADMIN 멤버 + schemeRepo true → CREATE/UPDATE/DELETE 모두 true.
 *     각 권한이 "MANAGE_VERSIONS" 코드로 roleHasPermission 호출되는지 verify (코드 매핑 검증).
 * (c) MEMBER 멤버 + schemeRepo false → CREATE/UPDATE/DELETE 모두 false (매트릭스 거부).
 *
 * MockK로 의존성을 격리하여 adapter 로직만 검증한다.
 * 선례: [IdentityAccessComponentPermissionResolverTest]. 포트가 projectId를 직접 받으므로
 * ProjectDirectory/scope 해석이 없다.
 */
class IdentityAccessVersionPermissionResolverTest {
    private val membershipRepo: ProjectMembershipRepository = mockk()
    private val schemeRepo: PermissionSchemeRepository = mockk()

    private val resolver =
        IdentityAccessVersionPermissionResolver(
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

    // ── (a) 비멤버 → 모든 권한 거부 (멤버 게이트) ────────────────────────────

    @Test
    fun `비멤버는 CREATE_UPDATE_DELETE 모두 거부 — 멤버 게이트`() {
        every { membershipRepo.findByProjectAndUser(projectId, actor) } returns null

        VersionPermission.entries.forEach { permission ->
            assertThat(resolver.hasPermission(actor, permission, projectId)).isFalse()
        }

        verify(exactly = 0) { schemeRepo.roleHasPermission(any(), any(), any()) }
    }

    // ── (b) PROJECT_ADMIN + 매트릭스 true → 허용 + 코드 매핑 검증 ──────────────

    @Test
    fun `PROJECT_ADMIN 멤버는 CREATE_UPDATE_DELETE 모두 허용 — MANAGE_VERSIONS 코드로 판정`() {
        every { membershipRepo.findByProjectAndUser(projectId, actor) } returns membership(ProjectRole.PROJECT_ADMIN)
        every {
            schemeRepo.roleHasPermission(projectId, "PROJECT_ADMIN", "MANAGE_VERSIONS")
        } returns true

        VersionPermission.entries.forEach { permission ->
            assertThat(resolver.hasPermission(actor, permission, projectId)).isTrue()
        }

        // CREATE/UPDATE/DELETE 3종 모두 동일 코드("MANAGE_VERSIONS")로 매핑되어 3회 호출.
        verify(exactly = 3) {
            schemeRepo.roleHasPermission(projectId, "PROJECT_ADMIN", "MANAGE_VERSIONS")
        }
    }

    // ── (c) MEMBER + 매트릭스 false → 거부 ───────────────────────────────────

    @Test
    fun `MEMBER 멤버는 CREATE_UPDATE_DELETE 모두 거부 — 매트릭스 미보유`() {
        every { membershipRepo.findByProjectAndUser(projectId, actor) } returns membership(ProjectRole.MEMBER)
        every {
            schemeRepo.roleHasPermission(projectId, "MEMBER", "MANAGE_VERSIONS")
        } returns false

        VersionPermission.entries.forEach { permission ->
            assertThat(resolver.hasPermission(actor, permission, projectId)).isFalse()
        }
    }
}
