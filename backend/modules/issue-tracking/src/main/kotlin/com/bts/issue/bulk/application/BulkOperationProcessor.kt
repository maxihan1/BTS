// BulkOperationProcessor — pgmq 워커가 호출하는 일괄 작업 처리 로직 (actor 복원, 청크, best-effort, 멱등)

package com.bts.issue.bulk.application

import com.bts.issue.application.IssueApplicationService
import com.bts.issue.application.TransitionIssueRequest
import com.bts.issue.application.UpdateIssueRequest
import com.bts.issue.bulk.domain.BULK_OPERATION_CHUNK_SIZE
import com.bts.issue.bulk.domain.BulkOperation
import com.bts.issue.bulk.domain.BulkOperationId
import com.bts.issue.bulk.domain.BulkOperationItem
import com.bts.issue.bulk.domain.BulkOperationPayload
import com.bts.issue.bulk.domain.FailureReasonCode
import com.bts.issue.bulk.domain.ItemStatus
import com.bts.issue.bulk.repository.BulkOperationRepository
import com.bts.issue.domain.ActorId
import com.bts.issue.domain.IssueAccessDeniedException
import com.bts.issue.domain.IssueKey
import com.bts.issue.domain.IssueNotFoundException
import com.bts.issue.domain.IssueTransitionNotAllowedException
import com.bts.issue.domain.IssueVersionConflictException
import com.bts.issue.domain.IssueWorkflowNotConfiguredException
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
 * @param issueService 이슈 변경 유스케이스. 도메인 검증·권한 검증·전이 위임을 포함한다.
 * @param bulkRepo 일괄 작업 Repository.
 */
@Component
class BulkOperationProcessor(
    private val issueService: IssueApplicationService,
    private val bulkRepo: BulkOperationRepository,
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
        val operation = bulkRepo.findById(bulkOperationId)
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

            processItem(actor, operation, item)
        }
    }

    /**
     * 단일 이슈 항목을 처리한다.
     *
     * 이슈 변경과 항목 상태 기록이 같은 트랜잭션에서 수행된다.
     * 예외 발생 시 [FailureReasonCode] 로 매핑하고 FAILED 로 기록한다.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun processItem(
        actor: ActorId,
        operation: BulkOperation,
        item: BulkOperationItem,
    ) {
        try {
            // 이슈 조회 — version 추출 (낙관적 잠금용)
            val existing = issueService.findByKey(actor, item.issueKey)

            // payload 적용 — 이슈 변경과 항목 상태 기록을 같은 트랜잭션에서 수행 (C1)
            applyPayload(actor, item.issueKey, operation.payload, existing.version)

            bulkRepo.updateItemResult(operation.id, item.issueKey, ItemStatus.SUCCEEDED, null)

            log.debug(
                "bulk_op_item_succeeded id={} issueKey={}",
                operation.id.value,
                item.issueKey.value,
            )
        } catch (e: Exception) {
            val reasonCode = mapToReasonCode(e)
            bulkRepo.updateItemResult(operation.id, item.issueKey, ItemStatus.FAILED, reasonCode)

            log.warn(
                "bulk_op_item_failed id={} issueKey={} reason={} message={}",
                operation.id.value,
                item.issueKey.value,
                reasonCode,
                e.message,
            )
        }
    }

    /**
     * payload 타입에 따라 이슈 변경을 위임한다.
     *
     * repository 직행 금지 — 도메인 정규화·검증·권한 검증·전이 위임은
     * [IssueApplicationService] 를 통해 수행한다 (learnings: PATCH-merge-domain-bypass).
     *
     * @param actor 행위자.
     * @param issueKey 처리 대상 이슈 키.
     * @param payload 일괄 작업 payload.
     * @param version 낙관적 잠금 버전 (IssueApplicationService.findByKey 에서 읽어온 현재 버전).
     */
    private fun applyPayload(
        actor: ActorId,
        issueKey: IssueKey,
        payload: BulkOperationPayload,
        version: Long,
    ) {
        when (payload) {
            is BulkOperationPayload.Edit -> {
                issueService.updateIssue(
                    actor,
                    issueKey,
                    UpdateIssueRequest(
                        summary = null,
                        priority = payload.priority,
                        impact = payload.impact,
                        expectedVersion = version,
                    ),
                )
            }
            is BulkOperationPayload.Transition -> {
                issueService.transitionIssue(
                    actor,
                    issueKey,
                    TransitionIssueRequest(
                        toStateKey = payload.toStateKey,
                        expectedVersion = version,
                    ),
                )
            }
        }
    }

    /**
     * 예외를 [FailureReasonCode] 로 매핑한다.
     *
     * 매핑되지 않는 예외는 [FailureReasonCode.NOT_FOUND] 로 안전하게 처리하고
     * 경고 로그를 남긴다. 빈 catch 금지 원칙(DEVELOPMENT.md §절대규칙) — 모든 예외는 로깅+처리.
     *
     * @param e 처리할 예외.
     * @return 매핑된 [FailureReasonCode].
     */
    private fun mapToReasonCode(e: Exception): FailureReasonCode =
        when (e) {
            is IssueNotFoundException -> FailureReasonCode.NOT_FOUND
            is IssueAccessDeniedException -> FailureReasonCode.FORBIDDEN
            is IssueTransitionNotAllowedException -> FailureReasonCode.TRANSITION_NOT_ALLOWED
            is IssueVersionConflictException -> FailureReasonCode.VERSION_CONFLICT
            is IssueWorkflowNotConfiguredException -> FailureReasonCode.WORKFLOW_NOT_CONFIGURED
            else -> {
                log.warn(
                    "bulk_op_item_unexpected_exception type={} message={}",
                    e::class.simpleName,
                    e.message,
                )
                FailureReasonCode.NOT_FOUND
            }
        }
}
