// ProjectRole.from() 정규화 규칙 + ProjectMembership 생성 단위 테스트 (FR-PM-01 Task 1)

package com.atlas.bts.identity.project

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class ProjectMembershipTest {

    private val projectId = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001")
    private val userId = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002")
    private val now = Instant.parse("2026-06-01T00:00:00Z")

    // ── ProjectRole.from — 정상 케이스 ────────────────────────────

    @Test
    fun `from — PROJECT_ADMIN 반환`() {
        assertEquals(ProjectRole.PROJECT_ADMIN, ProjectRole.from("PROJECT_ADMIN"))
    }

    @Test
    fun `from — MEMBER 반환`() {
        assertEquals(ProjectRole.MEMBER, ProjectRole.from("MEMBER"))
    }

    // ── ProjectRole.from — 거부 케이스 (IllegalArgumentException) ──

    @Test
    fun `from — owner 는 허용하지 않는다`() {
        assertThrows(IllegalArgumentException::class.java) { ProjectRole.from("owner") }
    }

    @Test
    fun `from — 소문자 member 는 허용하지 않는다`() {
        assertThrows(IllegalArgumentException::class.java) { ProjectRole.from("member") }
    }

    @Test
    fun `from — 빈 문자열은 허용하지 않는다`() {
        assertThrows(IllegalArgumentException::class.java) { ProjectRole.from("") }
    }

    // ── ProjectMembership 생성 + 필드 확인 ───────────────────────

    @Test
    fun `ProjectMembership 생성 후 필드가 정상 노출된다`() {
        val membership = ProjectMembership(
            projectId = projectId,
            userId = userId,
            role = ProjectRole.MEMBER,
            createdAt = now,
            updatedAt = now,
        )

        assertEquals(projectId, membership.projectId)
        assertEquals(userId, membership.userId)
        assertEquals(ProjectRole.MEMBER, membership.role)
        assertEquals(now, membership.createdAt)
        assertEquals(now, membership.updatedAt)
    }

    @Test
    fun `ProjectMembership — PROJECT_ADMIN 역할 보유 확인`() {
        val membership = ProjectMembership(
            projectId = projectId,
            userId = userId,
            role = ProjectRole.PROJECT_ADMIN,
            createdAt = now,
            updatedAt = now,
        )

        assertEquals(ProjectRole.PROJECT_ADMIN, membership.role)
    }
}
