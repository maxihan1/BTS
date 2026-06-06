// IssueResponse.securityLevelId 노출 단위 테스트 — FR-PM-06 PR-B BE-1 RED

package com.bts.issue.adapter.inbound.rest

import com.bts.issue.domain.ActorId
import com.bts.issue.domain.Issue
import com.bts.issue.domain.IssueId
import com.bts.issue.domain.IssueKey
import com.bts.shared.issue.IssueTypeId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * IssueResponse.securityLevelId 필드 노출 검증.
 *
 * FR-PM-06 PR-B BE-1 — 이슈 단건 응답에 현재 보안 등급 UUID가 포함되어야
 * 편집 화면이 현재 등급을 올바르게 표시할 수 있다.
 *
 * 검증 시나리오.
 * - SL-1. securityLevelId 가 지정된 Issue 로 from 호출 시 동일 UUID 가 응답에 포함된다.
 * - SL-2. securityLevelId 가 null 인 Issue 로 from 호출 시 응답도 null 이다.
 */
class IssueResponseSecurityLevelTest {
    private val fixedNow: Instant = Instant.parse("2026-06-06T00:00:00Z")
    private val typeInfo = IssueResponse.IssueTypeInfo(id = 3L, key = "task", name = "Task")

    private fun buildIssue(securityLevelId: UUID?) =
        Issue(
            id = IssueId(UUID.fromString("00000000-0000-4000-8000-000000000001")),
            key = IssueKey("ATLAS-1"),
            projectId = UUID.fromString("00000000-0000-4000-8000-000000000002"),
            summary = "보안 등급 노출 테스트",
            reporterId = ActorId(UUID.fromString("00000000-0000-4000-8000-000000000003")),
            currentStateKey = "open",
            version = 1L,
            deletedAt = null,
            createdAt = fixedNow,
            updatedAt = fixedNow,
            typeId = IssueTypeId(3L),
            securityLevelId = securityLevelId,
        )

    // ── SL-1. securityLevelId 지정 시 응답에 포함 ────────────────────────────

    @Test
    fun `SL-1 — securityLevelId 가 지정된 Issue from 결과에 동일 UUID 가 포함된다`() {
        val levelId = UUID.fromString("00000000-0000-4000-8000-000000000010")
        val response = IssueResponse.from(buildIssue(levelId), "ATLAS", typeInfo)

        assertEquals(levelId, response.securityLevelId)
    }

    // ── SL-2. securityLevelId null 이면 응답도 null ──────────────────────────

    @Test
    fun `SL-2 — securityLevelId 가 null 인 Issue from 결과 securityLevelId 가 null 이다`() {
        val response = IssueResponse.from(buildIssue(null), "ATLAS", typeInfo)

        assertNull(response.securityLevelId)
    }
}
