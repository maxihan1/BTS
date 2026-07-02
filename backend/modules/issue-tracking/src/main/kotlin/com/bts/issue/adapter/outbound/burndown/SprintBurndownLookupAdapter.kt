// 스프린트 번다운 원천 데이터 cross-BC 조회 어댑터 — issue-tracking 이 shared-kernel SprintBurndownLookupPort 구현 (FR-RP-01 Task 3)

package com.bts.issue.adapter.outbound.burndown

import com.bts.issue.adapter.outbound.burndown.repository.SprintBurndownQueryRepository
import com.bts.issue.repository.IssueRepository
import com.bts.shared.burndown.BurndownSource
import com.bts.shared.burndown.SprintBurndownLookupPort
import com.bts.shared.permission.IssueSecurityDirectory
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * [SprintBurndownLookupPort] 의 issue-tracking BC 구현 (FR-RP-01 Task 3).
 *
 * agile-planning BC 가 스프린트 번다운/번업 차트를 계산할 때 이 adapter 를 통해 스프린트에 속한
 * 이슈들의 추정 시간 합계와 worklog(작업 로그) 를 UTC 날짜별로 사전 집계해 받는다.
 * 두 BC 는 shared-kernel 의 [SprintBurndownLookupPort] 만 공유하며 서로를 직접 gradle 의존하지 않는다.
 *
 * ### 쿼리 2개 이하 — N+1 없음 (NFR3)
 * jOOQ 쿼리 자체는 [SprintBurndownQueryRepository] 로 추출되어 있다(FR-RP-02 Task 6 — ArchUnit 룰2 준수).
 * 1. [SprintBurndownQueryRepository.sumOriginalEstimateSeconds] — `issues` 에서 `original_estimate_seconds` 합계 1쿼리.
 * 2. [SprintBurndownQueryRepository.aggregateWorklogsByUtcDate] — `worklogs JOIN issues` 로 UTC 날짜별
 *    `time_spent_seconds` 합계 1쿼리.
 * 두 쿼리 모두 소프트 삭제(`deleted_at IS NULL`) 이슈/worklog 를 제외한다.
 *
 * ### UTC 날짜 버킷 — FR-TT-02 [com.bts.issue.worklog.aggregate.repository.WorklogAggregateRepository] 선례
 * [SprintBurndownQueryRepository] 는 `(started_at AT TIME ZONE 'UTC')::date` 로 TIMESTAMPTZ 를
 * UTC 벽시계 날짜로 변환한다. `AT TIME ZONE 'UTC'` 는 세션 TimeZone GUC 값에 무관하게 항상 UTC 로
 * 변환하므로(WorklogAggregateRepository E6 회귀 방지 선례와 동일 원리), 별도 세션 TZ 보정이 필요 없다.
 *
 * ### 이슈별 가시성 필터 — 정본 보안 술어 재사용 (리뷰 C1)
 * 집계 전에 [IssueSecurityDirectory.accessibleLevels] 로 viewer 의 접근 가능 보안 등급 집합을 1회 조회하고,
 * [IssueRepository.filterVisibleIssueKeys] 로 키 집합을 가시 이슈로 좁힌다. filterVisibleIssueKeys 는
 * 이슈 목록/타임라인 조회와 **동일한** `buildActiveSecureWhere` 정본 술어를 재사용하므로, 보안 판정
 * 로직을 복제하지 않고 프로젝트 BROWSE 통과 뷰어의 기밀 이슈 시간값 간접 추론을 차단한다.
 * (TimelineLookupAdapter 의 accessibleLevels→repository 위임 선례와 동일 패턴.)
 *
 * @see SprintBurndownLookupPort
 * @see IssueRepository.filterVisibleIssueKeys
 */
@Component
class SprintBurndownLookupAdapter(
    private val queryRepository: SprintBurndownQueryRepository,
    private val securityDirectory: IssueSecurityDirectory,
    private val issueRepository: IssueRepository,
) : SprintBurndownLookupPort {
    /**
     * 이슈 키 집합의 번다운 원천 데이터를 viewer 가시 이슈로 한정해 조회한다.
     *
     * 집계 전에 [viewerUserId] 가 볼 수 없는 이슈(이슈별 보안 등급 차단)를 정본 보안 술어로 제외한다 —
     * 프로젝트 BROWSE 통과 뷰어의 기밀 이슈 시간값 간접 추론을 차단한다(리뷰 C1).
     *
     * @param issueKeys 스프린트에 속한 이슈 키 집합. 빈 집합이면 조기 반환한다(jOOQ 빈 `IN` 절 함정 방지).
     * @param projectKey 이슈들이 속한 프로젝트 키. 보안 술어의 프로젝트 스코프 판정에 사용.
     * @param viewerUserId 번다운을 조회하는 viewer UUID. 이슈별 가시성 필터 기준.
     * @return 미삭제·가시 이슈의 추정 시간 합계와 미삭제 worklog 의 UTC 날짜별 집계.
     */
    @Transactional(readOnly = true)
    override fun fetchBurndownSource(
        issueKeys: Set<String>,
        projectKey: String,
        viewerUserId: UUID,
    ): BurndownSource {
        if (issueKeys.isEmpty()) {
            return BurndownSource(totalOriginalEstimateSeconds = 0L, worklogEntries = emptyList())
        }
        // 정본 보안 술어(buildActiveSecureWhere) 재사용 — viewer 가시 이슈로 키 집합을 좁힌다(복제 없음).
        val access = securityDirectory.accessibleLevels(viewerUserId, projectKey)
        val visibleKeys = issueRepository.filterVisibleIssueKeys(issueKeys, projectKey, viewerUserId, access)
        // 가시 이슈가 없으면 스코프 0·worklog 없음. 있으면 가시 키만으로 추정/worklog 를 집계한다.
        return if (visibleKeys.isEmpty()) {
            BurndownSource(totalOriginalEstimateSeconds = 0L, worklogEntries = emptyList())
        } else {
            BurndownSource(
                totalOriginalEstimateSeconds = queryRepository.sumOriginalEstimateSeconds(visibleKeys),
                worklogEntries = queryRepository.aggregateWorklogsByUtcDate(visibleKeys),
            )
        }
    }
}
