// IssueResponse.resolution 노출 단위 테스트 — fr-is-07 Task B11 RED

package com.bts.issue.adapter.inbound.rest

import com.bts.issue.domain.ActorId
import com.bts.issue.domain.Issue
import com.bts.issue.domain.IssueId
import com.bts.issue.domain.IssueKey
import com.bts.shared.issue.IssueTypeId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * IssueResponse.resolution 필드 노출 검증.
 *
 * FR-IS-07 Task B11 — 단건 이슈 조회 응답에 nullable resolution 요약(id/key/name) 이 포함되어야 한다.
 *
 * ## 검증 시나리오
 * - B11-1. from(resolution = ResolutionSummary) 호출 시 resolution 필드가 채워진다.
 * - B11-2. from(resolution = null) 호출 시 resolution 필드가 null 이다.
 * - B11-3. resolution 이 채워진 응답의 id/key/name 이 주입값과 일치한다.
 */
class IssueResponseResolutionTest {
    private val fixedNow: Instant = Instant.parse("2026-05-24T00:00:00Z")

    private val sampleIssue =
        Issue(
            id = IssueId(UUID.fromString("00000000-0000-4000-8000-000000000001")),
            key = IssueKey("ATLAS-1"),
            projectId = UUID.fromString("00000000-0000-4000-8000-000000000002"),
            summary = "Resolution 노출 테스트",
            reporterId = ActorId(UUID.fromString("00000000-0000-4000-8000-000000000003")),
            currentStateKey = "done",
            version = 1L,
            deletedAt = null,
            createdAt = fixedNow,
            updatedAt = fixedNow,
            typeId = IssueTypeId(3L),
        )

    private val typeInfo = IssueResponse.IssueTypeInfo(id = 3L, key = "task", name = "Task")

    private val resolutionId = UUID.fromString("00000000-0000-4000-8000-000000000010")

    // ── B11-1. resolution 주입 시 채워진다 ─────────────────────────────────────

    @Test
    fun `B11-1 — from에 ResolutionSummary 주입 시 resolution 필드가 채워진다`() {
        val summary =
            IssueResponse.ResolutionSummary(
                id = resolutionId,
                key = "fixed",
                name = "Fixed",
            )

        val response = IssueResponse.from(sampleIssue, "ATLAS", typeInfo, resolution = summary)

        assertNotNull(response.resolution)
    }

    // ── B11-2. resolution null 이면 null ────────────────────────────────────────

    @Test
    fun `B11-2 — from에 resolution=null 전달 시 resolution 필드가 null 이다`() {
        val response = IssueResponse.from(sampleIssue, "ATLAS", typeInfo, resolution = null)

        assertNull(response.resolution)
    }

    // ── B11-3. resolution id/key/name 일치 ──────────────────────────────────────

    @Test
    fun `B11-3 — from에 주입한 ResolutionSummary 의 id, key, name 이 응답에 그대로 반영된다`() {
        val summary =
            IssueResponse.ResolutionSummary(
                id = resolutionId,
                key = "fixed",
                name = "Fixed",
            )

        val response = IssueResponse.from(sampleIssue, "ATLAS", typeInfo, resolution = summary)

        assertEquals(resolutionId, response.resolution?.id)
        assertEquals("fixed", response.resolution?.key)
        assertEquals("Fixed", response.resolution?.name)
    }
}
