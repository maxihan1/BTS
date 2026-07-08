// cross-BC 개인 캘린더 조회 포트 계약 검증 — UserCalendarLookupPort·CalendarIssueView·CalendarWorklogView

package com.bts.shared.calendar

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * cross-BC 개인 캘린더 조회 포트 계약 테스트.
 *
 * Spring 컨텍스트 없이 순수 단위 테스트로 실행한다.
 *
 * 검증 항목.
 * - [CalendarIssueView] / [CalendarWorklogView] VO 필드 구성 — 필수/선택 필드, nullable 보존.
 * - [UserCalendarLookupPort.listAssignedScheduledIssues] default 구현이 빈 [CalendarIssuePage] 를 반환함(fail-safe).
 * - [UserCalendarLookupPort.listWorklogs] default 구현이 빈 [CalendarWorklogPage] 를 반환함(fail-safe).
 * - adapter 가 미등록된 환경에서 빈 페이지를 반환해 캘린더가 안전하게 표시된다.
 */
class UserCalendarLookupPortTest {
    // ── CalendarIssueView 필드 계약 ──────────────────────────────────────────

    @Test
    fun `CalendarIssueView 는 필수 필드를 모두 보존한다`() {
        val view =
            CalendarIssueView(
                key = "PROJ-1",
                summary = "로그인 버그 수정",
                issueType = "bug",
                currentStateKey = "in-progress",
                startDate = LocalDate.of(2026, 6, 1),
                dueDate = LocalDate.of(2026, 6, 30),
            )

        assertThat(view.key).isEqualTo("PROJ-1")
        assertThat(view.summary).isEqualTo("로그인 버그 수정")
        assertThat(view.issueType).isEqualTo("bug")
        assertThat(view.currentStateKey).isEqualTo("in-progress")
        assertThat(view.startDate).isEqualTo(LocalDate.of(2026, 6, 1))
        assertThat(view.dueDate).isEqualTo(LocalDate.of(2026, 6, 30))
    }

    @Test
    fun `CalendarIssueView 는 startDate 나 dueDate 중 한쪽만 있어도 된다`() {
        val startOnly =
            CalendarIssueView(
                key = "PROJ-2",
                summary = "시작일만 있음",
                issueType = "task",
                currentStateKey = "open",
                startDate = LocalDate.of(2026, 7, 1),
                dueDate = null,
            )
        val dueOnly =
            CalendarIssueView(
                key = "PROJ-3",
                summary = "마감일만 있음",
                issueType = "story",
                currentStateKey = "open",
                startDate = null,
                dueDate = LocalDate.of(2026, 7, 31),
            )

        assertThat(startOnly.startDate).isNotNull()
        assertThat(startOnly.dueDate).isNull()
        assertThat(dueOnly.startDate).isNull()
        assertThat(dueOnly.dueDate).isNotNull()
    }

    // ── UserCalendarLookupPort.listAssignedScheduledIssues fail-safe default ──

    @Test
    fun `listAssignedScheduledIssues default 구현은 빈 CalendarIssuePage 를 반환한다`() {
        // adapter 가 미등록된 환경에서 빈 페이지를 반환해 캘린더가 안전하게 표시된다.
        val port = object : UserCalendarLookupPort {}
        val result =
            port.listAssignedScheduledIssues(
                userId = UUID.randomUUID(),
                from = LocalDate.of(2026, 7, 1),
                to = LocalDate.of(2026, 7, 31),
            )

        assertThat(result.items).isEmpty()
        assertThat(result.truncated).isFalse()
    }

    @Test
    fun `listAssignedScheduledIssues default 는 userId 나 기간이 달라도 빈 페이지를 반환한다`() {
        val port = object : UserCalendarLookupPort {}

        assertThat(
            port
                .listAssignedScheduledIssues(UUID.randomUUID(), LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31))
                .items,
        ).isEmpty()
        assertThat(
            port
                .listAssignedScheduledIssues(UUID.randomUUID(), LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 31))
                .items,
        ).isEmpty()
    }

    // ── CalendarIssuePage 계약 ────────────────────────────────────────────────

    @Test
    fun `CalendarIssuePage 는 items 목록과 truncated 플래그를 보존한다`() {
        val item =
            CalendarIssueView(
                key = "PROJ-10",
                summary = "기간이 있는 이슈",
                issueType = "task",
                currentStateKey = "in-progress",
                startDate = LocalDate.of(2026, 6, 1),
                dueDate = LocalDate.of(2026, 6, 15),
            )
        val page = CalendarIssuePage(items = listOf(item), truncated = true)

        assertThat(page.items).containsExactly(item)
        assertThat(page.truncated).isTrue()
    }

    @Test
    fun `CalendarIssuePage truncated=false 는 LIMIT 미초과 정상 조회를 나타낸다`() {
        val page = CalendarIssuePage(items = emptyList(), truncated = false)

        assertThat(page.items).isEmpty()
        assertThat(page.truncated).isFalse()
    }

    // ── CalendarWorklogView 필드 계약 ─────────────────────────────────────────

    @Test
    fun `CalendarWorklogView 는 필수 필드를 모두 보존한다`() {
        val id = UUID.randomUUID()
        val startedAt = Instant.parse("2026-06-01T09:00:00Z")
        val view =
            CalendarWorklogView(
                id = id,
                issueKey = "PROJ-1",
                issueSummary = "로그인 버그 수정",
                startedAt = startedAt,
                timeSpentSeconds = 3600,
            )

        assertThat(view.id).isEqualTo(id)
        assertThat(view.issueKey).isEqualTo("PROJ-1")
        assertThat(view.issueSummary).isEqualTo("로그인 버그 수정")
        assertThat(view.startedAt).isEqualTo(startedAt)
        assertThat(view.timeSpentSeconds).isEqualTo(3600)
    }

    @Test
    fun `CalendarWorklogView 는 issueSummary 가 null 일 수 있다 (비가시 이슈 마스킹)`() {
        val view =
            CalendarWorklogView(
                id = UUID.randomUUID(),
                issueKey = "PROJ-99",
                issueSummary = null,
                startedAt = Instant.parse("2026-06-01T09:00:00Z"),
                timeSpentSeconds = 1800,
            )

        assertThat(view.issueSummary).isNull()
    }

    // ── UserCalendarLookupPort.listWorklogs fail-safe default ─────────────────

    @Test
    fun `listWorklogs default 구현은 빈 CalendarWorklogPage 를 반환한다`() {
        val port = object : UserCalendarLookupPort {}
        val result =
            port.listWorklogs(
                userId = UUID.randomUUID(),
                fromInstant = Instant.parse("2026-07-01T00:00:00Z"),
                toInstant = Instant.parse("2026-07-31T23:59:59Z"),
            )

        assertThat(result.items).isEmpty()
        assertThat(result.truncated).isFalse()
    }

    // ── CalendarWorklogPage 계약 ──────────────────────────────────────────────

    @Test
    fun `CalendarWorklogPage 는 items 목록과 truncated 플래그를 보존한다`() {
        val worklog =
            CalendarWorklogView(
                id = UUID.randomUUID(),
                issueKey = "PROJ-20",
                issueSummary = "작업 기록",
                startedAt = Instant.parse("2026-06-10T10:00:00Z"),
                timeSpentSeconds = 7200,
            )
        val page = CalendarWorklogPage(items = listOf(worklog), truncated = true)

        assertThat(page.items).containsExactly(worklog)
        assertThat(page.truncated).isTrue()
    }

    @Test
    fun `CalendarWorklogPage truncated=false 는 LIMIT 미초과 정상 조회를 나타낸다`() {
        val page = CalendarWorklogPage(items = emptyList(), truncated = false)

        assertThat(page.items).isEmpty()
        assertThat(page.truncated).isFalse()
    }
}
