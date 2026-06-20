// 워크로그 집계 유스케이스 서비스 — 권한 검증·버킷 매핑·사용자 레이블 해석·정렬 (FR-TT-02 Task 2)

package com.bts.issue.worklog.aggregate.application

import com.bts.issue.domain.ActorId
import com.bts.issue.domain.IssueAccessDeniedException
import com.bts.issue.worklog.aggregate.domain.AggregateGranularity
import com.bts.issue.worklog.aggregate.domain.WorklogAggregateBucket
import com.bts.issue.worklog.aggregate.domain.WorklogAggregateDimension
import com.bts.issue.worklog.aggregate.domain.WorklogAggregateResult
import com.bts.issue.worklog.aggregate.domain.WorklogAggregateRow
import com.bts.issue.worklog.aggregate.repository.WorklogAggregateRepository
import com.bts.shared.permission.IssuePermission
import com.bts.shared.permission.IssuePermissionResolver
import com.bts.shared.permission.IssueScope
import com.bts.shared.user.UserLookupPort
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/**
 * 워크로그 집계 유스케이스 서비스 (FR-TT-02).
 *
 * ## 권한 검증 순서
 * 이슈 존재 probe 방지를 위해 [IssuePermission.BROWSE] 검증을 repo 조회보다 먼저 수행한다.
 *
 * ## 트랜잭션
 * 집계는 읽기 전용 오케스트레이션이므로 [Transactional] 을 부착하지 않는다.
 * [WorklogAggregateRepository.aggregate] 가 자체 `@Transactional(readOnly = true)` 를 보유한다.
 *
 * ## BC 격리
 * 사용자 표시명 조회는 [UserLookupPort.findDisplayNamesByIds] 를 통해서만 수행한다.
 * identity-access 내부 클래스를 직접 import 하지 않는다.
 *
 * @param repo 워크로그 집계 리포지토리.
 * @param permissionResolver 이슈 권한 판정 포트.
 * @param userLookup 사용자 표시명 역방향 조회 포트.
 */
@Service
class WorklogAggregateService(
    private val repo: WorklogAggregateRepository,
    private val permissionResolver: IssuePermissionResolver,
    private val userLookup: UserLookupPort,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 프로젝트 워크로그를 차원(issue/user/period)별로 집계한다.
     *
     * ## 실행 순서
     * 1. [IssuePermission.BROWSE] + [IssueScope.Project] 검증 — repo 조회보다 앞.
     * 2. [WorklogAggregateRepository.aggregate] 호출 → [WorklogAggregateRow] 목록.
     * 3. [dimension] 에 따라 레이블 해석.
     *    - [WorklogAggregateDimension.USER] → [UserLookupPort.findDisplayNamesByIds] 1회 일괄 조회.
     *      미존재 id 는 빈 문자열(fail-safe).
     *    - [WorklogAggregateDimension.ISSUE] · [WorklogAggregateDimension.PERIOD] → label = groupKey.
     *      [UserLookupPort] 호출 없음 (C5).
     * 4. 정렬.
     *    - ISSUE / USER → [WorklogAggregateBucket.timeSpentSeconds] DESC, 동률 [WorklogAggregateBucket.label] ASC.
     *    - PERIOD → [WorklogAggregateBucket.key] ASC (시간순).
     * 5. [WorklogAggregateResult] 반환.
     *
     * @param actorId 집계를 요청하는 행위자.
     * @param projectKey 집계 대상 프로젝트 키.
     * @param dimension 집계 차원 (ISSUE / USER / PERIOD).
     * @param granularity PERIOD 차원일 때 버킷 단위. 나머지 차원에서는 무시.
     * @param from 시작 시각(포함). null 이면 필터 없음.
     * @param to 종료 시각. null 이면 필터 없음. [from] > [to] 이면 빈 결과(검증은 컨트롤러 책임).
     * @return [WorklogAggregateResult] — 버킷 목록 + 전체 총 소요 시간.
     * @throws [IssueAccessDeniedException] BROWSE 권한 미보유 시 (403).
     */
    @Suppress("LongParameterList") // 집계 조건 불가분 파라미터 — 기존 WorklogService 선례 동일
    fun aggregate(
        actorId: ActorId,
        projectKey: String,
        dimension: WorklogAggregateDimension,
        granularity: AggregateGranularity?,
        from: Instant?,
        to: Instant?,
    ): WorklogAggregateResult {
        // 1. 권한 검증 — repo 조회보다 먼저 (probe 방지)
        checkPermission(actorId, projectKey)

        log.debug(
            "aggregate actorId={} projectKey={} dimension={} granularity={} from={} to={}",
            actorId.value,
            projectKey,
            dimension,
            granularity,
            from,
            to,
        )

        // 2. 집계 쿼리
        val rows =
            repo.aggregate(
                projectKey = projectKey,
                dimension = dimension,
                granularity = granularity,
                from = from,
                to = to,
            )

        // 3. 레이블 해석
        val buckets = resolveLabels(rows, dimension)

        // 4. 정렬
        val sorted = sort(buckets, dimension)

        // 5. 합계
        val total = sorted.sumOf { it.timeSpentSeconds }

        return WorklogAggregateResult(
            buckets = sorted,
            totalTimeSpentSeconds = total,
        )
    }

    // ── private helpers ────────────────────────────────────────────────────────

    /**
     * BROWSE 권한을 검증한다.
     *
     * 프로젝트 범위([IssueScope.Project]) 에서 [IssuePermission.BROWSE] 를 요구한다.
     * 이슈 존재 여부 probe 방지를 위해 repo 조회 전 호출해야 한다.
     *
     * @param actorId 행위자.
     * @param projectKey 프로젝트 키 (scope 생성에 사용).
     * @throws [IssueAccessDeniedException] 권한 미보유 시.
     */
    private fun checkPermission(
        actorId: ActorId,
        projectKey: String,
    ) {
        val scope = IssueScope.Project(projectKey)
        val allowed = permissionResolver.hasPermission(actorId.value, IssuePermission.BROWSE, scope)
        if (!allowed) {
            throw IssueAccessDeniedException(actorId, IssuePermission.BROWSE, scope)
        }
    }

    /**
     * 집계 행 목록을 [WorklogAggregateBucket] 목록으로 변환하며 레이블을 해석한다.
     *
     * - [WorklogAggregateDimension.USER] → [UserLookupPort.findDisplayNamesByIds] 1회 일괄 조회.
     *   groupKey 가 UUID 텍스트이므로 [UUID.fromString] 으로 변환한다.
     *   변환 실패 시(잘못된 포맷) label = "" 으로 fail-safe 처리한다 (C2 — `!!` 금지).
     *   미존재 id 는 displayNames 맵에서 제외되므로 `?:""` 로 빈 문자열 반환.
     * - [WorklogAggregateDimension.ISSUE] · [WorklogAggregateDimension.PERIOD] → label = groupKey.
     *   [UserLookupPort] 호출 없음 (C5).
     *
     * @param rows 집계 행 목록.
     * @param dimension 집계 차원.
     * @return [WorklogAggregateBucket] 목록.
     */
    private fun resolveLabels(
        rows: List<WorklogAggregateRow>,
        dimension: WorklogAggregateDimension,
    ): List<WorklogAggregateBucket> {
        if (dimension == WorklogAggregateDimension.USER) {
            return resolveUserLabels(rows)
        }
        // ISSUE / PERIOD: label = groupKey (C5 — userLookup 호출 없음)
        return rows.map { row ->
            WorklogAggregateBucket(
                key = row.groupKey,
                label = row.groupKey,
                timeSpentSeconds = row.timeSpentSeconds,
                worklogCount = row.worklogCount,
            )
        }
    }

    /**
     * USER 차원의 레이블을 [UserLookupPort.findDisplayNamesByIds] 로 해석한다.
     *
     * groupKey 가 UUID 텍스트이므로 파싱한다.
     * 파싱 실패 시 label = "" (C2 — `!!` 금지, runCatching fail-safe).
     * 미존재 id 는 맵에서 제외되어 `?:""` 로 빈 문자열 반환.
     *
     * @param rows 집계 행 목록 (groupKey = UUID 텍스트).
     * @return [WorklogAggregateBucket] 목록 (label = displayName 또는 "").
     */
    private fun resolveUserLabels(rows: List<WorklogAggregateRow>): List<WorklogAggregateBucket> {
        // C2: UUID 변환은 !! 금지 — mapNotNull + Pair 로 안전하게 변환
        val uuidByKey: Map<String, UUID> =
            rows
                .mapNotNull { row ->
                    runCatching { UUID.fromString(row.groupKey) }
                        .getOrNull()
                        ?.let { uuid -> row.groupKey to uuid }
                }.toMap()

        val validIds: Set<UUID> = uuidByKey.values.toSet()

        // cross-BC 조회 — 정확히 1회
        val displayNames: Map<UUID, String> =
            if (validIds.isEmpty()) {
                emptyMap()
            } else {
                userLookup.findDisplayNamesByIds(validIds)
            }

        return rows.map { row ->
            val uuid = uuidByKey[row.groupKey]
            val label = if (uuid != null) displayNames[uuid] ?: "" else ""
            WorklogAggregateBucket(
                key = row.groupKey,
                label = label,
                timeSpentSeconds = row.timeSpentSeconds,
                worklogCount = row.worklogCount,
            )
        }
    }

    /**
     * 버킷 목록을 차원별 정렬 기준에 따라 정렬한다.
     *
     * - ISSUE / USER → timeSpentSeconds DESC, 동률 label ASC.
     * - PERIOD → key ASC (날짜 문자열은 YYYY-MM-DD 포맷이므로 사전순 = 시간순).
     *
     * @param buckets 정렬할 버킷 목록.
     * @param dimension 집계 차원.
     * @return 정렬된 버킷 목록.
     */
    private fun sort(
        buckets: List<WorklogAggregateBucket>,
        dimension: WorklogAggregateDimension,
    ): List<WorklogAggregateBucket> =
        when (dimension) {
            WorklogAggregateDimension.ISSUE,
            WorklogAggregateDimension.USER,
            ->
                buckets.sortedWith(
                    compareByDescending<WorklogAggregateBucket> { it.timeSpentSeconds }
                        .thenBy { it.label },
                )

            WorklogAggregateDimension.PERIOD ->
                buckets.sortedBy { it.key }
        }
}
