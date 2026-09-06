// 스프린트 번다운 원천 데이터 cross-BC 조회 어댑터 — issue-tracking 이 shared-kernel SprintBurndownLookupPort 구현 (FR-RP-01 Task 3)

package com.bts.issue.adapter.outbound.burndown

import com.bts.issue.adapter.outbound.burndown.repository.BurndownIssueRow
import com.bts.issue.adapter.outbound.burndown.repository.SprintBurndownQueryRepository
import com.bts.issue.adapter.outbound.velocity.IsolatedWorkflowStateLookup
import com.bts.issue.repository.IssueRepository
import com.bts.issue.statushistory.StatusCategory
import com.bts.issue.statushistory.repository.StatusChangeRow
import com.bts.issue.statushistory.repository.StatusHistoryRepository
import com.bts.issue.type.repository.IssueTypeRepository
import com.bts.shared.burndown.BurndownSource
import com.bts.shared.burndown.IssueCompletion
import com.bts.shared.burndown.SprintBurndownLookupPort
import com.bts.shared.issue.IssueTypeKey
import com.bts.shared.permission.IssueSecurityDirectory
import com.bts.shared.workflow.ProjectKey
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/**
 * [SprintBurndownLookupPort] 의 issue-tracking BC 구현 (FR-RP-01 Task 3).
 *
 * agile-planning BC 가 스프린트 번다운/번업 차트를 계산할 때 이 adapter 를 통해 스프린트에 속한
 * 이슈들의 추정 시간 합계와 worklog(작업 로그) 를 **1건당 1항목**으로 받는다.
 * 두 BC 는 shared-kernel 의 [SprintBurndownLookupPort] 만 공유하며 서로를 직접 gradle 의존하지 않는다.
 *
 * ### 개수 축도 함께 나른다 (부채 177 task-35)
 * 보드 「추정」 탭의 `time_tracking` 이 `NONE` 이면 번다운 세로축이 **이슈 개수**다(J36 · 스펙 S2).
 * 그 축은 「몇 개이고 언제 완료됐는가」를 필요로 하는데 추정 시간·worklog 로는 만들 수 없다.
 * 그래서 이 adapter 가 [BurndownSource.visibleIssueCount] 와 [BurndownSource.issueCompletions] 를
 * 함께 채운다.
 *
 * ★**어느 축을 쓸지는 여기서 정하지 않는다.** 보드 설정은 agile-planning 의 지식이고, 그것을
 * 인자로 받으면 issue-tracking 이 남의 BC 설정을 알아야 한다(timezone 을 넘기지 않은 것과 같은 판단).
 * 그래서 두 축의 원천을 늘 함께 조회한다 — 시간 축 보드에서도 이슈 상태 1쿼리와 전환 이력 1쿼리를
 * 더 태우는 값을 치른다. 대신 fail-open 하는 자리를 만들지 않는다.
 *
 * ### 완료 판정 — 「지금 DONE」 + 「마지막 DONE 전환」
 * 형제 `SprintVelocityLookupAdapter` · `CycleTimeService` 와 같은 조립이다 —
 * 이슈 타입별 상태 카테고리 맵을 캐싱(N+1 차단)하고, `WorkflowSchemeNoDefaultException` 은
 * 빈 맵으로 폴백해 운영 500 을 차단한다(그 타입의 이슈는 미완료 취급).
 * 시각은 [StatusHistoryRepository] 의 정본 전환 이력에서 읽는다 — 완료 시각을 저장하는 칸이
 * `issues` 에 없고, 있는 것처럼 새로 만들면 그것이 두 번째 진실이 된다.
 *
 * ### 쿼리 — N+1 없음 (NFR3)
 * jOOQ 쿼리 자체는 [SprintBurndownQueryRepository] 로 추출되어 있다(FR-RP-02 Task 6 — ArchUnit 룰2 준수).
 * 1. [SprintBurndownQueryRepository.sumOriginalEstimateSeconds] — `issues` 에서 `original_estimate_seconds` 합계 1쿼리.
 * 2. [SprintBurndownQueryRepository.findWorklogContributions] — `worklogs JOIN issues` 로 worklog 행을
 *    그대로 읽는 1쿼리.
 * 3. [SprintBurndownQueryRepository.findIssueStatusRows] — 이슈 상태 스칼라 1쿼리(개수 축).
 * 4. [StatusHistoryRepository.fetchStatusChanges] — 완료 이슈들의 status 전환 이력 1쿼리(개수 축).
 * 5. [IssueTypeRepository.findAll] — 타입 id → key 역매핑 1쿼리(개수 축).
 * ★**「5문」이 전부가 아니다**(재리뷰 C4 정정). 워크플로우 상태 조회는 **이슈 타입별 1회**이고
 * [IsolatedWorkflowStateLookup] 은 `REQUIRES_NEW` 라 그 호출마다 **별도 물리 트랜잭션**이 뜬다
 * (`SprintVelocityLookupAdapter` 가 세운 rollback-only 오염 차단 설계 — 그 격리가 목적이다).
 * 실제 비용은 `5 + N_types` 문 + `N_types` 물리 트랜잭션이다.
 *
 * **N+1 이 아닌 이유는 그것이 이슈 수가 아니라 타입 수에 비례하기 때문**이고, 타입은 프로젝트당
 * 한 자리 수다. 다만 시간 축(`REMAINING_AND_SPENT`) 보드도 개수 축 원천 3~5 를 **무조건** 태운다 —
 * 축 선택을 포트 인자로 받으면 issue-tracking 이 남의 BC 설정을 알게 되어 배제했다(그 판단은
 * 아래 「축을 모르는 채 둘 다 낸다」 절에 있다). 줄이려면 그 경계를 다시 정해야 한다.
 * 삭제 술어는 그대로다 — 소프트 삭제 이슈/worklog 는 어느 축에도 들어가지 않는다.
 *
 * ### 시각을 버리지 않는다 — 일 귀속은 소비측 책임 (부채 177 Task 30)
 * 이 adapter 는 worklog 를 날짜 버킷으로 사전 집계하지 않고 `started_at` 원본을
 * [com.bts.shared.burndown.WorklogContribution.startedAt] 으로 그대로 나른다.
 * 사전 집계하면 `10:00Z` 와 `23:30Z` 가 한 항목이 되는데 `Asia/Seoul` 에서는 다른 날이라,
 * 소비측이 무엇을 하든 되돌릴 수 없다(비단사). issue-tracking 이 보드 timezone 을 알아야 하는
 * 역전을 피하는 방향이기도 하다 — [com.bts.shared.calendar.UserCalendarLookupPort] 와 같은 선례.
 * 파생값 [com.bts.shared.burndown.WorklogContribution.startedOnUtcDate] 는 여전히
 * `(started_at AT TIME ZONE 'UTC')::date` 로 만든다 — `AT TIME ZONE 'UTC'` 는 세션 TimeZone GUC 값과
 * 무관하게 결정적이다(WorklogAggregateRepository E6 회귀 방지 선례와 동일 원리).
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
    private val issueTypeRepository: IssueTypeRepository,
    private val statusHistoryRepository: StatusHistoryRepository,
    private val workflowStateLookup: IsolatedWorkflowStateLookup,
) : SprintBurndownLookupPort {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 이슈 키 집합의 번다운 원천 데이터를 viewer 가시 이슈로 한정해 조회한다.
     *
     * 집계 전에 [viewerUserId] 가 볼 수 없는 이슈(이슈별 보안 등급 차단)를 정본 보안 술어로 제외한다 —
     * 프로젝트 BROWSE 통과 뷰어의 기밀 이슈 시간값 간접 추론을 차단한다(리뷰 C1).
     *
     * @param issueKeys 스프린트에 속한 이슈 키 집합. 빈 집합이면 조기 반환한다(jOOQ 빈 `IN` 절 함정 방지).
     * @param projectKey 이슈들이 속한 프로젝트 키. 보안 술어의 프로젝트 스코프 판정에 사용.
     * @param viewerUserId 번다운을 조회하는 viewer UUID. 이슈별 가시성 필터 기준.
     * @return 두 축의 원천 — 시간 축(추정 시간 합계 · worklog 1건당 1항목)과
     *   개수 축(가시 이슈 수 · 완료 이슈 1건당 1항목).
     */
    @Transactional(readOnly = true)
    override fun fetchBurndownSource(
        issueKeys: Set<String>,
        projectKey: String,
        viewerUserId: UUID,
    ): BurndownSource {
        if (issueKeys.isEmpty()) {
            return emptySource()
        }
        // 정본 보안 술어(buildActiveSecureWhere) 재사용 — viewer 가시 이슈로 키 집합을 좁힌다(복제 없음).
        val access = securityDirectory.accessibleLevels(viewerUserId, projectKey)
        val visibleKeys = issueRepository.filterVisibleIssueKeys(issueKeys, projectKey, viewerUserId, access)
        // 가시 이슈가 없으면 두 축 모두 빈 값이다. 있으면 가시 키만으로 집계한다.
        return if (visibleKeys.isEmpty()) {
            emptySource()
        } else {
            BurndownSource(
                totalOriginalEstimateSeconds = queryRepository.sumOriginalEstimateSeconds(visibleKeys),
                worklogEntries = queryRepository.findWorklogContributions(visibleKeys),
                // ★가시 키의 개수다 — 기밀 이슈를 세면 viewer 가 개수 차로 존재를 역산한다.
                visibleIssueCount = visibleKeys.size.toLong(),
                issueCompletions = findCompletions(visibleKeys, projectKey),
            )
        }
    }

    // ── private helpers ───────────────────────────────────────────────────────

    /** 두 축 모두 빈 값 — 조기 반환 경로가 두 곳이라 한 자리에 모은다. */
    private fun emptySource(): BurndownSource =
        BurndownSource(
            totalOriginalEstimateSeconds = 0L,
            worklogEntries = emptyList(),
            visibleIssueCount = 0L,
            issueCompletions = emptyList(),
        )

    /**
     * 가시 이슈 중 **지금 DONE 인** 것들의 완료 시각을 [IssueCompletion] 목록으로 만든다
     * (부채 177 task-35).
     *
     * 판정 두 단계.
     * 1. 이슈의 `current_state_key` 가 그 타입 워크플로우에서 DONE 카테고리인가 — 「지금」을 본다.
     *    되돌린 이슈를 완료로 세면 차트의 마지막 점이 보드의 실제 잔여와 어긋난다.
     * 2. 그 이슈의 status 전환 중 **마지막** DONE 전환 시각 — 되돌렸다 다시 완료한 이슈의
     *    완료 시각은 첫 완료가 아니다.
     *
     * 전환 이력이 없는 DONE 이슈는 완료 시각을 알 수 없어 목록에서 빠진다(스코프에는 남는다) —
     * `CycleTimeService` 와 같은 「전환 기반 신뢰」이고 [IssueCompletion] KDoc 이 그 한계를 적는다.
     */
    private fun findCompletions(
        visibleKeys: Set<String>,
        projectKey: String,
    ): List<IssueCompletion> {
        val rows = queryRepository.findIssueStatusRows(visibleKeys)
        if (rows.isEmpty()) return emptyList()

        val typeIdToKey = loadTypeIdToKeyMap()
        val stateCache =
            rows.mapNotNull { typeIdToKey[it.typeId] }
                .toSet()
                .associateWith { typeKey -> resolveStateCategories(projectKey, typeKey) }

        // ① 지금 DONE 인 이슈만 남긴다.
        val doneRows =
            rows.filter {
                categoryOf(it, it.currentStateKey, typeIdToKey, stateCache) == StatusCategory.DONE
            }

        // ② 그 이슈들의 전환 이력에서 마지막 DONE 전환 시각을 읽는다(1쿼리 · 오름차순 정렬 보장).
        //    doneRows 가 비면 fetchStatusChanges 가 빈 집합을 받아 조기 반환한다(빈 IN 절 함정 방지).
        val changesByIssueId =
            statusHistoryRepository.fetchStatusChanges(doneRows.map { it.issueId }.toSet())
                .groupBy { it.issueId }

        return doneRows.mapNotNull { row ->
            lastDoneAt(row, changesByIssueId[row.issueId].orEmpty(), typeIdToKey, stateCache)
                ?.let { IssueCompletion(issueKey = row.issueKey, completedAt = it) }
        }
    }

    /** 이 이슈의 **마지막** DONE 전환 시각. 전환 이력이 없으면 null(= 완료 시각을 모른다). */
    private fun lastDoneAt(
        row: BurndownIssueRow,
        changes: List<StatusChangeRow>,
        typeIdToKey: Map<Long, IssueTypeKey>,
        stateCache: Map<IssueTypeKey, Map<String, String>>,
    ): Instant? =
        changes.lastOrNull { categoryOf(row, it.toValue, typeIdToKey, stateCache) == StatusCategory.DONE }?.changedAt

    /** [stateKey] 가 이 이슈 타입의 워크플로우에서 어느 카테고리인가. 모르면 TODO 로 폴백한다. */
    private fun categoryOf(
        row: BurndownIssueRow,
        stateKey: String?,
        typeIdToKey: Map<Long, IssueTypeKey>,
        stateCache: Map<IssueTypeKey, Map<String, String>>,
    ): StatusCategory {
        val categoryMap = typeIdToKey[row.typeId]?.let { stateCache[it] }.orEmpty()
        return StatusCategory.fromCategoryString(stateKey?.let { categoryMap[it] })
    }

    /**
     * `issue_types.id → IssueTypeKey` 역매핑을 1쿼리로 로드한다(N+1 차단).
     * `SprintVelocityLookupAdapter.loadTypeIdToKeyMap` 과 동형 패턴.
     */
    private fun loadTypeIdToKeyMap(): Map<Long, IssueTypeKey> =
        issueTypeRepository.findAll()
            .mapNotNull { type -> type.id?.let { it.value to type.key } }
            .toMap()

    /**
     * [projectKey] 프로젝트의 [typeKey] 이슈 타입에 대한 상태 키 → 카테고리 문자열 맵을 반환한다.
     *
     * `WorkflowSchemeNoDefaultException` 발생 시 빈 맵으로 폴백해 운영 500 을 차단한다 —
     * 그 타입의 이슈는 전부 미완료 취급되어 개수 축에서 잔여로 남는다
     * (`SprintVelocityLookupAdapter.resolveStateCategories` 와 동형).
     *
     * TooGenericExceptionCaught suppress 근거. project-workflow BC 내부 예외를 직접 import 할 수 없어
     * RuntimeException 을 받아 simpleName 으로 식별한다.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun resolveStateCategories(
        projectKey: String,
        typeKey: IssueTypeKey,
    ): Map<String, String> =
        try {
            workflowStateLookup
                .listStates(ProjectKey.of(projectKey), typeKey)
                .associate { it.key to it.category }
        } catch (e: RuntimeException) {
            if (e.javaClass.simpleName == WORKFLOW_SCHEME_NO_DEFAULT_EXCEPTION) {
                log.warn(
                    "fetchBurndownSource: no workflow scheme for typeKey={} projectKey={} — defaulting to empty map",
                    typeKey.value,
                    projectKey,
                )
                emptyMap()
            } else {
                throw e
            }
        }

    private companion object {
        /** project-workflow BC 내부 예외 simpleName — 직접 import 불가하므로 문자열로 식별. */
        const val WORKFLOW_SCHEME_NO_DEFAULT_EXCEPTION = "WorkflowSchemeNoDefaultException"
    }
}
