// WorkflowResolver outbound port — 프로젝트+이슈타입 기준 워크플로우 결정 (Propagation.MANDATORY)

package com.bts.workflow.scheme.port.outbound

import com.bts.shared.issue.IssueTypeKey
import com.bts.workflow.domain.Workflow
import com.bts.workflow.scheme.domain.ProjectKey
import com.bts.workflow.scheme.exception.ProjectNotFoundException
import com.bts.workflow.scheme.exception.WorkflowSchemeNoDefaultException
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/**
 * 프로젝트 키와 이슈 타입 키를 기준으로 적용 워크플로우를 결정하는 outbound port.
 *
 * ## 호출 계층 제약 (Propagation.MANDATORY)
 * 호출자는 반드시 활성 트랜잭션 안에서 이 메서드를 호출해야 한다.
 * Application Service 또는 동등 계층에서만 호출하며, 트랜잭션 없이 호출하면
 * Spring 이 [org.springframework.transaction.IllegalTransactionStateException] 을 던진다.
 *
 * PR #10 [com.bts.workflow.engine.WorkflowEngine.plan] 의 Propagation.MANDATORY 패턴과 일치한다.
 *
 * ## 매핑 결정 순서 (spec §S5)
 * 1. `project_workflow_scheme_assignments` 에서 프로젝트에 할당된 스킴 조회.
 *    - 할당 없으면 software-scheme 자동 배정 후 resolve (EC-1, D10 채택).
 * 2. `workflow_scheme_issue_type_mappings` 에서 issueTypeKey 매칭 항목 조회.
 * 3. 매칭 없으면 default mapping (issue_type_id IS NULL) 조회.
 * 4. default mapping 도 없으면 [WorkflowSchemeNoDefaultException] (EC-2).
 *
 * ## 구현 책임 분리
 * 본 인터페이스만 정의한다. 실제 구현체 [com.bts.workflow.scheme.adapter.inbound.WorkflowResolverImpl]
 * 은 T22 (Wave 4) 에서 등록된다. consumer wiring 은 본 PR scope 외 (G2 — PR #17 후속).
 */
interface WorkflowResolver {
    /**
     * 프로젝트와 이슈 타입에 적합한 워크플로우를 반환한다.
     *
     * **쓰기 경로 전용 (auto-assign 포함).** 이슈 생성/전환(write path)에서만 호출한다.
     * 읽기 전용 경로에서는 [resolveExistingFor] 를 사용해야 한다.
     *
     * 호출자 트랜잭션 강제. Application Service 또는 동등 계층에서만 호출.
     *
     * @param projectKey 워크플로우를 조회할 프로젝트 키. 예: `ProjectKey("ATLAS")`.
     * @param issueTypeKey 워크플로우를 조회할 이슈 타입 키. null 이면 default mapping 을 직접 조회한다.
     * @return 해당 프로젝트+타입에 적용된 [Workflow] 인스턴스.
     * @throws ProjectNotFoundException projectKey 에 해당하는 프로젝트가 없을 때 (EC-7).
     * @throws WorkflowSchemeNoDefaultException issueTypeKey 매칭 mapping 없음 + default mapping 도 없을 때 (EC-2).
     */
    @Transactional(readOnly = true, propagation = Propagation.MANDATORY)
    fun resolveFor(
        projectKey: ProjectKey,
        issueTypeKey: IssueTypeKey?,
    ): Workflow

    /**
     * **읽기 전용 경로 전용 — auto-assign 없음.**
     *
     * 프로젝트에 워크플로우 스킴이 할당돼 있지 않으면 auto-assign 을 수행하지 않고
     * `null` 을 반환한다. 이 메서드는 DB 쓰기(INSERT/UPDATE)를 일절 수행하지 않는다.
     *
     * 호출자 트랜잭션 강제. Application Service 또는 동등 계층에서만 호출.
     *
     * @param projectKey 워크플로우를 조회할 프로젝트 키. 예: `ProjectKey("ATLAS")`.
     * @param issueTypeKey 워크플로우를 조회할 이슈 타입 키. null 이면 default mapping 직접 조회.
     * @return 할당된 워크플로우, 또는 스킴 할당이 없으면 `null`.
     * @throws ProjectNotFoundException projectKey 에 해당하는 프로젝트가 없을 때 (EC-7).
     * @throws WorkflowSchemeNoDefaultException 스킴은 있지만 매칭 mapping 도 default mapping 도
     *   없을 때 (EC-2).
     */
    @Transactional(readOnly = true, propagation = Propagation.MANDATORY)
    fun resolveExistingFor(
        projectKey: ProjectKey,
        issueTypeKey: IssueTypeKey?,
    ): Workflow?
}
