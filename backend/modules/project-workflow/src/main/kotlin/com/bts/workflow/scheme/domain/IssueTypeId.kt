// project-workflow BC 내 이슈 타입 식별자 VO — workflow_scheme_issue_type_mappings.issue_type_id 매핑
package com.bts.workflow.scheme.domain

/**
 * project-workflow BC 내에서 이슈 타입을 식별하는 VO (Value Object).
 *
 * DB 테이블 `workflow_scheme_issue_type_mappings` 의 `issue_type_id BIGINT FK` 에 대응한다.
 *
 * BC 격리 원칙에 따라 issue-tracking BC 의 `IssueTypeId` 를 직접 import 하지 않고
 * 별도로 정의한다. 두 VO 는 동일한 Long 원시 값을 보유하며, BC 경계를 넘는 변환은
 * Anti-Corruption Layer(ACL) 에서 처리한다.
 *
 * @property value 이슈 타입 PK. 1 이상의 양의 정수.
 */
@JvmInline
value class IssueTypeId(val value: Long) {
    init {
        require(value > 0) { "IssueTypeId must be positive, but was: $value" }
    }
}
