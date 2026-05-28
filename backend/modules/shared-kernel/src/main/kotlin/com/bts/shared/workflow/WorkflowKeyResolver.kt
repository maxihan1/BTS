// 워크플로우 결정 결과를 issue-tracking 등 consumer 가 사용하는 published language SPI

package com.bts.shared.workflow

import com.bts.shared.issue.IssueTypeKey
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/**
 * 프로젝트 키와 이슈 타입 키를 기준으로 워크플로우 시작 상태를 결정하는 SPI.
 *
 * issue-tracking BC 등 consumer 가 이슈 생성/전이 시 적용할 워크플로우와 시작 상태를 얻기 위해 호출한다.
 * project-workflow BC 의 내부 [com.bts.workflow.scheme.port.outbound.WorkflowResolver] 보다
 * 노출 범위를 최소화한 published language SPI 다 — consumer 는 [WorkflowStartState] 만 알면 된다.
 *
 * ## 호출 계층 제약 (Propagation.MANDATORY)
 *
 * 호출자는 반드시 활성 트랜잭션 안에서 이 메서드를 호출해야 한다.
 * Application Service 또는 동등 계층에서만 호출하며, 트랜잭션 없이 호출하면
 * Spring 이 [org.springframework.transaction.IllegalTransactionStateException] 을 던진다.
 *
 * ## 결정 순서
 *
 * 1. 프로젝트에 할당된 워크플로우 스킴 조회.
 *    - 할당 없으면 software-scheme 자동 배정 후 결정 (EC-1).
 * 2. `issueTypeKey` 에 매칭되는 mapping 조회.
 * 3. 매칭 없으면 default mapping (issue_type_id IS NULL) 조회.
 * 4. default mapping 도 없으면 [com.bts.workflow.scheme.exception.WorkflowSchemeNoDefaultException] (EC-2).
 *
 * ## 구현 책임 분리
 *
 * 본 인터페이스만 정의한다. 구현체는 project-workflow BC 의 `WorkflowKeyResolverImpl` 이며
 * Task 2 에서 등록된다.
 *
 * ADR 근거. [docs/decisions/2026-05-27-shared-kernel-extraction.md]
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
     * @throws com.bts.workflow.scheme.exception.ProjectNotFoundException projectKey 에 해당하는 프로젝트가 없을 때 (EC-7).
     * @throws com.bts.workflow.scheme.exception.WorkflowSchemeNoDefaultException issueTypeKey 매칭 mapping 없음 + default mapping 도 없을 때 (EC-2).
     */
    @Transactional(readOnly = true, propagation = Propagation.MANDATORY)
    fun resolveStart(
        projectKey: ProjectKey,
        issueTypeKey: IssueTypeKey?,
    ): WorkflowStartState
}
