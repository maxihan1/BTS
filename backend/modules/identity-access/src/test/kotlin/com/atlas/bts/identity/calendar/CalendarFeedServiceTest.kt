// CalendarFeedService 단위 테스트 — 토큰 생명주기 + 익명 피드(.ics) 생성(윈도·timezone fallback·truncated WARN) (FR-CA-02 Task 4)

package com.atlas.bts.identity.calendar

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.atlas.bts.identity.profile.ProfileView
import com.atlas.bts.identity.profile.UserProfileService
import com.bts.shared.calendar.CalendarIssuePage
import com.bts.shared.calendar.CalendarIssueView
import com.bts.shared.calendar.CalendarWorklogPage
import com.bts.shared.calendar.UserCalendarLookupPort
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

/**
 * [CalendarFeedService] 단위 테스트 (FR-CA-02 Task 4).
 *
 * MockK 기반 순수 단위 테스트 — [CalendarFeedTokenRepository] / [UserProfileService] /
 * [UserCalendarLookupPort] 모두 mock, 시각 의존 로직은 [Clock.fixed] 로 고정한다(회귀 방지 —
 * time-bomb 방지, [CalendarService] 와 동형).
 *
 * ## 테스트 시나리오
 * - 토큰 생명주기: 발급(raw 반환 + 해시만 upsert, raw 미저장) · rotate(재발급 시 새 raw) ·
 *   취소(deleteByUserId) · 상태(enabled/createdAt, 원문 없음).
 * - 피드 생성: 해시로 userId 조회(실패 시 null → 컨트롤러 404 위임) · 롤링 윈도 today−30~+180 계산 ·
 *   포트 직접 호출 · IcalSerializer 직렬화 반환.
 * - 무효 timezone → UTC fallback(윈도 경계 고정 Clock 단언, [CalendarService.resolveZone] 동치).
 * - truncated 수집 → WARN 로그(원문 미로깅).
 */
class CalendarFeedServiceTest {
    private lateinit var repository: CalendarFeedTokenRepository
    private lateinit var userProfileService: UserProfileService
    private lateinit var calendarLookupPort: UserCalendarLookupPort
    private lateinit var service: CalendarFeedService

    private val userId = UUID.fromString("33333333-3333-4333-8333-333333333333")

    // 2026-07-09T20:00Z — UTC 로컬 날짜는 07-09, Asia/Seoul(+9) 로컬 날짜는 07-10 (존 차이 관측용).
    private val fixedInstant = Instant.parse("2026-07-09T20:00:00Z")
    private val clock = Clock.fixed(fixedInstant, ZoneOffset.UTC)

    private val feedBaseUrl = "http://localhost:8080"

    @BeforeEach
    fun setUp() {
        repository = mockk()
        userProfileService = mockk()
        calendarLookupPort = mockk()
        service = CalendarFeedService(repository, userProfileService, calendarLookupPort, clock)
    }

    private fun profileView(timezone: String): ProfileView =
        ProfileView(
            userId = userId,
            username = "carol",
            email = "carol@bts.local",
            displayName = "Carol Danvers",
            avatarObjectKey = null,
            timezone = timezone,
            department = null,
        )

    private fun issueView(
        key: String,
        startDate: LocalDate? = null,
        dueDate: LocalDate? = null,
    ): CalendarIssueView =
        CalendarIssueView(
            key = key,
            summary = "summary-$key",
            issueType = "task",
            currentStateKey = "in_progress",
            startDate = startDate,
            dueDate = dueDate,
        )

    // ── 발급 / rotate ───────────────────────────────────────────────────────────

    @Test
    fun `issue는 raw 토큰을 반환하고 해시만 upsert한다 (raw 미저장)`() {
        val hashSlot = slot<String>()
        justRun { repository.upsert(userId, capture(hashSlot)) }
        every { repository.findByUserId(userId) } returns fixedInstant

        val issued = service.issue(userId)

        assertThat(issued.rawToken).matches("[0-9a-f]{64}")
        assertThat(issued.createdAt).isEqualTo(fixedInstant)
        // 저장된 값은 raw 가 아니라 그 SHA-256 해시여야 한다.
        assertThat(hashSlot.captured).isEqualTo(CalendarFeedToken.hash(issued.rawToken))
        assertThat(hashSlot.captured).isNotEqualTo(issued.rawToken)
    }

    @Test
    fun `issue를 다시 호출하면 새 raw 토큰으로 rotate한다`() {
        justRun { repository.upsert(userId, any()) }
        every { repository.findByUserId(userId) } returns fixedInstant

        val first = service.issue(userId)
        val second = service.issue(userId)

        assertThat(first.rawToken).isNotEqualTo(second.rawToken)
        verify(exactly = 2) { repository.upsert(userId, any()) }
    }

    // ── 취소 ────────────────────────────────────────────────────────────────────

    @Test
    fun `revoke는 repository deleteByUserId를 호출한다`() {
        justRun { repository.deleteByUserId(userId) }

        service.revoke(userId)

        verify { repository.deleteByUserId(userId) }
    }

    // ── 상태 ────────────────────────────────────────────────────────────────────

    @Test
    fun `status는 토큰이 있으면 enabled true와 createdAt을 반환한다`() {
        every { repository.findByUserId(userId) } returns fixedInstant

        val status = service.status(userId)

        assertThat(status.enabled).isTrue()
        assertThat(status.createdAt).isEqualTo(fixedInstant)
    }

    @Test
    fun `status는 토큰이 없으면 enabled false와 createdAt null을 반환한다`() {
        every { repository.findByUserId(userId) } returns null

        val status = service.status(userId)

        assertThat(status.enabled).isFalse()
        assertThat(status.createdAt).isNull()
    }

    // ── 피드 생성: 해시 조회 ────────────────────────────────────────────────────

    @Test
    fun `generateFeed는 rawToken의 SHA-256 해시로 userId를 조회한다`() {
        val rawToken = "a".repeat(64)
        val expectedHash = CalendarFeedToken.hash(rawToken)
        every { repository.findUserIdByHash(expectedHash) } returns null

        service.generateFeed(rawToken, feedBaseUrl)

        verify { repository.findUserIdByHash(expectedHash) }
    }

    @Test
    fun `generateFeed는 해시로 userId를 못 찾으면 null을 반환한다 (컨트롤러 404 위임)`() {
        every { repository.findUserIdByHash(any()) } returns null

        val result = service.generateFeed("unknown-token", feedBaseUrl)

        assertThat(result).isNull()
        verify(exactly = 0) { userProfileService.getProfile(any()) }
        verify(exactly = 0) { calendarLookupPort.listAssignedScheduledIssues(any(), any(), any()) }
    }

    @Test
    fun `generateFeed는 포트 데이터를 IcalSerializer로 직렬화해 반환한다`() {
        every { repository.findUserIdByHash(any()) } returns userId
        every { userProfileService.getProfile(userId) } returns profileView("UTC")
        val issue = issueView("PROJ-7", dueDate = LocalDate.of(2026, 7, 15))
        every { calendarLookupPort.listAssignedScheduledIssues(userId, any(), any()) } returns
            CalendarIssuePage(listOf(issue), truncated = false)
        every { calendarLookupPort.listWorklogs(userId, any(), any()) } returns
            CalendarWorklogPage(emptyList(), truncated = false)

        val result = service.generateFeed("token", feedBaseUrl)

        assertThat(result).isNotNull()
        assertThat(result).contains("BEGIN:VCALENDAR")
        assertThat(result).contains("UID:issue-PROJ-7@bts")
    }

    // ── 피드 생성: 롤링 윈도 (today−30 ~ +180) ─────────────────────────────────

    @Test
    fun `무효 timezone이면 UTC로 fallback해 윈도 경계를 계산한다`() {
        every { repository.findUserIdByHash(any()) } returns userId
        every { userProfileService.getProfile(userId) } returns profileView("Mars/Phobos")

        val fromSlot = slot<LocalDate>()
        val toSlot = slot<LocalDate>()
        every {
            calendarLookupPort.listAssignedScheduledIssues(userId, capture(fromSlot), capture(toSlot))
        } returns CalendarIssuePage(emptyList(), truncated = false)
        val fromInstantSlot = slot<Instant>()
        val toInstantSlot = slot<Instant>()
        every {
            calendarLookupPort.listWorklogs(userId, capture(fromInstantSlot), capture(toInstantSlot))
        } returns CalendarWorklogPage(emptyList(), truncated = false)

        service.generateFeed("token", feedBaseUrl)

        // fixedInstant=2026-07-09T20:00Z → UTC today=2026-07-09.
        val today = LocalDate.of(2026, 7, 9)
        assertThat(fromSlot.captured).isEqualTo(today.minusDays(30))
        assertThat(toSlot.captured).isEqualTo(today.plusDays(180))
        assertThat(fromInstantSlot.captured)
            .isEqualTo(today.minusDays(30).atStartOfDay(ZoneOffset.UTC).toInstant())
        assertThat(toInstantSlot.captured)
            .isEqualTo(today.plusDays(180).plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant())
    }

    @Test
    fun `유효한 Asia_Seoul timezone이면 사용자 존 기준으로 today와 윈도를 계산한다`() {
        every { repository.findUserIdByHash(any()) } returns userId
        every { userProfileService.getProfile(userId) } returns profileView("Asia/Seoul")

        val fromSlot = slot<LocalDate>()
        val toSlot = slot<LocalDate>()
        every {
            calendarLookupPort.listAssignedScheduledIssues(userId, capture(fromSlot), capture(toSlot))
        } returns CalendarIssuePage(emptyList(), truncated = false)
        val fromInstantSlot = slot<Instant>()
        val toInstantSlot = slot<Instant>()
        every {
            calendarLookupPort.listWorklogs(userId, capture(fromInstantSlot), capture(toInstantSlot))
        } returns CalendarWorklogPage(emptyList(), truncated = false)

        service.generateFeed("token", feedBaseUrl)

        // fixedInstant=2026-07-09T20:00Z → Asia/Seoul(+9)=2026-07-10T05:00 → today=2026-07-10 (UTC 와 다름).
        val zone = ZoneId.of("Asia/Seoul")
        val today = LocalDate.of(2026, 7, 10)
        assertThat(fromSlot.captured).isEqualTo(today.minusDays(30))
        assertThat(toSlot.captured).isEqualTo(today.plusDays(180))
        assertThat(fromInstantSlot.captured)
            .isEqualTo(today.minusDays(30).atStartOfDay(zone).toInstant())
        assertThat(toInstantSlot.captured)
            .isEqualTo(today.plusDays(180).plusDays(1).atStartOfDay(zone).toInstant())
    }

    // ── 피드 생성: truncated → WARN ─────────────────────────────────────────────

    @Test
    fun `이슈 또는 worklog 페이지가 truncated면 WARN 로그를 남긴다 (원문 미로깅)`() {
        every { repository.findUserIdByHash(any()) } returns userId
        every { userProfileService.getProfile(userId) } returns profileView("UTC")
        every { calendarLookupPort.listAssignedScheduledIssues(userId, any(), any()) } returns
            CalendarIssuePage(emptyList(), truncated = true)
        every { calendarLookupPort.listWorklogs(userId, any(), any()) } returns
            CalendarWorklogPage(emptyList(), truncated = false)

        val warns = captureWarnLogs { service.generateFeed("raw-secret-token", feedBaseUrl) }

        assertThat(warns).isNotEmpty()
        // 원문 토큰이 로그에 절대 노출되면 안 된다(비밀값 미로깅).
        assertThat(warns).noneMatch { it.contains("raw-secret-token") }
    }

    @Test
    fun `truncated가 아니면 WARN 로그가 없다`() {
        every { repository.findUserIdByHash(any()) } returns userId
        every { userProfileService.getProfile(userId) } returns profileView("UTC")
        every { calendarLookupPort.listAssignedScheduledIssues(userId, any(), any()) } returns
            CalendarIssuePage(emptyList(), truncated = false)
        every { calendarLookupPort.listWorklogs(userId, any(), any()) } returns
            CalendarWorklogPage(emptyList(), truncated = false)

        val warns = captureWarnLogs { service.generateFeed("token", feedBaseUrl) }

        assertThat(warns).isEmpty()
    }

    /**
     * [block] 실행 중 [CalendarFeedService] 로거에 쌓인 WARN 레벨 로그의 포맷 메시지 목록을 반환한다
     * ([ListAppender] 부착 후 실행, [WebAuthnConfigTest] 동형 패턴).
     */
    private fun captureWarnLogs(block: () -> Unit): List<String> {
        val logger = LoggerFactory.getLogger(CalendarFeedService::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().also { it.start() }
        logger.addAppender(appender)
        try {
            block()
        } finally {
            logger.detachAppender(appender)
        }
        return appender.list.filter { it.level == Level.WARN }.map { it.formattedMessage }
    }
}
