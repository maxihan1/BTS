// ProjectWorkflowSchemeAssignment Aggregate Root 단위 테스트
package com.bts.workflow.scheme.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class ProjectWorkflowSchemeAssignmentTest {
    private val fixedProjectId = 1L
    private val fixedSchemeId = WorkflowSchemeId(10L)
    private val fixedInstant = Instant.parse("2026-05-26T00:00:00Z")
    private val fixedUserId = UUID.fromString("00000000-0000-0000-0000-000000000001")

    @Test
    fun `4 필드 인스턴스화 후 각 프로퍼티가 올바르게 반환된다`() {
        val assignment =
            ProjectWorkflowSchemeAssignment(
                projectId = fixedProjectId,
                workflowSchemeId = fixedSchemeId,
                assignedAt = fixedInstant,
                assignedBy = fixedUserId,
            )

        assertEquals(fixedProjectId, assignment.projectId)
        assertEquals(fixedSchemeId, assignment.workflowSchemeId)
        assertEquals(fixedInstant, assignment.assignedAt)
        assertEquals(fixedUserId, assignment.assignedBy)
    }

    @Test
    fun `동일한 필드 값을 가진 두 인스턴스는 equals 가 true 이다`() {
        val a =
            ProjectWorkflowSchemeAssignment(
                projectId = fixedProjectId,
                workflowSchemeId = fixedSchemeId,
                assignedAt = fixedInstant,
                assignedBy = fixedUserId,
            )
        val b =
            ProjectWorkflowSchemeAssignment(
                projectId = fixedProjectId,
                workflowSchemeId = fixedSchemeId,
                assignedAt = fixedInstant,
                assignedBy = fixedUserId,
            )

        assertEquals(a, b)
    }

    @Test
    fun `projectId 가 다르면 equals 가 false 이다`() {
        val a =
            ProjectWorkflowSchemeAssignment(
                projectId = 1L,
                workflowSchemeId = fixedSchemeId,
                assignedAt = fixedInstant,
                assignedBy = fixedUserId,
            )
        val b = a.copy(projectId = 2L)

        assertNotEquals(a, b)
    }

    @Test
    fun `workflowSchemeId 가 다르면 equals 가 false 이다`() {
        val a =
            ProjectWorkflowSchemeAssignment(
                projectId = fixedProjectId,
                workflowSchemeId = WorkflowSchemeId(10L),
                assignedAt = fixedInstant,
                assignedBy = fixedUserId,
            )
        val b = a.copy(workflowSchemeId = WorkflowSchemeId(20L))

        assertNotEquals(a, b)
    }
}
