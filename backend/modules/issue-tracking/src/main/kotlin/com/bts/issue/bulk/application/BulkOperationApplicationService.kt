// 일괄 작업 접수 Application Service — 검증·도메인 생성·영속·enqueue 를 한 트랜잭션으로 처리

package com.bts.issue.bulk.application

import com.bts.issue.bulk.domain.BULK_OPERATION_MAX_SIZE
import com.bts.issue.bulk.domain.BulkOperation
import com.bts.issue.bulk.domain.BulkOperationId
import com.bts.issue.bulk.domain.BulkOperationItem
import com.bts.issue.bulk.domain.BulkOperationPayload
import com.bts.issue.bulk.domain.BulkOperationType
import com.bts.issue.bulk.domain.ItemStatus
import com.bts.issue.bulk.event.BulkOperationEnqueuePublisher
import com.bts.issue.bulk.repository.BulkOperationRepository
import com.bts.issue.domain.ActorId
import com.bts.issue.domain.IssueKey
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * 일괄 작업 접수 유스케이스를 담당하는 Application Service.
 *
 * 이 서비스는 **PR1 범위 — 접수/영속/enqueue** 만 처리한다.
 * 실제 이슈 갱신(updateIssue/transitionIssue 호출) 및 워커 처리는 PR2 에서 구현한다.
 *
 * ### 흐름
 * 1. 입력 검증 ([validateRequest])
 * 2. issueKeys dedup
 * 3. [BulkOperation.create] 로 도메인 Aggregate 생성 (PENDING)
 * 4. [BulkOperationRepository.insert] 로 DB 영속
 * 5. [BulkOperationEnqueuePublisher.enqueue] 로 q_bulk_operations 큐에 enqueue
 * 6. [BulkOperationId] 반환
 *
 * 영속과 enqueue 는 같은 트랜잭션 안에서 수행되어 outbox 패턴을 보장한다 (DATA.md §7.2).
 *
 * ### 접수 권한
 * PR1 범위에서 인증 사용자면 접수를 허용한다.
 * 이슈별 수정 권한은 처리 시점(PR2 워커)에서 검증한다.
 *
 * @param repo 일괄 작업 Repository.
 * @param enqueuePublisher pgmq q_bulk_operations enqueue 어댑터.
 */
@Service
class BulkOperationApplicationService(
    private val repo: BulkOperationRepository,
    private val enqueuePublisher: BulkOperationEnqueuePublisher,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        /** BULK_EDIT priority 허용 범위. */
        val VALID_PRIORITY_RANGE = 1..5

        /** BULK_EDIT impact 허용 범위. */
        val VALID_IMPACT_RANGE = 1..3
    }

    /**
     * 일괄 작업을 접수한다.
     *
     * @param actor 작업을 요청한 행위자.
     * @param request 접수 요청 커맨드.
     * @return 생성된 [BulkOperationId].
     * @throws IllegalArgumentException issueKeys 가 비어있거나 [BULK_OPERATION_MAX_SIZE] 초과,
     *   operationType ↔ payload 불일치, payload 필드 범위 위반 시.
     */
    @Transactional
    fun submit(
        actor: ActorId,
        request: BulkUpdateRequest,
    ): BulkOperationId {
        val payload = validateRequest(request)

        val uniqueKeys = request.issueKeys.distinct()
        val items =
            uniqueKeys.map { key ->
                BulkOperationItem(
                    issueKey = IssueKey(key),
                    status = ItemStatus.PENDING,
                )
            }

        val operationId = BulkOperationId(UUID.randomUUID())
        val operation =
            BulkOperation.create(
                id = operationId,
                actorId = actor.value,
                type = request.operationType,
                items = items,
                payload = payload,
            )

        repo.insert(operation)
        enqueuePublisher.enqueue(operationId)

        log.info(
            "bulk_op_submitted id={} type={} itemCount={} actor={}",
            operationId.value,
            request.operationType,
            items.size,
            actor.value,
        )
        return operationId
    }

    // ── private helpers ────────────────────────────────────────────────────────

    /**
     * 접수 요청의 유효성을 검증하고 검증된 [BulkOperationPayload] 를 반환한다.
     *
     * 검증 항목.
     * - issueKeys 비어있지 않음
     * - issueKeys [BULK_OPERATION_MAX_SIZE] 이하
     * - operationType ↔ payload 정합성 (반대편 payload 는 null 이어야 한다)
     * - BULK_EDIT: editPayload non-null, transitionPayload null, 변경 필드 1개 이상, priority/impact 범위
     * - BULK_TRANSITION: transitionPayload non-null, editPayload null, toStateKey 비어있지 않음
     *
     * @throws IllegalArgumentException 검증 위반 시.
     * @return 검증된 도메인 [BulkOperationPayload].
     */
    private fun validateRequest(request: BulkUpdateRequest): BulkOperationPayload {
        require(request.issueKeys.isNotEmpty()) {
            "issueKeys must not be empty"
        }
        require(request.issueKeys.size <= BULK_OPERATION_MAX_SIZE) {
            "issueKeys must not exceed $BULK_OPERATION_MAX_SIZE, but was ${request.issueKeys.size}"
        }

        return when (request.operationType) {
            BulkOperationType.BULK_EDIT -> {
                require(request.transitionPayload == null) {
                    "transitionPayload must be null for BULK_EDIT operation"
                }
                validateEditPayload(request.editPayload)
            }
            BulkOperationType.BULK_TRANSITION -> {
                require(request.editPayload == null) {
                    "editPayload must be null for BULK_TRANSITION operation"
                }
                validateTransitionPayload(request.transitionPayload)
            }
        }
    }

    /**
     * BULK_EDIT 페이로드를 검증하고 [BulkOperationPayload.Edit] 를 반환한다.
     *
     * @throws IllegalArgumentException editPayload null, 변경 필드 없음, 범위 위반 시.
     */
    private fun validateEditPayload(payload: BulkEditPayload?): BulkOperationPayload.Edit {
        require(payload != null) {
            "editPayload must not be null for BULK_EDIT operation"
        }
        require(payload.priority != null || payload.impact != null) {
            "editPayload must have at least one non-null field (priority or impact)"
        }
        if (payload.priority != null) {
            require(payload.priority in VALID_PRIORITY_RANGE) {
                "priority must be in $VALID_PRIORITY_RANGE, but was ${payload.priority}"
            }
        }
        if (payload.impact != null) {
            require(payload.impact in VALID_IMPACT_RANGE) {
                "impact must be in $VALID_IMPACT_RANGE, but was ${payload.impact}"
            }
        }
        return BulkOperationPayload.Edit(priority = payload.priority, impact = payload.impact)
    }

    /**
     * BULK_TRANSITION 페이로드를 검증하고 [BulkOperationPayload.Transition] 를 반환한다.
     *
     * @throws IllegalArgumentException transitionPayload null, toStateKey 공백/빈 문자열 시.
     */
    private fun validateTransitionPayload(payload: BulkTransitionPayload?): BulkOperationPayload.Transition {
        require(payload != null) {
            "transitionPayload must not be null for BULK_TRANSITION operation"
        }
        require(payload.toStateKey.isNotBlank()) {
            "transitionPayload.toStateKey must not be blank"
        }
        return BulkOperationPayload.Transition(toStateKey = payload.toStateKey, resolutionId = payload.resolutionId)
    }
}
