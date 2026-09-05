// 스프린트 번다운/번업 시계열 애플리케이션 서비스 — agile-planning BC (FR-RP-01)

package com.bts.agileplanning.application

import com.bts.agileplanning.domain.SprintStatus
import com.bts.agileplanning.domain.burndown.BurndownCalculator
import com.bts.agileplanning.domain.burndown.BurndownPoint
import com.bts.agileplanning.domain.burndown.WorkingDayCalendar
import com.bts.agileplanning.repository.BoardSettingsRepository
import com.bts.agileplanning.repository.BoardWorkingDays
import com.bts.agileplanning.repository.SprintRepository
import com.bts.shared.burndown.SprintBurndownLookupPort
import com.bts.shared.burndown.WorklogContribution
import com.bts.shared.permission.IssuePermission
import com.bts.shared.permission.IssuePermissionResolver
import com.bts.shared.permission.IssueScope
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.time.Clock
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

/**
 * 스프린트 번다운(Burndown) / 번업(Burnup) 시계열을 조회하는 애플리케이션 서비스.
 *
 * On-the-fly in-memory 계산 — 별도 스냅샷 테이블/스케줄러를 두지 않는다(ADR 2026-07-02-fr-rp-01 D3).
 * cross-BC 통신은 shared-kernel 포트([SprintBurndownLookupPort], [IssuePermissionResolver])만 사용한다.
 * [BurndownCalculator] 는 순수 함수 object 라 주입 대상이 아니라 직접 호출한다.
 *
 * ## 권한 판정 순서
 * 1. sprint 조회로 projectKey 확보 (404 — 미존재/soft-deleted, [SprintNotFoundException])
 * 2. 프로젝트 BROWSE 권한 판정 (403, [SprintApplicationService.get] 미러)
 * 3. start/end 기간 존재 검증 (422, [SprintDatesRequiredException])
 * 4. 계산 수행
 *
 * ## 일 귀속과 근무일 축은 보드 설정을 따른다 (부채 177 Task 12 · 스펙 R6·R10)
 * worklog 를 어느 날짜 칸에 놓을지는 **보드 timezone** 이 정하고, 차트에 그릴 x축은 **보드 근무일**이
 * 정한다.
 *
 * ### ★미설정의 뜻 — 「UTC · 달력일 전부」
 *
 * | 보드 설정 | 이 서비스가 하는 일 |
 * |---|---|
 * | `board_timezone` NULL | 일 귀속 기준이 **UTC** 다([resolveBoardZone]) |
 * | `working_days` NULL | `workingCalendar = null` — 축은 **달력일 전부**([toWorkingCalendar]) |
 * | `working_days` NULL + 비근무일 등록됨 | **비근무일을 무시**한다. 근무일 축이 없는데 구멍만 뚫으면 설정한 적 없는 규칙이 차트를 바꾼다 |
 *
 * 둘 다 미설정이면 이 서비스는 설정 기능이 없던 시절과 **한 점도 다르지 않게** 동작한다 —
 * 아무도 설정을 만지지 않았는데 배포 순간 기존 스프린트의 차트가 바뀌면 안 되기 때문이다(스펙 R6·E7).
 * 판정 정본은 [WorkingDaysSettingsService] 의 KDoc 이다.
 *
 * ★"오늘"([clock])은 **여전히 UTC** 다. asOf = min(end, today) 의 경계가 보드 timezone 을 따르지 않아,
 * 보드가 `Asia/Seoul` 인 스프린트의 마지막 하루는 최대 하루 늦게 채워질 수 있다. 이 task 의 범위는
 * worklog 일 귀속이라 함께 옮기지 않았다(범위를 넘겨 고치면 기존 결정성 계약도 함께 바뀐다).
 *
 * ## 보안 그레인 (NFR5, 리뷰 C1 강화)
 * 권한 판정은 스프린트 소속 projectKey 에 대한 프로젝트 BROWSE 1회를 게이트로 수행한다.
 * 추가로, 집계 원천 조회([SprintBurndownLookupPort.fetchBurndownSource])에 actor 를 전달해
 * 이슈별 [IssueScope.Issue] 가시성(security_level) 필터를 적용한다 — 프로젝트는 볼 수 있으나
 * 이슈별 보안으로 차단된 기밀 이슈의 estimate/worklog 를 viewer 가 집계값으로 간접 추론하지
 * 못하도록 한다(리뷰 C1). 이 경우 차트는 viewer 스코프의 부분값이 될 수 있으며 이는 의도된
 * 동작이다. 가시성 판정은 issue-tracking BC 의 정본 보안 술어를 재사용한다(복제 없음).
 *
 * @param sprintRepository sprints / sprint_issues jOOQ repository.
 * @param permissionResolver cross-BC 권한 판정 포트(fail-closed, non-null 주입).
 * @param burndownPort 이슈 추정/worklog 원천 데이터 cross-BC 조회 포트(issue-tracking 구현).
 * @param boardSettingsRepository 보드 설정 4탭 저장 칸 접근. 「작업일」 탭의 타임존·근무일을 읽는다.
 *   **기본값을 두지 않는다** — 기본값이 있으면 배선을 빠뜨려도 조용히 현행 동작(UTC·달력일 전부)으로
 *   fail-open 하고, 「설정은 되는데 번다운이 안 바뀐다」가 컴파일 에러 없이 배포된다.
 * @param clock "오늘" 날짜 산출용 Clock(UTC). 시각 의존 로직을 테스트 가능하게 만든다
 *   (AuthController time-bomb 회귀 학습). 별도 Clock 빈이 없는 컨텍스트에서도 부팅되도록
 *   default 값을 둔다(WorklogService 등 기존 Clock default 관례 — 전용 @Bean 미배선).
 */
@Service
class SprintBurndownService(
    private val sprintRepository: SprintRepository,
    private val permissionResolver: IssuePermissionResolver,
    private val burndownPort: SprintBurndownLookupPort,
    private val boardSettingsRepository: BoardSettingsRepository,
    private val clock: Clock = Clock.systemUTC(),
) {
    /**
     * 스프린트의 번다운/번업 시계열을 계산해 반환한다.
     *
     * @param actorId 행위자 UUID.
     * @param sprintId 조회할 스프린트 UUID.
     * @return [SprintBurndownResult].
     * @throws SprintNotFoundException 404 — 스프린트 미존재 또는 soft-deleted.
     * @throws ResponseStatusException 403 — 프로젝트 BROWSE 권한 미충족.
     * @throws SprintDatesRequiredException 422 — start_date 또는 end_date 미설정.
     */
    @Transactional(readOnly = true)
    fun getBurndown(
        actorId: UUID,
        sprintId: UUID,
    ): SprintBurndownResult {
        val sprint = sprintRepository.findById(sprintId) ?: throw SprintNotFoundException()
        requireBrowsePermission(actorId, sprint.projectKey)
        val (start, end) = requireDatesPresent(sprint.startDate, sprint.endDate)

        val issueKeys = sprintRepository.findIssueKeys(sprintId).toSet()
        val source = burndownPort.fetchBurndownSource(issueKeys, sprint.projectKey, actorId)
        val settings = boardSettingsRepository.findWorkingDays(sprint.boardId)

        val points =
            BurndownCalculator.calculate(
                start = start,
                end = end,
                scopeSeconds = source.totalOriginalEstimateSeconds,
                // 계산기 쪽 파라미터명은 `worklogByUtcDate` 로 남아 있다(다른 task 소유 파일이라 손대지 않는다).
                // 실제로 담기는 것은 **보드 timezone 기준** 버킷이다 — 이름이 아니라 이 호출부가 정본이다.
                worklogByUtcDate = aggregateByBoardDate(source.worklogEntries, resolveBoardZone(settings?.timezone)),
                today = LocalDate.now(clock),
                workingCalendar = toWorkingCalendar(settings),
            )

        return SprintBurndownResult(
            sprintId = sprint.id,
            projectKey = sprint.projectKey,
            status = sprint.status,
            startDate = start,
            endDate = end,
            totalScopeSeconds = source.totalOriginalEstimateSeconds,
            points = points,
        )
    }

    /**
     * [projectKey] 에 대한 프로젝트 BROWSE 권한을 판정하고 미충족 시 403 을 던진다.
     *
     * fail-closed — permissionResolver 는 non-null 주입이므로 빈 부재 시 부팅이 실패한다.
     * 거부 메시지는 일반화되어 내부 정보를 노출하지 않는다.
     */
    private fun requireBrowsePermission(
        actorId: UUID,
        projectKey: String,
    ) {
        val allowed =
            permissionResolver.hasPermission(actorId, IssuePermission.BROWSE, IssueScope.Project(projectKey))
        if (!allowed) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "접근 권한이 없습니다.")
        }
    }

    /**
     * [startDate]·[endDate] 가 모두 설정됐는지 검증하고 non-null 쌍으로 반환한다.
     *
     * 하나라도 null 이면 시간축 정박점이 없어 번다운을 계산할 수 없다(스펙 S3).
     * getBurndown 의 ThrowsCount 를 낮추기 위해 단일 throw 지점으로 분리한다.
     */
    private fun requireDatesPresent(
        startDate: LocalDate?,
        endDate: LocalDate?,
    ): Pair<LocalDate, LocalDate> {
        if (startDate == null || endDate == null) {
            throw SprintDatesRequiredException(DATES_REQUIRED_MESSAGE)
        }
        return startDate to endDate
    }

    /**
     * [WorklogContribution] 목록을 **보드 timezone 기준** 날짜별 합계 맵으로 변환한다.
     *
     * ★이름이 `...ByUtcDate` 로 남으면 거짓말이다. [zone] 이 UTC 인 것은 **보드가 미설정일 때뿐**이고,
     * 설정된 보드에서는 UTC 가 아닌 날짜 칸이 나온다. 이름이 계약을 말하게 둔다.
     *
     * 포트는 worklog 1건당 1항목을 시각 원본([WorklogContribution.startedAt])과 함께 나른다 —
     * 어느 로컬 날짜 칸에 놓을지는 소비측인 이 서비스의 책임이다([SprintBurndownLookupPort] KDoc).
     * [WorklogContribution.startedOnUtcDate] 는 UTC 축 파생값일 뿐이라 여기서 읽지 않는다.
     *
     * 합산은 여기 한 곳에서만 한다 — 포트도 합산하면 그것이 두 번째 진실이 된다.
     *
     * @param entries worklog 단건 기여 목록.
     * @param zone 일 귀속의 기준 timezone([resolveBoardZone]). 미설정 보드에서는 UTC 다.
     */
    private fun aggregateByBoardDate(
        entries: List<WorklogContribution>,
        zone: ZoneId,
    ): Map<LocalDate, Long> =
        entries.groupBy({ LocalDate.ofInstant(it.startedAt, zone) }, { it.timeSpentSeconds })
            .mapValues { (_, values) -> values.sum() }

    /**
     * 보드 설정에서 일 귀속의 기준 timezone 을 얻는다.
     *
     * 미설정(null)이면 **UTC** 다 — 설정을 한 번도 만지지 않은 보드의 차트가
     * 배포 순간 바뀌면 안 된다(스펙 E7).
     * 값 검증(IANA 여부)은 저장 시점의 [WorkingDaysSettingsService] 가 이미 했다.
     *
     * @param timezone `boards.board_timezone` 값. null 이면 미설정.
     */
    private fun resolveBoardZone(timezone: String?): ZoneId = timezone?.let(ZoneId::of) ?: ZoneOffset.UTC

    /**
     * 보드 설정을 [BurndownCalculator] 의 근무일 축 인자로 옮긴다.
     *
     * ★**[BoardWorkingDays.standardDays] 가 null 이면 통째로 null 을 준다** — 「미설정 = 달력일 전부」이고,
     * 그때는 **비근무일만 등록된 보드도 비근무일을 무시한다**([WorkingDaysSettingsService] KDoc 이 정본).
     * 근무일 축이 없는데 비근무일만 빼면 「설정한 적 없는 규칙」이 차트를 바꾸게 된다.
     *
     * 요일 키는 저장 시점에 `MON`..`SUN` 으로 정규화됐다. 해석 불가 키는 [WEEKDAY_BY_KEY] 에서
     * 걸러지며, 그 경로는 저장 검증이 이미 막았으므로 여기서 별도 판정을 복제하지 않는다.
     */
    private fun toWorkingCalendar(settings: BoardWorkingDays?): WorkingDayCalendar? {
        val standardDays = settings?.standardDays ?: return null
        return WorkingDayCalendar(
            standardDays = standardDays.mapNotNull(WEEKDAY_BY_KEY::get).toSet(),
            nonWorkingDates = settings.nonWorkingDates.toSet(),
        )
    }

    companion object {
        private const val DATES_REQUIRED_MESSAGE =
            "스프린트 기간(start_date, end_date)이 설정되지 않아 번다운을 계산할 수 없습니다."

        /**
         * `boards.working_days` 의 3글자 요일 키 → [DayOfWeek].
         *
         * 칸이 `VARCHAR(3)[]` 라 `MONDAY` 가 아니라 `MON` 이 들어간다.
         * 표기 정본은 [WorkingDaysSettingsService] 의 `WEEK_ORDER` 이고 여기는 그 역매핑이다.
         */
        private val WEEKDAY_BY_KEY: Map<String, DayOfWeek> = DayOfWeek.entries.associateBy { it.name.take(3) }
    }
}

/**
 * [SprintBurndownService.getBurndown] 의 결과 read-model VO.
 *
 * @property sprintId 스프린트 UUID.
 * @property projectKey 소속 프로젝트 키.
 * @property status 조회 시점의 스프린트 상태.
 * @property startDate 스프린트 시작일(non-null — [SprintDatesRequiredException] 검증 통과 후).
 * @property endDate 스프린트 종료일(non-null — [SprintDatesRequiredException] 검증 통과 후).
 * @property totalScopeSeconds 총 스코프(초). Σ original_estimate_seconds, NULL=0.
 * @property points 날짜 오름차순 번다운/번업 시계열.
 */
data class SprintBurndownResult(
    val sprintId: UUID,
    val projectKey: String,
    val status: SprintStatus,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val totalScopeSeconds: Long,
    val points: List<BurndownPoint>,
)
