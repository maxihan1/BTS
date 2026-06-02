// BulkOperationCompleter — markCompleted CAS + publishCompleted 이벤트를 단일 @Transactional로 처리

package com.bts.issue.bulk.worker

import com.bts.issue.bulk.domain.BulkOperationId
import com.bts.issue.bulk.event.BulkOperationEventPublisher
import com.bts.issue.bulk.repository.BulkOperationRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * 일괄 작업 완료 마킹과 이벤트 발행을 단일 트랜잭션으로 처리하는 컴포넌트.
 *
 * ## 왜 별도 빈으로 분리하는가
 * [BulkOperationEventPublisher.publishCompleted] 는 [org.springframework.transaction.annotation.Propagation.MANDATORY]
 * 로 선언되어 있어 반드시 활성 트랜잭션 안에서 호출해야 한다.
 * [BulkOperationRepository.markCompleted] 와 publishCompleted 가 같은 트랜잭션에 묶여야
 * "완료 마킹 성공 → 이벤트 발행" 의 원자성이 보장된다 (outbox 패턴, DATA.md §7.2).
 *
 * [BulkOperationWorker.pollAndProcess] 에 @Transactional 이 없으므로 이 메서드를 별도 Spring Bean 으로
 * 분리하여 프록시를 통해 @Transactional 을 적용한다.
 *
 * @param bulkRepo 완료 CAS 를 수행하는 Repository.
 * @param eventPublisher 완료 이벤트를 발행하는 Publisher.
 */
@Component
class BulkOperationCompleter(
    private val bulkRepo: BulkOperationRepository,
    private val eventPublisher: BulkOperationEventPublisher,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 작업을 COMPLETED 로 전환하고(CAS) 성공 시 완료 이벤트를 단일 트랜잭션으로 발행한다.
     *
     * [BulkOperationRepository.markCompleted] CAS 가 true 이면 [BulkOperationEventPublisher.publishCompleted] 를
     * 같은 트랜잭션 안에서 호출하여 원자성을 보장한다.
     *
     * @param bulkOperationId 완료할 작업 식별자.
     * @param msgId 로그용 pgmq 메시지 ID.
     * @return 완료 전환 성공 여부. false 이면 이미 완료됐거나 다른 경로로 처리됨.
     */
    @Transactional
    fun completeAndPublish(
        bulkOperationId: BulkOperationId,
        msgId: Long,
    ): Boolean {
        val completed = bulkRepo.markCompleted(bulkOperationId)
        if (completed) {
            // markCompleted CAS 성공(1회) → 완료 이벤트 단 1회 발행 (C4 중복 발행 금지)
            eventPublisher.publishCompleted(bulkOperationId)
            log.info("bulk_worker_completed msgId={} bulkOperationId={}", msgId, bulkOperationId.value)
        } else {
            log.warn(
                "bulk_worker_already_completed msgId={} bulkOperationId={}",
                msgId,
                bulkOperationId.value,
            )
        }
        return completed
    }
}
