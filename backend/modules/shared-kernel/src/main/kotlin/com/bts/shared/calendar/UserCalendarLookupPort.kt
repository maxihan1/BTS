// cross-BC 개인 캘린더 조회 포트 — 사용자 축 (identity-access → issue-tracking 위임) FR-CA-01

package com.bts.shared.calendar

import java.time.Instant
import java.time.LocalDate
import java.util.UUID

interface UserCalendarLookupPort {
    fun listAssignedScheduledIssues(
        userId: UUID,
        from: LocalDate,
        to: LocalDate,
    ): CalendarIssuePage = CalendarIssuePage(items = emptyList(), truncated = false)

    fun listWorklogs(
        userId: UUID,
        fromInstant: Instant,
        toInstant: Instant,
    ): CalendarWorklogPage = CalendarWorklogPage(items = emptyList(), truncated = false)
}

data class CalendarIssueView(
    val key: String,
    val summary: String,
    val issueType: String,
    val currentStateKey: String,
    val startDate: LocalDate?,
    val dueDate: LocalDate?,
)

data class CalendarIssuePage(
    val items: List<CalendarIssueView>,
    val truncated: Boolean,
)

data class CalendarWorklogView(
    val id: UUID,
    val issueKey: String,
    val issueSummary: String?,
    val startedAt: Instant,
    val timeSpentSeconds: Int,
)

data class CalendarWorklogPage(
    val items: List<CalendarWorklogView>,
    val truncated: Boolean,
)
