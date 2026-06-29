// Export 작업 pgmq consumer — q_export_jobs 큐 폴링 + CAS 클레임 + 처리 위임 (FR-EX-02 Task 9)

package com.bts.search.export.job.worker

import com.bts.search.export.job.application.ExportJobProcessor
import com.bts.search.export.job.domain.ExportJobId
import com.bts.search.export.job.domain.ExportJobStatus
import com.bts.search.export.job.event.ExportJobEnqueuePublisher
import com.bts.search.export.job.repository.ExportJobRepository
import org.jooq.DSLContext
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * pgmq `q_export_jobs` 큐를 폴링하여 비동기 Export 작업을 처리하는 스케줄 워커.
 *
 * ## 처리 흐름
 * 1. [Scheduled] 폴링 → `pgmq.read(queue, vt, qty)` 로 메시지 읽기 (read_ct 포함)
 * 2. 메시지의 exportJobId 에 대해 작업레벨 CAS [ExportJobRepository.claimForRun]
 *    - 1 row(true) → [ExportJobRepository.findById] 로 전체 작업 로드 → [ExportJobProcessor.process]
 *    - 0 row(false) → [ExportJobRepository.findStatus] 로 현재 상태 조회
 *      - COMPLETED/FAILED → `pgmq.delete` (소임 완료, 무한 재전달 차단)
 *      - RUNNING(stale 아님) → delete 안 함 (다른 워커 처리 중, 정상 재전달)
 *      - null → poison 처리 ([MAX_RECEIVE_COUNT] 기준)
 * 3. 성공 후 `pgmq.delete` — 예외 발생 시 delete 안 함 (at-least-once)
 *
 * ## dead-letter (poison 처리 — F3)
 * parseJobId null 또는 findStatus null 인 메시지 = poison.
 * read_ct > [MAX_RECEIVE_COUNT] 이면 `pgmq.archive` 로 dead-letter.
 *
 * ## @Transactional 없음 — 의도적 설계
 * [ExportJobProcessor.process] 는 분 단위 I/O 집약 작업이므로 @Transactional 없이 설계됐다.
 * 상태 변경은 각 repository 메서드의 단독 @Transactional 이 처리한다.
 * 외부 @Transactional 을 걸면 커넥션 풀 고갈 + REQUIRES_NEW 전파 문제가 발생한다.
 *
 * ## VT ↔ stale 정합 근거 (BLOCKER — stale > VT 필수)
 * - 처리 예산: 10만행 × ~50ms + XLSX 직렬화 + MinIO 업로드 ≈ 최대 2~3분.
 * - [VISIBILITY_TIMEOUT_SECONDS] = **300초** (처리 예산 초과, BulkOperation 60초 미러 금지).
 * - [ExportJobRepository.STALE_RUNNING_THRESHOLD_SECONDS] = **600초** (VT 2배 + 안전마진).
 * - stale > VT 필수 — VT 만료 재전달 시 정상 처리 중 worker 가 stale 로 오판해 중복 처리 차단.
 *
 * ## 고착 PENDING/RUNNING (MVP 허용)
 * stale RUNNING 은 claimForRun 재청으로 처리. expires_at=NULL PENDING 은 cleanup 미대상 — MVP 허용.
 *
 * @param dsl jOOQ [DSLContext]. pgmq.read/delete/archive raw SQL 실행. 문자열 결합 금지(NEVER-3).
 * @param exportRepo [ExportJobRepository]. CAS claim + 상태 조회 + 전체 작업 로드 담당.
 * @param processor [ExportJobProcessor]. Export 작업 실제 처리 담당.
 */
@Component
class ExportJobWorker(
    private val dsl: DSLContext,
    private val exportRepo: ExportJobRepository,
    private val processor: ExportJobProcessor,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * pgmq 큐를 폴링하여 대기 중인 Export 작업을 처리한다.
     *
     * 메시지가 없으면 즉시 반환한다.
     * **@Transactional 없음 — 의도적 설계** (클래스 KDoc 참조).
     */
    @Scheduled(fixedDelayString = "\${bts.export.worker.poll-interval-ms:1000}")
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
     * 단일 메시지를 처리한다. 예외 발생 시 delete 를 건너뛴다 (at-least-once).
     * 빈 catch 금지 — 모든 예외는 로깅 후 재전달 의도 명시 (DEVELOPMENT.md §절대규칙).
     *
     * @param msgId pgmq 메시지 ID.
     * @param messageJson pgmq 메시지 JSON. `{"exportJobId":"<UUID>"}` 형식.
     * @param readCt 수신 횟수. [MAX_RECEIVE_COUNT] 초과 시 archive.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun processMessage(
        msgId: Long,
        messageJson: String,
        readCt: Int,
    ) {
        val jobId = parseJobId(messageJson)

        if (jobId == null) {
            log.error("export_worker_invalid_message msgId={} readCt={} message={}", msgId, readCt, messageJson)
            handlePoison(msgId, readCt, jobId = null)
            return
        }

        log.info("export_worker_received msgId={} exportJobId={} readCt={}", msgId, jobId.value, readCt)

        try {
            val claimed = exportRepo.claimForRun(jobId)
            if (!claimed) {
                handleClaimFalse(msgId, jobId, readCt)
                return
            }

            val job = exportRepo.findById(jobId)
            if (job == null) {
                log.error("export_worker_job_not_found_after_claim msgId={} exportJobId={}", msgId, jobId.value)
                handlePoison(msgId, readCt, jobId)
                return
            }

            processor.process(job)
            deleteMessage(msgId)
        } catch (e: Exception) {
            log.error(
                "export_worker_processing_failed msgId={} exportJobId={} message={}",
                msgId,
                jobId.value,
                e.message,
                e,
            )
        }
    }

    /**
     * claimForRun false 시 메시지 처리를 결정한다.
     *
     * - COMPLETED/FAILED → delete (소임 완료)
     * - RUNNING(not stale) → skip (다른 워커 처리 중)
     * - null/else → poison 처리
     *
     * TOCTOU 주의 — 여기서의 findStatus 는 delete/archive 여부 결정에만 사용하며,
     * 처리 진입은 claimForRun CAS 가 이미 보장했으므로 TOCTOU 위험 없음.
     */
    private fun handleClaimFalse(
        msgId: Long,
        jobId: ExportJobId,
        readCt: Int,
    ) {
        val currentStatus = exportRepo.findStatus(jobId)
        when {
            currentStatus == ExportJobStatus.COMPLETED || currentStatus == ExportJobStatus.FAILED -> {
                log.info(
                    "export_worker_claim_skipped_terminal msgId={} exportJobId={} status={}",
                    msgId,
                    jobId.value,
                    currentStatus,
                )
                deleteMessage(msgId)
            }
            currentStatus == ExportJobStatus.RUNNING -> {
                log.info(
                    "export_worker_claim_skipped_active msgId={} exportJobId={} reason=other_worker_running",
                    msgId,
                    jobId.value,
                )
            }
            else -> {
                log.warn(
                    "export_worker_claim_skipped_unknown msgId={} exportJobId={} status={} readCt={}",
                    msgId,
                    jobId.value,
                    currentStatus,
                    readCt,
                )
                handlePoison(msgId, readCt, jobId)
            }
        }
    }

    /**
     * poison 메시지를 처리한다.
     * read_ct > [MAX_RECEIVE_COUNT] 이면 `pgmq.archive` 로 dead-letter, 미만이면 재전달 대기.
     * SQL 문자열 결합 금지 — ? 바인딩 사용 (DEVELOPMENT.md §NEVER-3).
     */
    private fun handlePoison(
        msgId: Long,
        readCt: Int,
        jobId: ExportJobId?,
    ) {
        if (readCt > MAX_RECEIVE_COUNT) {
            log.error(
                "export_worker_dead_letter msgId={} exportJobId={} readCt={} action=archive",
                msgId,
                jobId?.value,
                readCt,
            )
            dsl.execute("SELECT pgmq.archive(?, ?)", QUEUE_NAME, msgId)
        } else {
            log.warn(
                "export_worker_poison_retry msgId={} exportJobId={} readCt={} maxReceiveCount={}",
                msgId,
                jobId?.value,
                readCt,
                MAX_RECEIVE_COUNT,
            )
        }
    }

    /**
     * pgmq 에서 메시지를 삭제한다. SQL 문자열 결합 금지 — ? 바인딩 사용 (NEVER-3).
     */
    private fun deleteMessage(msgId: Long) {
        dsl.execute("SELECT pgmq.delete(?, ?)", QUEUE_NAME, msgId)
        log.debug("export_worker_deleted msgId={}", msgId)
    }

    /**
     * 메시지 JSON 에서 [ExportJobId] 를 파싱한다. 파싱 실패 시 null 반환 (`!!` 금지).
     */
    private fun parseJobId(messageJson: String): ExportJobId? =
        try {
            val uuidStr = UUID_PATTERN.find(messageJson)?.groupValues?.get(1) ?: return null
            ExportJobId(UUID.fromString(uuidStr))
        } catch (e: IllegalArgumentException) {
            log.warn("export_worker_parse_failed message={} error={}", messageJson, e.message)
            null
        }

    companion object {
        /**
         * pgmq 작업 큐 이름 — [ExportJobEnqueuePublisher.QUEUE_NAME] 과 일치해야 한다.
         * V602 마이그레이션에서 생성된 큐.
         */
        const val QUEUE_NAME = "q_export_jobs"

        /**
         * pgmq visibility timeout (초).
         *
         * **VT ↔ stale 정합 근거** (BLOCKER — stale > VT 필수).
         * - 처리 예산: 10만행 × ~50ms + XLSX + MinIO ≈ 최대 2~3분.
         * - VT = **300초** (처리 예산 초과, BulkOperation 60초 미러 금지).
         * - stale([ExportJobRepository.STALE_RUNNING_THRESHOLD_SECONDS]) = **600초** (VT 2배 + 안전마진).
         * - stale > VT 필수 — VT 만료 재전달 시 정상 처리 중 worker 가 stale 로 오판해 중복 처리.
         */
        const val VISIBILITY_TIMEOUT_SECONDS = 300

        /**
         * pgmq.read 1회 폴링에서 읽어올 최대 메시지 수.
         * 단일 폴링 트랜잭션이 과도하게 길어지지 않도록 소규모로 제한한다.
         */
        const val POLL_BATCH_SIZE = 5

        /**
         * poison 메시지 최대 수신 허용 횟수.
         * 초과 시 `pgmq.archive` 로 dead-letter.
         * at-least-once 특성상 일시 오류 시 재전달이 정상이므로 즉시 archive 하지 않는다.
         */
        const val MAX_RECEIVE_COUNT = 5

        /** exportJobId UUID 추출을 위한 JSON 패턴. */
        private val UUID_PATTERN = Regex(""""exportJobId"\s*:\s*"([^"]+)"""")
    }
}
