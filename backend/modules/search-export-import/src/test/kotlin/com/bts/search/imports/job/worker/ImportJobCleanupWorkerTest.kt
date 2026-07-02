// ImportJobCleanupWorker 통합 테스트 — 실 DB(Testcontainers) 위에서 TTL 만료 원본/에러로그 삭제 + DB 하드삭제 검증 (FR-IM-01 PR1 Task 10)

package com.bts.search.imports.job.worker

import com.bts.search.imports.job.domain.ImportJob
import com.bts.search.imports.job.domain.ImportJobId
import com.bts.search.imports.job.domain.ImportJobStatus
import com.bts.search.imports.job.repository.ImportJobRepository
import com.bts.search.imports.job.storage.ImportObjectStoragePort
import com.bts.search.savedfilter.persistence.SearchPersistenceTestBase
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * ImportJobCleanupWorker 통합 테스트.
 *
 * `export` BC [com.bts.search.export.job.worker.ExportJobCleanupWorkerTest] 를 시나리오 구조상
 * 미러하되, 실 [ImportJobRepository]([SearchPersistenceTestBase.dsl] 기반)를 사용해 DB 하드삭제가
 * 실제로 일어나는지(`findById` 재조회로) 직접 단언한다. [ImportObjectStoragePort] 만 MockK 로
 * 대체한다(MinIO I/O 자체는 [com.bts.search.imports.job.storage.MinioImportStorageAdapterTest] 책임).
 *
 * ## vacuous 금지 (CONCERN #4)
 * repository 를 MockK 로 스텁하면 `deleteById` SQL 자체(하드삭제 여부)가 검증되지 않는다.
 * 이 테스트는 `cleanupExpired()` 실행 후 `repo.findById(job.id)` 재조회로 행이 실제로
 * 사라졌는지 DB 상태로 직접 확인한다.
 *
 * ## Clock.fixed — time-bomb 방지
 * 기준 시각을 2027-01-01(현재 실행 시점보다 명백히 미래)로 고정한다.
 * 만료 작업의 expires_at 을 이 기준 이전으로 설정해, 실제 시스템 시각이 아니라
 * **주입된 [Clock] 을 사용해 만료를 판정**하는지 검증한다(learnings: authcontroller-revokesession-timebomb).
 */
class ImportJobCleanupWorkerTest : SearchPersistenceTestBase() {
    private val repo get() = ImportJobRepository(dsl)
    private val storage = mockk<ImportObjectStoragePort>()

    /** 고정 현재 시각 — 테스트 실행 시점(2026-xx)보다 명백히 미래인 2027-01-01. */
    private val fixedNow: Instant = Instant.parse("2027-01-01T00:00:00Z")
    private val fixedClock: Clock = Clock.fixed(fixedNow, ZoneOffset.UTC)
    private val cleanupWorker get() = ImportJobCleanupWorker(repo, storage, fixedClock)

    @AfterEach
    fun clean() {
        dsl.execute("DELETE FROM import_jobs")
    }

    private fun insertCompletedJob(
        expiresAt: Instant,
        errorLogObjectKey: String?,
    ): ImportJob {
        val job =
            ImportJob(
                id = ImportJobId(UUID.randomUUID()),
                projectKey = "ATLAS",
                format = "CSV",
                sourceObjectKey = "imports/raw/${UUID.randomUUID()}.csv",
                dryRun = false,
                requesterUserId = UUID.randomUUID(),
                status = ImportJobStatus.PENDING,
                progress = 0,
                totalRows = null,
                succeededRows = 0,
                failedRows = 0,
                errorCode = null,
                errorLogObjectKey = null,
                expiresAt = null,
                createdAt = Instant.now(),
                startedAt = null,
                completedAt = null,
            )
        repo.insert(job)
        repo.claimForRun(job.id)
        repo.markCompleted(job.id, succeededRows = 900L, failedRows = 100L, errorLogObjectKey, expiresAt)
        return repo.findById(job.id) ?: error("insertCompletedJob: job not found after markCompleted")
    }

    // ── (a) 만료 작업 없음 ────────────────────────────────────────────────────────

    @Test
    fun `만료 작업이 없을 때 storage delete 와 DB 삭제를 호출하지 않는다`() {
        cleanupWorker.cleanupExpired()

        verify(exactly = 0) { storage.delete(any()) }
    }

    // ── (b) 만료 작업(원본+에러로그) ──────────────────────────────────────────────

    @Test
    fun `만료 작업(원본+에러로그 둘 다 존재) - MinIO 원본과 에러로그를 모두 삭제하고 DB 행을 하드삭제한다`() {
        val errorLogKey = "ATLAS/errors.csv"
        val job = insertCompletedJob(expiresAt = fixedNow.minusSeconds(1), errorLogObjectKey = errorLogKey)
        justRun { storage.delete(any()) }

        cleanupWorker.cleanupExpired()

        verify(exactly = 1) { storage.delete(job.sourceObjectKey) }
        verify(exactly = 1) { storage.delete(errorLogKey) }
        assertThat(repo.findById(job.id)).isNull()
    }

    // ── (c) errorLogObjectKey null — 원본만 삭제 ─────────────────────────────────

    @Test
    fun `errorLogObjectKey 가 null 인 만료 작업 - 원본만 삭제하고 DB 행을 하드삭제한다`() {
        val job = insertCompletedJob(expiresAt = fixedNow.minusSeconds(1), errorLogObjectKey = null)
        justRun { storage.delete(any()) }

        cleanupWorker.cleanupExpired()

        verify(exactly = 1) { storage.delete(job.sourceObjectKey) }
        verify(exactly = 1) { storage.delete(any()) }
        assertThat(repo.findById(job.id)).isNull()
    }

    // ── (d) storage.delete 예외 — best-effort ────────────────────────────────────

    @Test
    fun `원본 storage delete 가 예외를 던져도 에러로그 삭제와 DB 하드삭제를 계속 진행한다`() {
        val errorLogKey = "ATLAS/errors-best-effort.csv"
        val job = insertCompletedJob(expiresAt = fixedNow.minusSeconds(1), errorLogObjectKey = errorLogKey)
        every { storage.delete(job.sourceObjectKey) } throws RuntimeException("MinIO 연결 오류")
        justRun { storage.delete(errorLogKey) }

        cleanupWorker.cleanupExpired()

        verify(exactly = 1) { storage.delete(errorLogKey) }
        assertThat(repo.findById(job.id)).isNull()
    }

    // ── (e) 미래 expires_at — 정리 대상 제외 ──────────────────────────────────────

    @Test
    fun `미래 expires_at 를 가진 작업은 정리 대상에서 제외된다`() {
        val job = insertCompletedJob(expiresAt = fixedNow.plusSeconds(3_600), errorLogObjectKey = null)

        cleanupWorker.cleanupExpired()

        verify(exactly = 0) { storage.delete(any()) }
        assertThat(repo.findById(job.id)).isNotNull()
    }

    // ── (f) Clock.fixed — time-bomb 방지 ─────────────────────────────────────────

    @Test
    fun `Clock fixed 로 고정된 기준 시각을 사용한다 - 만료(fixedNow 이전)만 삭제, 미만료(fixedNow 이후)는 보존`() {
        justRun { storage.delete(any()) }
        val expiredJob = insertCompletedJob(expiresAt = fixedNow.minusSeconds(1), errorLogObjectKey = null)
        val notExpiredJob = insertCompletedJob(expiresAt = fixedNow.plusSeconds(1), errorLogObjectKey = null)

        cleanupWorker.cleanupExpired()

        assertThat(repo.findById(expiredJob.id))
            .describedAs("fixedNow 이전 만료 작업은 주입된 Clock 기준으로 삭제되어야 한다")
            .isNull()
        assertThat(repo.findById(notExpiredJob.id))
            .describedAs("fixedNow 이후 만료 작업은 보존되어야 한다")
            .isNotNull()
    }
}
