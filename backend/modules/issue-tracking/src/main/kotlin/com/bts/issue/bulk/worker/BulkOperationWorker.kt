// BulkOperationWorker — pgmq q_bulk_operations 큐를 @Scheduled 폴링해 일괄 작업을 처리하는 BTS 최초 consumer

package com.bts.issue.bulk.worker

import com.bts.issue.bulk.application.BulkOperationProcessor
import com.bts.issue.bulk.domain.BulkOperationId
import com.bts.issue.bulk.event.BulkOperationEventPublisher
import com.bts.issue.bulk.event.BulkOperationEnqueuePublisher
import com.bts.issue.bulk.repository.BulkOperationRepository
import org.jooq.DSLContext
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * pgmq `q_bulk_operations` 큐를 폴링하여 일괄 작업을 처리하는 스케줄 워커.
 *
 * ## 처리 흐름
 * 1. [Scheduled] 폴링 → `pgmq.read(queue, vt, qty)` 로 메시지 읽기
 * 2. 메시지의 bulkOperationId 에 대해 작업레벨 CAS [BulkOperationRepository.claimForRun]
 *    - 0 row(false) → skip (동시 2워커가 같은 작업을 잡아도 단일 진입 보장)
 * 3. claim 성공 시 [BulkOperationProcessor.process] 호출
 * 4. [BulkOperationRepository.markCompleted] CAS (RUNNING→COMPLETED 1회)
 *    - 성공(true) → [BulkOperationEventPublisher.publishCompleted] 1회 발행 (C4 중복 금지)
 *    - 실패(false) → 이벤트 발행 안 함 (이미 다른 경로로 완료)
 * 5. 성공 처리 후 `pgmq.delete` 로 메시지 제거
 *    - 예외 발생 시 delete 안 함 → vt 만료 후 재전달 (at-least-once)
 *    - 멱등은 claimForRun + 항목 종료 스킵으로 보장
 *
 * ## CAS 단일성 보장 (learnings: advisory-lock-bigint-TOCTOU)
 * [BulkOperationRepository.claimForRun] 은 `WHERE status='PENDING'` 을 포함한 UPDATE 의
 * affected rows 로 단일 진입을 원자적으로 보장한다.
 * "상태 조회 후 별도 UPDATE" 방식은 TOCTOU 경쟁조건을 유발하므로 금지한다.
 * CAS affected rows 판정이 유일한 단일성 근거다.
 *
 * ## vt(visibility timeout) 산정 근거
 * - 최대 처리 항목: [com.bts.issue.bulk.domain.BULK_OPERATION_MAX_SIZE] = 1000개
 * - 청크: [com.bts.issue.bulk.domain.BULK_OPERATION_CHUNK_SIZE] = 50개
 * - 청크당 p95 처리 시간(DB + 도메인 검증): ~200ms
 * - 총 예산: (1000/50) × 200ms = **4초**
 * - vt = 처리 예산(4초) × 10배 = 40초 → 재전달 윈도우를 넉넉히 확보하되 60초 상한
 * - 채택: [VISIBILITY_TIMEOUT_SECONDS] = **60초** (처리 중 크래시 시 최대 60초 후 재전달)
 *
 * @param dsl jOOQ [DSLContext]. pgmq.read / pgmq.delete raw SQL 실행. 문자열 결합 금지.
 * @param bulkRepo [BulkOperationRepository]. CAS claim/complete 담당.
 * @param processor [BulkOperationProcessor]. 이슈 항목 실제 처리 담당.
 * @param eventPublisher [BulkOperationEventPublisher]. 완료 이벤트 발행 담당.
 */
@Component
class BulkOperationWorker(
    private val dsl: DSLContext,
    private val bulkRepo: BulkOperationRepository,
    private val processor: BulkOperationProcessor,
    private val eventPublisher: BulkOperationEventPublisher,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * pgmq 큐를 폴링하여 대기 중인 일괄 작업을 처리한다.
     *
     * 메시지가 없으면 즉시 반환한다.
     * 예외가 발생한 메시지는 delete 하지 않아 vt 만료 후 재전달된다 (at-least-once).
     */
    @Scheduled(fixedDelayString = "\${bts.bulk.worker.poll-interval-ms:1000}")
    @Transactional
    fun pollAndProcess() {
        val messages = dsl.fetch(
            "SELECT * FROM pgmq.read(?, ?, ?)",
            QUEUE_NAME,
            VISIBILITY_TIMEOUT_SECONDS,
            POLL_BATCH_SIZE,
        )

        if (messages.isEmpty()) return

        for (record in messages) {
            val msgId = record.get("msg_id", Long::class.java)
            val messageJson = record.get("message", String::class.java)
            processMessage(msgId, messageJson)
        }
    }

    // ── private helpers ────────────────────────────────────────────────────────

    /**
     * 단일 메시지를 처리한다.
     *
     * 예외 발생 시 로그를 남기고 delete 를 건너뛴다 (재전달 허용).
     * 빈 catch 금지(DEVELOPMENT.md §절대규칙) — 모든 예외는 로깅 후 재전달 의도 명시.
     *
     * @param msgId pgmq 메시지 ID. delete 호출 시 사용.
     * @param messageJson pgmq 메시지 JSON. `{"bulkOperationId":"<UUID>"}` 형식.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun processMessage(msgId: Long, messageJson: String) {
        val operationId = parseOperationId(messageJson) ?: run {
            log.error("bulk_worker_invalid_message msgId={} message={}", msgId, messageJson)
            return
        }

        log.info("bulk_worker_received msgId={} bulkOperationId={}", msgId, operationId.value)

        try {
            val claimed = bulkRepo.claimForRun(operationId)
            if (!claimed) {
                // 동시 2워커 중 타 워커가 이미 선점 — TOCTOU 없이 CAS로 단일 진입 보장
                log.info(
                    "bulk_worker_claim_skipped msgId={} bulkOperationId={} reason=already_claimed",
                    msgId,
                    operationId.value,
                )
                return
            }

            processor.process(operationId)

            val completed = bulkRepo.markCompleted(operationId)
            if (completed) {
                // markCompleted CAS 성공(1회) → 완료 이벤트 단 1회 발행 (C4 중복 발행 금지)
                eventPublisher.publishCompleted(operationId)
                log.info("bulk_worker_completed msgId={} bulkOperationId={}", msgId, operationId.value)
            } else {
                // 이미 다른 경로로 완료됨 → 이벤트 발행 없이 메시지만 제거
                log.warn(
                    "bulk_worker_already_completed msgId={} bulkOperationId={}",
                    msgId,
                    operationId.value,
                )
            }

            deleteMessage(msgId)
        } catch (e: Exception) {
            // 예외 발생 시 delete 하지 않음 — vt 만료 후 재전달 (at-least-once)
            // 멱등: claimForRun CAS + 항목 종료 스킵으로 재처리 안전 보장
            log.error(
                "bulk_worker_processing_failed msgId={} bulkOperationId={} message={}",
                msgId,
                operationId.value,
                e.message,
                e,
            )
        }
    }

    /**
     * pgmq 에서 메시지를 삭제한다.
     *
     * 처리 성공 후 호출한다. SQL 문자열 결합 금지 — ? 바인딩 사용 (DEVELOPMENT.md §NEVER-3).
     *
     * @param msgId 삭제할 pgmq 메시지 ID.
     */
    private fun deleteMessage(msgId: Long) {
        dsl.execute("SELECT pgmq.delete(?, ?)", QUEUE_NAME, msgId)
        log.debug("bulk_worker_deleted msgId={}", msgId)
    }

    /**
     * 메시지 JSON 에서 [BulkOperationId] 를 파싱한다.
     *
     * 파싱 실패 시 null 을 반환한다 (`!!` non-null assertion 금지).
     *
     * @param messageJson pgmq 메시지 JSON 문자열.
     * @return 파싱된 [BulkOperationId]. 실패 시 null.
     */
    private fun parseOperationId(messageJson: String): BulkOperationId? {
        return try {
            // JSON 에서 bulkOperationId 필드를 직접 파싱 (Jackson 의존 없이 간단 추출)
            val uuidStr = UUID_PATTERN.find(messageJson)?.groupValues?.get(1) ?: return null
            BulkOperationId(UUID.fromString(uuidStr))
        } catch (e: IllegalArgumentException) {
            log.warn("bulk_worker_parse_failed message={} error={}", messageJson, e.message)
            null
        }
    }

    companion object {
        /**
         * pgmq 작업 큐 이름 — [BulkOperationEnqueuePublisher.QUEUE_NAME] 과 일치해야 한다.
         * V008 마이그레이션에서 생성된 큐.
         */
        const val QUEUE_NAME = "q_bulk_operations"

        /**
         * pgmq visibility timeout (초).
         *
         * **산정 근거** (learnings: advisory-lock-bigint-TOCTOU).
         * - 최대 처리 항목 1000개, 청크 50개 단위.
         * - 청크당 p95 처리 시간 ~200ms → 총 예산 (1000/50) × 200ms = 4초.
         * - vt = 처리 예산(4초) × 10배 = 40초 → 안전 마진 포함 60초 상한 채택.
         * - 이보다 짧으면 처리 중 크래시 시 중복 재전달 빈도 증가.
         * - 이보다 길면 처리 실패 복구 지연 증가.
         */
        const val VISIBILITY_TIMEOUT_SECONDS = 60

        /**
         * pgmq.read 1회 폴링에서 읽어올 최대 메시지 수.
         *
         * 단일 폴링 트랜잭션이 너무 길어지지 않도록 소규모로 제한한다.
         * 처리량이 부족하면 폴링 주기([bts.bulk.worker.poll-interval-ms])를 줄이는 것이 우선.
         */
        const val POLL_BATCH_SIZE = 5

        /** bulkOperationId UUID 추출을 위한 JSON 패턴. */
        private val UUID_PATTERN = Regex(""""bulkOperationId"\s*:\s*"([^"]+)"""")
    }
}
