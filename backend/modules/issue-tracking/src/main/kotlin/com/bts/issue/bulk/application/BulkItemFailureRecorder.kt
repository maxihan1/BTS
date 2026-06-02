// BulkItemFailureRecorder — FAILED 상태 기록을 REQUIRES_NEW 독립 트랜잭션으로 수행

package com.bts.issue.bulk.application

import com.bts.issue.bulk.domain.BulkOperationId
import com.bts.issue.bulk.domain.FailureReasonCode
import com.bts.issue.bulk.domain.ItemStatus
import com.bts.issue.bulk.repository.BulkOperationRepository
import com.bts.issue.domain.IssueKey
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/**
 * 항목 실패 결과를 REQUIRES_NEW 독립 트랜잭션으로 기록하는 컴포넌트.
 *
 * ## 왜 별도 Bean 인가
 * Spring AOP @Transactional 은 프록시 경유 호출에만 적용된다.
 * [BulkItemExecutor] 와 별도 Bean 으로 분리해야 REQUIRES_NEW 트랜잭션이 AOP 프록시를 통해 생성된다.
 *
 * ## 트랜잭션 오염 없는 실패 기록
 * [BulkItemApplier.applyAndRecordSuccess] 실패로 성공 트랜잭션이 rollback-only 상태여도
 * 이 메서드는 완전히 새 REQUIRES_NEW 트랜잭션에서 실행되므로 오염 없이 커밋된다.
 *
 * @param bulkRepo 항목 상태 기록 Repository.
 */
@Component
class BulkItemFailureRecorder(
    private val bulkRepo: BulkOperationRepository,
) {
    /**
     * 항목 실패 결과를 새 REQUIRES_NEW 트랜잭션에서 기록한다.
     *
     * @param operationId 부모 작업 식별자.
     * @param issueKey 실패 항목 이슈 키.
     * @param reasonCode 실패 사유.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun recordFailure(
        operationId: BulkOperationId,
        issueKey: IssueKey,
        reasonCode: FailureReasonCode,
    ) {
        bulkRepo.updateItemResult(operationId, issueKey, ItemStatus.FAILED, reasonCode)
    }
}
