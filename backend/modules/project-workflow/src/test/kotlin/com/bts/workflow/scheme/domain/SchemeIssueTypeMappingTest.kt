// SchemeIssueTypeMapping Entity 단위 테스트 — nullable issueTypeId + UUID workflowId + equals
package com.bts.workflow.scheme.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class SchemeIssueTypeMappingTest {

    private val fixedSchemeId = WorkflowSchemeId(1L)
    private val fixedIssueTypeId = IssueTypeId(10L)
    private val fixedWorkflowId = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private val fixedInstant = Instant.parse("2026-05-26T00:00:00Z")

    @Test
    fun `issueTypeId non-null 인스턴스화 후 모든 프로퍼티가 올바르게 반환된다`() {
        val mapping = SchemeIssueTypeMapping(
            id = 100L,
            schemeId = fixedSchemeId,
            issueTypeId = fixedIssueTypeId,
            workflowId = fixedWorkflowId,
            createdAt = fixedInstant,
        )

        assertEquals(100L, mapping.id)
        assertEquals(fixedSchemeId, mapping.schemeId)
        assertEquals(fixedIssueTypeId, mapping.issueTypeId)
        assertEquals(fixedWorkflowId, mapping.workflowId)
        assertEquals(fixedInstant, mapping.createdAt)
    }

    @Test
    fun `issueTypeId null 인스턴스화 — NULL 은 default mapping 을 의미한다`() {
        val mapping = SchemeIssueTypeMapping(
            id = 101L,
            schemeId = fixedSchemeId,
            issueTypeId = null,
            workflowId = fixedWorkflowId,
            createdAt = fixedInstant,
        )

        assertNull(mapping.issueTypeId)
    }

    @Test
    fun `id null 인스턴스화 — 저장 전 상태`() {
        val mapping = SchemeIssueTypeMapping(
            id = null,
            schemeId = fixedSchemeId,
            issueTypeId = fixedIssueTypeId,
            workflowId = fixedWorkflowId,
            createdAt = fixedInstant,
        )

        assertNull(mapping.id)
    }

    @Test
    fun `동일한 필드 값을 가진 두 인스턴스는 equals 가 true 이다`() {
        val a = SchemeIssueTypeMapping(
            id = 1L,
            schemeId = fixedSchemeId,
            issueTypeId = fixedIssueTypeId,
            workflowId = fixedWorkflowId,
            createdAt = fixedInstant,
        )
        val b = SchemeIssueTypeMapping(
            id = 1L,
            schemeId = fixedSchemeId,
            issueTypeId = fixedIssueTypeId,
            workflowId = fixedWorkflowId,
            createdAt = fixedInstant,
        )

        assertEquals(a, b)
    }

    @Test
    fun `issueTypeId 가 다르면 equals 가 false 이다`() {
        val a = SchemeIssueTypeMapping(
            id = 1L,
            schemeId = fixedSchemeId,
            issueTypeId = IssueTypeId(10L),
            workflowId = fixedWorkflowId,
            createdAt = fixedInstant,
        )
        val b = a.copy(issueTypeId = IssueTypeId(20L))

        assertNotEquals(a, b)
    }

    @Test
    fun `issueTypeId null vs non-null 은 equals 가 false 이다`() {
        val withIssueType = SchemeIssueTypeMapping(
            id = 1L,
            schemeId = fixedSchemeId,
            issueTypeId = fixedIssueTypeId,
            workflowId = fixedWorkflowId,
            createdAt = fixedInstant,
        )
        val withoutIssueType = withIssueType.copy(issueTypeId = null)

        assertNotEquals(withIssueType, withoutIssueType)
    }

    @Test
    fun `workflowId 가 다르면 equals 가 false 이다`() {
        val a = SchemeIssueTypeMapping(
            id = 1L,
            schemeId = fixedSchemeId,
            issueTypeId = fixedIssueTypeId,
            workflowId = UUID.fromString("00000000-0000-0000-0000-000000000001"),
            createdAt = fixedInstant,
        )
        val b = a.copy(workflowId = UUID.fromString("00000000-0000-0000-0000-000000000002"))

        assertNotEquals(a, b)
    }

    @Test
    fun `schemeId 가 다르면 equals 가 false 이다`() {
        val a = SchemeIssueTypeMapping(
            id = 1L,
            schemeId = WorkflowSchemeId(1L),
            issueTypeId = fixedIssueTypeId,
            workflowId = fixedWorkflowId,
            createdAt = fixedInstant,
        )
        val b = a.copy(schemeId = WorkflowSchemeId(2L))

        assertNotEquals(a, b)
    }
}
