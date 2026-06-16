// 워크플로우 상태 목록 조회 SPI — 이슈 이동 preview 가 대상 프로젝트 상태 후보를 얻기 위해 사용

package com.bts.shared.workflow

import com.bts.shared.issue.IssueTypeKey
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/**
 * 프로젝트의 전체 워크플로우 상태 목록을 반환하는 SPI.
 *
 * 이슈 이동(move) 매핑 마법사가 대상 프로젝트 워크플로우의 상태 후보를 사용자에게
 * 보여주기 위해 호출한다. [WorkflowKeyResolver] 가 시작 상태 단일 결정에 집중하는 반면,
 * 이 SPI 는 **전체 상태 목록** 을 반환하는 읽기 전용 published language SPI 다.
 *
 * ### 구현 책임
 *
 * - 구현체. `project-workflow` BC 의 `WorkflowStateCatalogImpl`.
 * - consumer. `issue-tracking` BC 의 이슈 이동 preview 서비스.
 *
 * ### 호출 계층 제약 (Propagation.MANDATORY)
 *
 * 호출자는 반드시 활성 트랜잭션 안에서 이 메서드를 호출해야 한다.
 * 트랜잭션 없이 호출하면 Spring 이
 * [org.springframework.transaction.IllegalTransactionStateException] 을 던진다.
 * [WorkflowKeyResolver] 의 `Propagation.MANDATORY` 패턴과 동일하다.
 *
 * ### 읽기 전용 · 부수 효과 없음
 *
 * 이 SPI 는 DB 쓰기(INSERT/UPDATE/DELETE) 를 일절 수행하지 않는다.
 * 워크플로우 스킴 자동 배정([WorkflowKeyResolver.resolveStart] 의 auto-assign) 과 달리,
 * 스킴이 없는 프로젝트에 대해 배정을 수행하지 않고 빈 목록을 반환하거나
 * 구현체 재량에 따라 예외를 던진다.
 *
 * ADR 근거. `docs/decisions/2026-05-27-shared-kernel-extraction.md`
 */
interface WorkflowStateCatalog {
    /**
     * 프로젝트 + 이슈 타입 조합에서 가용한 전체 상태 목록을 반환한다.
     *
     * **읽기 전용 · 부수 효과 없음.** 이 메서드는 DB 쓰기나 이벤트 발행을 수행하지 않는다.
     * 이슈 이동 매핑 마법사처럼 상태 후보를 목록으로 보여야 하는 경로에서만 호출한다.
     *
     * 호출자 트랜잭션 강제. Application Service 또는 동등 계층에서만 호출.
     *
     * @param projectKey 상태 목록을 조회할 프로젝트 키. 예: `ProjectKey("ATLAS")`.
     * @param issueTypeKey 매핑 기준 이슈 타입 키. `null` 이면 default mapping 기준 워크플로우 상태를 반환한다.
     * @return 해당 프로젝트·이슈 타입의 워크플로우 상태 목록. 순서는 구현체가 정의한다.
     *   프로젝트에 워크플로우 스킴이 없거나 상태가 없으면 빈 리스트를 반환할 수 있다.
     * @throws RuntimeException (project-workflow BC 내부 예외) 프로젝트가 존재하지 않을 때.
     *   consumer BC 에서 이 예외를 처리하려면 메시지로 구분하거나 dedicated SPI 예외를 추가한다.
     */
    @Transactional(readOnly = true, propagation = Propagation.MANDATORY)
    fun listStates(
        projectKey: ProjectKey,
        issueTypeKey: IssueTypeKey?,
    ): List<WorkflowStateView>
}
