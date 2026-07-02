// Import 작업 enqueue — q_import_jobs 큐에 importJobId를 pgmq.send로 발행하는 아웃바운드 어댑터

package com.bts.search.imports.job.event

import com.bts.search.imports.job.domain.ImportJobId
import org.jooq.DSLContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/**
 * Import 작업 ID 를 pgmq [QUEUE_NAME] 큐에 enqueue 하는 아웃바운드 어댑터.
 *
 * `export` BC [com.bts.search.export.job.event.ExportJobEnqueuePublisher] 를 1:1 미러하되
 * importJobId 필드명만 조정했다.
 *
 * [Propagation.MANDATORY] — 반드시 호출자의 트랜잭션 안에서 실행되어야 한다.
 * ImportJob 영속과 enqueue 가 같은 트랜잭션에 묶여야 outbox 패턴이 보장된다 (DATA.md §7.2).
 * 트랜잭션 없이 호출하면 [org.springframework.transaction.IllegalTransactionStateException] 이 발생한다.
 *
 * 워커([com.bts.search.imports.job.worker.ImportJobWorker])는 이 큐를 소비하여 실제 CSV/JSON
 * 파싱 + 행별 이슈 생성을 수행한다.
 *
 * @param dsl jOOQ [DSLContext] — pgmq.send raw SQL 실행에 사용.
 *   SQL 문자열 결합 금지, ? 바인딩 사용 (NEVER-3).
 *   페이로드는 UUID 고정 형식이라 문자열 보간이 안전하다 (ExportJobEnqueuePublisher 동일 패턴).
 */
@Component
class ImportJobEnqueuePublisher(
    private val dsl: DSLContext,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * [importJobId] 를 JSON 페이로드로 직렬화해 [QUEUE_NAME] 큐에 enqueue 한다.
     *
     * 페이로드 형식: `{"importJobId":"<UUID>"}`.
     * 호출 시 활성 트랜잭션이 없으면 [org.springframework.transaction.IllegalTransactionStateException] 이 발생한다.
     *
     * @param importJobId enqueue 할 Import 작업 식별자.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    fun enqueue(importJobId: ImportJobId) {
        val payload = """{"importJobId":"${importJobId.value}"}"""
        dsl.execute("SELECT pgmq.send(?, ?::jsonb)", QUEUE_NAME, payload)
        log.info("import_job_enqueued queue={} importJobId={}", QUEUE_NAME, importJobId.value)
    }

    companion object {
        /** pgmq 큐 이름 — V604 마이그레이션에서 생성된 큐와 일치해야 한다. */
        const val QUEUE_NAME = "q_import_jobs"
    }
}
