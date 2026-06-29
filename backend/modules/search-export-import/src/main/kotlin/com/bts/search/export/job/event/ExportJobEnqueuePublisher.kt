// Export 작업 enqueue — q_export_jobs 큐에 exportJobId를 pgmq.send로 발행하는 아웃바운드 어댑터

package com.bts.search.export.job.event

import com.bts.search.export.job.domain.ExportJobId
import org.jooq.DSLContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/**
 * Export 작업 ID 를 pgmq [QUEUE_NAME] 큐에 enqueue 하는 아웃바운드 어댑터.
 *
 * [Propagation.MANDATORY] — 반드시 호출자의 트랜잭션 안에서 실행되어야 한다.
 * ExportJob 영속과 enqueue 가 같은 트랜잭션에 묶여야 outbox 패턴이 보장된다 (DATA.md §7.2).
 * 트랜잭션 없이 호출하면 [org.springframework.transaction.IllegalTransactionStateException] 이 발생한다.
 *
 * 워커(Task 5)는 이 큐를 소비하여 실제 CSV/XLSX 직렬화 + MinIO 업로드를 수행한다.
 *
 * @param dsl jOOQ [DSLContext] — pgmq.send raw SQL 실행에 사용.
 *   SQL 문자열 결합 금지, ? 바인딩 사용 (NEVER-3).
 *   페이로드는 UUID 고정 형식이라 문자열 보간이 안전하다 (BulkOperationEnqueuePublisher 동일 패턴).
 */
@Component
class ExportJobEnqueuePublisher(
    private val dsl: DSLContext,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * [exportJobId] 를 JSON 페이로드로 직렬화해 [QUEUE_NAME] 큐에 enqueue 한다.
     *
     * 페이로드 형식: `{"exportJobId":"<UUID>"}`.
     * 호출 시 활성 트랜잭션이 없으면 [org.springframework.transaction.IllegalTransactionStateException] 이 발생한다.
     *
     * @param exportJobId enqueue 할 Export 작업 식별자.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    fun enqueue(exportJobId: ExportJobId) {
        val payload = """{"exportJobId":"${exportJobId.value}"}"""
        dsl.execute("SELECT pgmq.send(?, ?::jsonb)", QUEUE_NAME, payload)
        log.info("export_job_enqueued queue={} exportJobId={}", QUEUE_NAME, exportJobId.value)
    }

    companion object {
        /** pgmq 큐 이름 — V602 마이그레이션에서 생성된 큐와 일치해야 한다. */
        const val QUEUE_NAME = "q_export_jobs"
    }
}
