// 일괄 작업 완료 이벤트 발행 — q_bulk_operation_events 큐에 BulkOperationCompleted를 pgmq.send로 발행

package com.bts.issue.bulk.event

import com.bts.issue.bulk.domain.BulkOperationId
import org.jooq.DSLContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/**
 * 일괄 작업 완료 이벤트([BulkOperationCompleted])를 pgmq [QUEUE_NAME] 큐에 발행하는 아웃바운드 어댑터.
 *
 * [Propagation.MANDATORY] — 반드시 호출자의 트랜잭션 안에서 실행되어야 한다.
 * markCompleted CAS 와 이벤트 발행이 같은 트랜잭션에 묶여야 outbox 패턴이 보장된다 (DATA.md §7.2).
 * 트랜잭션 없이 호출하면 [org.springframework.transaction.IllegalTransactionStateException] 이 발생한다.
 *
 * **중복 발행 방지(C4)** — 호출자([BulkOperationWorker])가 markCompleted CAS 결과(true)일 때만
 * 이 메서드를 호출해야 한다. 이 어댑터는 호출되면 무조건 발행한다.
 *
 * @param dsl jOOQ [DSLContext] — pgmq.send raw SQL 실행에 사용. SQL 문자열 결합 금지, ? 바인딩 사용.
 */
@Component
class BulkOperationEventPublisher(
    private val dsl: DSLContext,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * [bulkOperationId] 를 포함한 [BulkOperationCompleted] 이벤트를 [QUEUE_NAME] 큐에 발행한다.
     *
     * 페이로드 형식: `{"bulkOperationId":"<UUID>"}`.
     * 호출 시 활성 트랜잭션이 없으면 [org.springframework.transaction.IllegalTransactionStateException] 이 발생한다.
     *
     * @param bulkOperationId 완료된 일괄 작업 식별자.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    fun publishCompleted(bulkOperationId: BulkOperationId) {
        val payload = """{"bulkOperationId":"${bulkOperationId.value}"}"""
        dsl.execute("SELECT pgmq.send(?, ?::jsonb)", QUEUE_NAME, payload)
        log.info(
            "bulk_op_completed_published queue={} bulkOperationId={}",
            QUEUE_NAME,
            bulkOperationId.value,
        )
    }

    companion object {
        /** pgmq 완료 이벤트 큐 이름 — V008 마이그레이션에서 생성된 큐와 일치해야 한다. */
        const val QUEUE_NAME = "q_bulk_operation_events"
    }
}
