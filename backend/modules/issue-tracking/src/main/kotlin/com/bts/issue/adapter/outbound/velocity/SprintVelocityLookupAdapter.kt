// 스프린트 벨로시티 원천 데이터 cross-BC 조회 어댑터 — issue-tracking 이 shared-kernel SprintVelocityLookupPort 구현 (FR-RP-02 Task 3)

package com.bts.issue.adapter.outbound.velocity

import com.bts.issue.jooq.tables.references.ISSUES
import com.bts.issue.repository.IssueRepository
import com.bts.issue.type.repository.IssueTypeRepository
import com.bts.shared.issue.IssueTypeKey
import com.bts.shared.permission.IssueSecurityDirectory
import com.bts.shared.velocity.SprintVelocityLookupPort
import com.bts.shared.velocity.VelocityContribution
import com.bts.shared.workflow.ProjectKey
import com.bts.shared.workflow.WorkflowStateCatalog
import com.bts.shared.workflow.WorkflowStateView
import org.jooq.DSLContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/** 워크플로우 상태 카테고리 문자열 — DONE 판정 기준 ([com.bts.workflow.domain.StateCategory.DONE.name] 과 동일). */
private const val DONE_CATEGORY = "DONE"

/**
 * [SprintVelocityLookupPort] 의 issue-tracking BC 구현 (FR-RP-02 Task 3).
 *
 * agile-planning BC 가 스프린트 벨로시티(계획 대비 완료 작업량)를 계산할 때 이 adapter 를 통해
 * 스프린트별 이슈들의 계획(commitment) 추정 시간과 완료(completed) 추정 시간 합계를 사전 집계해 받는다.
 * 두 BC 는 shared-kernel 의 [SprintVelocityLookupPort] 만 공유하며 서로를 직접 gradle 의존하지 않는다.
 *
 * ### 완료 판정 — FR-EP-02 [com.bts.issue.epic.application.IssueEpicService] 동형
 *
 * 이슈타입별 [WorkflowStateCatalog.listStates] 를 캐싱해(N+1 차단) 각 이슈의 `current_state_key` 가
 * DONE 카테고리인지 판정한다. `WorkflowSchemeNoDefaultException`(simpleName 판정 — project-workflow BC
 * 내부 예외를 직접 import 하지 않음) 발생 시 해당 타입 이슈는 전부 미완료 취급하여 운영 500 을 차단한다.
 *
 * ### 이슈별 가시성 필터 — 정본 보안 술어 재사용 ([SprintBurndownLookupPort] 선례와 동일 패턴)
 *
 * 집계 전에 [IssueSecurityDirectory.accessibleLevels] 로 viewer 의 접근 가능 보안 등급 집합을 1회 조회하고,
 * [IssueRepository.filterVisibleIssueKeys] 로 키 집합을 가시 이슈로 좁힌다. 정본 `buildActiveSecureWhere`
 * 술어를 재사용하므로, 보안 판정 로직을 복제하지 않고 프로젝트 BROWSE 통과 뷰어의 기밀 이슈 시간값
 * 간접 추론을 차단한다.
 *
 * ### 스프린트 역매핑
 *
 * [issueKeysBySprint] 입력 자체가 스프린트별 이슈 키 분할을 제공하므로, 이 어댑터는 `sprint_issues`
 * 테이블을 직접 조회하지 않는다(agile-planning BC 소유 테이블 — cross-BC 직접 접근 금지). 이슈 키 →
 * 스프린트 UUID 역매핑은 입력 map 을 순회해 메모리에서 구성한다.
 *
 * ### WorkflowSchemeNoDefaultException 격리 호출 — UnexpectedRollbackException 방지
 *
 * `WorkflowStateCatalog.listStates` 는 `Propagation.MANDATORY` 로 호출자의 활성 트랜잭션에 참여한다.
 * 스킴/매핑이 없어 이 메서드가 예외를 던지면, Spring 의 트랜잭션 인터셉터가 **참여 중인(공유) 트랜잭션**을
 * rollback-only 로 마킹한다 — 호출자가 catch 로 예외를 흡수해도 이 마킹은 취소되지 않는다. 이 상태에서
 * 이 어댑터 자신의 `@Transactional(readOnly = true)` 가 정상 커밋을 시도하면 `UnexpectedRollbackException`
 * 이 발생해 catch-and-continue 폴백이 무력화된다(실제 Testcontainers 통합 테스트로만 표면화 — MockK 단위
 * 테스트는 실 Spring AOP 트랜잭션이 없어 이 문제를 잡지 못한다).
 * [IsolatedWorkflowStateLookup] 이 `Propagation.REQUIRES_NEW` 로 별도 물리 트랜잭션에서 호출해,
 * 예외로 인한 rollback-only 마킹이 그 격리된 트랜잭션에만 국한되고 이 어댑터의 트랜잭션은 오염되지 않는다.
 *
 * @see SprintVelocityLookupPort
 * @see IssueRepository.filterVisibleIssueKeys
 */
@Component
class SprintVelocityLookupAdapter(
    private val dsl: DSLContext,
    private val securityDirectory: IssueSecurityDirectory,
    private val issueRepository: IssueRepository,
    private val issueTypeRepository: IssueTypeRepository,
    private val workflowStateLookup: IsolatedWorkflowStateLookup,
) : SprintVelocityLookupPort {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 스프린트별 이슈 키 집합의 벨로시티 원천 데이터를 viewer 가시 이슈로 한정해 조회한다.
     *
     * 집계 전에 [viewerUserId] 가 볼 수 없는 이슈(이슈별 보안 등급 차단)를 정본 보안 술어로 제외한다.
     * [WorkflowStateCatalog.listStates] 가 `Propagation.MANDATORY` 이므로 이 메서드는 활성 트랜잭션
     * 안에서 실행되어야 한다(`@Transactional` 로 보장).
     *
     * @param issueKeysBySprint 스프린트 UUID → 이슈 키 집합. 값이 빈 집합인 스프린트는 계획/완료 0 으로 귀결.
     * @param projectKey 이슈들이 속한 프로젝트 키.
     * @param viewerUserId 벨로시티를 조회하는 viewer UUID.
     * @return 스프린트 UUID → [VelocityContribution] map. 입력의 모든 스프린트 UUID 를 키로 포함한다.
     */
    @Transactional(readOnly = true)
    override fun fetchVelocitySource(
        issueKeysBySprint: Map<UUID, Set<String>>,
        projectKey: String,
        viewerUserId: UUID,
    ): Map<UUID, VelocityContribution> {
        val allIssueKeys = issueKeysBySprint.values.flatten().toSet()
        if (allIssueKeys.isEmpty()) {
            return zeroContributions(issueKeysBySprint.keys)
        }

        // 정본 보안 술어(buildActiveSecureWhere) 재사용 — viewer 가시 이슈로 키 집합을 좁힌다(복제 없음).
        val access = securityDirectory.accessibleLevels(viewerUserId, projectKey)
        val visibleKeys = issueRepository.filterVisibleIssueKeys(allIssueKeys, projectKey, viewerUserId, access)
        if (visibleKeys.isEmpty()) {
            return zeroContributions(issueKeysBySprint.keys)
        }

        val rows = fetchVelocityRows(visibleKeys)
        val typeIdToKey = loadTypeIdToKeyMap()
        val stateCache =
            rows.mapNotNull { typeIdToKey[it.typeId] }
                .toSet()
                .associateWith { typeKey -> resolveStateCategories(projectKey, typeKey) }
        val issueKeyToSprintId = buildIssueKeyToSprintId(issueKeysBySprint)

        return aggregateBySprint(rows, typeIdToKey, stateCache, issueKeyToSprintId, issueKeysBySprint.keys)
    }

    // ── private helpers ───────────────────────────────────────────────────────

    /** 가시 이슈들의 (키, 추정 시간, 현재 상태 키, 타입 id) 를 스칼라 컬럼만으로 단일 조회한다 (cartesian 위험 없음). */
    private fun fetchVelocityRows(issueKeys: Set<String>): List<VelocityRow> =
        dsl
            .select(ISSUES.KEY, ISSUES.ORIGINAL_ESTIMATE_SECONDS, ISSUES.CURRENT_STATE_KEY, ISSUES.TYPE_ID)
            .from(ISSUES)
            .where(ISSUES.KEY.`in`(issueKeys))
            .and(ISSUES.DELETED_AT.isNull)
            .fetch { record ->
                VelocityRow(
                    issueKey = record.get(ISSUES.KEY) ?: error("issues.key must not be null"),
                    estimateSeconds = record.get(ISSUES.ORIGINAL_ESTIMATE_SECONDS)?.toLong() ?: 0L,
                    currentStateKey = record.get(ISSUES.CURRENT_STATE_KEY) ?: error("issues.current_state_key must not be null"),
                    typeId = record.get(ISSUES.TYPE_ID) ?: error("issues.type_id must not be null"),
                )
            }

    /** issue_types.id → [IssueTypeKey] 맵 (findAll 1쿼리 — N+1 차단, [com.bts.issue.epic.application.IssueEpicService] 동형). */
    private fun loadTypeIdToKeyMap(): Map<Long, IssueTypeKey> =
        issueTypeRepository.findAll()
            .mapNotNull { type -> type.id?.let { it.value to type.key } }
            .toMap()

    /**
     * [projectKey] 프로젝트의 [typeKey] 이슈 타입에 대한 상태 키 → 카테고리 문자열 맵을 반환한다.
     *
     * WorkflowSchemeNoDefaultException 발생 시 빈 맵으로 폴백해 운영 500 을 차단한다.
     * 빈 맵이 반환되면 해당 타입 이슈 전체가 미완료(commitment 만 포함, completed 제외) 취급된다.
     * [com.bts.issue.epic.application.IssueEpicService.resolveStateCategories] 와 동일 패턴.
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
            if (e.javaClass.simpleName == "WorkflowSchemeNoDefaultException") {
                log.warn(
                    "fetchVelocitySource: no workflow scheme for typeKey={} projectKey={} — defaulting to empty state map",
                    typeKey.value,
                    projectKey,
                )
                emptyMap()
            } else {
                throw e
            }
        }

    /** 스프린트 UUID → 이슈 키 집합 입력을 이슈 키 → 스프린트 UUID 역매핑으로 뒤집는다. */
    private fun buildIssueKeyToSprintId(issueKeysBySprint: Map<UUID, Set<String>>): Map<String, UUID> {
        val reverse = mutableMapOf<String, UUID>()
        for ((sprintId, keys) in issueKeysBySprint) {
            for (key in keys) {
                reverse[key] = sprintId
            }
        }
        return reverse
    }

    /** 가시 이슈 행들을 스프린트별로 집계한다. */
    private fun aggregateBySprint(
        rows: List<VelocityRow>,
        typeIdToKey: Map<Long, IssueTypeKey>,
        stateCache: Map<IssueTypeKey, Map<String, String>>,
        issueKeyToSprintId: Map<String, UUID>,
        sprintIds: Set<UUID>,
    ): Map<UUID, VelocityContribution> {
        val commitment = mutableMapOf<UUID, Long>()
        val completed = mutableMapOf<UUID, Long>()
        for (row in rows) {
            val sprintId = issueKeyToSprintId[row.issueKey] ?: continue
            commitment[sprintId] = (commitment[sprintId] ?: 0L) + row.estimateSeconds
            val category = typeIdToKey[row.typeId]?.let { stateCache[it] }?.get(row.currentStateKey)
            if (category == DONE_CATEGORY) {
                completed[sprintId] = (completed[sprintId] ?: 0L) + row.estimateSeconds
            }
        }
        return sprintIds.associateWith { sprintId ->
            VelocityContribution(
                commitmentSeconds = commitment[sprintId] ?: 0L,
                completedSeconds = completed[sprintId] ?: 0L,
            )
        }
    }

    /** 모든 스프린트를 계획 0 · 완료 0 으로 매핑한다 (빈 이슈 키 조기 반환 — jOOQ 빈 `IN` 절 함정 방지). */
    private fun zeroContributions(sprintIds: Set<UUID>): Map<UUID, VelocityContribution> =
        sprintIds.associateWith { VelocityContribution(commitmentSeconds = 0L, completedSeconds = 0L) }

    /** [fetchVelocityRows] 조회 결과 1행. */
    private data class VelocityRow(
        val issueKey: String,
        val estimateSeconds: Long,
        val currentStateKey: String,
        val typeId: Long,
    )
}

/**
 * [WorkflowStateCatalog.listStates] 호출을 `Propagation.REQUIRES_NEW` 로 격리해 실행하는 전용 Bean.
 *
 * ## 격리 이유 — self-invocation 으로는 REQUIRES_NEW 를 적용할 수 없음
 * [SprintVelocityLookupAdapter] 클래스 내부의 private 메서드로 `@Transactional(REQUIRES_NEW)` 를
 * 선언해도 같은 클래스 안에서 호출하면(self-invocation) Spring 프록시를 타지 않아 트랜잭션 속성이
 * 무시된다. 별도 `@Component` Bean 으로 분리해 주입받아 호출해야 실제로 새 물리 트랜잭션이 열린다.
 *
 * ## WorkflowStateCatalog 인터페이스를 구현하지 않는 이유
 * 이 클래스가 [WorkflowStateCatalog] 인터페이스를 구현하면, Spring 컨텍스트 전역에서 해당 인터페이스를
 * 자격한정자(`@Qualifier`) 없이 주입받는 다른 소비자(예: `IssueEpicService`, `IssueMoveService`)가
 * 두 개의 후보 Bean 사이에서 모호해진다. 별도의 전용 타입으로 정의해 [SprintVelocityLookupAdapter] 만
 * 이 격리 경로를 사용하도록 범위를 좁힌다.
 *
 * @param workflowStateCatalog 실제 워크플로우 상태 목록 조회를 수행하는 SPI 구현체.
 */
@Component
class IsolatedWorkflowStateLookup(
    private val workflowStateCatalog: WorkflowStateCatalog,
) {
    /**
     * [projectKey] + [issueTypeKey] 조합의 워크플로우 상태 목록을 별도 물리 트랜잭션에서 조회한다.
     *
     * 예외(예: `WorkflowSchemeNoDefaultException`)가 발생하면 이 격리된 트랜잭션만 rollback 되고,
     * 호출자의 트랜잭션은 영향받지 않는다 — 호출자가 예외를 catch 하고 정상적으로 계속 진행할 수 있다.
     *
     * @param projectKey 상태 목록을 조회할 프로젝트 키.
     * @param issueTypeKey 매핑 기준 이슈 타입 키.
     * @return 해당 프로젝트·이슈 타입의 워크플로우 상태 목록.
     * @throws RuntimeException project-workflow BC 내부 예외(스킴/매핑 미존재 등). 호출자가 catch 한다.
     */
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    fun listStates(
        projectKey: ProjectKey,
        issueTypeKey: IssueTypeKey,
    ): List<WorkflowStateView> = workflowStateCatalog.listStates(projectKey, issueTypeKey)
}
