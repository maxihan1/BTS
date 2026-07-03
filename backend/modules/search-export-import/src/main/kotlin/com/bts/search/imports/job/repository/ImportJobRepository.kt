// Import 작업 Repository — import_jobs 테이블 jOOQ DSL 접근 (FR-IM-01)

package com.bts.search.imports.job.repository

import com.bts.search.imports.job.domain.ImportJob
import com.bts.search.imports.job.domain.ImportJobId
import com.bts.search.imports.job.domain.ImportJobStatus
import com.bts.search.jooq.tables.records.ImportJobsRecord
import com.bts.search.jooq.tables.references.IMPORT_JOBS
import org.jooq.DSLContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * Import 작업 Repository.
 *
 * jOOQ DSLContext 를 통해 import_jobs 테이블에 접근한다.
 * 모든 public 쓰기 메서드는 @Transactional 를 명시한다 (DATA.md §6, DEVELOPMENT.md §절대규칙).
 *
 * `export` BC [com.bts.search.export.job.repository.ExportJobRepository] 를 1:1 미러하되
 * import 전용 필드(sourceObjectKey, dryRun, totalRows, succeededRows, failedRows,
 * errorLogObjectKey)로 조정했다.
 *
 * **CAS(Compare-And-Swap) 패턴** — [claimForRun], [markCompleted], [markFailed] 는
 * WHERE status=? 조건을 포함한 단일 UPDATE 의 affected rows 로 원자적 단일 진입을 보장한다.
 * 조회 후 별도 UPDATE(TOCTOU) 방식을 사용하지 않는다 (learnings: advisory-lock-bigint-TOCTOU).
 *
 * **Clock 주입** — 기본값 [Clock.systemUTC].
 * search 모듈에 Clock 빈이 없으므로 default 파라미터로 처리한다
 * (교훈 fr-ux-03-inbox-backend-done: notification Clock 빈 부재 패턴 동일).
 *
 * @param dsl jOOQ DSLContext — SQL 을 코드로 안전하게 작성하는 라이브러리의 핵심 진입점.
 * @param clock 시각 결정을 위한 시계. 테스트는 고정 Clock 을 주입한다.
 */
@Repository
@Suppress("TooManyFunctions")
class ImportJobRepository(
    private val dsl: DSLContext,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Import 작업 1건을 import_jobs 에 삽입한다.
     *
     * status=PENDING 가 보장된 [ImportJob] 을 받아 INSERT 한다.
     * totalRows/errorCode/errorLogObjectKey/expiresAt/startedAt/completedAt 은
     * DB DEFAULT(succeededRows=0, failedRows=0) 또는 NULL 로 남는다.
     * attachmentsObjectKey(V605)는 접수 시점(업로드 요청)에 이미 결정되므로 함께 INSERT 한다.
     *
     * @param job 삽입할 [ImportJob]. status=PENDING 이 보장되어야 한다.
     * @return 삽입한 [job] 그대로. INSERT 로 값이 파생되는 컬럼이 없어 재조회하지 않는다.
     */
    @Transactional
    fun insert(job: ImportJob): ImportJob {
        log.debug("Inserting ImportJob id={}", job.id.value)
        dsl.insertInto(IMPORT_JOBS)
            .set(IMPORT_JOBS.ID, job.id.value)
            .set(IMPORT_JOBS.PROJECT_KEY, job.projectKey)
            .set(IMPORT_JOBS.FORMAT, job.format)
            .set(IMPORT_JOBS.SOURCE_OBJECT_KEY, job.sourceObjectKey)
            .set(IMPORT_JOBS.DRY_RUN, job.dryRun)
            .set(IMPORT_JOBS.REQUESTER_USER_ID, job.requesterUserId)
            .set(IMPORT_JOBS.STATUS, job.status.name)
            .set(IMPORT_JOBS.PROGRESS, job.progress)
            .set(IMPORT_JOBS.ATTACHMENTS_OBJECT_KEY, job.attachmentsObjectKey)
            .set(IMPORT_JOBS.EXPIRES_AT, job.expiresAt?.toOffsetDateTime())
            .set(IMPORT_JOBS.CREATED_AT, job.createdAt.toOffsetDateTime())
            .execute()
        return job
    }

    /**
     * 소유권 검증 없는 단건 조회 — 워커 전용.
     *
     * `WHERE id=?` 조건으로 소유권 무관하게 작업을 로드한다.
     * [claimForRun] 성공 후 워커가 전체 [ImportJob] 도메인 객체를 로드할 때 사용한다.
     * 외부 HTTP 엔드포인트에서는 반드시 [findByIdForRequester] 를 사용해 소유권을 검증해야 한다.
     *
     * @param id 조회할 작업 식별자.
     * @return [ImportJob]. 없으면 null.
     */
    @Transactional(readOnly = true)
    fun findById(id: ImportJobId): ImportJob? =
        dsl.selectFrom(IMPORT_JOBS)
            .where(IMPORT_JOBS.ID.eq(id.value))
            .fetchOne()
            ?.toImportJob()

    /**
     * 요청자 소유권 검증을 포함한 단건 조회.
     *
     * `WHERE id=? AND requester_user_id=?` 조건으로 타인 소유 작업은 null 반환.
     * 존재 자체를 노출하지 않아 소유권 없는 요청자에게 404 응답을 내려줄 수 있다.
     *
     * @param id 조회할 작업 식별자.
     * @param requesterUserId 요청자 UUID.
     * @return 소유한 [ImportJob]. 없거나 소유권 없으면 null.
     */
    @Transactional(readOnly = true)
    fun findByIdForRequester(
        id: ImportJobId,
        requesterUserId: UUID,
    ): ImportJob? =
        dsl.selectFrom(IMPORT_JOBS)
            .where(IMPORT_JOBS.ID.eq(id.value))
            .and(IMPORT_JOBS.REQUESTER_USER_ID.eq(requesterUserId))
            .fetchOne()
            ?.toImportJob()

    /**
     * PENDING 상태 작업 또는 stale RUNNING 작업을 RUNNING 으로 전환한다 (CAS).
     *
     * 단일 UPDATE affected rows 로 원자적 단일 진입을 보장한다 (TOCTOU 방지).
     *
     * ### 선점 조건
     * ```sql
     * WHERE id = ?
     *   AND (
     *     status = 'PENDING'
     *     OR (status = 'RUNNING' AND started_at < ?staleThreshold)
     *   )
     * ```
     * - PENDING → 신규 처리 선점.
     * - RUNNING + started_at < staleThreshold → 크래시 후 방치된 stale 작업 재청.
     *
     * **CAS 의도** — affected rows 1 = 선점 성공, 0 = 타 워커 활성 처리 중.
     * TOCTOU(Time-Of-Check-Time-Of-Use) 경쟁조건을 방지한다.
     *
     * @param id 전환할 작업 식별자.
     * @return 선점 성공이면 true, 타 워커 활성 선점이면 false.
     */
    @Transactional
    fun claimForRun(id: ImportJobId): Boolean {
        log.debug("claimForRun id={}", id.value)
        val now = OffsetDateTime.now(clock)
        val staleThreshold = now.minusSeconds(STALE_RUNNING_THRESHOLD_SECONDS)
        val affected =
            dsl.update(IMPORT_JOBS)
                .set(IMPORT_JOBS.STATUS, ImportJobStatus.RUNNING.name)
                .set(IMPORT_JOBS.STARTED_AT, now)
                .where(IMPORT_JOBS.ID.eq(id.value))
                .and(
                    IMPORT_JOBS.STATUS.eq(ImportJobStatus.PENDING.name)
                        .or(
                            IMPORT_JOBS.STATUS.eq(ImportJobStatus.RUNNING.name)
                                .and(IMPORT_JOBS.STARTED_AT.lt(staleThreshold)),
                        ),
                )
                .execute()
        return affected == 1
    }

    /**
     * 작업 1건의 현재 상태를 조회한다 (경량 조회 — status 컬럼만 읽음).
     *
     * @param id 조회할 작업 식별자.
     * @return 현재 상태. 작업이 없으면 null.
     */
    @Transactional(readOnly = true)
    fun findStatus(id: ImportJobId): ImportJobStatus? =
        dsl.select(IMPORT_JOBS.STATUS)
            .from(IMPORT_JOBS)
            .where(IMPORT_JOBS.ID.eq(id.value))
            .fetchOne()
            ?.let { record ->
                val statusStr = record.get(IMPORT_JOBS.STATUS) ?: return@let null
                enumValueOf<ImportJobStatus>(statusStr)
            }

    /**
     * 작업 진행률과 파싱 확정 건수(전체/성공/실패)를 갱신한다.
     *
     * 워커가 RUNNING 중 파싱/생성 진행 상황을 주기적으로 반영하는 no-bump 부수 업데이트다.
     * CAS 조건 없이 단순 UPDATE 로 처리한다 — 소유권은 [claimForRun] 이 이미 보장했다.
     *
     * @param id 갱신할 작업 식별자.
     * @param progress 현재 진행률(0..100).
     * @param totalRows 파싱으로 확정된 전체 행 수.
     * @param succeededRows 지금까지 성공 처리된 행 수 누적.
     * @param failedRows 지금까지 실패 처리된 행 수 누적.
     */
    @Transactional
    fun updateCounts(
        id: ImportJobId,
        progress: Int,
        totalRows: Long,
        succeededRows: Long,
        failedRows: Long,
    ) {
        dsl.update(IMPORT_JOBS)
            .set(IMPORT_JOBS.PROGRESS, progress)
            .set(IMPORT_JOBS.TOTAL_ROWS, totalRows)
            .set(IMPORT_JOBS.SUCCEEDED_ROWS, succeededRows)
            .set(IMPORT_JOBS.FAILED_ROWS, failedRows)
            .where(IMPORT_JOBS.ID.eq(id.value))
            .execute()
    }

    /**
     * RUNNING 상태 작업을 COMPLETED 로 전환한다 (CAS — 1회 완료 보장).
     *
     * `WHERE id=? AND status='RUNNING'` 조건으로 이미 COMPLETED 인 경우 0 row 반환.
     *
     * **CAS 의도** — "내가 RUNNING 으로 claimForRun 한 워커만 COMPLETED 로 전환 가능"을
     * DB 레벨에서 보장한다. affected rows 1 = 성공, 0 = 이미 다른 경로로 상태 변경 완료.
     *
     * @param id 완료할 작업 식별자.
     * @param succeededRows 최종 성공 행 수.
     * @param failedRows 최종 실패 행 수.
     * @param errorLogObjectKey 실패행 로그 MinIO 오브젝트 키. 실패행이 없으면 null.
     * @param expiresAt 결과/로그 파일 만료 시각(UTC). 완료 후 24h 권장.
     * @return 완료 전환 성공이면 true, 이미 완료됐으면 false.
     */
    @Transactional
    fun markCompleted(
        id: ImportJobId,
        succeededRows: Long,
        failedRows: Long,
        errorLogObjectKey: String?,
        expiresAt: Instant,
    ): Boolean {
        log.debug("markCompleted id={}", id.value)
        val affected =
            dsl.update(IMPORT_JOBS)
                .set(IMPORT_JOBS.STATUS, ImportJobStatus.COMPLETED.name)
                .set(IMPORT_JOBS.SUCCEEDED_ROWS, succeededRows)
                .set(IMPORT_JOBS.FAILED_ROWS, failedRows)
                .set(IMPORT_JOBS.ERROR_LOG_OBJECT_KEY, errorLogObjectKey)
                .set(IMPORT_JOBS.EXPIRES_AT, expiresAt.toOffsetDateTime())
                .set(IMPORT_JOBS.COMPLETED_AT, OffsetDateTime.now(clock))
                .where(IMPORT_JOBS.ID.eq(id.value))
                .and(IMPORT_JOBS.STATUS.eq(ImportJobStatus.RUNNING.name))
                .execute()
        return affected == 1
    }

    /**
     * RUNNING 또는 PENDING 상태 작업을 FAILED 로 전환한다 (CAS).
     *
     * `WHERE id=? AND status IN ('RUNNING','PENDING')` 조건으로 이미 종단 상태이면 0 row 반환.
     *
     * **CAS 의도** — 워커 처리 중 예외 또는 인프라 오류 시 RUNNING/PENDING 양쪽에서 실패 기록 가능.
     *
     * @param id 실패 처리할 작업 식별자.
     * @param errorCode 실패 원인 코드 (예: `IMPORT_INTERNAL_ERROR`).
     * @return 실패 전환 성공이면 true, 이미 종단 상태이면 false.
     */
    @Transactional
    fun markFailed(
        id: ImportJobId,
        errorCode: String,
    ): Boolean {
        log.debug("markFailed id={}", id.value)
        val affected =
            dsl.update(IMPORT_JOBS)
                .set(IMPORT_JOBS.STATUS, ImportJobStatus.FAILED.name)
                .set(IMPORT_JOBS.ERROR_CODE, errorCode)
                .set(IMPORT_JOBS.COMPLETED_AT, OffsetDateTime.now(clock))
                .where(IMPORT_JOBS.ID.eq(id.value))
                .and(
                    IMPORT_JOBS.STATUS.eq(ImportJobStatus.RUNNING.name)
                        .or(IMPORT_JOBS.STATUS.eq(ImportJobStatus.PENDING.name)),
                )
                .execute()
        return affected == 1
    }

    /**
     * TTL 만료 작업 목록을 반환한다.
     *
     * `WHERE expires_at IS NOT NULL AND expires_at < ?now` 조건으로 만료된 작업만 조회한다.
     * TTL cleanup 워커가 이 목록을 기반으로 MinIO 객체(원본/에러로그) 삭제를 수행한다.
     *
     * @param now 현재 시각. 이 시각보다 이전에 expires_at 이 설정된 작업만 반환.
     * @return 만료된 작업 목록. 없으면 빈 리스트.
     */
    @Transactional(readOnly = true)
    fun findExpired(now: Instant): List<ImportJob> =
        dsl.selectFrom(IMPORT_JOBS)
            .where(IMPORT_JOBS.EXPIRES_AT.isNotNull)
            .and(IMPORT_JOBS.EXPIRES_AT.lt(now.toOffsetDateTime()))
            .fetch()
            .map { it.toImportJob() }

    /**
     * AWAITING_MAPPING 작업을 PENDING 으로 전환한다 (CAS + 만료 해제 + dry_run 확정).
     *
     * `WHERE id=? AND status='AWAITING_MAPPING'` 조건의 단일 UPDATE 로,
     * status 를 PENDING 으로 바꾸고 expires_at 을 NULL 로 해제하며 dry_run 을 [dryRun] 값으로
     * 확정한다 (FR-IM-02). 사용자가 소스 필드 ↔ 대상 필드 매핑을 확정(confirm)하면 이 전이가 일어나
     * 워커가 처리를 시작한다.
     *
     * **CAS 의도** — affected rows 1 = 확정 성공. 이미 PENDING/RUNNING 등 다른 상태이면 0 = false 를 반환해
     * 멱등하다(더블 confirm/재요청 시 두 번째는 무해히 false). 조회 후 UPDATE(TOCTOU) 를 쓰지 않는다.
     *
     * **expires_at 해제 이유** — AWAITING_MAPPING job 은 매핑 미확정 방치를 정리하기 위한 TTL(expires_at)을 가진다.
     * 확정 시 NULL 로 해제해 [deleteIfExpired] cleanup 대상에서 즉시 제외한다(cleanup vs confirm 레이스 차단).
     *
     * **dry_run 확정 이유 (dryrun-fix)** — analyze 단계는 dryRun 에 대해 중립이라 [insert] 시 항상
     * `false` 로 저장된다. 실제 dry-run 여부는 사용자가 매핑을 confirm 하는 시점에 비로소 선택되므로,
     * 이 CAS UPDATE 에서 `dry_run` 컬럼을 [dryRun] 값으로 함께 SET 해야 워커가 확정된 선택을 읽어
     * "검증만 하고 실제 이슈는 생성하지 않는" dry-run 실행을 수행할 수 있다. 이 SET 이 빠지면
     * confirm 의 dryRun 파라미터가 감사 로깅에만 쓰이고 조용히 버려져, dry-run 확정 매핑이 실제
     * Import 로 실행되는 silent bug 가 된다.
     *
     * @param id 전환할 작업 식별자.
     * @param dryRun 확정 시점에 사용자가 선택한 dry-run 여부. `dry_run` 컬럼에 그대로 반영된다.
     * @return 확정 전환 성공이면 true, AWAITING_MAPPING 이 아니면 false(멱등).
     */
    @Transactional
    fun transitionToPending(
        id: ImportJobId,
        dryRun: Boolean,
    ): Boolean {
        log.debug("transitionToPending id={} dryRun={}", id.value, dryRun)
        val affected =
            dsl.update(IMPORT_JOBS)
                .set(IMPORT_JOBS.STATUS, ImportJobStatus.PENDING.name)
                .set(IMPORT_JOBS.EXPIRES_AT, null as OffsetDateTime?)
                .set(IMPORT_JOBS.DRY_RUN, dryRun)
                .where(IMPORT_JOBS.ID.eq(id.value))
                .and(IMPORT_JOBS.STATUS.eq(ImportJobStatus.AWAITING_MAPPING.name))
                .execute()
        return affected > 0
    }

    /**
     * 만료된 작업 1건을 가드 조건부로 하드삭제한다 (cleanup 전용).
     *
     * `WHERE id=? AND expires_at IS NOT NULL AND expires_at < ?now` 조건의 단일 DELETE 로,
     * "만료 시점 스냅샷 이후에도 여전히 만료 상태인 행"만 삭제한다 (FR-IM-02).
     * 무조건 삭제하는 [deleteById] 와 달리 expires_at 가드를 DELETE 절에 포함한다.
     *
     * **가드 삭제 의도 (cleanup vs confirm 레이스 차단)** — cleanup 워커가 [findExpired] 로 만료 목록을 스냅샷한 뒤
     * 삭제하기 직전, 사용자가 [transitionToPending] 으로 매핑을 확정하면 해당 job 의 expires_at 이 NULL 이 된다.
     * 이 경우 가드 조건(`expires_at IS NOT NULL AND expires_at < now`)이 어긋나 0 row 가 되어,
     * 방금 확정된 job 이 잘못 삭제되는 것을 DB 레벨에서 막는다. affected rows > 0 = 실제 삭제됨.
     *
     * DELETE 는 항상 WHERE 를 포함한다 (id + expires_at 가드, DATA.md §5 NEVER-7).
     *
     * @param id 삭제할 작업 식별자.
     * @param now 만료 판정 기준 시각. expires_at 이 이 시각보다 이전인 행만 삭제.
     * @return 실제 삭제됐으면 true, 가드 불일치(만료 아님/확정으로 expires_at=NULL)면 false.
     */
    @Transactional
    fun deleteIfExpired(
        id: ImportJobId,
        now: Instant,
    ): Boolean {
        log.debug("deleteIfExpired id={}", id.value)
        val affected =
            dsl.deleteFrom(IMPORT_JOBS)
                .where(IMPORT_JOBS.ID.eq(id.value))
                .and(IMPORT_JOBS.EXPIRES_AT.isNotNull)
                .and(IMPORT_JOBS.EXPIRES_AT.lt(now.toOffsetDateTime()))
                .execute()
        return affected > 0
    }

    /**
     * 작업 1건을 하드삭제한다.
     *
     * 소프트삭제 없음 — TTL 만료 후 cleanup 경로 전용 (DATA.md §3, V604 마이그레이션 근거).
     * TTL cleanup 워커([com.bts.search.imports.job.worker.ImportJobCleanupWorker]) 또는
     * 테스트 teardown 에서만 호출한다.
     *
     * `export` BC [com.bts.search.export.job.repository.ExportJobRepository.deleteById] 를 1:1 미러한다.
     * (Task 10 — cleanup worker 가 필요로 하는 메서드를 Export Task 9 의 `findById` 추가 선례와
     * 동일하게 워커 companion 메서드로 추가했다.)
     *
     * @param id 삭제할 작업 식별자.
     */
    @Transactional
    fun deleteById(id: ImportJobId) {
        log.debug("deleteById id={}", id.value)
        dsl.deleteFrom(IMPORT_JOBS)
            .where(IMPORT_JOBS.ID.eq(id.value))
            .execute()
    }

    // ── private helpers ──────────────────────────────────────────────────────────

    /**
     * [ImportJobsRecord] 를 도메인 [ImportJob] 으로 변환한다.
     *
     * status/progress/dryRun/succeededRows/failedRows 는 DB DEFAULT 가 있지만
     * null safety 를 위해 fallback 처리한다.
     */
    private fun ImportJobsRecord.toImportJob(): ImportJob =
        ImportJob(
            id = ImportJobId(id),
            projectKey = projectKey,
            format = format,
            sourceObjectKey = sourceObjectKey,
            dryRun = dryRun ?: false,
            requesterUserId = requesterUserId,
            status = enumValueOf(status ?: ImportJobStatus.PENDING.name),
            progress = progress ?: 0,
            totalRows = totalRows,
            succeededRows = succeededRows ?: 0L,
            failedRows = failedRows ?: 0L,
            errorCode = errorCode,
            errorLogObjectKey = errorLogObjectKey,
            expiresAt = expiresAt?.toInstant(),
            createdAt = (createdAt ?: error("import_jobs.created_at must not be null")).toInstant(),
            startedAt = startedAt?.toInstant(),
            completedAt = completedAt?.toInstant(),
            attachmentsObjectKey = attachmentsObjectKey,
        )

    companion object {
        /**
         * stale RUNNING 작업 재청 기준 (초).
         *
         * 이 시간이 경과했음에도 status=RUNNING 인 작업은 크래시로 방치된 것으로 간주하여
         * [claimForRun] 이 재선점을 허용한다.
         *
         * `export` BC ExportJobRepository.STALE_RUNNING_THRESHOLD_SECONDS 와 동일한
         * 600 초(10 분) 값을 사용한다 — 처리 예산(10 만행 파싱+이슈 생성) 안전마진 확보.
         */
        const val STALE_RUNNING_THRESHOLD_SECONDS: Long = 600L
    }
}

// ── file-level helpers ────────────────────────────────────────────────────────────

/** [Instant] 를 UTC [OffsetDateTime] 으로 변환한다. */
private fun Instant.toOffsetDateTime(): OffsetDateTime = OffsetDateTime.ofInstant(this, ZoneOffset.UTC)
