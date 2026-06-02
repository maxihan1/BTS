// BulkItemExecutor — 항목 1건 처리 조율 — 성공/실패 트랜잭션 분리 위임

package com.bts.issue.bulk.application

import com.bts.issue.bulk.domain.BulkOperation
import com.bts.issue.bulk.domain.BulkOperationItem
import com.bts.issue.bulk.domain.FailureReasonCode
import com.bts.issue.domain.ActorId
import com.bts.issue.domain.IssueAccessDeniedException
import com.bts.issue.domain.IssueNotFoundException
import com.bts.issue.domain.IssueTransitionNotAllowedException
import com.bts.issue.domain.IssueVersionConflictException
import com.bts.issue.domain.IssueWorkflowNotConfiguredException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * 항목 1건 처리를 조율하는 컴포넌트.
 *
 * ## 설계 원칙 — 트랜잭션 경계 분리
 * 성공 경로([BulkItemApplier.applyAndRecordSuccess])와 실패 경로([BulkItemFailureRecorder.recordFailure])를
 * 각각 별도 REQUIRES_NEW 트랜잭션을 가진 Bean 으로 위임한다.
 *
 * **왜 self-invocation 방식이 아닌 별도 Bean 인가.**
 * Spring AOP @Transactional 은 프록시 경유 호출에만 적용된다.
 * 같은 Bean 내부에서 `this.applyAndRecordSuccess()` 를 호출하면 프록시를 우회해 REQUIRES_NEW 트랜잭션이
 * 생성되지 않는다. 별도 Bean 으로 분리해야 AOP 프록시가 정상 작동한다.
 *
 * @param applier 이슈 변경 + SUCCEEDED 기록을 REQUIRES_NEW 트랜잭션으로 처리.
 * @param failureRecorder FAILED 기록을 REQUIRES_NEW 트랜잭션으로 처리.
 */
@Component
class BulkItemExecutor(
    private val applier: BulkItemApplier,
    private val failureRecorder: BulkItemFailureRecorder,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 항목 1건을 처리한다.
     *
     * [BulkItemApplier.applyAndRecordSuccess] 성공 시 완료.
     * 예외 발생 시 [BulkItemFailureRecorder.recordFailure] 로 실패를 독립 트랜잭션에서 기록한다.
     * 예외가 이 메서드 밖으로 전파되지 않으므로 호출자 트랜잭션은 rollback-only 로 마킹되지 않는다.
     *
     * @param actor 행위자.
     * @param operation 부모 일괄 작업.
     * @param item 처리 대상 항목.
     */
    @Suppress("TooGenericExceptionCaught")
    fun executeItem(
        actor: ActorId,
        operation: BulkOperation,
        item: BulkOperationItem,
    ) {
        try {
            applier.applyAndRecordSuccess(actor, operation.id, item.issueKey, operation.payload)
            log.debug(
                "bulk_op_item_succeeded id={} issueKey={}",
                operation.id.value,
                item.issueKey.value,
            )
        } catch (e: Exception) {
            val reasonCode = mapToReasonCode(e)
            // 별도 REQUIRES_NEW Bean 으로 실패 기록 — 성공 트랜잭션 오염 없음
            failureRecorder.recordFailure(operation.id, item.issueKey, reasonCode)
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
     * 예외를 [FailureReasonCode] 로 매핑한다.
     *
     * 매핑되지 않는 예외는 [FailureReasonCode.NOT_FOUND] 로 안전하게 처리하고
     * 경고 로그를 남긴다. 빈 catch 금지 원칙(DEVELOPMENT.md §절대규칙) — 모든 예외는 로깅+처리.
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
