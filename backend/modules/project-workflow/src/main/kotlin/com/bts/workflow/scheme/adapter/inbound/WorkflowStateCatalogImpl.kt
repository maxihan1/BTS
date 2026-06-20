// WorkflowStateCatalog SPI 구현체 — 프로젝트+이슈타입 기준 워크플로우 상태 목록 반환

package com.bts.workflow.scheme.adapter.inbound

import com.bts.shared.issue.IssueTypeKey
import com.bts.shared.workflow.WorkflowStateCatalog
import com.bts.shared.workflow.WorkflowStateView
import com.bts.workflow.domain.StateCategory
import com.bts.workflow.scheme.port.outbound.WorkflowResolver
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import com.bts.shared.workflow.ProjectKey as SharedProjectKey
import com.bts.workflow.scheme.domain.ProjectKey as InternalProjectKey

/**
 * [WorkflowStateCatalog] SPI 구현체.
 *
 * shared-kernel SPI([WorkflowStateCatalog])를 project-workflow BC 내부
 * outbound port([WorkflowResolver])에 위임한다.
 *
 * ## 책임 분리
 * - 워크플로우 결정 로직은 [WorkflowResolver.resolveExistingFor] 가 담당한다 (auto-assign 없음).
 * - 본 구현체는 SPI 경계 변환과 도메인 VO 매핑만 수행한다.
 *   - [SharedProjectKey] → [InternalProjectKey] 변환 (값 동일, 타입만 변환).
 *   - [com.bts.workflow.domain.WorkflowState] → [WorkflowStateView] 변환
 *     (key, name, isDone, category, displayOrder 전체 필드 매핑).
 *
 * ## 스킴 미할당 동작
 * [WorkflowResolver.resolveExistingFor] 가 null 을 반환하는 경우(스킴 미할당)
 * 빈 리스트를 반환한다. auto-assign 은 수행하지 않는다.
 * 호출자(issue-tracking 이슈 이동 preview 서비스)가 빈 리스트를 수신하면 422 를 반환해야 한다.
 *
 * ## 트랜잭션 정책 (Propagation.MANDATORY)
 * 호출자(Application Service 또는 동등 계층)가 반드시 활성 트랜잭션을 제공해야 한다.
 * [WorkflowKeyResolverImpl] 의 동일 패턴을 따른다.
 *
 * @param workflowResolver project-workflow BC 내부 outbound port. 실제 결정 로직 담당.
 */
@Component
class WorkflowStateCatalogImpl(
    private val workflowResolver: WorkflowResolver,
) : WorkflowStateCatalog {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 프로젝트 + 이슈 타입 조합에서 가용한 전체 상태 목록을 반환한다.
     *
     * 내부적으로 [WorkflowResolver.resolveExistingFor] 에 위임하고,
     * 반환된 [com.bts.workflow.domain.Workflow] 의 states 를 [WorkflowStateView] 로 변환한다.
     *
     * 스킴 미할당 시 빈 리스트를 반환한다. 호출자 트랜잭션 강제(MANDATORY).
     *
     * @param projectKey shared-kernel [SharedProjectKey]. 내부 [InternalProjectKey] 로 변환 후 전달.
     * @param issueTypeKey 매핑 기준 이슈 타입 키. null 이면 default mapping 기준 워크플로우 상태 반환.
     * @return 해당 워크플로우의 전체 상태 목록. 스킴 미할당 시 빈 리스트.
     * @throws RuntimeException ([com.bts.workflow.scheme.exception.ProjectNotFoundException]) projectKey 미존재 시.
     * @throws RuntimeException ([com.bts.workflow.scheme.exception.WorkflowSchemeNoDefaultException]) 스킴은 있지만 매핑 없을 때.
     */
    @Transactional(readOnly = true, propagation = Propagation.MANDATORY)
    override fun listStates(
        projectKey: SharedProjectKey,
        issueTypeKey: IssueTypeKey?,
    ): List<WorkflowStateView> {
        val internalProjectKey = InternalProjectKey(projectKey.value)
        log.debug("listStates projectKey={} issueTypeKey={}", projectKey.value, issueTypeKey?.value)

        val workflow =
            workflowResolver.resolveExistingFor(internalProjectKey, issueTypeKey)
                ?: run {
                    log.debug(
                        "listStates: no scheme assignment for projectKey={} — returning empty list",
                        projectKey.value,
                    )
                    return emptyList()
                }

        return workflow.states.map { state ->
            WorkflowStateView(
                key = state.key,
                name = state.name,
                isDone = state.category == StateCategory.DONE,
                category = state.category.name,
                displayOrder = state.displayOrder,
            )
        }
    }
}
