// 캘린더 피드 토큰 생명주기(발급/rotate/취소/상태)와 익명 구독 피드(.ics) 생성을 조율하는 서비스 (FR-CA-02 Task 4)

package com.atlas.bts.identity.calendar

import com.atlas.bts.identity.profile.UserProfileService
import com.bts.shared.calendar.UserCalendarLookupPort
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** 롤링 윈도의 과거 범위(일) — today 기준으로 이만큼 이전까지 포함한다. */
private const val WINDOW_PAST_DAYS = 30L

/** 롤링 윈도의 미래 범위(일) — today 기준으로 이만큼 이후까지 포함한다. */
private const val WINDOW_FUTURE_DAYS = 180L

/** 사용자 프로필 timezone 이 미설정이거나 유효한 IANA 존이 아닐 때 사용하는 fallback. */
private const val FALLBACK_TIMEZONE = "UTC"

/**
 * 캘린더 피드 토큰 생명주기와 익명 구독 피드(.ics) 생성을 조율하는 서비스 (FR-CA-02 Task 4).
 *
 * 두 관심사를 담당한다.
 *  1. **토큰 생명주기** — [issue](발급/rotate) · [revoke](취소) · [status](상태 조회).
 *     [CalendarFeedToken] 로 불투명 토큰을 만들고, DB([CalendarFeedTokenRepository])에는
 *     `SHA-256(rawToken)` 해시만 저장한다. rawToken 평문은 발급 응답에 단 한 번만 반환하고
 *     이 서비스·리포지토리·로그 어디에도 저장/기록하지 않는다(DEVELOPMENT.md §1.1.1).
 *  2. **익명 피드 생성** — [generateFeed] 가 URL 경로의 rawToken 을 해시로 역매핑해 소유자를 찾고,
 *     롤링 윈도(과거 [WINDOW_PAST_DAYS] ~ 미래 [WINDOW_FUTURE_DAYS])의 담당 이슈/worklog 를
 *     [UserCalendarLookupPort] 로 조회해 [IcalSerializer] 로 RFC 5545 문자열로 직렬화한다.
 *
 * ## 왜 [CalendarService] 를 재사용하지 않고 포트를 직접 호출하는가
 * [CalendarService] 는 인앱 캘린더용으로 조회 창을 [최대 90일][CalendarService]로 검증하고 worklog 를
 * 로컬 날짜([CalendarWorklogEvent.date])로 매핑해 버린다. 반면 iCal 피드는
 * (a) 윈도가 210일(30+180)로 90일 캡을 넘고,
 * (b) worklog VEVENT 를 UTC `Instant` 타임드 이벤트로 그대로 내보내야 해서 원본 [Instant] 보존이
 *     필요하다. 두 요구가 [CalendarService] 의 계약과 충돌하므로 포트([UserCalendarLookupPort])를
 *     직접 호출한다. timezone fallback 만 [CalendarService.resolveZone] 과 동작 동치로 재구현한다
 *     (private 이라 문자 재사용 불가 — 동치는 테스트로 고정).
 *
 * ## Clock 주입
 * 롤링 윈도 기준 `today` 와 `DTSTAMP` 는 [clock] 으로만 시각을 얻는다([Instant.now] 하드코딩 금지 —
 * 특정 날짜에 깨지는 time-bomb 방지). 기본값 [Clock.systemUTC] 이며 테스트는 [Clock.fixed] 로 고정한다.
 *
 * @param repository 토큰 해시 저장/조회/삭제(같은 모듈).
 * @param userProfileService 사용자 timezone 조회(같은 모듈).
 * @param calendarLookupPort 담당 이슈/worklog cross-BC 조회 포트(shared-kernel). viewer=토큰 소유자.
 * @param clock 시각 소스 — 롤링 윈도/DTSTAMP 계산에 사용. 테스트는 [Clock.fixed] 주입.
 */
@Service
class CalendarFeedService(
    private val repository: CalendarFeedTokenRepository,
    private val userProfileService: UserProfileService,
    private val calendarLookupPort: UserCalendarLookupPort,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 캘린더 피드 토큰을 발급/재발급(rotate)한다.
     *
     * [CalendarFeedToken.generate] 로 새 불투명 토큰을 만들어 해시만 [CalendarFeedTokenRepository.upsert]
     * 하고(사용자당 1개 rotate), rawToken 평문과 발급 시각을 [IssuedCalendarFeed] 로 반환한다.
     * rawToken 은 이 반환값(발급 응답)으로만 노출되고 어디에도 저장되지 않는다.
     *
     * @param userId 토큰 소유자(컨트롤러가 JWT subject 로 식별한 본인 — me-scope).
     * @return rawToken(1회 노출) + 발급 시각.
     */
    @Transactional
    fun issue(userId: UUID): IssuedCalendarFeed {
        val token = CalendarFeedToken.generate()
        repository.upsert(userId, token.hash)
        val createdAt =
            repository.findByUserId(userId)
                ?: error("발급 직후 토큰 조회에 실패했습니다 — upsert 후 행이 존재해야 합니다.")
        return IssuedCalendarFeed(rawToken = token.rawToken, createdAt = createdAt)
    }

    /**
     * 캘린더 피드 토큰을 취소(하드 삭제)한다. 취소 즉시 해당 구독 URL 은 404 로 수렴한다.
     *
     * @param userId 토큰 소유자. 행이 없어도 멱등(0행 삭제).
     */
    @Transactional
    fun revoke(userId: UUID) {
        repository.deleteByUserId(userId)
    }

    /**
     * 캘린더 피드 발급 상태를 조회한다. rawToken/해시는 절대 노출하지 않는다.
     *
     * @param userId 조회 대상 사용자.
     * @return 활성 토큰이 있으면 `enabled=true` + 발급 시각, 없으면 `enabled=false` + null.
     */
    @Transactional(readOnly = true)
    fun status(userId: UUID): CalendarFeedStatus {
        val createdAt = repository.findByUserId(userId)
        return CalendarFeedStatus(enabled = createdAt != null, createdAt = createdAt)
    }

    /**
     * 익명 구독 피드(`GET /ical/feed/{token}.ics`)의 RFC 5545 문자열을 생성한다.
     *
     * URL 경로의 [rawToken] 을 `SHA-256` 해시로 역매핑해 소유자를 찾고(실패 시 null → 컨트롤러가 404),
     * 소유자 timezone 기준 롤링 윈도(과거 [WINDOW_PAST_DAYS] ~ 미래 [WINDOW_FUTURE_DAYS])의 담당 이슈와
     * worklog 를 [UserCalendarLookupPort] 로 조회해 [IcalSerializer] 로 직렬화한다. viewer 는 토큰
     * 소유자이므로 보안 등급 필터는 포트 구현(issue-tracking adapter)이 소유자 기준으로 fail-closed 적용한다.
     *
     * @param rawToken URL 경로에서 받은 불투명 토큰 평문(해시로만 조회 — 평문은 로깅하지 않는다).
     * @param feedBaseUrl 이슈 VEVENT `URL` 조립용 애플리케이션 베이스 URL(컨트롤러가 `issuer-uri` 주입).
     * @return 유효 토큰이면 VCALENDAR 문자열, 미존재/취소 토큰이면 null(컨트롤러 404 위임).
     */
    @Transactional(readOnly = true)
    fun generateFeed(
        rawToken: String,
        feedBaseUrl: String,
    ): String? {
        val userId = repository.findUserIdByHash(CalendarFeedToken.hash(rawToken)) ?: return null

        val zone = resolveZone(userId)
        val today = LocalDate.now(clock.withZone(zone))
        val from = today.minusDays(WINDOW_PAST_DAYS)
        val to = today.plusDays(WINDOW_FUTURE_DAYS)
        val fromInstant = from.atStartOfDay(zone).toInstant()
        val toInstant = to.plusDays(1).atStartOfDay(zone).toInstant()

        val issuePage = calendarLookupPort.listAssignedScheduledIssues(userId, from, to)
        val worklogPage = calendarLookupPort.listWorklogs(userId, fromInstant, toInstant)
        if (issuePage.truncated || worklogPage.truncated) {
            log.warn(
                "캘린더 피드 데이터가 LIMIT 초과로 일부 잘렸습니다(누락 가능). userId={} issuesTruncated={} worklogsTruncated={}",
                userId,
                issuePage.truncated,
                worklogPage.truncated,
            )
        }

        return IcalSerializer.serialize(issuePage.items, worklogPage.items, clock.instant(), feedBaseUrl)
    }

    /**
     * 사용자 프로필 timezone 을 [ZoneId] 로 해석한다. 미설정/무효 값은 [FALLBACK_TIMEZONE] 으로 대체한다
     * ([CalendarService.resolveZone] 와 동작 동치 — 동치는 테스트로 고정).
     */
    private fun resolveZone(userId: UUID): ZoneId {
        val timezone = userProfileService.getProfile(userId).timezone
        return try {
            ZoneId.of(timezone)
        } catch (e: DateTimeException) {
            log.debug("유효하지 않은 프로필 timezone — {} 로 대체합니다. cause={}", FALLBACK_TIMEZONE, e.message)
            ZoneId.of(FALLBACK_TIMEZONE)
        }
    }
}

/**
 * [CalendarFeedService.issue] 반환 — 발급된 rawToken(1회 노출)과 발급 시각.
 *
 * @property rawToken 불투명 토큰 평문. 발급 응답으로만 노출되며 DB/로그에 저장하지 않는다.
 * @property createdAt 토큰 발급(rotate) 시각.
 */
data class IssuedCalendarFeed(
    val rawToken: String,
    val createdAt: Instant,
)

/**
 * [CalendarFeedService.status] 반환 — 발급 여부와 발급 시각(rawToken/해시는 노출하지 않는다).
 *
 * @property enabled 활성 캘린더 피드 토큰 존재 여부.
 * @property createdAt 활성 토큰이 있으면 발급 시각, 없으면 null.
 */
data class CalendarFeedStatus(
    val enabled: Boolean,
    val createdAt: Instant?,
)
