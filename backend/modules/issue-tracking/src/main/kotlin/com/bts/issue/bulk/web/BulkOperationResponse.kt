// 일괄 작업 조회 응답 DTO — BulkOperation + items 별쿼리 결과를 HTTP 응답 형태로 표현

package com.bts.issue.bulk.web

import com.bts.issue.bulk.domain.BulkOperation
import com.bts.issue.bulk.domain.BulkOperationItem
import com.bts.issue.bulk.domain.BulkOperationPayload
import java.util.UUID

/**
 * 일괄 작업 접수(POST) 응답 DTO.
 *
 * 202 Accepted 응답 본문. 접수된 작업의 식별자와 초기 상태만 포함한다.
 *
 * @property bulkOperationId 생성된 작업의 UUID.
 * @property status 초기 상태. 항상 "PENDING".
 * @property totalCount dedup 후 처리 대상 이슈 키 수.
 */
data class BulkOperationAcceptedResponse(
    val bulkOperationId: UUID,
    val status: String,
    val totalCount: Int,
)

/**
 * 일괄 작업 항목 응답 DTO.
 *
 * @property issueKey 처리 대상 이슈 키 문자열.
 * @property status 항목 처리 상태 문자열 (PENDING / SUCCEEDED / FAILED).
 * @property failureReasonCode 실패 시 원인 코드. FAILED 상태일 때만 non-null.
 */
data class BulkOperationItemResponse(
    val issueKey: String,
    val status: String,
    val failureReasonCode: String?,
) {
    companion object {
        /** [BulkOperationItem] 도메인 객체를 응답 DTO 로 변환한다. */
        fun from(item: BulkOperationItem): BulkOperationItemResponse =
            BulkOperationItemResponse(
                issueKey = item.issueKey.value,
                status = item.status.name,
                failureReasonCode = item.failureReasonCode?.name,
            )
    }
}

/**
 * 일괄 작업 조회(GET) 응답 DTO.
 *
 * 별쿼리로 조회한 [BulkOperation] 과 items 를 하나의 응답으로 합친다.
 * cartesian product 방지를 위해 items 는 별도 쿼리로 로드한 후 이 DTO 에서 결합한다
 * (learnings: jOOQ-cartesian-product).
 *
 * @property id 작업 UUID.
 * @property operationType 작업 유형 문자열 (BULK_EDIT / BULK_TRANSITION / STATUS_MIGRATION).
 *   STATUS_MIGRATION 은 POST /api/v1/issues/bulk-update 로 접수되지 않는다 — 워크플로우 상태 이관
 *   어댑터만 큐잉할 수 있으므로 이 값은 조회 응답에서만 관측된다.
 * @property status 현재 작업 상태 문자열.
 * @property payload 접수 시 요청한 파라미터. 사용자가 자신이 요청한 내용을 조회로 확인 가능.
 * @property totalCount 총 이슈 수.
 * @property processedCount 처리 완료(성공+실패) 수.
 * @property succeededCount 성공 수.
 * @property failedCount 실패 수.
 * @property items 항목 목록.
 */
data class BulkOperationResponse(
    val id: UUID,
    val operationType: String,
    val status: String,
    val payload: BulkOperationPayload,
    val totalCount: Int,
    val processedCount: Int,
    val succeededCount: Int,
    val failedCount: Int,
    val items: List<BulkOperationItemResponse>,
) {
    companion object {
        /**
         * [BulkOperation] 과 별쿼리로 로드한 [items] 를 응답 DTO 로 변환한다.
         *
         * @param operation 부모 작업 도메인 객체.
         * @param items 별쿼리 조회 항목 목록.
         */
        fun from(
            operation: BulkOperation,
            items: List<BulkOperationItem>,
        ): BulkOperationResponse =
            BulkOperationResponse(
                id = operation.id.value,
                operationType = operation.type.name,
                status = operation.status.name,
                payload = operation.payload,
                totalCount = operation.totalCount,
                processedCount = operation.processedCount,
                succeededCount = operation.succeededCount,
                failedCount = operation.failedCount,
                items = items.map { BulkOperationItemResponse.from(it) },
            )
    }
}
