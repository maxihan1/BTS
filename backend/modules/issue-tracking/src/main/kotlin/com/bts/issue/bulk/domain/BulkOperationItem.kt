// 일괄 작업 내 단일 이슈 항목 VO — 처리 상태와 실패 사유를 보유한다

package com.bts.issue.bulk.domain

import com.bts.issue.domain.IssueKey

/**
 * 일괄 작업 단일 항목의 처리 상태.
 *
 * - [PENDING]: 아직 처리 전.
 * - [SUCCEEDED]: 처리 성공.
 * - [FAILED]: 처리 실패. [BulkOperationItem.failureReasonCode] 에 사유가 기록된다.
 */
enum class ItemStatus {
    PENDING,
    SUCCEEDED,
    FAILED,
}

/**
 * [BulkOperation] 내 단일 이슈 처리 항목.
 *
 * 불변 VO(Value Object)이므로 상태 변경 시 copy() 로 새 인스턴스를 반환한다.
 *
 * invariant.
 * - [status] 가 [ItemStatus.FAILED] 이면 [failureReasonCode] 는 non-null 이어야 한다.
 * - [status] 가 [ItemStatus.SUCCEEDED] 이면 [failureReasonCode] 는 null 이어야 한다.
 *
 * @property issueKey 처리 대상 이슈 키. 불변.
 * @property status 현재 처리 상태.
 * @property failureReasonCode 실패 시 원인 코드. [ItemStatus.FAILED] 일 때만 non-null.
 */
data class BulkOperationItem(
    val issueKey: IssueKey,
    val status: ItemStatus,
    val failureReasonCode: FailureReasonCode? = null,
) {
    init {
        require(status != ItemStatus.FAILED || failureReasonCode != null) {
            "failureReasonCode must be provided when status is FAILED (issueKey=${issueKey.value})"
        }
        require(status != ItemStatus.SUCCEEDED || failureReasonCode == null) {
            "failureReasonCode must be null when status is SUCCEEDED (issueKey=${issueKey.value})"
        }
    }
}
