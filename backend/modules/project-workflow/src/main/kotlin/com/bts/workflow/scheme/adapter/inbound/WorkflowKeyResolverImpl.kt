// WorkflowKeyResolver SPI 구현체 — shared-kernel published language → project-workflow 내부 WorkflowResolver 위임

package com.bts.workflow.scheme.adapter.inbound

import com.bts.shared.issue.IssueTypeKey
import com.bts.shared.workflow.WorkflowKeyResolver
import com.bts.shared.workflow.WorkflowStartState
import com.bts.workflow.scheme.port.outbound.WorkflowResolver
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import com.bts.shared.workflow.ProjectKey as SharedProjectKey
import com.bts.workflow.scheme.domain.ProjectKey as InternalProjectKey

/**
 * [WorkflowKeyResolver] SPI 구현체.
 *
 * shared-kernel published language SPI([WorkflowKeyResolver])를 project-workflow BC 내부
 * outbound port([WorkflowResolver])에 위임한다.
 *
 * ## 책임 분리
 * - 실제 워크플로우 결정 로직은 [WorkflowResolver] (= [WorkflowResolverImpl])가 담당한다.
 * - 본 구현체는 SPI 경계 변환만 수행한다.
 *   - [SharedProjectKey] → [InternalProjectKey] (값 동일, 타입 변환)
 *   - [com.bts.workflow.domain.Workflow] → [WorkflowStartState] (workflowKey + 최소 displayOrder 상태 키)
 *
 * ## 시작 상태 결정 규칙
 * `Workflow.states` 중 `displayOrder` 가 가장 작은 상태를 시작 상태로 간주한다.
 * [com.bts.workflow.domain.Workflow.of] factory 가 `states.isNotEmpty()` invariant 를 보장하므로
 * `minByOrNull` 결과는 항상 non-null 이다. null 인 경우는 데이터 무결성 위반으로 처리한다.
 *
 * ## 시나리오 요약
 * - **EC-1 auto-assign**: assignment 없는 신규 프로젝트 → [WorkflowResolver] 가 software-scheme 자동 배정 후 결정.
 * - **EC-2 no-default**: default mapping 부재 → [WorkflowResolver] 가 [com.bts.workflow.scheme.exception.WorkflowSchemeNoDefaultException] 발생.
 * - **EC-8 race**: 동시 auto-assign 요청 → [WorkflowResolverImpl] 의 ON CONFLICT 처리로 idempotent.
 *
 * ## 트랜잭션 정책
 * [Propagation.MANDATORY] — 호출자(Application Service 또는 동등 계층)가 활성 트랜잭션을 제공해야 한다.
 *
 * @param workflowResolver project-workflow BC 내부 outbound port. 실제 결정 로직 담당.
 */
@Component
class WorkflowKeyResolverImpl(
    private val workflowResolver: WorkflowResolver,
) : WorkflowKeyResolver {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 프로젝트와 이슈 타입에 적합한 워크플로우 시작 상태를 반환한다.
     *
     * 내부적으로 [WorkflowResolver.resolveFor] 에 위임하고,
     * 반환된 [com.bts.workflow.domain.Workflow] 에서 최소 displayOrder 상태를 시작 상태로 추출한다.
     *
     * @param projectKey shared-kernel [SharedProjectKey]. 내부 [InternalProjectKey] 로 변환 후 전달.
     * @param issueTypeKey 이슈 타입 키. null 이면 default mapping 직접 조회.
     * @return 결정된 [WorkflowStartState] (workflowKey + startStateKey).
     * @throws RuntimeException ([com.bts.workflow.scheme.exception.ProjectNotFoundException]) projectKey 미존재 시 (EC-7).
     * @throws RuntimeException ([com.bts.workflow.scheme.exception.WorkflowSchemeNoDefaultException]) default mapping 부재 시 (EC-2).
     */
    @Transactional(readOnly = true, propagation = Propagation.MANDATORY)
    override fun resolveStart(
        projectKey: SharedProjectKey,
        issueTypeKey: IssueTypeKey?,
    ): WorkflowStartState {
        val internalProjectKey = InternalProjectKey(projectKey.value)
        log.debug("resolveStart projectKey={} issueTypeKey={}", projectKey.value, issueTypeKey?.value)

        val workflow = workflowResolver.resolveFor(internalProjectKey, issueTypeKey)

        val startState =
            workflow.states.minByOrNull { it.displayOrder }
                ?: error(
                    "Workflow '${workflow.key}' 에 상태가 없습니다. " +
                        "Workflow.of() factory invariant 위반 — 데이터 무결성 오류.",
                )

        return WorkflowStartState(
            workflowKey = workflow.key,
            startStateKey = startState.key,
        )
    }
}
