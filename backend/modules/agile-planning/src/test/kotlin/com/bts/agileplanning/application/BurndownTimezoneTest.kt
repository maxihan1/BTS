// worklog 일 귀속이 보드 타임존을 따르는지 · 근무일 설정이 실제 응답에 배선됐는지 재는 단위테스트 (부채 177 Task 12)

package com.bts.agileplanning.application

import com.bts.agileplanning.domain.Sprint
import com.bts.agileplanning.domain.SprintStatus
import com.bts.agileplanning.domain.burndown.BurndownPoint
import com.bts.agileplanning.repository.BoardSettingsRepository
import com.bts.agileplanning.repository.BoardWorkingDays
import com.bts.agileplanning.repository.SprintRepository
import com.bts.shared.burndown.BurndownSource
import com.bts.shared.burndown.SprintBurndownLookupPort
import com.bts.shared.burndown.WorklogContribution
import com.bts.shared.permission.IssuePermissionResolver
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.SoftAssertions.assertSoftly
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID

/** 스프린트 시작 — 월요일. */
private val SPRINT_START: LocalDate = LocalDate.of(2026, 6, 29)

/** 스프린트 종료 — 일요일. 14 달력일 · 그중 근무일(월~금)은 10일이다. */
private val SPRINT_END: LocalDate = LocalDate.of(2026, 7, 12)

/** 심야 worklog. UTC 로는 7/2, `Asia/Seoul`(+9) 에서는 **7/3 08:30** 이다. */
private val LATE_NIGHT_UTC: Instant = Instant.parse("2026-07-02T23:30:00Z")

/** 새벽 worklog. UTC 로는 7/2, `America/New_York`(-4, EDT) 에서는 **7/1 22:00** 이다. */
private val EARLY_MORNING_UTC: Instant = Instant.parse("2026-07-02T02:00:00Z")

/** 한낮 worklog. UTC·서울·뉴욕 **셋 다 7/2** 다 — 어느 축으로 옮겨도 칸이 바뀌지 않는다. */
private val MIDDAY_UTC: Instant = Instant.parse("2026-07-02T12:00:00Z")

private val JUL_01: LocalDate = LocalDate.of(2026, 7, 1)
private val JUL_02: LocalDate = LocalDate.of(2026, 7, 2)
private val JUL_03: LocalDate = LocalDate.of(2026, 7, 3)

private const val SEOUL = "Asia/Seoul"
private const val NEW_YORK = "America/New_York"

private val WEEKDAY_KEYS = listOf("MON", "TUE", "WED", "THU", "FRI")

private const val SCOPE_SECONDS = 360_000L
private const val WORKLOG_SECONDS = 21_600L

/**
 * 보드 타임존 일 귀속과 근무일 배선의 단위테스트 (부채 177 Task 12 · 스펙 R6·R10·G2).
 *
 * MockK 로 [SprintRepository]·[SprintBurndownLookupPort]·[BoardSettingsRepository] 를 세워
 * [SprintBurndownService] 만 재는 순수 단위테스트다(Testcontainers 를 타지 않는다).
 *
 * ## 이 파일이 지는 두 축
 *
 * | 축 | 무엇을 재는가 | 왜 필요한가 |
 * |---|---|---|
 * | ① 일 귀속 | worklog 가 **보드 타임존** 날짜 칸에 들어간다 | 포트는 UTC [Instant] 원본만 나른다(Task 30) — 칸 배치는 이쪽 책임이다 |
 * | ② 근무일 배선 | 보드 설정의 근무일이 **응답 point 수**를 바꾼다 | [com.bts.agileplanning.domain.burndown.BurndownCalculator] 의 근무일 축이 프로덕션 호출자 0 이었다 |
 *
 * ★②가 이 파일의 숨은 핵심이다. Task 11 이 계산기에 근무일 축을 넣었지만 그 인자를 넘기는
 * 프로덕션 코드가 없어 **설정은 저장되는데 차트가 안 바뀌는** 상태였다(스펙 G2 가 피하려던 형태).
 * 계산기 단위테스트([com.bts.agileplanning.domain.burndown.BurndownWorkingDaysTest])는 계산기를
 * 직접 호출하므로 배선 부재를 **원리적으로 못 잡는다** — 그 사각을 이 파일이 닫는다.
 *
 * ## 공허 방지 — 대조군을 쌍으로 둔다
 * - **타임존 대조군**: 같은 worklog 가 미설정 보드에서는 **UTC 칸**에 남는다를 함께 잰다.
 *   없으면 「무조건 +9 하는」 구현이 통과한다.
 * - **UTC− 방향**: `America/New_York` 로 **전날 칸**으로 가는 것도 잰다. `+9` 만 재면 부호를
 *   뒤집은 구현이 통과한다.
 * - **경계를 넘지 않는 낮 시각**: 세 타임존에서 **같은 칸**이어야 한다. 「무조건 하루 옮기는」
 *   구현을 잡는다.
 * - **근무일 대조군**: 「설정하면 10개」와 「미설정이면 14개」를 함께 잰다. 하나만 두면
 *   반대 구현(배선 누락 · 미설정을 월~금으로 채움)이 통과한다.
 *
 * 각 대조군은 [assertSoftly] 로 묶는다 — soft 가 아니면 앞쪽이 먼저 죽어 뒤쪽 판정이 **실행조차
 * 되지 않고**, 「한쪽만 깨는 뮤테이션이 반대쪽을 초록으로 남긴다」를 기계가 보여주지 못한다.
 *
 * ## 시각 상수가 실제로 경계를 넘는지도 테스트가 직접 확인한다
 * 낮 시각으로 쓰면 두 타임존에서 같은 날이라 **판정을 지워도 통과하는** 공허한 테스트가 된다.
 * 그래서 각 테스트 첫머리에서 상수 자체의 UTC/로컬 날짜를 단언한다(픽스처 자기검증).
 */
class BurndownTimezoneTest {
    private val actorId: UUID = UUID.randomUUID()
    private val sprintId: UUID = UUID.randomUUID()
    private val boardId: UUID = UUID.randomUUID()
    private val projectKey = "ATLAS"

    /** 스프린트 종료 뒤로 고정된 "오늘" — 전 구간이 asOf 안쪽이라 모든 point 가 non-null 이다. */
    private val fixedClock: Clock = Clock.fixed(Instant.parse("2026-07-20T00:00:00Z"), ZoneOffset.UTC)

    private val sprint =
        Sprint(
            id = sprintId,
            projectKey = projectKey,
            boardId = boardId,
            name = "Sprint 1",
            goal = null,
            status = SprintStatus.ACTIVE,
            startDate = SPRINT_START,
            endDate = SPRINT_END,
            version = 0L,
        )

    // ── ① 일 귀속 (보드 타임존) ────────────────────────────────────────────────

    @Test
    fun `control pair - 심야 worklog 는 미설정이면 UTC 칸, Asia_Seoul 이면 다음 날 칸이다`() {
        // 픽스처 자기검증 — 이 상수가 경계를 넘지 않으면 아래 두 단언이 조용히 같은 값이 된다.
        assertThat(LocalDate.ofInstant(LATE_NIGHT_UTC, ZoneOffset.UTC)).isEqualTo(JUL_02)
        assertThat(LocalDate.ofInstant(LATE_NIGHT_UTC, ZoneId.of(SEOUL))).isEqualTo(JUL_03)

        val unset = creditedDate(burndown(timezone = null, startedAt = LATE_NIGHT_UTC))
        val seoul = creditedDate(burndown(timezone = SEOUL, startedAt = LATE_NIGHT_UTC))

        assertSoftly { softly ->
            softly.assertThat(unset)
                .describedAs("타임존 미설정 = UTC. 23:30Z 는 UTC 로 7/2 다(현행 유지 · E7)")
                .isEqualTo(JUL_02)
            softly.assertThat(seoul)
                .describedAs("Asia/Seoul(+9) 에서 23:30Z 는 7/3 08:30 — 다음 날 칸이다(R10)")
                .isEqualTo(JUL_03)
        }
    }

    @Test
    fun `control pair - 새벽 worklog 는 America_New_York 에서 전날 칸으로 간다`() {
        assertThat(LocalDate.ofInstant(EARLY_MORNING_UTC, ZoneOffset.UTC)).isEqualTo(JUL_02)
        assertThat(LocalDate.ofInstant(EARLY_MORNING_UTC, ZoneId.of(NEW_YORK))).isEqualTo(JUL_01)

        val unset = creditedDate(burndown(timezone = null, startedAt = EARLY_MORNING_UTC))
        val newYork = creditedDate(burndown(timezone = NEW_YORK, startedAt = EARLY_MORNING_UTC))

        assertSoftly { softly ->
            softly.assertThat(unset)
                .describedAs("타임존 미설정 = UTC. 02:00Z 는 UTC 로 7/2 다")
                .isEqualTo(JUL_02)
            softly.assertThat(newYork)
                .describedAs("America/New_York(-4) 에서 02:00Z 는 7/1 22:00 — 부호가 반대다")
                .isEqualTo(JUL_01)
        }
    }

    @Test
    fun `경계를 넘지 않는 낮 시각은 세 타임존에서 모두 같은 칸이다`() {
        assertThat(LocalDate.ofInstant(MIDDAY_UTC, ZoneOffset.UTC)).isEqualTo(JUL_02)
        assertThat(LocalDate.ofInstant(MIDDAY_UTC, ZoneId.of(SEOUL))).isEqualTo(JUL_02)
        assertThat(LocalDate.ofInstant(MIDDAY_UTC, ZoneId.of(NEW_YORK))).isEqualTo(JUL_02)

        assertSoftly { softly ->
            softly.assertThat(creditedDate(burndown(timezone = null, startedAt = MIDDAY_UTC)))
                .describedAs("미설정 — 12:00Z 는 7/2")
                .isEqualTo(JUL_02)
            softly.assertThat(creditedDate(burndown(timezone = SEOUL, startedAt = MIDDAY_UTC)))
                .describedAs("서울에서 12:00Z 는 7/2 21:00 — 같은 칸. 무조건 하루 옮기면 여기서 깨진다")
                .isEqualTo(JUL_02)
            softly.assertThat(creditedDate(burndown(timezone = NEW_YORK, startedAt = MIDDAY_UTC)))
                .describedAs("뉴욕에서 12:00Z 는 7/2 08:00 — 같은 칸")
                .isEqualTo(JUL_02)
        }
    }

    @Test
    fun `같은 UTC 날짜의 두 worklog 가 보드 타임존에서는 서로 다른 칸으로 갈린다`() {
        // 포트가 사전집계를 풀어 시각 원본을 나르기에(Task 30) 비로소 가능한 판정이다.
        val entries =
            listOf(
                WorklogContribution(JUL_02, WORKLOG_SECONDS, MIDDAY_UTC),
                WorklogContribution(JUL_02, WORKLOG_SECONDS, LATE_NIGHT_UTC),
            )

        val utcBuckets = completedDeltas(burndown(timezone = null, entries = entries))
        val seoulBuckets = completedDeltas(burndown(timezone = SEOUL, entries = entries))

        assertSoftly { softly ->
            softly.assertThat(utcBuckets)
                .describedAs("UTC 로는 둘 다 7/2 — 한 칸에 합산된다")
                .isEqualTo(mapOf(JUL_02 to WORKLOG_SECONDS * 2))
            softly.assertThat(seoulBuckets)
                .describedAs("서울에서는 7/2 와 7/3 로 갈린다. 합계는 보존된다")
                .isEqualTo(mapOf(JUL_02 to WORKLOG_SECONDS, JUL_03 to WORKLOG_SECONDS))
        }
    }

    // ── ② 근무일 배선 ─────────────────────────────────────────────────────────

    @Test
    fun `control pair - 근무일을 설정하면 10개, 미설정이면 달력일 14개다`() {
        // 픽스처 자기검증 — 날짜 상수가 틀어지면 개수 단언이 조용히 공허해진다.
        assertThat(SPRINT_START.dayOfWeek).isEqualTo(DayOfWeek.MONDAY)
        assertThat(SPRINT_END.dayOfWeek).isEqualTo(DayOfWeek.SUNDAY)
        assertThat(ChronoUnit.DAYS.between(SPRINT_START, SPRINT_END) + 1).isEqualTo(14L)

        val unset = burndown(standardDays = null)
        val monToFri = burndown(standardDays = WEEKDAY_KEYS)

        assertSoftly { softly ->
            softly.assertThat(unset.map { it.date })
                .describedAs("미설정(NULL) = 달력일 전부. 설정을 안 만진 보드의 차트는 안 바뀐다(R6)")
                .hasSize(14)
            softly.assertThat(monToFri.map { it.date })
                .describedAs("월~금 설정이 응답에 닿는다 — 주말 4일이 축에서 빠져 10개다(G2 배선)")
                .hasSize(10)
                .noneMatch { it.dayOfWeek == DayOfWeek.SATURDAY || it.dayOfWeek == DayOfWeek.SUNDAY }
        }
    }

    @Test
    fun `control pair - 비근무일은 근무일이 설정된 보드에서만 축을 줄인다`() {
        val holiday = listOf(JUL_01)

        val unset = burndown(standardDays = null, nonWorkingDates = holiday)
        val monToFri = burndown(standardDays = WEEKDAY_KEYS, nonWorkingDates = holiday)

        assertSoftly { softly ->
            softly.assertThat(unset.map { it.date })
                .describedAs("미설정 보드는 비근무일만 등록돼 있어도 달력일 전부다(standardDays==null ⇒ calendar=null)")
                .hasSize(14)
                .contains(JUL_01)
            softly.assertThat(monToFri.map { it.date })
                .describedAs("월~금 10개에서 비근무일 7/1 이 한 칸 더 빠져 9개다")
                .hasSize(9)
                .doesNotContain(JUL_01)
        }
    }

    // ── 헬퍼 ──────────────────────────────────────────────────────────────────

    /**
     * 보드 설정을 주고 번다운 시계열을 계산한다.
     *
     * @param timezone 보드 타임존. null 이면 미설정(UTC).
     * @param standardDays 표준 근무일 키. null 이면 미설정(달력일 전부).
     * @param nonWorkingDates 비근무일.
     * @param startedAt worklog 1건의 시작 시각. [entries] 를 직접 주면 무시된다.
     * @param entries worklog 목록을 통째로 지정할 때 사용한다.
     */
    private fun burndown(
        timezone: String? = null,
        standardDays: List<String>? = null,
        nonWorkingDates: List<LocalDate> = emptyList(),
        startedAt: Instant = MIDDAY_UTC,
        entries: List<WorklogContribution> =
            listOf(WorklogContribution(LocalDate.ofInstant(startedAt, ZoneOffset.UTC), WORKLOG_SECONDS, startedAt)),
    ): List<BurndownPoint> {
        val sprintRepository =
            mockk<SprintRepository>().also {
                every { it.findById(sprintId) } returns sprint
                every { it.findIssueKeys(sprintId) } returns listOf("ATLAS-1")
            }
        val permissionResolver =
            mockk<IssuePermissionResolver>().also {
                every { it.hasPermission(any(), any(), any()) } returns true
            }
        val burndownPort =
            mockk<SprintBurndownLookupPort>().also {
                every { it.fetchBurndownSource(any(), any(), any()) } returns
                    BurndownSource(totalOriginalEstimateSeconds = SCOPE_SECONDS, worklogEntries = entries)
            }
        val settingsRepository =
            mockk<BoardSettingsRepository>().also {
                every { it.findWorkingDays(boardId) } returns
                    BoardWorkingDays(
                        standardDays = standardDays,
                        timezone = timezone,
                        nonWorkingDates = nonWorkingDates,
                    )
            }

        return SprintBurndownService(
            sprintRepository,
            permissionResolver,
            burndownPort,
            settingsRepository,
            fixedClock,
        ).getBurndown(actorId, sprintId).points
    }

    /**
     * worklog 가 실제로 귀속된 칸 — 누적 completed 가 처음 0 을 벗어나는 날이다.
     *
     * completed 는 누적값이라 「어느 날 올랐는가」가 곧 일 귀속이다.
     */
    private fun creditedDate(points: List<BurndownPoint>): LocalDate? =
        points.firstOrNull { (it.completedSeconds ?: 0L) > 0L }?.date

    /**
     * 누적 completed 를 날짜별 증분으로 되돌린다 — 0 인 날은 버린다.
     *
     * 「칸이 갈렸는가」와 「합계가 보존되는가」를 한 값으로 함께 볼 수 있다.
     */
    private fun completedDeltas(points: List<BurndownPoint>): Map<LocalDate, Long> {
        var previous = 0L
        return points.mapNotNull { point ->
            val cumulative = point.completedSeconds ?: previous
            val delta = cumulative - previous
            previous = cumulative
            if (delta == 0L) null else point.date to delta
        }.toMap()
    }
}
