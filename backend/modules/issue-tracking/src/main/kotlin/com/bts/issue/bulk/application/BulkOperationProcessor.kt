// BulkOperationProcessor — pgmq 워커가 호출하는 일괄 작업 처리 로직 (actor 복원, 청크, best-effort, 멱등)

package com.bts.issue.bulk.application

import com.bts.issue.bulk.domain.BULK_OPERATION_CHUNK_SIZE
import com.bts.issue.bulk.domain.BULK_OPERATION_MAX_SIZE
import com.bts.issue.bulk.domain.BulkOperation
import com.bts.issue.bulk.domain.BulkOperationId
import com.bts.issue.bulk.domain.BulkOperationItem
import com.bts.issue.bulk.domain.BulkOperationPayload
import com.bts.issue.bulk.domain.BulkOperationType
import com.bts.issue.bulk.repository.BulkOperationRepository
import com.bts.issue.domain.ActorId
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

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
     * **@Transactional 없음 — 의도적 설계 (F5)**.
     * 각 항목은 [com.bts.issue.bulk.application.BulkItemExecutor.executeItem] 의 REQUIRES_NEW 트랜잭션이 담당한다.
     * process() 에 @Transactional 을 걸면 REQUIRES_NEW 의 외부 트랜잭션이 생겨
     * 항목 실패 시 rollback-only 마킹이 다른 항목 커밋을 방해하는 전파 문제가 발생한다.
     * recomputeAndPersistCounts 는 자체 @Transactional 을 가진 repository 메서드가 처리한다.
     *
     * @param bulkOperationId 처리할 일괄 작업 식별자.
     * @throws IllegalStateException 작업을 찾을 수 없을 때.
     */
    fun process(bulkOperationId: BulkOperationId) {
        val operation =
            bulkRepo.findById(bulkOperationId)
                ?: error("BulkOperation not found: ${bulkOperationId.value}")

        // 0단계 — STATUS_MIGRATION 만 항목을 여기서 만든다. 실행할 수 없는 작업이면 더 가지 않는다.
        if (!materializeItemsIfNeeded(operation)) return

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
        reportStatusMigrationFailures(operation)

        log.info("bulk_op_processed id={}", bulkOperationId.value)
    }

    // ── private helpers ────────────────────────────────────────────────────────

    /**
     * [BulkOperationType.STATUS_MIGRATION] 이면 항목을 **지금** 적재한다 (F15).
     *
     * 나머지 두 타입은 접수 시점에 항목이 박히므로 그대로 통과한다.
     *
     * 실행 시점 대상이 [BULK_OPERATION_MAX_SIZE] 를 넘으면 아무것도 적재하지 않고 작업을
     * [BulkOperationRepository.markFailed] 로 끝낸다 — **조용히 자르지 않는다**(E4 · J6).
     * 잘린 나머지는 옛 상태에 남아 유령이 되는데 화면은 「완료」로 보이기 때문이다.
     * 그리고 그대로 재시도해도 결과가 같으므로 사유에 [OVER_LIMIT_FAILURE_REASON] 의
     * **분할 재시도 안내**를 함께 남긴다 — 막다른 길임을 알리지 않는 것 자체가 결함이다(C-2 · F19).
     *
     * @return 항목 처리를 계속해도 되면 true. 작업이 FAILED 로 끝났으면 false.
     */
    private fun materializeItemsIfNeeded(operation: BulkOperation): Boolean {
        val payload = operation.payload
        if (payload !is BulkOperationPayload.StatusMigration) return true

        val targetCount = bulkRepo.materializeStatusMigrationItems(operation.id, payload)
        if (targetCount <= BULK_OPERATION_MAX_SIZE) return true

        bulkRepo.markFailed(operation.id)
        log.error(
            "status_migration_over_limit id={} targetCount={} maxSize={} reason={}",
            operation.id.value,
            targetCount,
            BULK_OPERATION_MAX_SIZE,
            OVER_LIMIT_FAILURE_REASON,
        )
        return false
    }

    /**
     * 이관이 실패한 항목을 남긴 채 끝났으면 그것을 로그로 드러낸다 (C-3 · F20).
     *
     * 작업 상태는 `COMPLETED` 인데 실패한 건은 옛 상태에 그대로 남는다 — 그게 유령이다.
     * 이 PR 에는 이관 결과를 보여 주는 화면이 아직 없으므로(결선은 PR 7b) 운영자가 그것을 알 수 있는
     * 경로는 이 로그뿐이다. 조용한 실패를 금지하는 것이 이 기능의 존재 이유이므로 조용히 끝내지 않는다.
     *
     * 카운트는 [BulkOperationRepository.recomputeAndPersistCounts] 뒤에 **다시 읽는다** —
     * 항목 결과는 각 항목의 REQUIRES_NEW 트랜잭션이 썼으므로 메모리의 [operation] 은 낡았다.
     */
    private fun reportStatusMigrationFailures(operation: BulkOperation) {
        if (operation.type != BulkOperationType.STATUS_MIGRATION) return
        val settled = bulkRepo.findById(operation.id) ?: return
        if (settled.failedCount == 0) return

        log.warn(
            "status_migration_items_failed id={} total={} succeeded={} failed={}",
            settled.id.value,
            settled.totalCount,
            settled.succeededCount,
            settled.failedCount,
        )
    }

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

    companion object {
        /**
         * 실행 시점 상한 초과로 작업을 FAILED 로 둘 때 함께 남기는 사유 문구.
         *
         * **분할 재시도 안내가 이 문구의 본체다**(C-2 · F19). 상한을 넘은 이관은 그대로 재시도해도
         * 같은 결과라, 안내가 없으면 운영자는 그 상태를 영영 못 뺀다. 「아무것도 안 옮겼다」를 함께
         * 밝히는 이유는 부분 이관을 의심하며 DB 를 뒤지게 만들지 않기 위해서다.
         */
        const val OVER_LIMIT_FAILURE_REASON: String =
            "status migration targets exceed the bulk operation limit — nothing was migrated; " +
                "split the scope into smaller projectKeys or fewer status mappings and retry"
    }
}
