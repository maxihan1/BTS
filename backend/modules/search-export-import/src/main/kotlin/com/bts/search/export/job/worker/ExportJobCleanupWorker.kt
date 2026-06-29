// Export 작업 TTL 정리 워커 — expires_at 경과 작업의 MinIO 객체 삭제 + DB 행 삭제 (FR-EX-02 Task 9)

package com.bts.search.export.job.worker

import com.bts.search.export.job.repository.ExportJobRepository
import com.bts.search.export.job.storage.ExportObjectStoragePort
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock

/**
 * TTL(Time-To-Live) 만료 Export 작업의 MinIO 오브젝트와 DB 행을 정리하는 배치 워커.
 *
 * ## 삭제 기준
 * [ExportJobRepository.findExpired] 로 `expires_at < now` 인 작업 목록 조회.
 * 각 작업에 대해 [ExportObjectStoragePort.remove] → [ExportJobRepository.deleteById] 순으로 실행한다.
 *
 * ## best-effort MinIO 삭제
 * [ExportObjectStoragePort.remove] 가 실패해도 DB 행 삭제를 진행한다 (best-effort).
 * MinIO 오브젝트는 다음 날 재시도 또는 MinIO 자체 TTL 로 처리 가능하므로
 * DB 일관성(행 삭제)을 우선한다. 예외는 반드시 로그에 기록 (빈 catch 금지).
 *
 * ## @Transactional 없음 — 의도적 설계
 * [ExportObjectStoragePort.remove] 는 MinIO 외부 I/O 이며 트랜잭션 밖에서 수행해야 한다.
 * [ExportJobRepository.deleteById] 는 단독 @Transactional 이 처리한다.
 * cleanup 워커에 외부 트랜잭션을 걸면 long transaction + MinIO I/O 점유 문제가 발생한다.
 * **@Transactional 없음 — 의도적 설계.**
 *
 * ## 시각 의존 — Clock 주입
 * [Clock] 을 주입받아 "현재 시각"을 계산하므로, 테스트에서 [Clock.fixed] 로 기준 시각을
 * 핀(pin)하여 time-bomb 을 방지한다 (learnings: authcontroller-revokesession-timebomb).
 *
 * @param exportRepo [ExportJobRepository]. 만료 작업 조회 + 하드삭제 담당.
 * @param storage [ExportObjectStoragePort]. MinIO 오브젝트 삭제 담당.
 * @param clock 현재 시각 소스. search 모듈 Clock 빈 부재로 기본값 [Clock.systemUTC] 사용.
 */
@Component
class ExportJobCleanupWorker(
    private val exportRepo: ExportJobRepository,
    private val storage: ExportObjectStoragePort,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 만료된 Export 작업의 MinIO 오브젝트와 DB 행을 삭제한다.
     *
     * 실행 주기: [CLEANUP_CRON] (매일 새벽 4시 UTC).
     * **@Transactional 없음 — 의도적 설계** (클래스 KDoc 참조).
     */
    @Scheduled(cron = CLEANUP_CRON)
    @Suppress("TooGenericExceptionCaught")
    fun cleanupExpired() {
        val now = clock.instant()
        val expired = exportRepo.findExpired(now)

        if (expired.isEmpty()) {
            log.debug("export_cleanup_no_expired now={}", now)
            return
        }

        log.info("export_cleanup_start count={} now={}", expired.size, now)

        for (job in expired) {
            val objectKey = job.resultObjectKey

            if (objectKey != null) {
                try {
                    storage.remove(objectKey)
                    log.debug("export_cleanup_storage_removed jobId={} objectKey={}", job.id, objectKey)
                } catch (e: Exception) {
                    // best-effort — 스토리지 삭제 실패 시 로그만 남기고 DB 행 삭제 진행
                    log.warn(
                        "export_cleanup_storage_remove_failed jobId={} objectKey={} error={}",
                        job.id,
                        objectKey,
                        e.message,
                        e,
                    )
                }
            }

            exportRepo.deleteById(job.id)
            log.debug("export_cleanup_job_deleted jobId={}", job.id)
        }

        log.info("export_cleanup_done deleted={}", expired.size)
    }

    companion object {
        /**
         * 삭제 배치 실행 cron 표현식 (Spring cron 형식: 초 분 시 일 월 요일).
         *
         * 기본값: 매일 04:00 UTC — 서비스 사용량이 가장 낮은 시간대.
         * 운영 환경에서 변경이 필요하면 `bts.export.cleanup.cron` 프로퍼티로 override 가능.
         */
        const val CLEANUP_CRON = "\${bts.export.cleanup.cron:0 0 4 * * *}"
    }
}
