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
 * 0. **[BulkOperationType.STATUS_MIGRATION] 이면 항목을 지금 적재한다**
 *    ([BulkOperationRepository.materializeStatusMigrationItems]). 이 타입만 항목 생성 시점이 다르며
 *    사유는 아래 §「이 타입만 항목 생성 시점이 다르다」. 실행 시점 대상이 [BULK_OPERATION_MAX_SIZE] 를
 *    넘으면 **장부를 먼저 정산한 뒤** 작업을 FAILED 로 끝내고 나머지 단계를 밟지 않는다
 *    (E4 — 조용히 자르지 않는다 · 정산 순서의 근거는 [failOverLimit])
 * 1. [BulkOperationRepository.findById] + [BulkOperationRepository.findItemsByOperationId] 로 작업·항목 로드
 * 2. 이미 종단(SUCCEEDED/FAILED) 항목은 멱등 스킵
 * 3. 항목을 [BULK_OPERATION_CHUNK_SIZE] 단위 청크로 나눠 처리
 * 4. 각 항목에 대해 [IssueApplicationService.findByKey] → version 추출 → payload 적용
 *    - [BulkOperationPayload.Edit] → [IssueApplicationService.updateIssue]
 *    - [BulkOperationPayload.Transition] → [IssueApplicationService.transitionIssue]
 *    - [BulkOperationPayload.StatusMigration] → 엔진을 우회한 직접 재작성 ([BulkItemApplier] KDoc 이 정본)
 * 5. 이슈 변경과 항목 상태 기록([BulkOperationRepository.updateItemResult])을 같은 트랜잭션에서 수행
 *    — C1 부분실패 창 제거(이슈 변경 커밋 후 항목 상태 기록 전 크래시로 PENDING 잔존 방지)
 * 6. 예외 발생 시 [FailureReasonCode] 로 매핑하여 FAILED 기록, 나머지 항목은 계속 처리(best-effort)
 * 7. 모든 항목 처리 완료 후 [BulkOperationRepository.recomputeAndPersistCounts] 로 카운트 재집계
 * 8. 이관이 **실패 항목을 남긴 채** 끝났으면 로그로 드러낸다 (F20 — 조용한 실패 금지)
 *
 * ### ★이 타입만 항목 생성 시점이 다르다 (F15)
 * [BulkOperationType.BULK_EDIT] · [BulkOperationType.BULK_TRANSITION] 은 접수 시점에 호출자가 이슈 키를
 * 직접 주므로 항목이 그때 박힌다. [BulkOperationType.STATUS_MIGRATION] 의 대상은 **상태와 프로젝트
 * 범위로만 기술**되고, 그 조건을 만족하는 이슈 집합은 시간에 따라 변한다.
 *
 * 큐잉 시점에 그 집합을 굳히면 큐잉 → 실행 사이에 그 상태로 들어온 이슈를 통째로 버린다. 버려진
 * 이슈들은 워크플로우 정의가 교체된 뒤 **사라진 상태를 가리키는 유령**이 되는데 작업은 `COMPLETED`
 * 로 보인다. 장부 `TODOS.md` 부채 143(이관 판정과 교체 사이 TOCTOU)의 처방 문구가 정확히 그것이다 —
 * 「**세고 나서 옮긴다가 아니라 옮기면서 센다**」. 그래서 이 클래스가 0단계를 갖는다.
 *
 * ### 닫힌 창과 남은 창 — 구분해서 적는다
 * - **닫혔다** — 큐잉 → claim 사이의 유입(E12). 0단계가 claim 뒤에 다시 긁으므로 그때까지 들어온
 *   것이 전부 대상이 된다. 부채 143 의 절반이 여기서 닫힌다.
 * - **남았다** — **이관 완료 → 정의 교체** 사이의 유입(E15). 이 경로는 그것을 모른다. 발행 경로가
 *   「이관 → 재확인 → 교체」 루프를 돌아야 닫히고 그 루프는 project-workflow 소관이라 **PR 7b** 다.
 *   반쪽임을 숨기지 않는다 — 숨기면 다음 사람이 「이미 닫힌 창」으로 읽는다.
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
     * 실행 시점 대상이 [BULK_OPERATION_MAX_SIZE] 를 넘으면 아무것도 적재하지 않고 [failOverLimit] 로
     * 작업을 끝낸다 — **조용히 자르지 않는다**(E4 · J6). 잘린 나머지는 옛 상태에 남아 유령이 되는데
     * 화면은 「완료」로 보이기 때문이다.
     *
     * 경계는 **초과일 때만** 실패다. 대상이 정확히 [BULK_OPERATION_MAX_SIZE] 면 그대로 처리한다 —
     * `>` 를 `>=` 로 바꾸면 정확히 상한인 이관이 전부 죽는다.
     *
     * @return 항목 처리를 계속해도 되면 true. 작업이 FAILED 로 끝났으면 false.
     */
    private fun materializeItemsIfNeeded(operation: BulkOperation): Boolean {
        val payload = operation.payload
        if (payload !is BulkOperationPayload.StatusMigration) return true

        val targetCount = bulkRepo.materializeStatusMigrationItems(operation.id, payload)
        if (targetCount <= BULK_OPERATION_MAX_SIZE) return true

        failOverLimit(operation, targetCount)
        return false
    }

    /**
     * 상한 초과로 작업을 FAILED 로 끝낸다. **장부를 먼저 정산하고 사유를 그 정산에서 유도한다.**
     *
     * ## 왜 재집계가 markFailed 앞인가 (게이트 2 리뷰 ⑤)
     * [BulkOperationRepository.recomputeAndPersistCounts] 는 원래 [process] 맨 끝에만 있었다. 그래서
     * 「1차 실행이 항목을 적용하다 크래시 → 재전달 사이에 유입이 늘어 재스캔이 상한 초과」라는 흐름에서
     * 이 분기가 재집계를 건너뛰고 return 해 `succeeded_count` 가 **0인 채** 굳었다. 실제로는 옮겨진
     * 이슈가 있는데 장부는 0을 말하는 상태다 — 이 기능이 없애려는 「DB 와 장부의 불일치」 그 자체다.
     *
     * ## 사유를 카운트에서 유도하는 이유
     * 고정 문구는 「아무것도 안 옮겼다」를 무조건 주장한다. 부분 이관이 일어난 뒤라면 그것은 거짓이고,
     * 운영자는 그 문구를 믿고 **DB 를 뒤지지 않는다**. 부분 이관과 0건 이관은 다음 행동이 다르므로
     * ([overLimitFailureReason] 의 두 갈래) 사유가 그 둘을 구분해야 한다.
     *
     * ## 남은 PENDING 항목 — **남긴다. 그리고 그 수를 로그로 드러낸다** (명시된 선택)
     * 1차 실행이 적재만 하고 처리하지 못한 항목은 PENDING 으로 **남긴다**.
     * - **지우지 않는다** — 무엇이 대상이었는지의 기록이 사라진다. `total_count` 도 그 항목들을 세어
     *   확정된 값이라 지우는 순간 장부가 다시 어긋난다.
     * - **FAILED 로 꾸미지 않는다** — 시도한 적이 없다. 시도하지 않은 것을 실패로 적으면
     *   [com.bts.issue.bulk.domain.FailureReasonCode] 중 무엇을 붙여도 거짓이 된다.
     * - **대신 드러낸다** — `leftPending` 을 로그에 싣는다. 조용히 두지 않는 것이 요구사항이고,
     *   `total_count - processed_count` 가 곧 그 수라 장부만으로도 되짚을 수 있다. 종단 작업이므로
     *   [com.bts.issue.bulk.worker.BulkOperationCleanupWorker] 의 TTL 이 항목과 함께 정리한다.
     *
     * @param operation 상한을 넘긴 작업.
     * @param targetCount 실행 시점 실제 대상 수. 운영자가 분할 폭을 정하는 근거라 근사치를 쓰지 않는다.
     */
    private fun failOverLimit(
        operation: BulkOperation,
        targetCount: Int,
    ) {
        bulkRepo.recomputeAndPersistCounts(operation.id)
        val settled = bulkRepo.findById(operation.id)
        val migrated = settled?.succeededCount ?: 0
        val leftPending = ((settled?.totalCount ?: 0) - (settled?.processedCount ?: 0)).coerceAtLeast(0)

        bulkRepo.markFailed(operation.id)
        log.error(
            "status_migration_over_limit id={} targetCount={} maxSize={} migrated={} leftPending={} reason={}",
            operation.id.value,
            targetCount,
            BULK_OPERATION_MAX_SIZE,
            migrated,
            leftPending,
            overLimitFailureReason(migrated),
        )
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
        val settled = bulkRepo.findById(operation.id)
        if (settled == null || settled.failedCount == 0) return

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
         * 실행 시점 상한 초과로 작업을 FAILED 로 둘 때 남기는 사유 문구를 **정산된 카운트에서** 만든다.
         *
         * **분할 재시도 안내가 이 문구의 본체다**(C-2 · F19). 상한을 넘은 이관은 그대로 재시도해도
         * 같은 결과라, 안내가 없으면 운영자는 그 상태를 영영 못 뺀다.
         *
         * 앞머리는 두 갈래다. 다음 행동이 다르기 때문이다.
         * - `migratedCount == 0` — 아무것도 안 옮겼다. 범위를 나눠 다시 걸면 그만이다.
         * - `migratedCount > 0` — 앞선 시도가 일부를 이미 옮겼다. 분할 재시도 전에 **무엇이 남았는지**
         *   부터 확인해야 한다. 여기서 0건을 주장하면 운영자는 그 확인을 건너뛴다.
         *
         * @param migratedCount 이 작업이 지금까지 실제로 옮긴 이슈 수(정산된 `succeeded_count`).
         * @return 로그에 실을 사유 문구.
         */
        fun overLimitFailureReason(migratedCount: Int): String {
            val head =
                if (migratedCount == 0) {
                    "nothing was migrated"
                } else {
                    "$migratedCount issue(s) were already migrated by an earlier attempt " +
                        "and the remaining targets stay in their old status"
                }
            return "status migration targets exceed the bulk operation limit — $head; " +
                "split the scope into smaller projectKeys or fewer status mappings and retry"
        }
    }
}
