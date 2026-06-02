// 일괄 작업 enqueue — q_bulk_operations 큐에 bulkOperationId를 pgmq.send로 발행하는 아웃바운드 어댑터

package com.bts.issue.bulk.event

import com.bts.issue.bulk.domain.BulkOperationId
import org.jooq.DSLContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/**
 * 일괄 작업 ID 를 pgmq [QUEUE_NAME] 큐에 enqueue 하는 아웃바운드 어댑터.
 *
 * [Propagation.MANDATORY] — 반드시 호출자의 트랜잭션 안에서 실행되어야 한다.
 * BulkOperation 영속과 enqueue 가 같은 트랜잭션에 묶여야 outbox 패턴이 보장된다 (DATA.md §7.2).
 * 트랜잭션 없이 호출하면 [org.springframework.transaction.IllegalTransactionStateException] 이 발생한다.
 *
 * 워커(PR2)는 이 큐를 소비하여 실제 이슈 갱신을 수행한다.
 *
 * @param dsl jOOQ [DSLContext] — pgmq.send raw SQL 실행에 사용. SQL 문자열 결합 금지, ? 바인딩 사용.
 */
@Component
class BulkOperationEnqueuePublisher(
    private val dsl: DSLContext,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * [bulkOperationId] 를 JSON 페이로드로 직렬화해 [QUEUE_NAME] 큐에 enqueue 한다.
     *
     * 페이로드 형식: `{"bulkOperationId":"<UUID>"}`.
     * 호출 시 활성 트랜잭션이 없으면 [org.springframework.transaction.IllegalTransactionStateException] 이 발생한다.
     *
     * @param bulkOperationId enqueue 할 일괄 작업 식별자.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    fun enqueue(bulkOperationId: BulkOperationId) {
        val payload = """{"bulkOperationId":"${bulkOperationId.value}"}"""
        dsl.execute("SELECT pgmq.send(?, ?::jsonb)", QUEUE_NAME, payload)
        log.info("bulk_op_enqueued queue={} bulkOperationId={}", QUEUE_NAME, bulkOperationId.value)
    }

    companion object {
        /** pgmq 큐 이름 — V007 마이그레이션에서 생성된 큐와 일치해야 한다. */
        const val QUEUE_NAME = "q_bulk_operations"
    }
}
