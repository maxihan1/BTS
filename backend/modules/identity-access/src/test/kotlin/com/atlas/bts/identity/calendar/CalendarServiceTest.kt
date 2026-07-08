// CalendarService 단위 테스트 — 창 검증/timezone 변환/정렬/조립 (FR-CA-01 Task 3)

package com.atlas.bts.identity.calendar

import com.atlas.bts.identity.profile.ProfileView
import com.atlas.bts.identity.profile.UserProfileService
import com.bts.shared.calendar.CalendarIssuePage
import com.bts.shared.calendar.CalendarIssueView
import com.bts.shared.calendar.CalendarWorklogPage
import com.bts.shared.calendar.CalendarWorklogView
import com.bts.shared.calendar.UserCalendarLookupPort
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * [CalendarService] 단위 테스트 (FR-CA-01 Task 3).
 *
 * MockK 기반 순수 단위 테스트 — [UserProfileService] / [UserCalendarLookupPort] 모두 mock.
 *
 * ## 테스트 시나리오
 * - 창 검증: from > to, 90일 초과, 90일 경계(OK).
 * - timezone 변환: Asia/Seoul 로컬 매핑, America/New_York 봄 DST 전환일 경계(C5).
 * - 정렬: issueEvents (startDate ?: dueDate, key), worklogEvents (date, startedAt).
 * - 빈 결과, truncated 전파, timezone 미설정/무효 시 UTC fallback.
 */
class CalendarServiceTest {
    private lateinit var userProfileService: UserProfileService
    private lateinit var calendarLookupPort: UserCalendarLookupPort
    private lateinit var service: CalendarService

    private val userId = UUID.fromString("22222222-2222-4222-8222-222222222222")

    @BeforeEach
    fun setUp() {
        userProfileService = mockk()
        calendarLookupPort = mockk()
        service = CalendarService(userProfileService, calendarLookupPort)
    }

    private fun profileView(timezone: String): ProfileView =
        ProfileView(
            userId = userId,
            username = "alice",
            email = "alice@bts.local",
            displayName = "Alice Cooper",
            avatarObjectKey = null,
            timezone = timezone,
            department = null,
        )

    private fun issueView(
        key: String,
        summary: String = "summary-$key",
        startDate: LocalDate? = null,
        dueDate: LocalDate? = null,
    ): CalendarIssueView =
        CalendarIssueView(
            key = key,
            summary = summary,
            issueType = "task",
            currentStateKey = "in_progress",
            startDate = startDate,
            dueDate = dueDate,
        )

    private fun worklogView(
        id: UUID = UUID.randomUUID(),
        issueKey: String = "PROJ-1",
        issueSummary: String? = "worklog summary",
        startedAt: Instant,
        timeSpentSeconds: Int = 3600,
    ): CalendarWorklogView =
        CalendarWorklogView(
            id = id,
            issueKey = issueKey,
            issueSummary = issueSummary,
            startedAt = startedAt,
            timeSpentSeconds = timeSpentSeconds,
        )

    // ── 창 검증 ───────────────────────────────────────────────────────────────

    @Test
    fun `from이 to보다 늦으면 400 INVALID_CALENDAR_RANGE`() {
        val from = LocalDate.of(2026, 7, 10)
        val to = LocalDate.of(2026, 7, 5)

        assertThatThrownBy { service.getCalendar(userId, from, to) }
            .isInstanceOf(ResponseStatusException::class.java)
            .hasMessageContaining("INVALID_CALENDAR_RANGE")

        verify(exactly = 0) { userProfileService.getProfile(any()) }
    }

    @Test
    fun `창 길이가 90일을 초과하면 400 INVALID_CALENDAR_RANGE`() {
        val from = LocalDate.of(2026, 1, 1)
        val to = from.plusDays(91)

        assertThatThrownBy { service.getCalendar(userId, from, to) }
            .isInstanceOf(ResponseStatusException::class.java)
            .hasMessageContaining("INVALID_CALENDAR_RANGE")
    }

    @Test
    fun `창 길이가 정확히 90일이면 통과한다`() {
        val from = LocalDate.of(2026, 1, 1)
        val to = from.plusDays(90)
        every { userProfileService.getProfile(userId) } returns profileView("UTC")
        every { calendarLookupPort.listAssignedScheduledIssues(userId, from, to) } returns
            CalendarIssuePage(emptyList(), truncated = false)
        every { calendarLookupPort.listWorklogs(userId, any(), any()) } returns
            CalendarWorklogPage(emptyList(), truncated = false)

        val result = service.getCalendar(userId, from, to)

        assertThat(result.from).isEqualTo(from)
        assertThat(result.to).isEqualTo(to)
    }

    // ── timezone 변환 ─────────────────────────────────────────────────────────

    @Test
    fun `Asia_Seoul 타임존에서 worklog startedAt 14시UTC는 같은 로컬 날짜로 매핑된다`() {
        val day = LocalDate.of(2026, 7, 5)
        every { userProfileService.getProfile(userId) } returns profileView("Asia/Seoul")
        every { calendarLookupPort.listAssignedScheduledIssues(userId, day, day) } returns
            CalendarIssuePage(emptyList(), truncated = false)
        val worklog = worklogView(startedAt = Instant.parse("2026-07-05T14:00:00Z"))
        every { calendarLookupPort.listWorklogs(userId, any(), any()) } returns
            CalendarWorklogPage(listOf(worklog), truncated = false)

        val result = service.getCalendar(userId, day, day)

        assertThat(result.worklogEvents).hasSize(1)
        assertThat(result.worklogEvents[0].date).isEqualTo(day)
        assertThat(result.timezone).isEqualTo("Asia/Seoul")
    }

    @Test
    fun `America_New_York 봄 DST 전환일 경계에서 fromInstant toInstant와 worklog 매핑이 올바르다`() {
        // 2026-03-08 은 미국 동부 봄 DST 전환일(02:00 -> 03:00). from=Mar7, to=Mar8 로 경계를 가로지른다.
        val from = LocalDate.of(2026, 3, 7)
        val to = LocalDate.of(2026, 3, 8)
        every { userProfileService.getProfile(userId) } returns profileView("America/New_York")
        every { calendarLookupPort.listAssignedScheduledIssues(userId, from, to) } returns
            CalendarIssuePage(emptyList(), truncated = false)

        val fromInstantSlot = slot<Instant>()
        val toInstantSlot = slot<Instant>()
        // 전환 이후(EDT, -04:00) 시각의 worklog — 로컬 날짜는 여전히 Mar8.
        val worklog = worklogView(startedAt = Instant.parse("2026-03-08T18:00:00Z"))
        every {
            calendarLookupPort.listWorklogs(userId, capture(fromInstantSlot), capture(toInstantSlot))
        } returns CalendarWorklogPage(listOf(worklog), truncated = false)

        val result = service.getCalendar(userId, from, to)

        // from(Mar7 00:00 로컬)은 전환 전이라 EST(-05:00) 오프셋 -> 05:00Z
        assertThat(fromInstantSlot.captured).isEqualTo(Instant.parse("2026-03-07T05:00:00Z"))
        // to+1일(Mar9 00:00 로컬)은 전환 후라 EDT(-04:00) 오프셋 -> 04:00Z
        assertThat(toInstantSlot.captured).isEqualTo(Instant.parse("2026-03-09T04:00:00Z"))
        assertThat(result.worklogEvents[0].date).isEqualTo(LocalDate.of(2026, 3, 8))
    }

    // ── 정렬 ──────────────────────────────────────────────────────────────────

    @Test
    fun `issueEvents는 startDate 우선 dueDate 폴백, key 오름차순으로 정렬된다`() {
        val from = LocalDate.of(2026, 7, 1)
        val to = LocalDate.of(2026, 7, 31)
        every { userProfileService.getProfile(userId) } returns profileView("UTC")
        val shuffled =
            listOf(
                issueView("PROJ-3", startDate = LocalDate.of(2026, 7, 10)),
                issueView("PROJ-1", startDate = LocalDate.of(2026, 7, 5)),
                issueView("PROJ-2", dueDate = LocalDate.of(2026, 7, 5)),
                issueView("PROJ-4", startDate = LocalDate.of(2026, 7, 10)),
            )
        every { calendarLookupPort.listAssignedScheduledIssues(userId, from, to) } returns
            CalendarIssuePage(shuffled, truncated = false)
        every { calendarLookupPort.listWorklogs(userId, any(), any()) } returns
            CalendarWorklogPage(emptyList(), truncated = false)

        val result = service.getCalendar(userId, from, to)

        assertThat(result.issueEvents.map { it.key }).containsExactly("PROJ-1", "PROJ-2", "PROJ-3", "PROJ-4")
    }

    @Test
    fun `worklogEvents는 date 오름차순, 동일 date는 startedAt 오름차순으로 정렬된다`() {
        val from = LocalDate.of(2026, 7, 1)
        val to = LocalDate.of(2026, 7, 31)
        every { userProfileService.getProfile(userId) } returns profileView("UTC")
        every { calendarLookupPort.listAssignedScheduledIssues(userId, from, to) } returns
            CalendarIssuePage(emptyList(), truncated = false)
        val late =
            worklogView(
                id = UUID.fromString("00000000-0000-4000-8000-000000000001"),
                startedAt = Instant.parse("2026-07-10T14:00:00Z"),
            )
        val early =
            worklogView(
                id = UUID.fromString("00000000-0000-4000-8000-000000000002"),
                startedAt = Instant.parse("2026-07-05T09:00:00Z"),
            )
        val sameDayLate =
            worklogView(
                id = UUID.fromString("00000000-0000-4000-8000-000000000003"),
                startedAt = Instant.parse("2026-07-05T18:00:00Z"),
            )
        every { calendarLookupPort.listWorklogs(userId, any(), any()) } returns
            CalendarWorklogPage(listOf(late, sameDayLate, early), truncated = false)

        val result = service.getCalendar(userId, from, to)

        assertThat(result.worklogEvents.map { it.id })
            .containsExactly(early.id, sameDayLate.id, late.id)
    }

    // ── 빈 결과 / truncated / timezone fallback ────────────────────────────────

    @Test
    fun `빈 결과는 빈 리스트를 반환한다`() {
        val from = LocalDate.of(2026, 7, 1)
        val to = LocalDate.of(2026, 7, 2)
        every { userProfileService.getProfile(userId) } returns profileView("UTC")
        every { calendarLookupPort.listAssignedScheduledIssues(userId, from, to) } returns
            CalendarIssuePage(emptyList(), truncated = false)
        every { calendarLookupPort.listWorklogs(userId, any(), any()) } returns
            CalendarWorklogPage(emptyList(), truncated = false)

        val result = service.getCalendar(userId, from, to)

        assertThat(result.issueEvents).isEmpty()
        assertThat(result.worklogEvents).isEmpty()
        assertThat(result.truncated).isFalse()
    }

    @Test
    fun `이슈 페이지가 truncated면 응답도 truncated다`() {
        val from = LocalDate.of(2026, 7, 1)
        val to = LocalDate.of(2026, 7, 2)
        every { userProfileService.getProfile(userId) } returns profileView("UTC")
        every { calendarLookupPort.listAssignedScheduledIssues(userId, from, to) } returns
            CalendarIssuePage(emptyList(), truncated = true)
        every { calendarLookupPort.listWorklogs(userId, any(), any()) } returns
            CalendarWorklogPage(emptyList(), truncated = false)

        val result = service.getCalendar(userId, from, to)

        assertThat(result.truncated).isTrue()
    }

    @Test
    fun `worklog 페이지가 truncated면 응답도 truncated다`() {
        val from = LocalDate.of(2026, 7, 1)
        val to = LocalDate.of(2026, 7, 2)
        every { userProfileService.getProfile(userId) } returns profileView("UTC")
        every { calendarLookupPort.listAssignedScheduledIssues(userId, from, to) } returns
            CalendarIssuePage(emptyList(), truncated = false)
        every { calendarLookupPort.listWorklogs(userId, any(), any()) } returns
            CalendarWorklogPage(emptyList(), truncated = true)

        val result = service.getCalendar(userId, from, to)

        assertThat(result.truncated).isTrue()
    }

    @Test
    fun `프로필 timezone이 무효 문자열이면 UTC로 동작한다`() {
        val day = LocalDate.of(2026, 7, 5)
        every { userProfileService.getProfile(userId) } returns profileView("Mars/Phobos")
        every { calendarLookupPort.listAssignedScheduledIssues(userId, day, day) } returns
            CalendarIssuePage(emptyList(), truncated = false)
        val worklog = worklogView(startedAt = Instant.parse("2026-07-05T23:30:00Z"))
        every { calendarLookupPort.listWorklogs(userId, any(), any()) } returns
            CalendarWorklogPage(listOf(worklog), truncated = false)

        val result = service.getCalendar(userId, day, day)

        assertThat(result.timezone).isEqualTo("UTC")
        // UTC 기준이므로 23:30Z는 그대로 같은 날짜.
        assertThat(result.worklogEvents[0].date).isEqualTo(day)
    }
}
