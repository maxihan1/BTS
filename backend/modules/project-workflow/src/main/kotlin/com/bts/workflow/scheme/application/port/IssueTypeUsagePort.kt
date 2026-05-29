// 이슈 타입 사용 현황 조회 outbound port — ADR 2026-05-29-issue-type-cross-bc-introduction, workflow-bc-cross-bc-port

package com.bts.workflow.scheme.application.port

/**
 * 이슈 타입 사용 현황 조회 outbound port.
 *
 * project-workflow BC 가 정의하고, project-workflow BC 의 adapter 가 구현을 제공한다.
 * issue-tracking BC 가 이슈 타입 삭제 전 참조 유무를 확인하기 위해 소비한다.
 *
 * ## 패턴 일관성 — IssueTypeLookupPort 와 동일 방향
 * [IssueTypeLookupPort] 선례와 동일하게 project-workflow BC 가 port 를 선언한다.
 * issue-tracking 이 이미 project-workflow 를 `implementation` 으로 의존하므로 순환 없이
 * 이 port 를 소비할 수 있다.
 *
 * ## BC 격리 예외 정당화
 * issue-tracking 은 이슈 타입 삭제 전 참조 유무를 확인해야 하나, 참조 데이터
 * (`workflow_scheme_issue_type_mappings`) 는 project-workflow BC 소유이다.
 * ArchUnit 강제 BC 격리 규칙상 issue-tracking 이 project-workflow 내부를 직접 import
 * 할 수 없으므로 port-adapter 패턴으로 경계를 유지한다.
 * port 선언 위치는 데이터 소유 BC(project-workflow) 이며, issue-tracking → project-workflow
 * 컴파일 의존 방향이 이미 존재하므로 순환을 만들지 않는다.
 *
 * 자세한 결정 기록은 다음 ADR 을 참조한다.
 * - `docs/adr/2026-05-29-issue-type-cross-bc-introduction.md`
 * - `docs/adr/workflow-bc-cross-bc-port.md`
 *
 * ## ArchUnit 강제
 * - issue-tracking Service 계층은 이 interface 만 의존한다. 구체 구현체 직접 import 금지.
 * - adapter 구현체([com.bts.workflow.scheme.adapter.outbound.IssueTypeUsageAdapter])는
 *   project-workflow BC 에 위치하므로 BC 격리 룰 위반 없음.
 *
 * @see com.bts.workflow.scheme.adapter.outbound.IssueTypeUsageAdapter
 * @see IssueTypeLookupPort
 */
interface IssueTypeUsagePort {
    /**
     * `workflow_scheme_issue_type_mappings` 테이블에서 주어진 이슈 타입을 참조하는 스킴 매핑 수를 반환한다.
     *
     * 이슈 타입 삭제 전 사전 검사(pre-condition check)에 사용한다.
     * 반환값이 0 보다 크면 해당 이슈 타입은 하나 이상의 워크플로우 스킴에서 사용 중이므로
     * 삭제할 수 없다.
     *
     * @param issueTypeId 조회 대상 이슈 타입 PK.
     * @return 참조 중인 스킴 매핑 건수. 참조 없으면 0.
     */
    fun countSchemeMappings(issueTypeId: Long): Long
}
