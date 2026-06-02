// 일괄 작업 완료 이벤트 페이로드 — q_bulk_operation_events 큐에 발행되는 도메인 이벤트

package com.bts.issue.bulk.event

import com.bts.issue.bulk.domain.BulkOperationId

/**
 * 일괄 작업이 성공적으로 완료되었음을 나타내는 도메인 이벤트 페이로드.
 *
 * [BulkOperationWorker] 가 [com.bts.issue.bulk.repository.BulkOperationRepository.markCompleted] CAS
 * 성공 직후 [BulkOperationEventPublisher] 를 통해 `q_bulk_operation_events` 큐에 단 1회 발행한다.
 *
 * **중복 발행 금지(C4)** — markCompleted CAS 가 false 를 반환한 경우(이미 다른 경로로 완료)
 * 이 이벤트를 발행하지 않는다. DB CAS 원자성이 단 1회 보장의 근거다.
 *
 * 페이로드 JSON 형식.
 * ```json
 * {"bulkOperationId":"<UUID>"}
 * ```
 *
 * @property bulkOperationId 완료된 일괄 작업 식별자.
 */
data class BulkOperationCompleted(
    val bulkOperationId: BulkOperationId,
)
