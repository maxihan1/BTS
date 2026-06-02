// 일괄 작업 전체의 생명주기 상태 enum — PENDING→RUNNING→COMPLETED / PENDING→FAILED

package com.bts.issue.bulk.domain

/**
 * [BulkOperation] 전체 상태 머신의 상태.
 *
 * 허용 전이.
 * - [PENDING] → [RUNNING] (start)
 * - [RUNNING] → [COMPLETED] (complete)
 * - [PENDING] → [FAILED] (인프라 오류, fail)
 * - [RUNNING] → [FAILED] (fail)
 *
 * [COMPLETED], [FAILED] 는 종단(terminal) 상태로 추가 전이가 불가하다.
 */
enum class BulkOperationStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    FAILED,
}
