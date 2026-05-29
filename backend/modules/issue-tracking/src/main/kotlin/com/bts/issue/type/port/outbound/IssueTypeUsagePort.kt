// 이슈 타입 사용 현황 조회 port 위치 안내 — 실제 인터페이스는 project-workflow 모듈에 위치

package com.bts.issue.type.port.outbound

/**
 * ## IssueTypeUsagePort 위치 안내
 *
 * 명세(Task 4)는 이 패키지에 [IssueTypeUsagePort] 를 선언하도록 지시했으나,
 * 실제 구현 시 아키텍처 제약으로 인해 [com.bts.workflow.scheme.application.port.IssueTypeUsagePort]
 * 에 위치한다.
 *
 * ## 이유
 * issue-tracking 은 이미 project-workflow 를 `implementation` 의존으로 갖고 있다.
 * 이 패키지에 인터페이스를 두면 project-workflow 가 issue-tracking 을 추가로 `implementation`
 * 의존해야 하므로 **양방향 순환 의존**이 발생한다 (Gradle 빌드 불가).
 *
 * 기존 [com.bts.workflow.scheme.application.port.IssueTypeLookupPort] 선례와 동일하게
 * project-workflow BC 가 port 를 선언하고, issue-tracking 이 소비하는 방향을 유지한다.
 *
 * ## ADR 참조
 * - `docs/adr/2026-05-29-issue-type-cross-bc-introduction.md`
 * - `docs/adr/workflow-bc-cross-bc-port.md`
 *
 * @see com.bts.workflow.scheme.application.port.IssueTypeUsagePort
 * @see com.bts.workflow.scheme.adapter.outbound.IssueTypeUsageAdapter
 */
private object IssueTypeUsagePortLocation
