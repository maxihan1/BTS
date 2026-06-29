// Export 작업 Repository — export_jobs 테이블 jOOQ DSL 접근 (FR-EX-02)

package com.bts.search.export.job.repository

import com.bts.search.export.job.domain.ExportJob
import com.bts.search.export.job.domain.ExportJobId
import com.bts.search.export.job.domain.ExportJobStatus
import com.bts.search.jooq.tables.records.ExportJobsRecord
import com.bts.search.jooq.tables.references.EXPORT_JOBS
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
 * Export 작업 Repository.
 *
 * jOOQ DSLContext 를 통해 export_jobs 테이블에 접근한다.
 * 모든 public 쓰기 메서드는 @Transactional 를 명시한다 (DATA.md §6, DEVELOPMENT.md §절대규칙).
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
class ExportJobRepository(
    private val dsl: DSLContext,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Export 작업 1건을 export_jobs 에 삽입한다.
     *
     * status=PENDING 가 보장된 [ExportJob] 을 받아 INSERT 한다.
     * progress=0, rowCount/resultObjectKey/errorCode/expiresAt/startedAt/completedAt 은
     * 도메인 초기값(null/0)을 그대로 저장한다.
     *
     * @param exportJob 삽입할 [ExportJob]. status=PENDING 이 보장되어야 한다.
     */
    @Transactional
    fun insert(exportJob: ExportJob) {
        log.debug("Inserting ExportJob id={}", exportJob.id.value)
        dsl.insertInto(EXPORT_JOBS)
            .set(EXPORT_JOBS.ID, exportJob.id.value)
            .set(EXPORT_JOBS.PROJECT_KEY, exportJob.projectKey)
            .set(EXPORT_JOBS.QUERY, exportJob.query)
            .set(EXPORT_JOBS.FORMAT, exportJob.format)
            .set(EXPORT_JOBS.COLUMNS, exportJob.columns.toColumnsString())
            .set(EXPORT_JOBS.REQUESTER_USER_ID, exportJob.requesterUserId)
            .set(EXPORT_JOBS.STATUS, exportJob.status.name)
            .set(EXPORT_JOBS.PROGRESS, exportJob.progress)
            .set(EXPORT_JOBS.CREATED_AT, exportJob.createdAt.toOffsetDateTime())
            .execute()
    }

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
    fun claimForRun(id: ExportJobId): Boolean {
        log.debug("claimForRun id={}", id.value)
        val now = OffsetDateTime.now(clock)
        val staleThreshold = now.minusSeconds(STALE_RUNNING_THRESHOLD_SECONDS)
        val affected =
            dsl.update(EXPORT_JOBS)
                .set(EXPORT_JOBS.STATUS, ExportJobStatus.RUNNING.name)
                .set(EXPORT_JOBS.STARTED_AT, now)
                .where(EXPORT_JOBS.ID.eq(id.value))
                .and(
                    EXPORT_JOBS.STATUS.eq(ExportJobStatus.PENDING.name)
                        .or(
                            EXPORT_JOBS.STATUS.eq(ExportJobStatus.RUNNING.name)
                                .and(EXPORT_JOBS.STARTED_AT.lt(staleThreshold)),
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
    fun findStatus(id: ExportJobId): ExportJobStatus? =
        dsl.select(EXPORT_JOBS.STATUS)
            .from(EXPORT_JOBS)
            .where(EXPORT_JOBS.ID.eq(id.value))
            .fetchOne()
            ?.let { record ->
                val statusStr = record.get(EXPORT_JOBS.STATUS) ?: return@let null
                enumValueOf<ExportJobStatus>(statusStr)
            }

    /**
     * 작업 진행률과 현재까지 처리된 총 행 수를 갱신한다.
     *
     * count-first 로 확정된 row_count 와 스트리밍 진행률을 함께 UPDATE 한다.
     *
     * @param id 갱신할 작업 식별자.
     * @param percent 현재 진행률 (0..100).
     * @param rowCount count-first 로 확정된 총 행 수.
     */
    @Transactional
    fun updateProgress(
        id: ExportJobId,
        percent: Int,
        rowCount: Long,
    ) {
        dsl.update(EXPORT_JOBS)
            .set(EXPORT_JOBS.PROGRESS, percent)
            .set(EXPORT_JOBS.ROW_COUNT, rowCount)
            .where(EXPORT_JOBS.ID.eq(id.value))
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
     * @param objectKey MinIO 결과 파일 오브젝트 키.
     * @param expiresAt 결과 파일 만료 시각(UTC). 완료 후 24h 권장.
     * @return 완료 전환 성공이면 true, 이미 완료됐으면 false.
     */
    @Transactional
    fun markCompleted(
        id: ExportJobId,
        objectKey: String,
        expiresAt: Instant,
    ): Boolean {
        log.debug("markCompleted id={}", id.value)
        val affected =
            dsl.update(EXPORT_JOBS)
                .set(EXPORT_JOBS.STATUS, ExportJobStatus.COMPLETED.name)
                .set(EXPORT_JOBS.RESULT_OBJECT_KEY, objectKey)
                .set(EXPORT_JOBS.EXPIRES_AT, expiresAt.toOffsetDateTime())
                .set(EXPORT_JOBS.COMPLETED_AT, OffsetDateTime.now(clock))
                .where(EXPORT_JOBS.ID.eq(id.value))
                .and(EXPORT_JOBS.STATUS.eq(ExportJobStatus.RUNNING.name))
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
     * @param errorCode 실패 원인 코드 (예: `SEARCH_INTERNAL_ERROR`).
     * @return 실패 전환 성공이면 true, 이미 종단 상태이면 false.
     */
    @Transactional
    fun markFailed(
        id: ExportJobId,
        errorCode: String,
    ): Boolean {
        log.debug("markFailed id={}", id.value)
        val affected =
            dsl.update(EXPORT_JOBS)
                .set(EXPORT_JOBS.STATUS, ExportJobStatus.FAILED.name)
                .set(EXPORT_JOBS.ERROR_CODE, errorCode)
                .set(EXPORT_JOBS.COMPLETED_AT, OffsetDateTime.now(clock))
                .where(EXPORT_JOBS.ID.eq(id.value))
                .and(
                    EXPORT_JOBS.STATUS.eq(ExportJobStatus.RUNNING.name)
                        .or(EXPORT_JOBS.STATUS.eq(ExportJobStatus.PENDING.name)),
                )
                .execute()
        return affected == 1
    }

    /**
     * 요청자 소유권 검증을 포함한 단건 조회.
     *
     * `WHERE id=? AND requester_user_id=?` 조건으로 타인 소유 작업은 null 반환.
     * 존재 자체를 노출하지 않아 소유권 없는 요청자에게 404 응답을 내려줄 수 있다.
     *
     * @param id 조회할 작업 식별자.
     * @param requesterUserId 요청자 UUID.
     * @return 소유한 [ExportJob]. 없거나 소유권 없으면 null.
     */
    @Transactional(readOnly = true)
    fun findByIdForRequester(
        id: ExportJobId,
        requesterUserId: UUID,
    ): ExportJob? =
        dsl.selectFrom(EXPORT_JOBS)
            .where(EXPORT_JOBS.ID.eq(id.value))
            .and(EXPORT_JOBS.REQUESTER_USER_ID.eq(requesterUserId))
            .fetchOne()
            ?.toExportJob()

    /**
     * TTL 만료 작업 목록을 반환한다.
     *
     * `WHERE expires_at IS NOT NULL AND expires_at < ?now` 조건으로 만료된 작업만 조회한다.
     * TTL cleanup 워커가 이 목록을 기반으로 MinIO 객체 삭제 + [deleteById] 를 수행한다.
     *
     * @param now 현재 시각. 이 시각보다 이전에 expires_at 이 설정된 작업만 반환.
     * @return 만료된 작업 목록. 없으면 빈 리스트.
     */
    @Transactional(readOnly = true)
    fun findExpired(now: Instant): List<ExportJob> =
        dsl.selectFrom(EXPORT_JOBS)
            .where(EXPORT_JOBS.EXPIRES_AT.isNotNull)
            .and(EXPORT_JOBS.EXPIRES_AT.lt(now.toOffsetDateTime()))
            .fetch()
            .map { it.toExportJob() }

    /**
     * 작업 1건을 하드삭제한다.
     *
     * 소프트삭제 없음 — TTL 만료 후 cleanup 경로 전용 (DATA.md §3, V602 ADR 근거).
     * TTL cleanup 워커 또는 테스트 teardown 에서만 호출한다.
     *
     * @param id 삭제할 작업 식별자.
     */
    @Transactional
    fun deleteById(id: ExportJobId) {
        log.debug("deleteById id={}", id.value)
        dsl.deleteFrom(EXPORT_JOBS)
            .where(EXPORT_JOBS.ID.eq(id.value))
            .execute()
    }

    // ── private helpers ──────────────────────────────────────────────────────────

    /**
     * [ExportJobsRecord] 를 도메인 [ExportJob] 으로 변환한다.
     *
     * columns 컬럼 = 콤마 구분 TEXT (NULL 이면 전체 컬럼 → emptyList).
     * status 는 DB DEFAULT 'PENDING' 이지만 null safety 를 위해 fallback 처리한다.
     */
    private fun ExportJobsRecord.toExportJob(): ExportJob =
        ExportJob(
            id = ExportJobId(id),
            projectKey = projectKey,
            query = query,
            format = format,
            columns = columns?.toColumnsList() ?: emptyList(),
            requesterUserId = requesterUserId,
            status = enumValueOf(status ?: ExportJobStatus.PENDING.name),
            progress = progress ?: 0,
            rowCount = rowCount,
            resultObjectKey = resultObjectKey,
            errorCode = errorCode,
            expiresAt = expiresAt?.toInstant(),
            createdAt = (createdAt ?: error("export_jobs.created_at must not be null")).toInstant(),
            startedAt = startedAt?.toInstant(),
            completedAt = completedAt?.toInstant(),
        )

    companion object {
        /**
         * stale RUNNING 작업 재청 기준 (초).
         *
         * 이 시간이 경과했음에도 status=RUNNING 인 작업은 크래시로 방치된 것으로 간주하여
         * [claimForRun] 이 재선점을 허용한다.
         *
         * **산정 근거** — vt(visibility timeout) = 60초, threshold = vt × 5 = 300초.
         * 정상 처리 시간을 충분히 초과한 값으로 설정해 정상 처리 중 재청을 방지한다.
         */
        const val STALE_RUNNING_THRESHOLD_SECONDS: Long = 300L
    }
}

// ── file-level helpers ────────────────────────────────────────────────────────────

/** [Instant] 를 UTC [OffsetDateTime] 으로 변환한다. */
private fun Instant.toOffsetDateTime(): OffsetDateTime = OffsetDateTime.ofInstant(this, ZoneOffset.UTC)

/**
 * [List<String>] 을 콤마 구분 TEXT 로 직렬화한다.
 *
 * 빈 목록이면 null 반환 — DB 컬럼 NULL = "전체 컬럼" 의미.
 */
private fun List<String>.toColumnsString(): String? = if (isEmpty()) null else joinToString(",")

/**
 * 콤마 구분 TEXT 를 [List<String>] 으로 역직렬화한다.
 *
 * 빈 세그먼트는 제거한다 (trailing comma 방어).
 */
private fun String.toColumnsList(): List<String> = split(",").filter { it.isNotBlank() }
