// CalendarControllerIntegrationTest 용 UserCalendarLookupPort stub — BC 격리로 실 issue-tracking adapter 미사용 (FR-CA-01 Task 4)

package com.atlas.bts.identity.calendar

import com.bts.shared.calendar.CalendarIssuePage
import com.bts.shared.calendar.CalendarIssueView
import com.bts.shared.calendar.CalendarWorklogPage
import com.bts.shared.calendar.CalendarWorklogView
import com.bts.shared.calendar.UserCalendarLookupPort
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * [CalendarControllerIntegrationTest] 전용 [UserCalendarLookupPort] stub 등록 설정 (FR-CA-01 Task 4).
 *
 * identity-access 는 issue-tracking 을 gradle 수준에서 의존하지 않는다(BC 격리) — 실 adapter 대신
 * 고정 데이터를 반환하는 [StubUserCalendarLookupPort] 를 `@Primary` 로 등록해, 프로덕션
 * [com.atlas.bts.identity.calendar.CalendarPortConfig] 의 fail-safe 기본 빈(빈 결과)보다 우선 적용되게
 * 한다([com.atlas.bts.identity.integration.MfaEnforcementEndToEndTest.SensitiveProjectFakeConfig] 와
 * 동일하게 `@ConditionalOnMissingBean` 처리 순서에 의존하지 않고 `@Primary` 로 확실히 override).
 */
@TestConfiguration
class StubUserCalendarLookupPortConfig {
    @Bean
    @Primary
    fun stubUserCalendarLookupPort(): UserCalendarLookupPort = StubUserCalendarLookupPort()
}

/**
 * 고정 시드 데이터를 반환하는 [UserCalendarLookupPort] stub 구현체.
 *
 * 담당 이슈 1건([issuePage]) + worklog 2건([worklogPage], 그 중 하나는 [CalendarWorklogView.issueSummary]
 * 가 null — 보안 등급 마스킹 케이스 직렬화 확인용)을 [userId]/기간과 무관하게 항상 반환한다.
 */
class StubUserCalendarLookupPort : UserCalendarLookupPort {
    override fun listAssignedScheduledIssues(
        userId: UUID,
        from: LocalDate,
        to: LocalDate,
    ): CalendarIssuePage =
        CalendarIssuePage(
            items =
                listOf(
                    CalendarIssueView(
                        key = "CAL-1",
                        summary = "캘린더 통합테스트 이슈",
                        issueType = "task",
                        currentStateKey = "in_progress",
                        startDate = LocalDate.of(2026, 7, 10),
                        dueDate = LocalDate.of(2026, 7, 12),
                    ),
                ),
            truncated = false,
        )

    override fun listWorklogs(
        userId: UUID,
        fromInstant: Instant,
        toInstant: Instant,
    ): CalendarWorklogPage =
        CalendarWorklogPage(
            items =
                listOf(
                    CalendarWorklogView(
                        id = WORKLOG_ID,
                        issueKey = "CAL-1",
                        issueSummary = "캘린더 통합테스트 이슈",
                        startedAt = Instant.parse("2026-07-10T09:00:00Z"),
                        timeSpentSeconds = 3600,
                    ),
                    CalendarWorklogView(
                        id = MASKED_WORKLOG_ID,
                        issueKey = "CAL-2",
                        issueSummary = null,
                        startedAt = Instant.parse("2026-07-11T09:00:00Z"),
                        timeSpentSeconds = 1800,
                    ),
                ),
            truncated = false,
        )

    private companion object {
        val WORKLOG_ID: UUID = UUID.fromString("0000cccc-0000-4000-8000-0000000000c1")
        val MASKED_WORKLOG_ID: UUID = UUID.fromString("0000cccc-0000-4000-8000-0000000000c2")
    }
}
