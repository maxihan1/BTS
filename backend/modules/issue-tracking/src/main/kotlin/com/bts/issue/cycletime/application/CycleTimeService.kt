// Cycle/Lead Time 조회 유스케이스 서비스 — 권한 검증·가시 이슈 조회·전이 기반 소요시간 추출·계산 위임 (FR-RP-04 Task 5)

package com.bts.issue.cycletime.application

import com.bts.issue.adapter.outbound.velocity.IsolatedWorkflowStateLookup
import com.bts.issue.cycletime.domain.CycleTimeCalculator
import com.bts.issue.cycletime.domain.CycleTimeResult
import com.bts.issue.cycletime.domain.IssueDurationInput
import com.bts.issue.domain.ActorId
import com.bts.issue.domain.IssueAccessDeniedException
import com.bts.issue.repository.CycleTimeIssueSourceRow
import com.bts.issue.repository.IssueRepository
import com.bts.issue.statushistory.StatusCategory
import com.bts.issue.statushistory.repository.StatusChangeRow
import com.bts.issue.statushistory.repository.StatusHistoryRepository
import com.bts.issue.type.repository.IssueTypeRepository
import com.bts.shared.issue.IssueTypeKey
import com.bts.shared.permission.IssuePermission
import com.bts.shared.permission.IssuePermissionResolver
import com.bts.shared.permission.IssueScope
import com.bts.shared.permission.IssueSecurityDirectory
import com.bts.shared.workflow.ProjectKey
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate

/**
 * Cycle/Lead Time 조회 유스케이스 서비스 (FR-RP-04 Task 5).
 *
 * [com.bts.issue.cfd.application.CfdService] 를 미러하되, 이슈 타임라인 전체를 조립하는 대신 전이
 * 이력에서 "첫 IN_PROGRESS 전이"와 "마지막 DONE 전이" 시각만 축약해 [CycleTimeCalculator] 에
 * 위임한다.
 *
 * ## 오케스트레이션 순서
 * 1. [IssuePermission.BROWSE] 검증 — repo 조회보다 먼저 수행해 이슈 존재 probe 를 차단한다.
 * 2. [IssueSecurityDirectory.accessibleLevels] 로 뷰어 접근 가능 보안 등급을 조회하고,
 *    [IssueRepository.fetchActiveVisibleIssuesForCycleTime] 로 활성·가시 이슈 메타를 가져온다.
 * 3. 가시 이슈가 없으면 fetchStatusChanges/listStates 호출 없이 빈 결과를 즉시 반환한다
 *    (jOOQ 빈 `IN` 절 방어).
 * 4. [StatusHistoryRepository.fetchStatusChanges] 로 status 전이 이력을 일괄 조회해 이슈별로
 *    그룹핑한다.
 * 5. `issue_types.id → IssueTypeKey` 역매핑을 1쿼리로 로드하고, 이슈에 등장하는 타입별로
 *    [IsolatedWorkflowStateLookup.listStates] 결과를 캐싱한다(N+1 차단). 스킴/매핑이 없어
 *    `WorkflowSchemeNoDefaultException` 이 발생하면 해당 타입은 빈 상태맵으로 폴백한다(500 차단).
 * 6. 이슈별로 [IssueDurationInput] 을 조립해 [CycleTimeCalculator.calculate] 에 위임한다.
 *
 * ## 트랜잭션
 * `listStates` 가 `Propagation.REQUIRES_NEW` 로 격리되어 있지만([IsolatedWorkflowStateLookup]),
 * 이 서비스 자신은 활성 트랜잭션 안에서 실행되어야 하므로 `@Transactional(readOnly = true)` 를 부착한다.
 *
 * @param permissionResolver 이슈 권한 판정 포트.
 * @param issueRepository Cycle/Lead Time 원천 이슈 메타 조회 리포지토리.
 * @param statusHistoryRepository status 전이 이력 배치 조회 리포지토리.
 * @param securityDirectory 이슈 보안 등급 조회 포트.
 * @param issueTypeRepository 이슈 타입 조회 리포지토리(`id → key` 역매핑용).
 * @param workflowStateLookup 워크플로우 상태 목록 격리 조회 Bean(velocity 가 정의한 기존 Bean 재사용).
 */
@Service
class CycleTimeService(
    private val permissionResolver: IssuePermissionResolver,
    private val issueRepository: IssueRepository,
    private val statusHistoryRepository: StatusHistoryRepository,
    private val securityDirectory: IssueSecurityDirectory,
    private val issueTypeRepository: IssueTypeRepository,
    private val workflowStateLookup: IsolatedWorkflowStateLookup,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 프로젝트의 Cycle/Lead Time 분포를 [from]~[to] 창으로 조회한다.
     *
     * @param actorId 조회를 요청하는 행위자.
     * @param projectKey 대상 프로젝트 키.
     * @param from 창 시작일(inclusive).
     * @param to 창 종료일(inclusive).
     * @return [CycleTimeResult] — Cycle Time/Lead Time 요약 통계 및 표본.
     * @throws IssueAccessDeniedException [IssuePermission.BROWSE] 권한 미보유 시.
     */
    @Transactional(readOnly = true)
    fun getCycleTime(
        actorId: ActorId,
        projectKey: String,
        from: LocalDate,
        to: LocalDate,
    ): CycleTimeResult {
        checkBrowsePermission(actorId, projectKey)

        val access = securityDirectory.accessibleLevels(actorId.value, projectKey)
        val issues = issueRepository.fetchActiveVisibleIssuesForCycleTime(projectKey, actorId.value, access)
        if (issues.isEmpty()) {
            log.debug("getCycleTime: no visible issues, projectKey={} from={} to={}", projectKey, from, to)
            return CycleTimeCalculator.calculate(projectKey, from, to, emptyList())
        }

        val changesByIssueId =
            statusHistoryRepository
                .fetchStatusChanges(issues.map { it.issueId }.toSet())
                .groupBy { it.issueId }
        val typeIdToKey = loadTypeIdToKeyMap()
        val stateCache = buildStateCache(issues, typeIdToKey, projectKey)

        val inputs =
            issues.map { issue ->
                buildDurationInput(issue, changesByIssueId[issue.issueId].orEmpty(), typeIdToKey, stateCache)
            }

        return CycleTimeCalculator.calculate(projectKey, from, to, inputs)
    }

    // ── private helpers ───────────────────────────────────────────────────────

    /**
     * [IssuePermission.BROWSE] 권한을 프로젝트 범위로 검증한다.
     *
     * 이슈 존재 probe 방지를 위해 repo 조회보다 먼저 호출해야 한다.
     *
     * @throws IssueAccessDeniedException 권한 미보유 시.
     */
    private fun checkBrowsePermission(
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
     * `issue_types.id → IssueTypeKey` 역매핑을 1쿼리로 로드한다(N+1 차단).
     *
     * [com.bts.issue.cfd.application.CfdService.loadTypeIdToKeyMap] 과 동형 패턴.
     */
    private fun loadTypeIdToKeyMap(): Map<Long, IssueTypeKey> =
        issueTypeRepository.findAll()
            .mapNotNull { type -> type.id?.let { it.value to type.key } }
            .toMap()

    /**
     * [issues] 에 등장하는 distinct 타입 키마다 상태 키 → 카테고리 맵을 1회씩 캐싱한다(N+1 차단).
     */
    private fun buildStateCache(
        issues: List<CycleTimeIssueSourceRow>,
        typeIdToKey: Map<Long, IssueTypeKey>,
        projectKey: String,
    ): Map<IssueTypeKey, Map<String, String>> =
        issues.mapNotNull { typeIdToKey[it.typeId] }
            .toSet()
            .associateWith { typeKey -> resolveStateCategories(projectKey, typeKey) }

    /**
     * [projectKey] 프로젝트의 [typeKey] 이슈 타입에 대한 상태 키 → 카테고리 문자열 맵을 반환한다.
     *
     * `WorkflowSchemeNoDefaultException` 발생 시 빈 맵으로 폴백해 운영 500 을 차단한다
     * ([com.bts.issue.cfd.application.CfdService.resolveStateCategories] 동형 패턴).
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
                    "getCycleTime: no workflow scheme for typeKey={} projectKey={} — defaulting to empty map",
                    typeKey.value,
                    projectKey,
                )
                emptyMap()
            } else {
                throw e
            }
        }

    /**
     * 이슈 하나의 [IssueDurationInput] 을 전이 이력에서 조립한다.
     *
     * 초기 상태(생성 시점의 상태)는 신뢰하지 않고 전이 이력만 사용한다(EC8, 전이 기반 신뢰). [changes]
     * 는 오름차순 정렬되어 있다고 가정한다(repo 가 이미 정렬해 반환).
     *
     * - `firstInProgressAt` — `toValue` 카테고리가 [StatusCategory.IN_PROGRESS] 인 **첫** 전이의
     *   `changedAt`. 없으면 `null`(= 착수 시점 미정의, Cycle Time 표본에서 제외됨).
     * - `lastDoneAt` — `toValue` 카테고리가 [StatusCategory.DONE] 인 **마지막** 전이의 `changedAt`.
     *   없으면 `null`(= 미완료, 모집단에서 제외됨).
     *
     * @param issue Cycle/Lead Time 원천 이슈 메타.
     * @param changes 이 이슈의 status 전이 이력(오름차순).
     * @param typeIdToKey `issue_types.id → IssueTypeKey` 역매핑.
     * @param stateCache 타입별 상태 키 → 카테고리 맵 캐시.
     * @return 이 이슈의 [IssueDurationInput].
     */
    private fun buildDurationInput(
        issue: CycleTimeIssueSourceRow,
        changes: List<StatusChangeRow>,
        typeIdToKey: Map<Long, IssueTypeKey>,
        stateCache: Map<IssueTypeKey, Map<String, String>>,
    ): IssueDurationInput {
        val categoryMap = typeIdToKey[issue.typeId]?.let { stateCache[it] }.orEmpty()

        fun categoryOf(stateKey: String?): StatusCategory {
            return StatusCategory.fromCategoryString(stateKey?.let { categoryMap[it] })
        }

        val firstInProgressAt =
            changes.firstOrNull { categoryOf(it.toValue) == StatusCategory.IN_PROGRESS }?.changedAt
        val lastDoneAt =
            changes.lastOrNull { categoryOf(it.toValue) == StatusCategory.DONE }?.changedAt

        return IssueDurationInput(
            issueKey = issue.issueKey,
            createdAt = issue.createdAt,
            firstInProgressAt = firstInProgressAt,
            lastDoneAt = lastDoneAt,
        )
    }

    private companion object {
        /** project-workflow BC 내부 예외 simpleName — 직접 import 불가하므로 문자열로 식별. */
        const val WORKFLOW_SCHEME_NO_DEFAULT_EXCEPTION = "WorkflowSchemeNoDefaultException"
    }
}
