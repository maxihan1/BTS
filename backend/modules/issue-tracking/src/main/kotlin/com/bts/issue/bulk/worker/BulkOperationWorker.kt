// BulkOperationWorker — pgmq q_bulk_operations 큐를 @Scheduled 폴링해 일괄 작업을 처리하는 BTS 최초 consumer

package com.bts.issue.bulk.worker

import com.bts.issue.bulk.application.BulkOperationProcessor
import com.bts.issue.bulk.domain.BulkOperationId
import com.bts.issue.bulk.domain.BulkOperationStatus
import com.bts.issue.bulk.event.BulkOperationEnqueuePublisher
import com.bts.issue.bulk.repository.BulkOperationRepository
import org.jooq.DSLContext
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * pgmq `q_bulk_operations` 큐를 폴링하여 일괄 작업을 처리하는 스케줄 워커.
 *
 * ## 처리 흐름
 * 1. [Scheduled] 폴링 → `pgmq.read(queue, vt, qty)` 로 메시지 읽기 (read_ct 포함)
 * 2. 메시지의 bulkOperationId 에 대해 작업레벨 CAS [BulkOperationRepository.claimForRun]
 *    - 1 row(true) → claim 성공 → 처리 진행
 *    - 0 row(false) → [BulkOperationRepository.findStatus] 로 현재 상태 조회
 *      - COMPLETED/FAILED(종단) → `pgmq.delete` (소임 완료, 무한 재전달 차단)
 *      - RUNNING(아직 stale 아님) → delete 안 함 (다른 워커가 처리 중, 정상 재전달)
 *      - null(작업 없음) → poison 처리 (read_ct 기준, 아래 F3 참조)
 * 3. claim 성공 시 [BulkOperationProcessor.process] 호출
 * 4. [BulkOperationCompleter.completeAndPublish] — markCompleted CAS + 이벤트 발행 단일 트랜잭션
 *    - 성공(true) → 완료 이벤트 1회 발행 (C4 중복 금지)
 *    - 실패(false) → 이벤트 발행 안 함 (이미 다른 경로로 완료)
 * 5. 성공 처리 후 `pgmq.delete` 로 메시지 제거
 *    - 예외 발생 시 delete 안 함 → vt 만료 후 재전달 (at-least-once)
 *
 * ## dead-letter (poison 메시지 처리 — F3)
 * parseOperationId null 또는 findStatus null 인 메시지 = poison.
 * read_ct 가 [MAX_RECEIVE_COUNT] 를 초과하면 `pgmq.archive` 로 dead-letter 처리.
 * 미만이면 재전달 대기 (vt 만료 후 재시도).
 *
 * ## @Transactional 없음 — 의도적 설계
 * pollAndProcess 자체에는 @Transactional 을 걸지 않는다.
 * 항목별 처리는 [com.bts.issue.bulk.application.BulkItemExecutor.executeItem] 의
 * REQUIRES_NEW 독립 트랜잭션이 담당한다. pollAndProcess 에 외부 트랜잭션이 있으면
 * REQUIRES_NEW executeItem 이 외부 트랜잭션을 rollback-only 로 마킹하는 전파 문제가 발생한다.
 *
 * ## CAS 단일성 보장 (learnings: advisory-lock-bigint-TOCTOU)
 * [BulkOperationRepository.claimForRun] 은 `WHERE status='PENDING' OR (status='RUNNING' AND started_at < stale)`
 * 조건을 포함한 UPDATE affected rows 로 단일 진입을 원자적으로 보장한다.
 * "상태 조회 후 별도 UPDATE" 방식은 TOCTOU 경쟁조건을 유발하므로 금지한다.
 *
 * ## vt(visibility timeout) 산정 근거
 * - 최대 처리 항목: [com.bts.issue.bulk.domain.BULK_OPERATION_MAX_SIZE] = 1000개
 * - 청크: [com.bts.issue.bulk.domain.BULK_OPERATION_CHUNK_SIZE] = 50개
 * - 청크당 p95 처리 시간(DB + 도메인 검증): ~200ms
 * - 총 예산: (1000/50) × 200ms = **4초**
 * - vt = 처리 예산(4초) × 10배 = 40초 → 재전달 윈도우를 넉넉히 확보하되 60초 상한
 * - 채택: [VISIBILITY_TIMEOUT_SECONDS] = **60초** (처리 중 크래시 시 최대 60초 후 재전달)
 *
 * @param dsl jOOQ [DSLContext]. pgmq.read / pgmq.delete / pgmq.archive raw SQL 실행. 문자열 결합 금지.
 * @param bulkRepo [BulkOperationRepository]. CAS claim + 상태 조회 담당.
 * @param processor [BulkOperationProcessor]. 이슈 항목 실제 처리 담당.
 * @param completer [BulkOperationCompleter]. markCompleted CAS + 이벤트 발행 단일 트랜잭션 담당.
 */
@Component
class BulkOperationWorker(
    private val dsl: DSLContext,
    private val bulkRepo: BulkOperationRepository,
    private val processor: BulkOperationProcessor,
    private val completer: BulkOperationCompleter,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * pgmq 큐를 폴링하여 대기 중인 일괄 작업을 처리한다.
     *
     * 메시지가 없으면 즉시 반환한다.
     * 예외가 발생한 메시지는 delete 하지 않아 vt 만료 후 재전달된다 (at-least-once).
     *
     * **@Transactional 없음 — 의도적 설계.**
     * pgmq.read 는 트랜잭션 범위 밖에서 호출해도 pgmq 내부 상태(vt)가 atomic 하게 갱신된다.
     * 항목별 처리([com.bts.issue.bulk.application.BulkItemExecutor.executeItem]) 는
     * REQUIRES_NEW 로 독립 트랜잭션을 열어 처리한다.
     * pollAndProcess 에 @Transactional 을 걸면 REQUIRES_NEW executeItem 이 정지시키는
     * 외부 트랜잭션이 생겨 항목 실패 시 rollback-only 전파 문제가 발생한다.
     */
    @Scheduled(fixedDelayString = "\${bts.bulk.worker.poll-interval-ms:1000}")
    fun pollAndProcess() {
        val messages =
            dsl.fetch(
                "SELECT * FROM pgmq.read(?, ?, ?)",
                QUEUE_NAME,
                VISIBILITY_TIMEOUT_SECONDS,
                POLL_BATCH_SIZE,
            )

        if (messages.isEmpty()) return

        for (record in messages) {
            val msgId = record.get("msg_id", Long::class.java)
            val messageJson = record.get("message", String::class.java)
            val readCt = record.get("read_ct", Int::class.java) ?: 1
            processMessage(msgId, messageJson, readCt)
        }
    }

    // ── private helpers ────────────────────────────────────────────────────────

    /**
     * 단일 메시지를 처리한다.
     *
     * 예외 발생 시 로그를 남기고 delete 를 건너뛴다 (재전달 허용).
     * 빈 catch 금지(DEVELOPMENT.md §절대규칙) — 모든 예외는 로깅 후 재전달 의도 명시.
     *
     * @param msgId pgmq 메시지 ID. delete/archive 호출 시 사용.
     * @param messageJson pgmq 메시지 JSON. `{"bulkOperationId":"<UUID>"}` 형식.
     * @param readCt pgmq 메시지 수신 횟수. [MAX_RECEIVE_COUNT] 초과 시 dead-letter (archive).
     */
    @Suppress("TooGenericExceptionCaught")
    private fun processMessage(
        msgId: Long,
        messageJson: String,
        readCt: Int,
    ) {
        val operationId = parseOperationId(messageJson)

        if (operationId == null) {
            log.error("bulk_worker_invalid_message msgId={} readCt={} message={}", msgId, readCt, messageJson)
            handlePoison(msgId, readCt, operationId = null)
            return
        }

        log.info("bulk_worker_received msgId={} bulkOperationId={} readCt={}", msgId, operationId.value, readCt)

        try {
            val claimed = bulkRepo.claimForRun(operationId)
            if (!claimed) {
                handleClaimFalse(msgId, operationId, readCt)
                return
            }

            processor.process(operationId)

            // markCompleted CAS + publishCompleted 를 단일 트랜잭션으로 처리 (outbox 패턴)
            completer.completeAndPublish(operationId, msgId)

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
     * claimForRun 이 false 를 반환했을 때의 메시지 처리를 결정한다.
     *
     * - 종단 상태(COMPLETED/FAILED) → delete (소임 완료 — 무한 재전달 차단)
     * - RUNNING(not stale) → delete 안 함 (다른 워커가 처리 중, 정상 재전달 대기)
     * - null → poison 처리 ([handlePoison])
     *
     * **TOCTOU 주의** — 여기서의 findStatus 는 delete/archive 여부를 결정할 뿐이며,
     * 처리 진입은 claimForRun CAS 가 이미 보장했으므로 TOCTOU 위험이 없다.
     *
     * @param msgId 처리할 메시지 ID.
     * @param operationId claim 실패한 작업 ID.
     * @param readCt 수신 횟수. poison 판정 시 사용.
     */
    private fun handleClaimFalse(
        msgId: Long,
        operationId: BulkOperationId,
        readCt: Int,
    ) {
        val currentStatus = bulkRepo.findStatus(operationId)
        when {
            currentStatus == BulkOperationStatus.COMPLETED || currentStatus == BulkOperationStatus.FAILED -> {
                // 종단 상태 — 메시지 삭제하여 무한 재전달 차단
                log.info(
                    "bulk_worker_claim_skipped_terminal msgId={} bulkOperationId={} status={}",
                    msgId,
                    operationId.value,
                    currentStatus,
                )
                deleteMessage(msgId)
            }
            currentStatus == BulkOperationStatus.RUNNING -> {
                // 다른 워커가 활성 처리 중 — vt 만료 후 재전달 대기 (정상)
                log.info(
                    "bulk_worker_claim_skipped_active msgId={} bulkOperationId={} reason=other_worker_running",
                    msgId,
                    operationId.value,
                )
            }
            else -> {
                // null(작업 없음) 또는 PENDING(재진입 불가 상태) — poison 처리
                log.warn(
                    "bulk_worker_claim_skipped_unknown msgId={} bulkOperationId={} status={} readCt={}",
                    msgId,
                    operationId.value,
                    currentStatus,
                    readCt,
                )
                handlePoison(msgId, readCt, operationId)
            }
        }
    }

    /**
     * poison 메시지를 처리한다.
     *
     * read_ct 가 [MAX_RECEIVE_COUNT] 를 초과하면 `pgmq.archive` 로 dead-letter 처리한다.
     * 미만이면 재전달 대기 (vt 만료 후 재시도 — 일시적 오류 허용).
     *
     * SQL 문자열 결합 금지 — ? 바인딩 사용 (DEVELOPMENT.md §NEVER-3).
     *
     * @param msgId 처리할 메시지 ID.
     * @param readCt 수신 횟수.
     * @param operationId null 이면 parseOperationId 실패, non-null 이면 작업 없음.
     */
    private fun handlePoison(
        msgId: Long,
        readCt: Int,
        operationId: BulkOperationId?,
    ) {
        if (readCt > MAX_RECEIVE_COUNT) {
            log.error(
                "bulk_worker_dead_letter msgId={} bulkOperationId={} readCt={} action=archive",
                msgId,
                operationId?.value,
                readCt,
            )
            dsl.execute("SELECT pgmq.archive(?, ?)", QUEUE_NAME, msgId)
        } else {
            log.warn(
                "bulk_worker_poison_retry msgId={} bulkOperationId={} readCt={} maxReceiveCount={}",
                msgId,
                operationId?.value,
                readCt,
                MAX_RECEIVE_COUNT,
            )
            // delete 하지 않음 — vt 만료 후 재전달
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

        /**
         * poison 메시지 최대 수신 허용 횟수.
         *
         * pgmq.read 의 read_ct 가 이 값을 초과하면 `pgmq.archive` 로 dead-letter 처리한다.
         * at-least-once 특성으로 일시적 오류(DB 재시작 등) 시 재전달이 정상이므로
         * 즉시 archive 하지 않고 일정 횟수 재시도를 허용한다.
         */
        const val MAX_RECEIVE_COUNT = 5

        /** bulkOperationId UUID 추출을 위한 JSON 패턴. */
        private val UUID_PATTERN = Regex(""""bulkOperationId"\s*:\s*"([^"]+)"""")
    }
}
