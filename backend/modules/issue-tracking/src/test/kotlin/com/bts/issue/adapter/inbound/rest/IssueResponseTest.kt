// IssueResponse DTO 매핑 검증 — Issue Aggregate → REST 응답 변환 단위 테스트

package com.bts.issue.adapter.inbound.rest

import com.bts.issue.domain.ActorId
import com.bts.issue.domain.Issue
import com.bts.issue.domain.IssueId
import com.bts.issue.domain.IssueKey
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class IssueResponseTest {
    private val fixedNow: Instant = Instant.parse("2026-05-24T00:00:00Z")

    private val sampleIssue =
        Issue(
            id = IssueId(UUID.fromString("00000000-0000-0000-0000-000000000001")),
            key = IssueKey("ATLAS-1"),
            projectId = UUID.fromString("00000000-0000-0000-0000-000000000002"),
            summary = "Sample issue",
            reporterId = ActorId(UUID.fromString("00000000-0000-0000-0000-000000000003")),
            currentStateKey = "open",
            version = 1L,
            deletedAt = null,
            createdAt = fixedNow,
            updatedAt = fixedNow,
        )

    @Test
    fun `from — Issue 의 모든 필드가 IssueResponse 에 올바르게 매핑된다`() {
        val response = IssueResponse.from(sampleIssue, "ATLAS")

        assertEquals("ATLAS-1", response.key)
        assertEquals(UUID.fromString("00000000-0000-0000-0000-000000000001"), response.id)
        assertEquals("ATLAS", response.projectKey)
        assertEquals("Sample issue", response.summary)
        assertEquals("open", response.currentStateKey)
        assertEquals(UUID.fromString("00000000-0000-0000-0000-000000000003"), response.reporterId)
        assertEquals(1L, response.version)
        assertEquals(fixedNow, response.createdAt)
        assertEquals(fixedNow, response.updatedAt)
    }

    @Test
    fun `from — 다른 projectKey 를 주면 그 값이 그대로 반영된다`() {
        val response = IssueResponse.from(sampleIssue, "BETA")

        assertEquals("BETA", response.projectKey)
    }
}
