// 추정 탭의 시간 추적 설정이 번다운 세로축(시간 ↔ 이슈 개수)을 실제로 가르는지 재는 단위테스트 (부채 177 task-35)

package com.bts.agileplanning.application

import com.bts.agileplanning.domain.Sprint
import com.bts.agileplanning.domain.SprintStatus
import com.bts.agileplanning.domain.burndown.BurndownPoint
import com.bts.agileplanning.domain.burndown.BurndownUnit
import com.bts.agileplanning.repository.BoardSettingsRepository
import com.bts.agileplanning.repository.BoardWorkingDays
import com.bts.agileplanning.repository.SprintRepository
import com.bts.shared.burndown.BurndownSource
import com.bts.shared.burndown.IssueCompletion
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
import java.util.UUID

/** 3일 스프린트 시작 — 수요일. 주말이 걸치지 않아 근무일 설정과 무관하게 축이 3점이다. */
private val SHORT_START: LocalDate = LocalDate.of(2026, 7, 1)

/** 3일 스프린트 종료 — 금요일. */
private val SHORT_END: LocalDate = LocalDate.of(2026, 7, 3)

/** 2주 스프린트 시작 — 월요일. 근무일 축 대조군(14 vs 10)이 성립하는 구간이다. */
private val LONG_START: LocalDate = LocalDate.of(2026, 6, 29)

/** 2주 스프린트 종료 — 일요일. 달력 14일 · 그중 월~금은 10일이다. */
private val LONG_END: LocalDate = LocalDate.of(2026, 7, 12)

private val JULY_2: LocalDate = LocalDate.of(2026, 7, 2)
private val JULY_3: LocalDate = LocalDate.of(2026, 7, 3)

/** 한낮 — UTC·서울 모두 7/2 다. 타임존을 바꿔도 칸이 안 움직이는 대조 시각이다. */
private val MIDDAY_UTC: Instant = Instant.parse("2026-07-02T12:00:00Z")

/** 심야 — UTC 로는 7/2, `Asia/Seoul`(+9) 에서는 **7/3 08:30** 이다. */
private val LATE_NIGHT_UTC: Instant = Instant.parse("2026-07-02T23:30:00Z")

private const val SEOUL = "Asia/Seoul"
private val WEEKDAY_KEYS = listOf("MON", "TUE", "WED", "THU", "FRI")

/** 24시간(초). 부채 177 리뷰가 제시한 표의 「첫날 24시간」이다. */
private const val SCOPE_SECONDS = 86_400L

/** 8시간(초). 하루치 worklog — 24h 스코프에서 16h 를 남긴다. */
private const val WORKLOG_SECONDS = 28_800L

/** 스프린트의 가시 이슈 수. 표의 「첫날 3개」다. */
private const val ISSUE_COUNT = 3L

private const val NONE = "NONE"
private const val REMAINING_AND_SPENT = "REMAINING_AND_SPENT"

/**
 * 「추정」 탭의 `time_tracking` 이 번다운 **세로축의 단위**를 가르는지 재는 단위테스트 (부채 177 task-35).
 *
 * ## 이 파일이 닫는 결함
 *
 * task-9(저장)·task-17(화면)·task-31(되싣기)이 끝난 뒤에도 `time_tracking` 은
 * **저장 → 조회 → 자기 화면 되그림**으로 닫힌 고리였다. 바깥으로 나가는 선이 0개라
 * 「200 OK 로 저장되고 새로고침해도 유지되는데 번다운은 픽셀 하나 안 바뀌는」 설정이었고,
 * 그럼에도 스펙 S2 는 *"번다운의 진행 계산이 그 방식을 따른다"* 고 주장하고 있었다.
 * 이 파일은 그 문장을 **기계가 재는 판정으로** 바꾼다.
 *
 * ## 무엇이 축을 가르는가 (task-35 결정)
 *
 * | `time_tracking` | 세로축 | 첫날 | 이슈 하나 완료 후 |
 * |---|---|---|---|
 * | `REMAINING_AND_SPENT` | 시간(초) | 24시간 | 16시간 |
 * | `NONE` | **이슈 개수** | **3개** | **2개** |
 *
 * `NONE` 은 *"시간으로 진행을 재지 않는다"* 이므로 잔여를 초로 그릴 근거가 없다. 그때 남는
 * 유일한 척도가 개수다(J36 · 지라의 Estimation 탭이 같은 자리에서 개수 축을 낸다).
 *
 * ## ★기본값 `NONE` 의 뜻 — 배포 시점에 기존 보드의 차트가 **바뀐다**
 *
 * `boards.time_tracking` 은 `NOT NULL DEFAULT 'NONE'`(V509)이라 **미설정을 표현할 자리가 없다.**
 * 형제 `working_days` 가 쓴 「NULL = 미설정 = 현행 유지」(R6) 관용구를 여기서는 쓸 수 없고,
 * 그래서 이 task 는 **선언된 변경**을 택했다 — 판정 근거의 정본은
 * [SprintBurndownService.resolveUnit] KDoc 이다. 이 파일의 `미설정 대조군` 테스트가 그 결정을 못 박는다.
 *
 * ## 공허 방지 — 대조군을 쌍으로 둔다
 * - **게이팅 대조군**: 같은 원천 데이터([BurndownSource])에 설정만 바꿔 두 축을 함께 잰다.
 *   한쪽만 두면 「항상 시간 축」/「항상 개수 축」 중 하나가 그대로 통과한다.
 * - **입력 분리 대조군**: 개수 축이 worklog 를 쓰지 않고, 시간 축이 완료를 쓰지 않는지 잰다.
 *   두 축이 같은 알고리즘을 공유하므로 **입력을 잘못 꽂아도 그림은 그려진다** — 그 자리를 막는다.
 * - **근무일·타임존 대조군**: 개수 축에서도 T12·T30 이 닫은 두 축(근무일 · 보드 타임존)이
 *   그대로 서는지 잰다. 새 축을 만들며 기존 축을 조용히 잃는 것이 이 변경의 최대 위험이다.
 *
 * 각 대조군은 [assertSoftly] 로 묶는다 — soft 가 아니면 앞쪽이 먼저 죽어 뒤쪽 판정이 실행조차
 * 되지 않고, 「한쪽만 깨는 뮤테이션이 반대쪽을 초록으로 남긴다」를 기계가 보여주지 못한다.
 *
 * ## 뮤테이션 검증 이력 — 재현 가능한 증거 (2026-09-06 실측)
 *
 * 「깨면 red 가 당연한 방향」이 아니라 **느슨한 구현과 올바른 구현이 갈리는 입력**인지를 잰 기록이다.
 * 잰 명령(6회 모두 동일).
 * ```
 * ./gradlew :modules:agile-planning:test --rerun --no-build-cache \
 *     --tests '*Burndown*' --tests '*SprintBurndown*'
 * ```
 * 이 명령이 도는 모집단은 **39건**이다(이 파일 7 + 계산기 13 + 타임존 6 + 서비스 4 + 컨트롤러 3 +
 * 통합 6). 아래 「red」는 그 39건 중 실제로 빨개진 것을 XML 로 센 값이다.
 *
 * | # | 구현에 건 뮤테이션 | red | 그 뮤테이션이 **여전히 통과시키는** 것 |
 * |---|---|---|---|
 * | M1 | `resolveUnit` 이 **항상 SECONDS**(게이팅 끊기) | 6 — 이 파일 5건 + 통합 `S1b` | 이 파일의 빈 스프린트·근무일 2건, 시간 축 전부 |
 * | M2 | 반대로 **항상 ISSUE_COUNT** | 8 — 이 파일 2건 + 타임존 4건 + 서비스 해피패스 + 통합 `S1` | 계산기 단위테스트 13건, 근무일 대조군, 컨트롤러 3건 |
 * | M3 | `TimeTracking.fromStored` 의 폴백을 `REMAINING_AND_SPENT` 로 | 1 — `보드 행이 사라져 …` | 나머지 38건 전부 |
 * | M4 | 개수 축에 **worklog** 를 꽂는다(입력 뒤바꿈) | 4 — 이 파일 3건 + 통합 `S1b` | 시간 축 전부, 근무일·빈 스프린트 |
 * | M5 | 완료 일 귀속을 **UTC 고정**(보드 타임존 무시) | 1 — `완료 일 귀속 …` | 나머지 38건 전부 |
 * | M6 | 개수 축에서 `workingCalendar = null`(배선 누락) | 1 — `개수 축도 근무일 …` | 나머지 38건 전부 |
 *
 * ★**M1 이 이 task 의 존재 이유다.** 게이팅을 끊으면 「저장은 되는데 차트가 안 바뀐다」로 정확히
 * 돌아가는데, task-35 이전의 테스트는 **그 상태에서 전부 초록**이었다. 지금은 6건이 죽는다.
 *
 * ★**M1 이 곧 결정 ②의 반대 선택이기도 하다.** `NONE -> SECONDS` 로 바꾸는 것(=「미설정이면
 * 현행 유지」)은 `REMAINING_AND_SPENT` 가 이미 SECONDS 라 M1 과 **같은 뮤턴트**다. 그래서 결정 ②를
 * 뒤집으면 `미설정 대조군` 과 통합 `S1b` 가 red 가 된다 — 결정이 코드에만 적힌 산문이 아니라
 * 기계가 지키는 판정이라는 뜻이다.
 *
 * ★**M1 에서 `개수 축도 근무일 설정을 따른다` 가 살아남는다**(실측). 그 판정은 point **개수**만
 * 재는데 축의 길이는 단위와 무관하기 때문이다 — 근무일 배선을 지키는 것은 M6 이고, 두 판정이
 * 서로 다른 것을 지킨다. 「근무일 테스트가 게이팅도 지켜 줄 것」이라고 적었으면 실측에 반증됐다.
 *
 * ★**M3 은 1건만 죽인다.** `fromStored` 의 폴백은 `findTimeTracking` 이 null 일 때만 닿고,
 * 그 입력을 가진 판정이 이 파일에 하나뿐이라 그렇다. 좁게 특정된다는 뜻이지 약하다는 뜻이 아니다.
 *
 * ★**M5·M6 은 「새 축을 만들며 기존 축을 잃는다」를 잡는 자리다.** 둘 다 개수 축에서만 배선을
 * 끊었고 시간 축 판정은 전부 초록이다 — 시간 축 테스트만으로는 원리적으로 못 잡는다.
 */
class BurndownIssueCountAxisTest {
    private val actorId: UUID = UUID.randomUUID()
    private val sprintId: UUID = UUID.randomUUID()
    private val boardId: UUID = UUID.randomUUID()
    private val projectKey = "ATLAS"

    /** 스프린트 종료 뒤로 고정된 "오늘" — 전 구간이 asOf 안쪽이라 모든 point 가 non-null 이다. */
    private val fixedClock: Clock = Clock.fixed(Instant.parse("2026-07-20T00:00:00Z"), ZoneOffset.UTC)

    // ── ① 게이팅 — 설정이 세로축을 가른다 ────────────────────────────────────

    @Test
    fun `control pair - NONE 이면 개수 축, REMAINING_AND_SPENT 이면 시간 축이다`() {
        // 같은 원천 데이터다 — 갈리는 것은 오직 보드 설정 하나뿐이다.
        val source =
            BurndownSource(
                totalOriginalEstimateSeconds = SCOPE_SECONDS,
                worklogEntries = listOf(worklog(MIDDAY_UTC)),
                visibleIssueCount = ISSUE_COUNT,
                issueCompletions = listOf(IssueCompletion("ATLAS-1", MIDDAY_UTC)),
            )

        val counted = result(timeTracking = NONE, source = source)
        val timed = result(timeTracking = REMAINING_AND_SPENT, source = source)

        assertSoftly { softly ->
            softly.assertThat(counted.unit)
                .describedAs("NONE 은 시간으로 진행을 재지 않는다 — 남는 척도는 개수다")
                .isEqualTo(BurndownUnit.ISSUE_COUNT)
            softly.assertThat(counted.totalScopeSeconds)
                .describedAs("개수 축의 스코프는 가시 이슈 수다")
                .isEqualTo(ISSUE_COUNT)
            softly.assertThat(counted.points.map { it.remainingSeconds })
                .describedAs("첫날 3개 → 이슈 하나 완료 후 2개 (리뷰 표)")
                .containsExactly(3L, 2L, 2L)
            softly.assertThat(timed.unit)
                .describedAs("REMAINING_AND_SPENT 는 시간 축이다 — 현행 계산 그대로")
                .isEqualTo(BurndownUnit.SECONDS)
            softly.assertThat(timed.totalScopeSeconds)
                .describedAs("시간 축의 스코프는 Σ original_estimate_seconds 다")
                .isEqualTo(SCOPE_SECONDS)
            softly.assertThat(timed.points.map { it.remainingSeconds })
                .describedAs("첫날 24시간 → 8시간 기록 후 16시간 (리뷰 표)")
                .containsExactly(SCOPE_SECONDS, 57_600L, 57_600L)
        }
    }

    @Test
    fun `control pair - 개수 축은 worklog 를, 시간 축은 완료를 서로 쓰지 않는다`() {
        // 개수 축에 worklog 만 있다 — 완료가 없으므로 개수는 줄지 않아야 한다.
        val worklogOnly =
            BurndownSource(
                totalOriginalEstimateSeconds = SCOPE_SECONDS,
                worklogEntries = listOf(worklog(MIDDAY_UTC)),
                visibleIssueCount = ISSUE_COUNT,
                issueCompletions = emptyList(),
            )
        // 시간 축에 완료만 있다 — worklog 가 없으므로 잔여 시간은 줄지 않아야 한다.
        val completionOnly =
            BurndownSource(
                totalOriginalEstimateSeconds = SCOPE_SECONDS,
                worklogEntries = emptyList(),
                visibleIssueCount = ISSUE_COUNT,
                issueCompletions = listOf(IssueCompletion("ATLAS-1", MIDDAY_UTC)),
            )

        val counted = result(timeTracking = NONE, source = worklogOnly)
        val timed = result(timeTracking = REMAINING_AND_SPENT, source = completionOnly)

        assertSoftly { softly ->
            softly.assertThat(counted.points.map { it.remainingSeconds })
                .describedAs("개수 축이 worklog 를 소진으로 읽으면 여기서 줄어든다 — 입력을 잘못 꽂은 것이다")
                .containsExactly(3L, 3L, 3L)
            softly.assertThat(timed.points.map { it.remainingSeconds })
                .describedAs("시간 축이 완료를 소진으로 읽으면 여기서 줄어든다")
                .containsExactly(SCOPE_SECONDS, SCOPE_SECONDS, SCOPE_SECONDS)
        }
    }

    // ── ② 기본값 NONE 의 뜻 (task-35 결정 ②) ────────────────────────────────

    @Test
    fun `미설정 대조군 - 설정을 한 번도 만지지 않은 보드는 DB 기본값 NONE 이라 개수 축이다`() {
        // V509 가 기존 보드 전량을 'NONE' 으로 백필한다 — 「아무도 고르지 않은 상태」가 곧 이 값이다.
        val untouched = result(timeTracking = NONE, source = defaultSource())

        assertThat(untouched.unit)
            .describedAs("기존 보드의 차트가 배포 시점에 개수 축으로 바뀐다 — 선언된 변경이다(결정 ②)")
            .isEqualTo(BurndownUnit.ISSUE_COUNT)
    }

    @Test
    fun `보드 행이 사라져 설정을 못 읽으면 DB 기본값과 같은 NONE 으로 읽는다`() {
        // findTimeTracking 의 null 은 「보드가 없다」는 뜻뿐이다. 그때 세 번째 동작을 만들지 않는다.
        val vanished = result(timeTracking = null, source = defaultSource())

        assertThat(vanished.unit).isEqualTo(BurndownUnit.ISSUE_COUNT)
    }

    // ── ③ 빈 스프린트 — 0 나눗셈 ────────────────────────────────────────────

    @Test
    fun `이슈가 0개인 스프린트는 개수 축에서 전 구간 0 이고 축은 그대로 그려진다`() {
        val empty =
            BurndownSource(
                totalOriginalEstimateSeconds = 0L,
                worklogEntries = emptyList(),
                visibleIssueCount = 0L,
                issueCompletions = emptyList(),
            )

        val points = result(timeTracking = NONE, source = empty).points

        assertSoftly { softly ->
            softly.assertThat(points).describedAs("축은 스코프와 무관하다 — 3일이면 3점이다").hasSize(3)
            softly.assertThat(points.map { it.remainingSeconds }).containsExactly(0L, 0L, 0L)
            softly.assertThat(points.map { it.idealSeconds })
                .describedAs("스코프 0 의 ideal 은 전 구간 0 이다(0 나눗셈 없음)")
                .containsExactly(0L, 0L, 0L)
        }
    }

    // ── ④ 기존 두 축(근무일 · 보드 타임존)이 개수 축에서도 선다 ──────────────

    @Test
    fun `control pair - 개수 축도 근무일 설정을 따른다 (설정 10개 · 미설정 14개)`() {
        val unset = longSprintPoints(standardDays = null)
        val monToFri = longSprintPoints(standardDays = WEEKDAY_KEYS)

        assertSoftly { softly ->
            softly.assertThat(unset)
                .describedAs("미설정(NULL) = 달력일 전부. 개수 축에서도 R6 이 그대로다")
                .hasSize(14)
            softly.assertThat(monToFri.map { it.date })
                .describedAs("월~금 설정이 개수 축에서도 주말 4일을 뺀다(T12 배선을 잃지 않았다)")
                .hasSize(10)
                .noneMatch { it.dayOfWeek == DayOfWeek.SATURDAY || it.dayOfWeek == DayOfWeek.SUNDAY }
        }
    }

    @Test
    fun `control pair - 완료 일 귀속도 보드 타임존을 따른다`() {
        // 픽스처 자기검증 — 이 시각이 경계를 넘지 않으면 아래 두 단언이 조용히 같은 값이 된다.
        assertThat(LocalDate.ofInstant(LATE_NIGHT_UTC, ZoneOffset.UTC)).isEqualTo(JULY_2)
        assertThat(LocalDate.ofInstant(LATE_NIGHT_UTC, ZoneId.of(SEOUL))).isEqualTo(JULY_3)

        val source =
            BurndownSource(
                totalOriginalEstimateSeconds = 0L,
                worklogEntries = emptyList(),
                visibleIssueCount = ISSUE_COUNT,
                issueCompletions = listOf(IssueCompletion("ATLAS-1", LATE_NIGHT_UTC)),
            )

        val unset = droppedOn(result(timeTracking = NONE, source = source, timezone = null).points)
        val seoul = droppedOn(result(timeTracking = NONE, source = source, timezone = SEOUL).points)

        assertSoftly { softly ->
            softly.assertThat(unset)
                .describedAs("타임존 미설정 = UTC. 23:30Z 는 7/2 칸이다")
                .isEqualTo(JULY_2)
            softly.assertThat(seoul)
                .describedAs("Asia/Seoul(+9) 에서 23:30Z 는 7/3 08:30 — 다음 날 칸이다(R10)")
                .isEqualTo(JULY_3)
        }
    }

    // ── 헬퍼 ──────────────────────────────────────────────────────────────────

    /** 개수 축 판정에 쓰는 기본 원천 데이터 — 이슈 3개, 완료·worklog 없음. */
    private fun defaultSource(): BurndownSource =
        BurndownSource(
            totalOriginalEstimateSeconds = SCOPE_SECONDS,
            worklogEntries = emptyList(),
            visibleIssueCount = ISSUE_COUNT,
            issueCompletions = emptyList(),
        )

    private fun worklog(startedAt: Instant): WorklogContribution =
        WorklogContribution(
            startedOnUtcDate = LocalDate.ofInstant(startedAt, ZoneOffset.UTC),
            timeSpentSeconds = WORKLOG_SECONDS,
            startedAt = startedAt,
        )

    /**
     * 보드 설정과 원천 데이터를 주고 번다운을 계산한다.
     *
     * @param timeTracking `boards.time_tracking` 값. null 이면 보드 행을 못 읽은 경우다.
     * @param source 포트가 나르는 원천 데이터.
     * @param timezone 보드 타임존. null 이면 미설정(UTC).
     * @param standardDays 표준 근무일. null 이면 미설정(달력일 전부).
     * @param start 스프린트 시작일.
     * @param end 스프린트 종료일.
     */
    @Suppress("LongParameterList")
    private fun result(
        timeTracking: String?,
        source: BurndownSource,
        timezone: String? = null,
        standardDays: List<String>? = null,
        start: LocalDate = SHORT_START,
        end: LocalDate = SHORT_END,
    ): SprintBurndownResult {
        val sprint =
            Sprint(
                id = sprintId,
                projectKey = projectKey,
                boardId = boardId,
                name = "Sprint 1",
                goal = null,
                status = SprintStatus.ACTIVE,
                startDate = start,
                endDate = end,
                version = 0L,
            )
        val sprintRepository =
            mockk<SprintRepository>().also {
                every { it.findById(sprintId) } returns sprint
                every { it.findIssueKeys(sprintId) } returns listOf("ATLAS-1", "ATLAS-2", "ATLAS-3")
            }
        val permissionResolver =
            mockk<IssuePermissionResolver>().also {
                every { it.hasPermission(any(), any(), any()) } returns true
            }
        val burndownPort =
            mockk<SprintBurndownLookupPort>().also {
                every { it.fetchBurndownSource(any(), any(), any()) } returns source
            }
        val settingsRepository =
            mockk<BoardSettingsRepository>().also {
                every { it.findTimeTracking(boardId) } returns timeTracking
                every { it.findWorkingDays(boardId) } returns
                    BoardWorkingDays(
                        standardDays = standardDays,
                        timezone = timezone,
                        nonWorkingDates = emptyList(),
                    )
            }

        return SprintBurndownService(
            sprintRepository,
            permissionResolver,
            burndownPort,
            settingsRepository,
            fixedClock,
        ).getBurndown(actorId, sprintId)
    }

    /** 2주 스프린트를 개수 축으로 그린 점 목록 — 근무일 축 대조군용. */
    private fun longSprintPoints(standardDays: List<String>?): List<BurndownPoint> =
        result(
            timeTracking = NONE,
            source = defaultSource(),
            standardDays = standardDays,
            start = LONG_START,
            end = LONG_END,
        ).points

    /** 잔여 개수가 처음 줄어든 날 — 완료가 어느 칸에 귀속됐는가. */
    private fun droppedOn(points: List<BurndownPoint>): LocalDate? =
        points.firstOrNull { (it.remainingSeconds ?: ISSUE_COUNT) < ISSUE_COUNT }?.date
}
