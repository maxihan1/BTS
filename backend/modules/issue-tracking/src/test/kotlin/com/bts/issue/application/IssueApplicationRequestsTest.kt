// IssueApplicationRequests의 DatePatch 3-state와 UpdateIssueRequest 날짜 필드 계약을 검증하는 단위 테스트

package com.bts.issue.application

import java.time.LocalDate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

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
        val patches: List<DatePatch> = listOf(
            DatePatch.Unchanged,
            DatePatch.Clear,
            DatePatch.Set(LocalDate.of(2026, 1, 1)),
        )
        val labels = patches.map { patch ->
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
        val req = UpdateIssueRequest(
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
}
