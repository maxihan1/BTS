// BulkOperationProcessor — pgmq 워커가 호출하는 일괄 작업 처리 로직 (actor 복원, 청크, best-effort, 멱등)

package com.bts.issue.bulk.application

import com.bts.issue.bulk.domain.BULK_OPERATION_CHUNK_SIZE
import com.bts.issue.bulk.domain.BulkOperation
import com.bts.issue.bulk.domain.BulkOperationId
import com.bts.issue.bulk.domain.BulkOperationItem
import com.bts.issue.bulk.repository.BulkOperationRepository
import com.bts.issue.domain.ActorId
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * 일괄 작업 처리 핵심 로직.
 *
 * pgmq 워커([com.bts.issue.bulk.worker.BulkOperationWorker])가 호출하며,
 * HTTP SecurityContext 없이 DB에 저장된 actorId 로 행위자를 복원하여 이슈 변경을 처리한다.
 *
 * ### 처리 흐름
 * 1. [BulkOperationRepository.findById] + [BulkOperationRepository.findItemsByOperationId] 로 작업·항목 로드
 * 2. 이미 종단(SUCCEEDED/FAILED) 항목은 멱등 스킵
 * 3. 항목을 [BULK_OPERATION_CHUNK_SIZE] 단위 청크로 나눠 처리
 * 4. 각 항목에 대해 [IssueApplicationService.findByKey] → version 추출 → payload 적용
 *    - [BulkOperationPayload.Edit] → [IssueApplicationService.updateIssue]
 *    - [BulkOperationPayload.Transition] → [IssueApplicationService.transitionIssue]
 * 5. 이슈 변경과 항목 상태 기록([BulkOperationRepository.updateItemResult])을 같은 트랜잭션에서 수행
 *    — C1 부분실패 창 제거(이슈 변경 커밋 후 항목 상태 기록 전 크래시로 PENDING 잔존 방지)
 * 6. 예외 발생 시 [FailureReasonCode] 로 매핑하여 FAILED 기록, 나머지 항목은 계속 처리(best-effort)
 * 7. 모든 항목 처리 완료 후 [BulkOperationRepository.recomputeAndPersistCounts] 로 카운트 재집계
 *
 * ### best-effort 예외 → FailureReasonCode 매핑
 * - [IssueNotFoundException] → [FailureReasonCode.NOT_FOUND]
 * - [IssueAccessDeniedException] → [FailureReasonCode.FORBIDDEN]
 * - [IssueTransitionNotAllowedException] → [FailureReasonCode.TRANSITION_NOT_ALLOWED]
 * - [IssueVersionConflictException] → [FailureReasonCode.VERSION_CONFLICT]
 * - [IssueWorkflowNotConfiguredException] → [FailureReasonCode.WORKFLOW_NOT_CONFIGURED]
 *
 * @param bulkRepo 일괄 작업 Repository.
 * @param itemExecutor 항목 1건 처리를 조율하는 실행기.
 */
@Component
class BulkOperationProcessor(
    private val bulkRepo: BulkOperationRepository,
    private val itemExecutor: BulkItemExecutor,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 일괄 작업을 처리한다.
     *
     * 항목별 독립 처리(best-effort) — 한 항목이 실패해도 나머지 항목 처리를 계속한다.
     * 이미 종단 상태(SUCCEEDED/FAILED)인 항목은 멱등 스킵한다.
     *
     * **actor 복원** — HTTP 요청 밖(pgmq 워커)이므로 SecurityContext 가 없다.
     * bulk_operations.actor_id 를 [ActorId] 로 복원하여 [IssueApplicationService] 에 전달한다.
     *
     * **동일 트랜잭션** — 이슈 변경([IssueApplicationService]) 과 항목 상태 기록
     * ([BulkOperationRepository.updateItemResult]) 이 같은 @Transactional 경계 안에서 수행된다.
     * 이슈 변경 커밋 후 항목 상태 기록 전 크래시로 PENDING 항목이 잔존하는 C1 부분실패 창을 제거한다.
     *
     * @param bulkOperationId 처리할 일괄 작업 식별자.
     * @throws IllegalStateException 작업을 찾을 수 없을 때.
     */
    @Transactional
    fun process(bulkOperationId: BulkOperationId) {
        val operation =
            bulkRepo.findById(bulkOperationId)
                ?: error("BulkOperation not found: ${bulkOperationId.value}")
        val items = bulkRepo.findItemsByOperationId(bulkOperationId)

        // actor 복원 — HTTP SecurityContext 없이 DB에 저장된 actorId 로 복원
        val actor = ActorId(operation.actorId)

        log.info(
            "bulk_op_processing id={} type={} totalItems={} actor={}",
            bulkOperationId.value,
            operation.type,
            items.size,
            actor.value,
        )

        // 청크 단위로 나눠 처리 (상한 BULK_OPERATION_MAX_SIZE / 청크 BULK_OPERATION_CHUNK_SIZE)
        items.chunked(BULK_OPERATION_CHUNK_SIZE).forEach { chunk ->
            processChunk(actor, operation, chunk)
        }

        bulkRepo.recomputeAndPersistCounts(bulkOperationId)

        log.info("bulk_op_processed id={}", bulkOperationId.value)
    }

    // ── private helpers ────────────────────────────────────────────────────────

    /**
     * 청크 내 항목들을 순차 처리한다.
     *
     * 이미 종단 상태인 항목은 멱등 스킵. 예외는 [FailureReasonCode] 로 매핑하여
     * best-effort 처리 — 한 항목 실패가 청크 전체를 중단하지 않는다.
     */
    private fun processChunk(
        actor: ActorId,
        operation: BulkOperation,
        chunk: List<BulkOperationItem>,
    ) {
        chunk.forEach { item ->
            // 멱등 스킵 — 이미 종단(SUCCEEDED/FAILED) 상태이면 재처리하지 않음
            if (BulkOperation.isTerminal(item)) {
                log.debug(
                    "bulk_op_item_skip id={} issueKey={} status={}",
                    operation.id.value,
                    item.issueKey.value,
                    item.status,
                )
                return@forEach
            }

            // REQUIRES_NEW 독립 트랜잭션으로 위임 — 한 항목 실패가 전체 트랜잭션을
            // rollback-only 로 마킹하지 않도록 격리한다 (BulkItemExecutor KDoc 참조)
            itemExecutor.executeItem(actor, operation, item)
        }
    }
}
