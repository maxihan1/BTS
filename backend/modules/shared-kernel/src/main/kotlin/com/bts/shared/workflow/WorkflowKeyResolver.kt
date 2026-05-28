// 워크플로우 결정 결과를 issue-tracking 등 consumer 가 사용하는 published language SPI

package com.bts.shared.workflow

import com.bts.shared.issue.IssueTypeKey
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/**
 * 프로젝트 키와 이슈 타입 키를 기준으로 워크플로우 시작 상태를 결정하는 SPI.
 *
 * issue-tracking BC 등 consumer 가 이슈 생성/전이 시 적용할 워크플로우와 시작 상태를 얻기 위해 호출한다.
 * project-workflow BC 내부의 `WorkflowResolver` (outbound port) 보다 노출 범위를 최소화한
 * published language SPI 다 — consumer 는 [WorkflowStartState] (workflowKey + startStateKey) 만 알면 된다.
 *
 * ## 호출 계층 제약 (Propagation.MANDATORY)
 *
 * 호출자는 반드시 활성 트랜잭션 안에서 이 메서드를 호출해야 한다.
 * Application Service 또는 동등 계층에서만 호출하며, 트랜잭션 없이 호출하면
 * Spring 이 [org.springframework.transaction.IllegalTransactionStateException] 을 던진다.
 * [WorkflowTransitionPort.plan] 의 `Propagation.MANDATORY` 패턴과 일치한다.
 *
 * ## 결정 순서 (spec §S5 기준)
 *
 * 1. 프로젝트에 할당된 워크플로우 스킴 조회.
 *    - 할당 없으면 software-scheme 자동 배정 후 결정 (EC-1, D10 채택).
 * 2. `issueTypeKey` 에 매칭되는 mapping 조회.
 * 3. 매칭 없으면 default mapping (`issue_type_id IS NULL`) 조회.
 * 4. default mapping 도 없으면 `WorkflowSchemeNoDefaultException` 발생 (EC-2).
 *
 * ## 구현 책임 분리
 *
 * 본 인터페이스만 정의한다. 구현체는 project-workflow BC 의 `WorkflowKeyResolverImpl` 이며
 * Task 2 에서 등록된다. consumer wiring 은 Task 4/5 (IssueApplicationService) 에서 완성된다.
 *
 * ADR 근거. `docs/decisions/2026-05-27-shared-kernel-extraction.md`
 */
interface WorkflowKeyResolver {
    /**
     * 프로젝트와 이슈 타입에 적합한 워크플로우 시작 상태를 반환한다.
     *
     * 호출자 트랜잭션 강제. Application Service 또는 동등 계층에서만 호출.
     *
     * @param projectKey 워크플로우를 조회할 프로젝트 키. 예: `ProjectKey("ATLAS")`.
     * @param issueTypeKey 워크플로우를 조회할 이슈 타입 키. `null` 이면 default mapping 을 직접 조회한다.
     *   FR-IS-02 (이슈 타입 도입) 이전에는 `null` 을 전달한다.
     * @return 결정된 워크플로우 키 + 시작 상태 키를 담은 [WorkflowStartState].
     * @throws RuntimeException (project-workflow BC 내부 `ProjectNotFoundException`) projectKey 에 해당하는 프로젝트가 없을 때 (EC-7).
     *   consumer BC 에서 이 예외를 처리하려면 메시지로 구분하거나 dedicated SPI 예외를 추가한다.
     * @throws RuntimeException (project-workflow BC 내부 `WorkflowSchemeNoDefaultException`) issueTypeKey 매칭 mapping 없음
     *   + default mapping 도 없을 때 (EC-2). issue-tracking 에서 `IssueWorkflowNotConfiguredException` 으로 변환한다 (Task 3/4).
     */
    @Transactional(readOnly = true, propagation = Propagation.MANDATORY)
    fun resolveStart(
        projectKey: ProjectKey,
        issueTypeKey: IssueTypeKey?,
    ): WorkflowStartState
}
