// 이슈 타입 식별자 VO — issue_types.id BIGINT 매핑

package com.bts.shared.issue

/**
 * 이슈 타입 식별자 VO.
 *
 * `issue_types.id BIGINT` 컬럼과 1:1로 매핑된다.
 * 0 이하 값은 DB에서 유효하지 않으므로 생성 시점에 거부한다.
 *
 * @param value 양수인 이슈 타입 식별자
 * @throws IllegalArgumentException [value]가 0 이하인 경우
 */
@JvmInline
value class IssueTypeId(val value: Long) {
    init {
        require(value > 0) { "IssueTypeId must be positive, but was: $value" }
    }
}
