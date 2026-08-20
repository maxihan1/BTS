// WorkflowKeyResolver SPI 구현체 — shared-kernel published language → project-workflow 내부 WorkflowResolver 위임

package com.bts.workflow.scheme.adapter.inbound

import com.bts.shared.issue.IssueTypeKey
import com.bts.shared.workflow.WorkflowKeyResolver
import com.bts.shared.workflow.WorkflowStartState
import com.bts.workflow.domain.TransitionKind
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
 *   - [com.bts.workflow.domain.Workflow] → [WorkflowStartState] (workflowKey + 시작 상태 키)
 *
 * ## 시작 상태 결정 규칙
 * 1. [TransitionKind.INITIAL] 전환이 있으면 그 전환의 도착 상태가 시작 상태다.
 *    [com.bts.workflow.domain.Workflow.of] factory 가 INITIAL 은 워크플로우당 최대 1개이고
 *    `toStateKey` 가 `states` 집합 안에 있음을 보장한다.
 * 2. INITIAL 전환이 없으면 `displayOrder` 가 가장 작은 상태로 폴백한다.
 *    V207 백필이 닿지 않은 워크플로우를 방어하는 경로다.
 *
 * 종전에는 2번만 있었고, 그래서 **관리자가 상태 표시 순서를 바꾸면 이슈 생성 상태가 조용히 바뀌었다.**
 * 워크플로우 편집(FR-WF-04~07)이 열리면서 사고가 되므로 1번이 앞에 섰다
 * (ADR `docs/adr/2026-08-18-workflow-transition-id-identity.md` §맥락 · §D2).
 *
 * factory 가 `states.isNotEmpty()` 를 보장하므로 두 경로 모두 실패하는 일은 없다.
 * 그럼에도 null 이면 데이터 무결성 위반으로 처리한다.
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
     * **쓰기 경로 전용 (auto-assign 포함).** 이슈 생성/전환(write path)에서만 호출한다.
     *
     * 내부적으로 [WorkflowResolver.resolveFor] 에 위임하고,
     * 반환된 [com.bts.workflow.domain.Workflow] 에서 위 「시작 상태 결정 규칙」대로 시작 상태를 추출한다.
     *
     * ## 시나리오별 동작
     * - **EC-1 auto-assign**: assignment 없는 신규 프로젝트는 software-scheme 을 자동 배정한 뒤 default mapping workflow 를 반환한다.
     *   자동 배정은 [WorkflowResolverImpl] 이 수행한다.
     * - **EC-2 no-default**: issueTypeKey 매칭 + default mapping 모두 부재 시 [com.bts.workflow.scheme.exception.WorkflowSchemeNoDefaultException] 가 발생한다.
     *   issue-tracking 에서 [com.bts.issue.domain.IssueWorkflowNotConfiguredException] 으로 변환한다 (Task 3/4).
     * - **EC-8 race-safe**: 동시에 여러 스레드가 동일 프로젝트에 auto-assign 을 시도해도 DB 의 ON CONFLICT 처리로 idempotent 하게 동작한다.
     *   중복 배정 없이 최초 1회만 기록되며 모든 스레드가 정상 응답을 받는다.
     *
     * @param projectKey shared-kernel [SharedProjectKey]. 내부 [InternalProjectKey] 로 변환 후 전달.
     * @param issueTypeKey 이슈 타입 키. null 이면 default mapping 직접 조회.
     *   FR-IS-02 (이슈 타입 도입) 이전에는 null 을 전달한다.
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

        val startStateKey =
            workflow.transitions.firstOrNull { it.kind == TransitionKind.INITIAL }?.toStateKey
                ?: workflow.states.minByOrNull { it.displayOrder }?.key
                ?: error(
                    "Workflow '${workflow.key}' 에 INITIAL 전환도 상태도 없습니다. " +
                        "Workflow.of() factory invariant 위반 — 데이터 무결성 오류.",
                )

        return WorkflowStartState(
            workflowKey = workflow.key,
            startStateKey = startStateKey,
        )
    }

    /**
     * **읽기 전용 경로 전용 — auto-assign 없음.**
     *
     * 프로젝트에 워크플로우 스킴이 할당되지 않은 경우 부수 효과 없이 `null` 을 반환한다.
     * `WorkflowSchemeAssignedEvent` 를 포함한 어떤 이벤트도 발행하지 않는다.
     * DB 쓰기(INSERT/UPDATE)가 일어나지 않는다.
     *
     * @param projectKey shared-kernel [SharedProjectKey].
     * @param issueTypeKey 이슈 타입 키. null 이면 default mapping 직접 조회.
     * @return 결정된 [WorkflowStartState], 또는 스킴 할당이 없으면 `null`.
     * @throws RuntimeException ([com.bts.workflow.scheme.exception.ProjectNotFoundException])
     *   projectKey 미존재 시 (EC-7).
     * @throws RuntimeException ([com.bts.workflow.scheme.exception.WorkflowSchemeNoDefaultException])
     *   스킴은 있지만 mapping 이 없을 때 (EC-2).
     */
    @Transactional(readOnly = true, propagation = Propagation.MANDATORY)
    override fun resolveExisting(
        projectKey: SharedProjectKey,
        issueTypeKey: IssueTypeKey?,
    ): WorkflowStartState? {
        val internalProjectKey = InternalProjectKey(projectKey.value)
        log.debug("resolveExisting projectKey={} issueTypeKey={}", projectKey.value, issueTypeKey?.value)

        val workflow =
            workflowResolver.resolveExistingFor(internalProjectKey, issueTypeKey)
                ?: return null

        val startStateKey =
            workflow.transitions.firstOrNull { it.kind == TransitionKind.INITIAL }?.toStateKey
                ?: workflow.states.minByOrNull { it.displayOrder }?.key
                ?: error(
                    "Workflow '${workflow.key}' 에 INITIAL 전환도 상태도 없습니다. " +
                        "Workflow.of() factory invariant 위반 — 데이터 무결성 오류.",
                )

        return WorkflowStartState(
            workflowKey = workflow.key,
            startStateKey = startStateKey,
        )
    }
}
