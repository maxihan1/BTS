// 담당 이슈/Worklog를 RFC 5545(iCalendar) 문자열로 직렬화하는 자체 순수 함수 (FR-CA-02)

package com.atlas.bts.identity.calendar

import com.bts.shared.calendar.CalendarIssueView
import com.bts.shared.calendar.CalendarWorklogView
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private const val ICS_PRODID = "-//BTS//Atlas Issues//KO"
private const val ICS_CALENDAR_NAME = "BTS 내 일정"

/** worklog 소요 시간이 0초여도 DTSTART==DTEND(무점유 이벤트)가 되지 않도록 두는 최소 표시 길이(초). */
private const val MIN_WORKLOG_DISPLAY_SECONDS = 60

private const val SECONDS_PER_HOUR = 3600
private const val SECONDS_PER_MINUTE = 60

private val ICS_DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")
private val ICS_DATE_TIME_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)

/**
 * 담당 이슈 일정과 worklog 를 RFC 5545 VCALENDAR 문자열로 직렬화하는 자체 구현체 (FR-CA-02 Task 3).
 *
 * 신규 외부 의존성(ical4j 등)을 도입하지 않는 관례를 따른다 — mermaid 자체 SVG·Gantt SVG·AQL 파서·
 * 네이티브 캘린더 그리드와 동일한 근거([docs/specs/2026-07-09-fr-ca-02-ical-export.md] 참조).
 * 부수 효과 없는 순수 함수만 노출한다 — `now`(DTSTAMP·롤링 윈도 기준 시각)는 항상 호출부가 주입한다.
 *
 * ## 책임 분리
 * 이 오브젝트는 이슈/worklog 도메인 값을 VEVENT content line 목록으로 매핑하는 **조립 책임**만
 * 진다. TEXT 값 이스케이핑과 75옥텟 라인 폴딩 같은 RFC 5545 저수준 문자열 규칙은 [IcalLineFormat]
 * 에 위임한다 — 두 관심사(도메인 매핑 vs 문자 인코딩 규칙)를 분리해 각각 독립적으로 이해·검증할 수
 * 있게 한다.
 *
 * @see IcalSerializer.serialize
 * @see IcalLineFormat
 */
object IcalSerializer {
    /**
     * 담당 이슈([issues])와 worklog([worklogs])를 하나의 RFC 5545 VCALENDAR 문자열로 직렬화한다.
     *
     * 결과는 `METHOD` 프로퍼티를 포함하지 않는다 — 읽기 전용 구독 피드는 iTIP `METHOD:PUBLISH` 가
     * 요구하는 `ORGANIZER` 를 채울 수 없고, 엄격한 검증기가 경고를 낼 수 있어 의도적으로 생략한다.
     *
     * 직렬화 최종 단계에서 [IcalLineFormat.foldLine] 을 **모든 content line 에 예외 없이** 적용한다
     * (`BEGIN:VEVENT` 같은 짧은 구조 라인도 통과하지만 75옥텟 미만이라 실질적 변화는 없다).
     *
     * @param issues 캘린더에 표시할 담당 이슈 목록. all-day VEVENT 로 변환된다.
     * @param worklogs 캘린더에 표시할 작업 기록 목록. UTC 타임드 VEVENT 로 변환된다.
     * @param now `DTSTAMP` 에 사용할 현재 시각(UTC). 호출부가 `Clock` 을 통해 주입한다.
     * @param feedBaseUrl 이슈 VEVENT 의 `URL` 프로퍼티 조립에 쓰는 애플리케이션 베이스 URL(끝의 `/` 는 무시).
     * @return CRLF 로 줄바꿈되고 75옥텟 폴딩이 적용된, 유효한 VCALENDAR 문자열(마지막 줄도 CRLF 로 종료).
     */
    fun serialize(
        issues: List<CalendarIssueView>,
        worklogs: List<CalendarWorklogView>,
        now: Instant,
        feedBaseUrl: String,
    ): String {
        val lines = mutableListOf<String>()
        lines += "BEGIN:VCALENDAR"
        lines += "VERSION:2.0"
        lines += "PRODID:$ICS_PRODID"
        lines += "CALSCALE:GREGORIAN"
        lines += "X-WR-CALNAME:$ICS_CALENDAR_NAME"
        issues.forEach { lines += issueEventLines(it, now, feedBaseUrl) }
        worklogs.forEach { lines += worklogEventLines(it, now) }
        lines += "END:VCALENDAR"

        return lines.joinToString(IcalLineFormat.CRLF) { IcalLineFormat.foldLine(it) } + IcalLineFormat.CRLF
    }

    /**
     * 이슈 하나를 all-day VEVENT content line 목록으로 변환한다.
     *
     * `DTSTART` = [CalendarIssueView.startDate] ?: [CalendarIssueView.dueDate].
     * `DTEND` = ([CalendarIssueView.dueDate] ?: [CalendarIssueView.startDate]) + 1일
     * (RFC 5545 all-day `DTEND` 는 exclusive 이므로 마감 당일을 포함하려면 +1일이 필요하다).
     * 둘 다 null 이면(포트 계약상 발생하지 않아야 하나 방어적으로) 빈 목록을 반환해 이벤트를 생략한다.
     */
    private fun issueEventLines(
        issue: CalendarIssueView,
        now: Instant,
        feedBaseUrl: String,
    ): List<String> {
        val dtStart = issue.startDate ?: issue.dueDate
        val dtEndBase = issue.dueDate ?: issue.startDate
        if (dtStart == null || dtEndBase == null) return emptyList()
        val dtEnd = dtEndBase.plusDays(1)

        return listOf(
            "BEGIN:VEVENT",
            "UID:issue-${issue.key}@bts",
            "DTSTAMP:${formatDateTimeUtc(now)}",
            "DTSTART;VALUE=DATE:${formatDate(dtStart)}",
            "DTEND;VALUE=DATE:${formatDate(dtEnd)}",
            "SUMMARY:[${issue.key}] ${IcalLineFormat.escapeText(issue.summary)}",
            "DESCRIPTION:" +
                IcalLineFormat.escapeText("이슈 타입: ${issue.issueType} / 상태: ${issue.currentStateKey}"),
            "URL:${feedBaseUrl.trimEnd('/')}/issues/${issue.key}",
            "STATUS:CONFIRMED",
            "END:VEVENT",
        )
    }

    /**
     * worklog 하나를 타임드 VEVENT content line 목록으로 변환한다.
     *
     * `DTSTART`/`DTEND` 는 UTC `Z` 접미사로 출력한다(캘린더 앱이 로컬로 변환 — DST 경계 무관).
     * [CalendarWorklogView.timeSpentSeconds] 가 [MIN_WORKLOG_DISPLAY_SECONDS] 미만(0 포함)이면
     * `DTEND` 계산에는 [MIN_WORKLOG_DISPLAY_SECONDS] 를 대신 사용해 `DTSTART == DTEND` 인
     * 무점유(zero-duration) 이벤트가 되는 것을 방지한다. `SUMMARY` 의 소요시간 표기는
     * 실제 [CalendarWorklogView.timeSpentSeconds] 값을 그대로 사용한다(데이터 왜곡 방지).
     */
    private fun worklogEventLines(
        worklog: CalendarWorklogView,
        now: Instant,
    ): List<String> {
        val displaySeconds = maxOf(worklog.timeSpentSeconds, MIN_WORKLOG_DISPLAY_SECONDS)
        val dtEnd = worklog.startedAt.plusSeconds(displaySeconds.toLong())
        val summary =
            "${formatDuration(worklog.timeSpentSeconds)} — ${worklog.issueKey}" +
                (worklog.issueSummary?.let { " $it" } ?: "")

        return listOf(
            "BEGIN:VEVENT",
            "UID:worklog-${worklog.id}@bts",
            "DTSTAMP:${formatDateTimeUtc(now)}",
            "DTSTART:${formatDateTimeUtc(worklog.startedAt)}",
            "DTEND:${formatDateTimeUtc(dtEnd)}",
            "SUMMARY:${IcalLineFormat.escapeText(summary)}",
            "END:VEVENT",
        )
    }

    /** 초 단위 소요 시간을 `"1h 30m"` 또는(시간이 0이면) `"30m"` 형태로 표기한다. 음수는 0으로 취급한다. */
    private fun formatDuration(totalSeconds: Int): String {
        val safeSeconds = totalSeconds.coerceAtLeast(0)
        val hours = safeSeconds / SECONDS_PER_HOUR
        val minutes = (safeSeconds % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE
        return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
    }

    private fun formatDate(date: LocalDate): String = ICS_DATE_FORMATTER.format(date)

    private fun formatDateTimeUtc(instant: Instant): String = ICS_DATE_TIME_FORMATTER.format(instant)
}

/**
 * RFC 5545 저수준 content line 인코딩 규칙(이스케이핑·줄바꿈·라인 폴딩) 전담 오브젝트.
 *
 * [IcalSerializer] 의 도메인 매핑 책임과 분리한다 — 이 오브젝트는 이슈/worklog 를 전혀 알지 못하고
 * 순수하게 RFC 5545 문자열 규칙(§3.1 라인 폴딩, §3.3.11 TEXT 이스케이핑)만 다룬다.
 */
private object IcalLineFormat {
    /** RFC 5545 §3.1 이 요구하는 줄바꿈 — LF 단독 사용 금지. */
    const val CRLF = "\r\n"

    /** RFC 5545 §3.1 라인 폴딩 임계값 — **옥텟**(UTF-8 바이트) 기준이며 문자 수가 아니다(한글 등 멀티바이트 문자 주의). */
    private const val MAX_LINE_OCTETS = 75

    /** UTF-8 연속 바이트를 가려내는 비트마스크(`11000000`) — 이 마스크를 적용한 결과가 [UTF8_CONT_TAG] 면 연속 바이트다. */
    private const val UTF8_CONT_MASK = 0xC0

    /** UTF-8 연속 바이트(멀티바이트 문자의 2번째 이후 바이트)의 상위 비트 패턴(`10000000`). */
    private const val UTF8_CONT_TAG = 0x80

    /**
     * RFC 5545 §3.3.11 TEXT 값 이스케이핑 — `SUMMARY`/`DESCRIPTION` 같은 TEXT 값에만 적용한다.
     * UID·DATE·URL·파라미터 값은 TEXT 값 규칙 대상이 아니므로 이 함수를 거치지 않는다.
     *
     * 순서가 중요하다. 백슬래시를 먼저 두 배로 만든 뒤 `;`/`,` 를 이스케이핑해야, 이스케이핑으로
     * 새로 삽입된 백슬래시가 다시 이스케이핑되는 이중 처리를 피할 수 있다. 개행(`\r\n`/`\n`/`\r`)은
     * 마지막에 리터럴 두 글자 `\n` 으로 치환한다(실제 개행 문자를 결과에 남기지 않는다 — CRLF 불변식).
     */
    fun escapeText(raw: String): String =
        raw
            .replace("\\", "\\\\")
            .replace(";", "\\;")
            .replace(",", "\\,")
            .replace("\r\n", "\\n")
            .replace("\n", "\\n")
            .replace("\r", "\\n")

    /**
     * RFC 5545 §3.1 라인 폴딩 — content line 이 [MAX_LINE_OCTETS] 옥텟을 초과하면 CRLF + 선행 공백
     * 하나로 접는다. **모든 content line 에 예외 없이 적용**하며(SUMMARY 뿐 아니라 URL·DESCRIPTION
     * 등 어떤 프로퍼티든 동일), 속성명(`SUMMARY:` 등)도 옥텟 카운트에 포함한다.
     *
     * UTF-8 멀티바이트 문자(한글 등, 3바이트)의 중간에서 자르지 않도록, 분할 지점의 다음 바이트가
     * UTF-8 연속 바이트(상위 2비트가 `10`)이면 문자 경계까지 분할 지점을 앞으로 물린다
     * (RFC 5545 §3.1 "SHOULD be avoided" 권고 준수).
     */
    fun foldLine(line: String): String {
        val bytes = line.toByteArray(Charsets.UTF_8)
        if (bytes.size <= MAX_LINE_OCTETS) return line

        val chunks = mutableListOf<String>()
        var start = 0
        var isFirstChunk = true
        while (start < bytes.size) {
            // 첫 줄은 MAX_LINE_OCTETS 전부 콘텐츠, 후속 줄은 선행 공백 1옥텟을 뺀 나머지가 콘텐츠 한도다.
            val chunkLimit = if (isFirstChunk) MAX_LINE_OCTETS else MAX_LINE_OCTETS - 1
            var end = minOf(start + chunkLimit, bytes.size)
            while (end < bytes.size && end > start && isUtf8ContinuationByte(bytes[end])) {
                end--
            }
            chunks += String(bytes, start, end - start, Charsets.UTF_8)
            start = end
            isFirstChunk = false
        }
        return chunks.joinToString("$CRLF ")
    }

    /** UTF-8 연속 바이트(상위 2비트 `10xxxxxx`, 멀티바이트 문자의 2번째 이후 바이트)인지 판별한다. */
    private fun isUtf8ContinuationByte(byte: Byte): Boolean = (byte.toInt() and UTF8_CONT_MASK) == UTF8_CONT_TAG
}
