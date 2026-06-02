// BulkOperationCleanupWorker — 완료 후 30일 경과한 일괄 작업과 항목을 매일 자동 삭제하는 TTL 배치 워커

package com.bts.issue.bulk.worker

import com.bts.issue.bulk.repository.BulkOperationRepository
import org.jooq.DSLContext
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.UUID

/**
 * 완료(COMPLETED/FAILED) 상태 일괄 작업의 TTL(Time-To-Live) 만료분을 매일 삭제하는 배치 워커.
 *
 * ## 삭제 기준
 * - [BulkOperationRepository.findCompletedBefore] 로 `now - CLEANUP_RETENTION_DAYS` 이전에
 *   `completed_at` 이 찍힌 작업 목록을 조회한다.
 * - bulk_operation_items → bulk_operations 순으로 삭제하여 FK 제약을 만족한다.
 *
 * ## 왜 repository 에 삭제 메서드를 두지 않는가
 * - 삭제는 운영(cleanup) 관심사이며 조회/갱신과 성격이 다르다.
 * - cleanup 전용 워커가 직접 jOOQ DSL 로 DELETE 를 수행하는 것이
 *   [BulkOperationWorker] 의 pgmq 직접 호출 패턴과 일관된다.
 * - PR1 자산인 repository 를 수정하지 않아 모듈 경계를 존중한다.
 *
 * ## 시각 의존 — Clock 주입
 * [Clock] 을 주입받아 "현재 시각"을 계산하므로, 테스트에서 [Clock.fixed] 로 기준 시각을
 * 핀(pin)하여 time-bomb 을 방지한다 (learnings: authcontroller-revokesession-timebomb).
 *
 * @param bulkRepo [BulkOperationRepository]. 만료 작업 목록 조회 담당.
 * @param dsl jOOQ [DSLContext]. items / operations 직접 DELETE 담당.
 * @param clock 현재 시각 소스. 기본값 UTC. 테스트에서 [Clock.fixed] 로 교체 가능.
 */
@Component
class BulkOperationCleanupWorker(
    private val bulkRepo: BulkOperationRepository,
    private val dsl: DSLContext,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 만료된 일괄 작업과 그 항목을 삭제한다.
     *
     * 실행 주기: [CLEANUP_CRON] (매일 새벽 3시 UTC).
     * 기준: 완료 시각(`completed_at`)이 `now - CLEANUP_RETENTION_DAYS` 이전인 작업.
     */
    @Scheduled(cron = CLEANUP_CRON)
    @Transactional
    fun cleanupExpired() {
        val threshold = clock.instant().minusSeconds(CLEANUP_RETENTION_SECONDS)
        val expired = bulkRepo.findCompletedBefore(threshold)

        if (expired.isEmpty()) {
            log.debug("bulk_cleanup_no_expired threshold={}", threshold)
            return
        }

        log.info("bulk_cleanup_start count={} threshold={}", expired.size, threshold)

        val expiredIds: List<UUID> = expired.map { it.id.value }

        // FK 제약 — items(자식) 먼저 삭제 후 operations(부모) 삭제
        expiredIds.forEach { id ->
            dsl.execute(
                "DELETE FROM bulk_operation_items WHERE bulk_operation_id = ?",
                id,
            )
        }

        expiredIds.forEach { id ->
            dsl.execute(
                "DELETE FROM bulk_operations WHERE id = ?",
                id,
            )
        }

        log.info("bulk_cleanup_done deleted={}", expiredIds.size)
    }

    companion object {
        /**
         * 완료 후 보존 기간(일).
         *
         * 이 기간이 지난 작업은 [cleanupExpired] 에서 영구 삭제된다.
         * NFR4 에 따른 TTL = 30일.
         */
        const val CLEANUP_RETENTION_DAYS: Long = 30L

        /**
         * 보존 기간을 초 단위로 변환한 값.
         *
         * `threshold = Instant.now(clock) - CLEANUP_RETENTION_SECONDS`
         * 로 기준 시각을 계산한다.
         */
        const val CLEANUP_RETENTION_SECONDS: Long = 60L * 60 * 24 * CLEANUP_RETENTION_DAYS

        /**
         * 삭제 배치 실행 cron 표현식 (Spring cron 형식: 초 분 시 일 월 요일).
         *
         * 기본값: 매일 03:00 UTC — 서비스 사용량이 가장 낮은 시간대.
         * 운영 환경에서 변경이 필요하면 `bts.bulk.cleanup.cron` 프로퍼티로 override 가능.
         */
        const val CLEANUP_CRON = "\${bts.bulk.cleanup.cron:0 0 3 * * *}"
    }
}
