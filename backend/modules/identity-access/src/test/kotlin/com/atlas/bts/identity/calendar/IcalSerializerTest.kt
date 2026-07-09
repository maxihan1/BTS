// IcalSerializer 단위 테스트 — RFC 5545 골든 문자열 대조 (FR-CA-02 Task 3)

package com.atlas.bts.identity.calendar

import com.bts.shared.calendar.CalendarIssueView
import com.bts.shared.calendar.CalendarWorklogView
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

private const val CRLF = "\r\n"

/**
 * [IcalSerializer] 단위 테스트 (FR-CA-02 Task 3).
 *
 * 외부 iCal 파서 라이브러리를 도입하지 않는 관례(신규 의존성 0)에 따라
 * **골든 문자열 정확 대조**로 검증한다 — 예상 출력 전체를 직접 조립해 [assertThat] 로 비교한다.
 *
 * ## 테스트 시나리오
 * - all-day 이슈 VEVENT: 시작+마감/마감만/시작만 세 조합의 DTEND exclusive 계산.
 * - 타임드 Worklog VEVENT: UTC DTSTART~DTEND, `time_spent_seconds=0` 최소 보정.
 * - TEXT 값 이스케이핑(`,` `;` `\` 개행)과 CRLF 줄바꿈 불변식.
 * - UID 안정성(`issue-<key>@bts` / `worklog-<id>@bts`)과 VEVENT 별 DTSTAMP.
 * - 라인 폴딩(75옥텟, 속성명 포함, UTF-8 멀티바이트 경계 회피) — 한글 SUMMARY·긴 URL/DESCRIPTION.
 * - 빈 입력 → 유효한 빈 VCALENDAR.
 */
class IcalSerializerTest {
    private val now = Instant.parse("2026-07-09T10:15:30Z")
    private val feedBaseUrl = "https://bts.example.com"

    private fun issueView(
        key: String,
        summary: String = "summary-$key",
        issueType: String = "task",
        currentStateKey: String = "open",
        startDate: LocalDate? = null,
        dueDate: LocalDate? = null,
    ): CalendarIssueView =
        CalendarIssueView(
            key = key,
            summary = summary,
            issueType = issueType,
            currentStateKey = currentStateKey,
            startDate = startDate,
            dueDate = dueDate,
        )

    private fun worklogView(
        id: UUID,
        issueKey: String = "PROJ-1",
        issueSummary: String? = "Design review",
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

    /** 여러 content line 을 CRLF 로 이어붙이고 마지막에도 CRLF 를 붙인다(직렬화 결과와 동일 관례). */
    private fun crlf(vararg lines: String): String = lines.joinToString(CRLF) + CRLF

    // ── 빈 입력 ───────────────────────────────────────────────────────────────

    @Test
    fun `빈 입력이면 이벤트 0개인 유효한 VCALENDAR를 반환한다`() {
        val actual = IcalSerializer.serialize(emptyList(), emptyList(), now, feedBaseUrl)

        val expected =
            crlf(
                "BEGIN:VCALENDAR",
                "VERSION:2.0",
                "PRODID:-//BTS//Atlas Issues//KO",
                "CALSCALE:GREGORIAN",
                "X-WR-CALNAME:BTS 내 일정",
                "END:VCALENDAR",
            )
        assertThat(actual).isEqualTo(expected)
    }

    // ── 이슈 all-day VEVENT ──────────────────────────────────────────────────

    @Test
    fun `시작일과 마감일이 모두 있으면 DTEND는 마감일+1일(exclusive)이다`() {
        val issue =
            issueView(
                key = "PROJ-1",
                summary = "Design review",
                issueType = "task",
                currentStateKey = "in_progress",
                startDate = LocalDate.of(2026, 7, 10),
                dueDate = LocalDate.of(2026, 7, 12),
            )

        val actual = IcalSerializer.serialize(listOf(issue), emptyList(), now, feedBaseUrl)

        val expected =
            crlf(
                "BEGIN:VCALENDAR",
                "VERSION:2.0",
                "PRODID:-//BTS//Atlas Issues//KO",
                "CALSCALE:GREGORIAN",
                "X-WR-CALNAME:BTS 내 일정",
                "BEGIN:VEVENT",
                "UID:issue-PROJ-1@bts",
                "DTSTAMP:20260709T101530Z",
                "DTSTART;VALUE=DATE:20260710",
                "DTEND;VALUE=DATE:20260713",
                "SUMMARY:[PROJ-1] Design review",
                "DESCRIPTION:이슈 타입: task / 상태: in_progress",
                "URL:https://bts.example.com/issues/PROJ-1",
                "STATUS:CONFIRMED",
                "END:VEVENT",
                "END:VCALENDAR",
            )
        assertThat(actual).isEqualTo(expected)
    }

    @Test
    fun `마감일만 있으면 단일일 이벤트로 직렬화한다`() {
        val issue =
            issueView(
                key = "PROJ-2",
                summary = "Ship release",
                dueDate = LocalDate.of(2026, 7, 15),
            )

        val actual = IcalSerializer.serialize(listOf(issue), emptyList(), now, feedBaseUrl)

        val expected =
            crlf(
                "BEGIN:VCALENDAR",
                "VERSION:2.0",
                "PRODID:-//BTS//Atlas Issues//KO",
                "CALSCALE:GREGORIAN",
                "X-WR-CALNAME:BTS 내 일정",
                "BEGIN:VEVENT",
                "UID:issue-PROJ-2@bts",
                "DTSTAMP:20260709T101530Z",
                "DTSTART;VALUE=DATE:20260715",
                "DTEND;VALUE=DATE:20260716",
                "SUMMARY:[PROJ-2] Ship release",
                "DESCRIPTION:이슈 타입: task / 상태: open",
                "URL:https://bts.example.com/issues/PROJ-2",
                "STATUS:CONFIRMED",
                "END:VEVENT",
                "END:VCALENDAR",
            )
        assertThat(actual).isEqualTo(expected)
    }

    @Test
    fun `시작일만 있으면 시작일+1일을 DTEND로 사용한다`() {
        val issue =
            issueView(
                key = "PROJ-3",
                summary = "Kickoff",
                issueType = "epic",
                startDate = LocalDate.of(2026, 7, 20),
            )

        val actual = IcalSerializer.serialize(listOf(issue), emptyList(), now, feedBaseUrl)

        val expected =
            crlf(
                "BEGIN:VCALENDAR",
                "VERSION:2.0",
                "PRODID:-//BTS//Atlas Issues//KO",
                "CALSCALE:GREGORIAN",
                "X-WR-CALNAME:BTS 내 일정",
                "BEGIN:VEVENT",
                "UID:issue-PROJ-3@bts",
                "DTSTAMP:20260709T101530Z",
                "DTSTART;VALUE=DATE:20260720",
                "DTEND;VALUE=DATE:20260721",
                "SUMMARY:[PROJ-3] Kickoff",
                "DESCRIPTION:이슈 타입: epic / 상태: open",
                "URL:https://bts.example.com/issues/PROJ-3",
                "STATUS:CONFIRMED",
                "END:VEVENT",
                "END:VCALENDAR",
            )
        assertThat(actual).isEqualTo(expected)
    }

    // ── Worklog 타임드 VEVENT ────────────────────────────────────────────────

    @Test
    fun `Worklog는 UTC 타임드 VEVENT로 직렬화되고 SUMMARY에 소요시간을 표기한다`() {
        val worklog =
            worklogView(
                id = UUID.fromString("11111111-1111-1111-1111-111111111111"),
                issueKey = "PROJ-1",
                issueSummary = "Design review",
                startedAt = Instant.parse("2026-07-09T09:00:00Z"),
                timeSpentSeconds = 5400,
            )

        val actual = IcalSerializer.serialize(emptyList(), listOf(worklog), now, feedBaseUrl)

        val expected =
            crlf(
                "BEGIN:VCALENDAR",
                "VERSION:2.0",
                "PRODID:-//BTS//Atlas Issues//KO",
                "CALSCALE:GREGORIAN",
                "X-WR-CALNAME:BTS 내 일정",
                "BEGIN:VEVENT",
                "UID:worklog-11111111-1111-1111-1111-111111111111@bts",
                "DTSTAMP:20260709T101530Z",
                "DTSTART:20260709T090000Z",
                "DTEND:20260709T103000Z",
                "SUMMARY:1h 30m — PROJ-1 Design review",
                "END:VEVENT",
                "END:VCALENDAR",
            )
        assertThat(actual).isEqualTo(expected)
    }

    @Test
    fun `time_spent_seconds가 0이면 DTSTART와 DTEND가 같아지지 않도록 최소 1분으로 보정한다`() {
        val worklog =
            worklogView(
                id = UUID.fromString("22222222-2222-2222-2222-222222222222"),
                issueKey = "PROJ-4",
                issueSummary = null,
                startedAt = Instant.parse("2026-07-09T09:00:00Z"),
                timeSpentSeconds = 0,
            )

        val actual = IcalSerializer.serialize(emptyList(), listOf(worklog), now, feedBaseUrl)

        val expected =
            crlf(
                "BEGIN:VCALENDAR",
                "VERSION:2.0",
                "PRODID:-//BTS//Atlas Issues//KO",
                "CALSCALE:GREGORIAN",
                "X-WR-CALNAME:BTS 내 일정",
                "BEGIN:VEVENT",
                "UID:worklog-22222222-2222-2222-2222-222222222222@bts",
                "DTSTAMP:20260709T101530Z",
                "DTSTART:20260709T090000Z",
                "DTEND:20260709T090100Z",
                "SUMMARY:0m — PROJ-4",
                "END:VEVENT",
                "END:VCALENDAR",
            )
        assertThat(actual).isEqualTo(expected)
    }

    // ── 이스케이핑 · CRLF ────────────────────────────────────────────────────

    @Test
    fun `SUMMARY의 콤마 세미콜론 백슬래시 개행을 RFC 5545 규칙대로 이스케이핑한다`() {
        val issue =
            issueView(
                key = "PROJ-5",
                summary = "Fix bug, urgent; needs\\review\nplease check",
                issueType = "bug",
                startDate = LocalDate.of(2026, 7, 10),
                dueDate = LocalDate.of(2026, 7, 10),
            )

        val actual = IcalSerializer.serialize(listOf(issue), emptyList(), now, feedBaseUrl)

        val expected =
            crlf(
                "BEGIN:VCALENDAR",
                "VERSION:2.0",
                "PRODID:-//BTS//Atlas Issues//KO",
                "CALSCALE:GREGORIAN",
                "X-WR-CALNAME:BTS 내 일정",
                "BEGIN:VEVENT",
                "UID:issue-PROJ-5@bts",
                "DTSTAMP:20260709T101530Z",
                "DTSTART;VALUE=DATE:20260710",
                "DTEND;VALUE=DATE:20260711",
                "SUMMARY:[PROJ-5] Fix bug\\, urgent\\; needs\\\\review\\nplease check",
                "DESCRIPTION:이슈 타입: bug / 상태: open",
                "URL:https://bts.example.com/issues/PROJ-5",
                "STATUS:CONFIRMED",
                "END:VEVENT",
                "END:VCALENDAR",
            )
        assertThat(actual).isEqualTo(expected)
    }

    @Test
    fun `모든 줄바꿈은 CRLF이고 이스케이핑된 개행은 실제 개행 문자를 남기지 않는다`() {
        val issue =
            issueView(
                key = "PROJ-5",
                summary = "Fix bug, urgent; needs\\review\nplease check",
                issueType = "bug",
                startDate = LocalDate.of(2026, 7, 10),
                dueDate = LocalDate.of(2026, 7, 10),
            )

        val actual = IcalSerializer.serialize(listOf(issue), emptyList(), now, feedBaseUrl)

        // 원본 summary 의 실제 개행(\n)은 escape 단계에서 리터럴 "\n" 두 글자로 치환되므로,
        // CRLF 쌍을 전부 제거하면 남은 개행 문자(단독 \r 또는 \n)가 하나도 없어야 한다.
        val withoutCrlf = actual.replace(CRLF, "")
        assertThat(withoutCrlf).doesNotContain("\n")
        assertThat(withoutCrlf).doesNotContain("\r")
    }

    // ── UID 안정성 · DTSTAMP per-VEVENT ─────────────────────────────────────

    @Test
    fun `이슈와 Worklog를 함께 직렬화하면 각 VEVENT가 안정적 UID와 DTSTAMP를 포함한다`() {
        val issue =
            issueView(
                key = "PROJ-1",
                summary = "Design review",
                issueType = "task",
                currentStateKey = "in_progress",
                startDate = LocalDate.of(2026, 7, 10),
                dueDate = LocalDate.of(2026, 7, 12),
            )
        val worklog =
            worklogView(
                id = UUID.fromString("11111111-1111-1111-1111-111111111111"),
                issueKey = "PROJ-1",
                issueSummary = "Design review",
                startedAt = Instant.parse("2026-07-09T09:00:00Z"),
                timeSpentSeconds = 5400,
            )

        val actual = IcalSerializer.serialize(listOf(issue), listOf(worklog), now, feedBaseUrl)

        val expected =
            crlf(
                "BEGIN:VCALENDAR",
                "VERSION:2.0",
                "PRODID:-//BTS//Atlas Issues//KO",
                "CALSCALE:GREGORIAN",
                "X-WR-CALNAME:BTS 내 일정",
                "BEGIN:VEVENT",
                "UID:issue-PROJ-1@bts",
                "DTSTAMP:20260709T101530Z",
                "DTSTART;VALUE=DATE:20260710",
                "DTEND;VALUE=DATE:20260713",
                "SUMMARY:[PROJ-1] Design review",
                "DESCRIPTION:이슈 타입: task / 상태: in_progress",
                "URL:https://bts.example.com/issues/PROJ-1",
                "STATUS:CONFIRMED",
                "END:VEVENT",
                "BEGIN:VEVENT",
                "UID:worklog-11111111-1111-1111-1111-111111111111@bts",
                "DTSTAMP:20260709T101530Z",
                "DTSTART:20260709T090000Z",
                "DTEND:20260709T103000Z",
                "SUMMARY:1h 30m — PROJ-1 Design review",
                "END:VEVENT",
                "END:VCALENDAR",
            )
        assertThat(actual).isEqualTo(expected)
    }

    // ── 라인 폴딩(75옥텟, 속성명 포함, UTF-8 멀티바이트 경계 회피) ────────────

    @Test
    fun `한글 SUMMARY는 UTF-8 3바이트 문자 경계를 넘지 않도록 폴딩된다`() {
        // "가"(3바이트) 25개 — "SUMMARY:[PROJ-1] "(17옥텟) + 25*3=75옥텟 = 92옥텟, 75옥텟 초과.
        // naive 75옥텟 절단은 20번째 "가"의 중간(1바이트째)에서 잘리므로,
        // 폴딩 로직이 19번째 문자 뒤(74옥텟)에서 멈춰야 한다.
        val issue =
            issueView(
                key = "PROJ-1",
                summary = "가".repeat(25),
                startDate = LocalDate.of(2026, 7, 10),
                dueDate = LocalDate.of(2026, 7, 10),
            )

        val actual = IcalSerializer.serialize(listOf(issue), emptyList(), now, feedBaseUrl)

        val expected =
            crlf(
                "BEGIN:VCALENDAR",
                "VERSION:2.0",
                "PRODID:-//BTS//Atlas Issues//KO",
                "CALSCALE:GREGORIAN",
                "X-WR-CALNAME:BTS 내 일정",
                "BEGIN:VEVENT",
                "UID:issue-PROJ-1@bts",
                "DTSTAMP:20260709T101530Z",
                "DTSTART;VALUE=DATE:20260710",
                "DTEND;VALUE=DATE:20260711",
                "SUMMARY:[PROJ-1] " + "가".repeat(19),
                " " + "가".repeat(6),
                "DESCRIPTION:이슈 타입: task / 상태: open",
                "URL:https://bts.example.com/issues/PROJ-1",
                "STATUS:CONFIRMED",
                "END:VEVENT",
                "END:VCALENDAR",
            )
        assertThat(actual).isEqualTo(expected)
    }

    @Test
    fun `75옥텟을 초과하는 DESCRIPTION은 속성명을 포함한 옥텟 수 기준으로 폴딩된다`() {
        val issue =
            issueView(
                key = "PROJ-6",
                summary = "Short",
                currentStateKey = "requires-additional-manager-approval-before-final-close-signoff",
                startDate = LocalDate.of(2026, 7, 10),
                dueDate = LocalDate.of(2026, 7, 10),
            )

        val actual = IcalSerializer.serialize(listOf(issue), emptyList(), now, feedBaseUrl)

        val expected =
            crlf(
                "BEGIN:VCALENDAR",
                "VERSION:2.0",
                "PRODID:-//BTS//Atlas Issues//KO",
                "CALSCALE:GREGORIAN",
                "X-WR-CALNAME:BTS 내 일정",
                "BEGIN:VEVENT",
                "UID:issue-PROJ-6@bts",
                "DTSTAMP:20260709T101530Z",
                "DTSTART;VALUE=DATE:20260710",
                "DTEND;VALUE=DATE:20260711",
                "SUMMARY:[PROJ-6] Short",
                "DESCRIPTION:이슈 타입: task / 상태: requires-additional-manager-appro",
                " val-before-final-close-signoff",
                "URL:https://bts.example.com/issues/PROJ-6",
                "STATUS:CONFIRMED",
                "END:VEVENT",
                "END:VCALENDAR",
            )
        assertThat(actual).isEqualTo(expected)
    }

    @Test
    fun `75옥텟을 초과하는 URL도 속성명을 포함한 옥텟 수 기준으로 폴딩된다`() {
        val longKey = "VERYLONGPROJECTKEYNAME-1234567890123456789"
        val issue =
            issueView(
                key = longKey,
                summary = "Short",
                startDate = LocalDate.of(2026, 7, 10),
                dueDate = LocalDate.of(2026, 7, 10),
            )

        val actual = IcalSerializer.serialize(listOf(issue), emptyList(), now, feedBaseUrl)

        val expected =
            crlf(
                "BEGIN:VCALENDAR",
                "VERSION:2.0",
                "PRODID:-//BTS//Atlas Issues//KO",
                "CALSCALE:GREGORIAN",
                "X-WR-CALNAME:BTS 내 일정",
                "BEGIN:VEVENT",
                "UID:issue-$longKey@bts",
                "DTSTAMP:20260709T101530Z",
                "DTSTART;VALUE=DATE:20260710",
                "DTEND;VALUE=DATE:20260711",
                "SUMMARY:[$longKey] Short",
                "DESCRIPTION:이슈 타입: task / 상태: open",
                "URL:https://bts.example.com/issues/VERYLONGPROJECTKEYNAME-12345678901234567",
                " 89",
                "STATUS:CONFIRMED",
                "END:VEVENT",
                "END:VCALENDAR",
            )
        assertThat(actual).isEqualTo(expected)
    }
}
