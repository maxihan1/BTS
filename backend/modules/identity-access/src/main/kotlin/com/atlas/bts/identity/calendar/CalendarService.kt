// 개인 캘린더 조립 서비스 — timezone 변환·창 검증·정렬 (FR-CA-01)

package com.atlas.bts.identity.calendar

import com.atlas.bts.identity.profile.UserProfileService
import com.bts.shared.calendar.CalendarIssueView
import com.bts.shared.calendar.CalendarWorklogView
import com.bts.shared.calendar.UserCalendarLookupPort
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.time.DateTimeException
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID

/** 캘린더 조회 창의 최대 길이(일) — from~to 사이 일수가 이 값을 초과하면 400. */
private const val MAX_CALENDAR_RANGE_DAYS = 90L

/** 사용자 프로필 timezone 이 미설정이거나 유효한 IANA 존이 아닐 때 사용하는 fallback. */
private const val FALLBACK_TIMEZONE = "UTC"

/**
 * 개인 캘린더 화면을 위해 담당 이슈 일정과 worklog 를 조회·조립하는 서비스 (FR-CA-01).
 *
 * [com.bts.shared.calendar.UserCalendarLookupPort] 하나만 생성자 주입받는다 — issue-tracking BC 를
 * 직접 import 하지 않고 shared-kernel 포트를 통해서만 접근한다(BC 격리).
 * timezone 조회는 같은 identity-access 모듈의 [UserProfileService] 를 직접 사용한다.
 *
 * ## timezone 변환 공식(고정)
 * `fromInstant = from.atStartOfDay(zone).toInstant()`,
 * `toInstant = to.plusDays(1).atStartOfDay(zone).toInstant()` — half-open `[fromInstant, toInstant)`
 * 로 `to` 당일을 포함한다. 이 변환은 봄철 일광절약시간(DST) 전환일 경계를 가로질러도 각 로컬
 * 자정의 실제 UTC 오프셋을 그대로 반영한다([ZoneId] 표준 규칙에 위임 — 별도 보정 불필요).
 *
 * @param userProfileService 사용자 timezone 조회(같은 모듈).
 * @param calendarLookupPort 담당 이슈/worklog cross-BC 조회 포트(shared-kernel).
 */
@Service
class CalendarService(
    private val userProfileService: UserProfileService,
    private val calendarLookupPort: UserCalendarLookupPort,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 개인 캘린더를 조회한다.
     *
     * @param userId 캘린더를 조회하는 사용자 id.
     * @param from 조회 시작일(포함).
     * @param to 조회 종료일(포함).
     * @return 정렬·조립된 [CalendarResponse].
     * @throws ResponseStatusException `from > to` 이거나 창 길이가 [MAX_CALENDAR_RANGE_DAYS] 를
     *   초과하면 400(`INVALID_CALENDAR_RANGE`).
     */
    @Transactional(readOnly = true)
    fun getCalendar(
        userId: UUID,
        from: LocalDate,
        to: LocalDate,
    ): CalendarResponse {
        validateRange(from, to)
        val zone = resolveZone(userId)

        val fromInstant = from.atStartOfDay(zone).toInstant()
        val toInstant = to.plusDays(1).atStartOfDay(zone).toInstant()

        val issuePage = calendarLookupPort.listAssignedScheduledIssues(userId, from, to)
        val worklogPage = calendarLookupPort.listWorklogs(userId, fromInstant, toInstant)

        return CalendarResponse(
            from = from,
            to = to,
            timezone = zone.id,
            issueEvents = mapIssueEvents(issuePage.items),
            worklogEvents = mapWorklogEvents(worklogPage.items, zone),
            truncated = issuePage.truncated || worklogPage.truncated,
        )
    }

    /** `from > to` 또는 창 길이 초과를 400(`INVALID_CALENDAR_RANGE`)으로 거부한다. */
    private fun validateRange(
        from: LocalDate,
        to: LocalDate,
    ) {
        if (from.isAfter(to) || ChronoUnit.DAYS.between(from, to) > MAX_CALENDAR_RANGE_DAYS) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_CALENDAR_RANGE")
        }
    }

    /** 사용자 프로필 timezone 을 조회해 [ZoneId] 로 해석한다. 미설정/무효 값은 [FALLBACK_TIMEZONE] 으로 대체한다. */
    private fun resolveZone(userId: UUID): ZoneId {
        val timezone = userProfileService.getProfile(userId).timezone
        return try {
            ZoneId.of(timezone)
        } catch (e: DateTimeException) {
            log.debug("유효하지 않은 프로필 timezone — {} 로 대체합니다. cause={}", FALLBACK_TIMEZONE, e.message)
            ZoneId.of(FALLBACK_TIMEZONE)
        }
    }

    /** 담당 이슈 뷰를 [CalendarIssueEvent] 로 옮기고 `(startDate ?: dueDate, key)` 오름차순 정렬한다. */
    private fun mapIssueEvents(items: List<CalendarIssueView>): List<CalendarIssueEvent> =
        items
            .map {
                CalendarIssueEvent(
                    key = it.key,
                    summary = it.summary,
                    issueType = it.issueType,
                    currentStateKey = it.currentStateKey,
                    startDate = it.startDate,
                    dueDate = it.dueDate,
                )
            }.sortedWith(compareBy({ it.startDate ?: it.dueDate }, { it.key }))

    /**
     * worklog 뷰의 UTC `startedAt` 을 [zone] 기준 로컬 날짜로 매핑해 [CalendarWorklogEvent] 로 옮긴다.
     *
     * 정렬 기준 `(date, startedAt)` 은 매핑된 날짜가 같으면 원본 시각(Instant) 순으로 배치하기 위해
     * DTO 변환 이전(원본 [CalendarWorklogView] 단계)에서 수행한다 — [CalendarWorklogEvent] 는
     * `startedAt` 을 노출하지 않기 때문이다.
     */
    private fun mapWorklogEvents(
        items: List<CalendarWorklogView>,
        zone: ZoneId,
    ): List<CalendarWorklogEvent> =
        items
            .map { it to it.startedAt.atZone(zone).toLocalDate() }
            .sortedWith(compareBy({ it.second }, { it.first.startedAt }))
            .map { (view, date) ->
                CalendarWorklogEvent(
                    id = view.id,
                    issueKey = view.issueKey,
                    issueSummary = view.issueSummary,
                    date = date,
                    timeSpentSeconds = view.timeSpentSeconds,
                )
            }
}
