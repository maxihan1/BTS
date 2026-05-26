// 이슈 타입 식별자 VO — issue_types.id BIGINT 매핑
package com.bts.issue.type.domain

/**
 * 이슈 타입을 식별하는 VO (Value Object).
 *
 * DB 테이블 `issue_types` 의 PK 컬럼 `id BIGINT` 에 대응한다.
 * 0 이하의 값은 유효하지 않은 DB PK 이므로 허용하지 않는다.
 *
 * `@JvmInline value class` 로 선언하여 런타임에 래퍼 객체 할당 없이
 * Long 원시값으로 처리된다 (PR #14 IssueKey 패턴 일치).
 *
 * @property value 이슈 타입 PK. 1 이상의 양의 정수.
 */
@JvmInline
value class IssueTypeId(val value: Long) {
    init {
        require(value > 0) { "IssueTypeId must be positive, but was: $value" }
    }
}
