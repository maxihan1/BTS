// IssueApplicationRequests의 3-state 표현(DatePatch·AssigneeIntent)과 요청 DTO 기본값 계약을 검증하는 단위 테스트

package com.bts.issue.application

import com.bts.issue.domain.ActorId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.UUID

class IssueApplicationRequestsTest {
    // ──────────────────────────────────────────────────────────────────────
    // DatePatch 3-state 존재 검증
    // ──────────────────────────────────────────────────────────────────────

    @Test
    fun `DatePatch Unchanged는 별개의 singleton 객체다`() {
        val patch: DatePatch = DatePatch.Unchanged
        assertInstanceOf(DatePatch.Unchanged::class.java, patch)
    }

    @Test
    fun `DatePatch Clear는 별개의 singleton 객체다`() {
        val patch: DatePatch = DatePatch.Clear
        assertInstanceOf(DatePatch.Clear::class.java, patch)
    }

    @Test
    fun `DatePatch Set은 LocalDate 값을 보유한다`() {
        val date = LocalDate.of(2026, 6, 19)
        val patch: DatePatch = DatePatch.Set(date)
        assertInstanceOf(DatePatch.Set::class.java, patch)
        assertEquals(date, (patch as DatePatch.Set).value)
    }

    @Test
    fun `DatePatch when 식이 else 없이 완전성을 보장한다`() {
        val patches: List<DatePatch> =
            listOf(
                DatePatch.Unchanged,
                DatePatch.Clear,
                DatePatch.Set(LocalDate.of(2026, 1, 1)),
            )
        val labels =
            patches.map { patch ->
                when (patch) {
                    is DatePatch.Unchanged -> "unchanged"
                    is DatePatch.Clear -> "clear"
                    is DatePatch.Set -> "set:${patch.value}"
                }
            }
        assertEquals(listOf("unchanged", "clear", "set:2026-01-01"), labels)
    }

    // ──────────────────────────────────────────────────────────────────────
    // App UpdateIssueRequest 날짜 필드 기본값 검증
    // ──────────────────────────────────────────────────────────────────────

    @Test
    fun `UpdateIssueRequest는 startDate 필드를 DatePatch Unchanged 기본값으로 보유한다`() {
        val req = UpdateIssueRequest(summary = null, expectedVersion = 1L)
        assertInstanceOf(DatePatch.Unchanged::class.java, req.startDate)
    }

    @Test
    fun `UpdateIssueRequest는 dueDate 필드를 DatePatch Unchanged 기본값으로 보유한다`() {
        val req = UpdateIssueRequest(summary = null, expectedVersion = 1L)
        assertInstanceOf(DatePatch.Unchanged::class.java, req.dueDate)
    }

    @Test
    fun `UpdateIssueRequest는 targetDate 필드를 DatePatch Unchanged 기본값으로 보유한다`() {
        val req = UpdateIssueRequest(summary = null, expectedVersion = 1L)
        assertInstanceOf(DatePatch.Unchanged::class.java, req.targetDate)
    }

    @Test
    fun `UpdateIssueRequest 날짜 3필드에 각각 다른 DatePatch를 지정할 수 있다`() {
        val startDate = LocalDate.of(2026, 6, 1)
        val req =
            UpdateIssueRequest(
                summary = null,
                expectedVersion = 1L,
                startDate = DatePatch.Set(startDate),
                dueDate = DatePatch.Clear,
                targetDate = DatePatch.Unchanged,
            )
        assertEquals(DatePatch.Set(startDate), req.startDate)
        assertInstanceOf(DatePatch.Clear::class.java, req.dueDate)
        assertInstanceOf(DatePatch.Unchanged::class.java, req.targetDate)
    }

    // ──────────────────────────────────────────────────────────────────────
    // AssigneeIntent 3-state 존재 검증 (FR-UX-09 B1 · ADR D-2)
    // ──────────────────────────────────────────────────────────────────────

    @Test
    fun `AssigneeIntent Auto는 별개의 singleton 객체다`() {
        val intent: AssigneeIntent = AssigneeIntent.Auto
        assertInstanceOf(AssigneeIntent.Auto::class.java, intent)
    }

    @Test
    fun `AssigneeIntent None은 별개의 singleton 객체다`() {
        val intent: AssigneeIntent = AssigneeIntent.None
        assertInstanceOf(AssigneeIntent.None::class.java, intent)
    }

    @Test
    fun `AssigneeIntent User는 담당자 UUID를 보유한다`() {
        val userId = UUID.randomUUID()
        val intent: AssigneeIntent = AssigneeIntent.User(userId)
        assertInstanceOf(AssigneeIntent.User::class.java, intent)
        assertEquals(userId, (intent as AssigneeIntent.User).userId)
    }

    @Test
    fun `AssigneeIntent when 식이 else 없이 완전성을 보장한다`() {
        val userId = UUID.randomUUID()
        val intents: List<AssigneeIntent> =
            listOf(AssigneeIntent.Auto, AssigneeIntent.None, AssigneeIntent.User(userId))
        val labels =
            intents.map { intent ->
                when (intent) {
                    is AssigneeIntent.Auto -> "auto"
                    is AssigneeIntent.None -> "none"
                    is AssigneeIntent.User -> "user:${intent.userId}"
                }
            }
        assertEquals(listOf("auto", "none", "user:$userId"), labels)
    }

    // ──────────────────────────────────────────────────────────────────────
    // App CreateIssueRequest 신규 3필드 + notifyAssignment 기본값 검증
    //
    // ★이 블록이 「신규 생산자가 알림을 조용히 켜지 못한다」(ADR D-5 fail-safe)의 회귀 가드다.
    //   notifyAssignment 의 기본값이 true 로 뒤집히면 여기서 red 가 난다.
    // ──────────────────────────────────────────────────────────────────────

    private fun minimalCreateRequest() =
        CreateIssueRequest(
            projectKey = "BTS",
            summary = "제목",
            reporterId = ActorId(UUID.randomUUID()),
        )

    @Test
    fun `CreateIssueRequest는 assignee를 AssigneeIntent Auto 기본값으로 보유한다`() {
        assertInstanceOf(AssigneeIntent.Auto::class.java, minimalCreateRequest().assignee)
    }

    @Test
    fun `CreateIssueRequest는 priority 기본값이 null이다`() {
        assertNull(minimalCreateRequest().priority)
    }

    @Test
    fun `CreateIssueRequest는 labels 기본값이 null이다`() {
        assertNull(minimalCreateRequest().labels)
    }

    @Test
    fun `CreateIssueRequest는 notifyAssignment 기본값이 false다`() {
        assertFalse(minimalCreateRequest().notifyAssignment)
    }

    @Test
    fun `CreateIssueRequest 신규 4필드를 각각 지정할 수 있다`() {
        val userId = UUID.randomUUID()
        val req =
            CreateIssueRequest(
                projectKey = "BTS",
                summary = "제목",
                reporterId = ActorId(UUID.randomUUID()),
                assignee = AssigneeIntent.User(userId),
                priority = 1,
                labels = listOf("urgent"),
                notifyAssignment = true,
            )
        assertEquals(AssigneeIntent.User(userId), req.assignee)
        assertEquals(1, req.priority)
        assertEquals(listOf("urgent"), req.labels)
        assertEquals(true, req.notifyAssignment)
    }
}
