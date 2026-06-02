// 일괄 작업 유형 enum — BULK_EDIT(필드 수정), BULK_TRANSITION(상태 전이)

package com.bts.issue.bulk.domain

/**
 * 일괄 작업의 유형을 나타내는 enum.
 *
 * - [BULK_EDIT]: 여러 이슈의 필드(담당자, 우선순위 등)를 한 번에 수정한다.
 * - [BULK_TRANSITION]: 여러 이슈의 워크플로우 상태를 한 번에 전이한다.
 */
enum class BulkOperationType {
    BULK_EDIT,
    BULK_TRANSITION,
}
