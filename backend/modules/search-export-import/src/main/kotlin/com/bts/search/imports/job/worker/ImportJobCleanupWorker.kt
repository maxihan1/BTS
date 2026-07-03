// Import 작업 TTL 정리 워커 — expires_at 경과 작업의 원본/에러로그 MinIO 객체 삭제 + DB 행 삭제 (FR-IM-01 PR1 Task 10)

package com.bts.search.imports.job.worker

import com.bts.search.imports.job.repository.ImportJobRepository
import com.bts.search.imports.job.storage.ImportObjectStoragePort
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock

/**
 * TTL(Time-To-Live) 만료 Import 작업의 MinIO 오브젝트(원본 + 에러로그)와 DB 행을 정리하는 배치 워커.
 *
 * `export` BC [com.bts.search.export.job.worker.ExportJobCleanupWorker] 를 1:1 미러하되,
 * import 는 결과 산출물이 아닌 **업로드 원본**([sourceObjectKey])이 항상 존재하고 에러로그
 * ([errorLogObjectKey])는 실패행이 있을 때만 존재하는 2-오브젝트 구조로 조정했다.
 *
 * ## 삭제 기준
 * [ImportJobRepository.findExpired] 로 `expires_at < now` 인 작업 목록 조회.
 * 각 작업에 대해 [ImportJobRepository.deleteIfExpired] 가 true 를 반환할 때만
 * sourceObjectKey 삭제 → errorLogObjectKey(있으면) 삭제 순으로 실행한다(FR-IM-02 CONCERN-4).
 *
 * ## best-effort MinIO 삭제
 * [ImportObjectStoragePort.delete] 가 실패해도 다음 오브젝트 삭제와 DB 행 삭제를 진행한다
 * (best-effort). MinIO 오브젝트는 다음 날 재시도 또는 MinIO 자체 TTL 로 처리 가능하므로
 * DB 일관성(행 삭제)을 우선한다. 예외는 반드시 로그에 기록 (빈 catch 금지).
 *
 * ## @Transactional 없음 — 의도적 설계
 * [ImportObjectStoragePort.delete] 는 MinIO 외부 I/O 이며 트랜잭션 밖에서 수행해야 한다.
 * [ImportJobRepository.deleteIfExpired] 는 단독 @Transactional 이 처리한다.
 * cleanup 워커에 외부 트랜잭션을 걸면 long transaction + MinIO I/O 점유 문제가 발생한다.
 * **@Transactional 없음 — 의도적 설계.**
 *
 * ## 시각 의존 — Clock 주입
 * [Clock] 을 주입받아 "현재 시각"을 계산하므로, 테스트에서 [Clock.fixed] 로 기준 시각을
 * 핀(pin)하여 time-bomb 을 방지한다 (learnings: authcontroller-revokesession-timebomb).
 *
 * @param importRepo [ImportJobRepository]. 만료 작업 조회 + 하드삭제 담당.
 * @param storage [ImportObjectStoragePort]. MinIO 오브젝트 삭제 담당.
 * @param clock 현재 시각 소스. search 모듈 Clock 빈 부재로 기본값 [Clock.systemUTC] 사용.
 */
@Component
class ImportJobCleanupWorker(
    private val importRepo: ImportJobRepository,
    private val storage: ImportObjectStoragePort,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 만료된 Import 작업의 MinIO 오브젝트(원본 + 에러로그)와 DB 행을 삭제한다.
     *
     * 실행 주기: [CLEANUP_CRON] (매일 새벽 4시 UTC — [ExportJobCleanupWorker.CLEANUP_CRON] 미러).
     * **@Transactional 없음 — 의도적 설계** (클래스 KDoc 참조).
     */
    @Scheduled(cron = CLEANUP_CRON)
    @Suppress("TooGenericExceptionCaught")
    fun cleanupExpired() {
        val now = clock.instant()
        val expired = importRepo.findExpired(now)

        if (expired.isEmpty()) {
            log.debug("import_cleanup_no_expired now={}", now)
            return
        }

        log.info("import_cleanup_start count={} now={}", expired.size, now)

        var deletedCount = 0
        for (job in expired) {
            if (!importRepo.deleteIfExpired(job.id, now)) {
                log.debug("import_cleanup_job_skipped_confirmed_race jobId={}", job.id)
                continue
            }
            deletedCount++

            removeObject(job.id.value.toString(), job.sourceObjectKey)

            val errorLogObjectKey = job.errorLogObjectKey
            if (errorLogObjectKey != null) {
                removeObject(job.id.value.toString(), errorLogObjectKey)
            }

            log.debug("import_cleanup_job_deleted jobId={}", job.id)
        }

        log.info("import_cleanup_done deleted={}", deletedCount)
    }

    /**
     * best-effort 로 단일 오브젝트를 삭제한다. 실패해도 예외를 전파하지 않고 로그만 남긴다
     * (빈 catch 금지 — DEVELOPMENT.md §절대규칙).
     */
    @Suppress("TooGenericExceptionCaught")
    private fun removeObject(
        jobId: String,
        objectKey: String,
    ) {
        try {
            storage.delete(objectKey)
            log.debug("import_cleanup_storage_removed jobId={} objectKey={}", jobId, objectKey)
        } catch (e: Exception) {
            // best-effort — 스토리지 삭제 실패 시 로그만 남기고 나머지 정리(다음 오브젝트/DB 행 삭제) 진행
            log.warn(
                "import_cleanup_storage_remove_failed jobId={} objectKey={} error={}",
                jobId,
                objectKey,
                e.message,
                e,
            )
        }
    }

    companion object {
        /**
         * 삭제 배치 실행 cron 표현식 (Spring cron 형식: 초 분 시 일 월 요일).
         *
         * 기본값: 매일 04:00 UTC — 서비스 사용량이 가장 낮은 시간대.
         * 운영 환경에서 변경이 필요하면 `bts.import.cleanup.cron` 프로퍼티로 override 가능.
         */
        const val CLEANUP_CRON = "\${bts.import.cleanup.cron:0 0 4 * * *}"
    }
}
