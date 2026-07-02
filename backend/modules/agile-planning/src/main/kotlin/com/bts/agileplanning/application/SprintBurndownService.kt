// 스프린트 번다운/번업 시계열 애플리케이션 서비스 — agile-planning BC (FR-RP-01)

package com.bts.agileplanning.application

import com.bts.agileplanning.domain.SprintStatus
import com.bts.agileplanning.domain.burndown.BurndownCalculator
import com.bts.agileplanning.domain.burndown.BurndownPoint
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
import java.time.LocalDate
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
 * ## 보안 그레인 (NFR5)
 * 권한 판정은 스프린트 소속 projectKey 에 대한 프로젝트 BROWSE 1회만 수행한다.
 * 스프린트 소속 이슈별 [IssueScope.Issue] 가시성 필터는 적용하지 않는다 — 번다운은 스프린트
 * 집계 지표라 일부 이슈만 필터하면 차트가 부분·오값이 되기 때문이다(FR-TT-02 worklog 집계 선례와
 * 동일 그레인).
 *
 * @param sprintRepository sprints / sprint_issues jOOQ repository.
 * @param permissionResolver cross-BC 권한 판정 포트(fail-closed, non-null 주입).
 * @param burndownPort 이슈 추정/worklog 원천 데이터 cross-BC 조회 포트(issue-tracking 구현).
 * @param clock "오늘" 날짜 산출용 Clock(UTC). 시각 의존 로직을 테스트 가능하게 만든다
 *   (AuthController time-bomb 회귀 학습). 별도 Clock 빈이 없는 컨텍스트에서도 부팅되도록
 *   default 값을 둔다(WorklogService 등 기존 Clock default 관례 — 전용 @Bean 미배선).
 */
@Service
class SprintBurndownService(
    private val sprintRepository: SprintRepository,
    private val permissionResolver: IssuePermissionResolver,
    private val burndownPort: SprintBurndownLookupPort,
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
        val source = burndownPort.fetchBurndownSource(issueKeys)
        val worklogByUtcDate = aggregateByUtcDate(source.worklogEntries)

        val points =
            BurndownCalculator.calculate(
                start = start,
                end = end,
                scopeSeconds = source.totalOriginalEstimateSeconds,
                worklogByUtcDate = worklogByUtcDate,
                today = LocalDate.now(clock),
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
     * [WorklogContribution] 목록을 UTC 날짜별 합계 맵으로 변환한다.
     *
     * 포트가 이미 UTC 날짜별로 사전 집계해 반환하므로(1:1) 통상 그대로 매핑되지만,
     * 중복 키가 존재할 가능성에 대비해 groupBy+sum 으로 안전하게 합산한다.
     */
    private fun aggregateByUtcDate(entries: List<WorklogContribution>): Map<LocalDate, Long> =
        entries.groupBy({ it.startedOnUtcDate }, { it.timeSpentSeconds })
            .mapValues { (_, values) -> values.sum() }

    companion object {
        private const val DATES_REQUIRED_MESSAGE =
            "스프린트 기간(start_date, end_date)이 설정되지 않아 번다운을 계산할 수 없습니다."
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
