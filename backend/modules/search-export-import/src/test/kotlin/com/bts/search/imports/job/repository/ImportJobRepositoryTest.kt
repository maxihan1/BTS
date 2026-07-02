// ImportJobRepository 통합 테스트 (FR-IM-01)
// 검증 범위: insert/findById/CAS(claimForRun/markCompleted/markFailed)/updateCounts/findByIdForRequester/findExpired

package com.bts.search.imports.job.repository

import com.bts.search.imports.job.domain.ImportJob
import com.bts.search.imports.job.domain.ImportJobId
import com.bts.search.imports.job.domain.ImportJobStatus
import com.bts.search.savedfilter.persistence.SearchPersistenceTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * ImportJobRepository 통합 테스트.
 *
 * Testcontainers (테스트용 DB 를 도커로 자동 실행하는 라이브러리) PG16-pgmq 위에서
 * Flyway V600~V604 마이그레이션 체인 적용 후 Repository 동작을 검증한다.
 *
 * 테스트 DSLContext 는 Spring 없이 수동 구성되므로 [SearchPersistenceTestBase] 의 [dsl] 을 재사용한다.
 * `export` BC [com.bts.search.export.job.repository.ExportJobRepositoryTest] 를 1:1 미러하되
 * import 전용 메서드(updateCounts, markCompleted 확장 파라미터)로 조정했다.
 *
 * ## 검증 범위
 *
 * - insert → findById 라운드트립 (모든 필드 일치)
 * - claimForRun: PENDING→RUNNING CAS, 동시 2호출 시 1성공, stale RUNNING 재청
 * - updateCounts: progress/totalRows/succeededRows/failedRows 갱신
 * - markCompleted: RUNNING→COMPLETED CAS (succeededRows/failedRows/errorLogObjectKey/expiresAt/completedAt 설정)
 * - markFailed: RUNNING/PENDING→FAILED CAS (errorCode/completedAt 설정)
 * - findByIdForRequester: 소유자 성공 / 타인 null
 * - findExpired: expires_at < now 포함 / expires_at NULL 제외 / 미래 expires_at 제외
 */
class ImportJobRepositoryTest : SearchPersistenceTestBase() {
    private val repo get() = ImportJobRepository(dsl)

    @AfterEach
    fun clean() {
        dsl.execute("DELETE FROM import_jobs")
    }

    @Suppress("LongParameterList")
    private fun makeJob(
        id: ImportJobId = ImportJobId(UUID.randomUUID()),
        projectKey: String = "ATLAS",
        format: String = "CSV",
        sourceObjectKey: String = "imports/raw/${UUID.randomUUID()}.csv",
        dryRun: Boolean = false,
        requesterUserId: UUID = UUID.randomUUID(),
    ): ImportJob =
        ImportJob(
            id = id,
            projectKey = projectKey,
            format = format,
            sourceObjectKey = sourceObjectKey,
            dryRun = dryRun,
            requesterUserId = requesterUserId,
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

    // ── insert + findById ────────────────────────────────────────────────────────

    @Test
    fun `insert 후 findById 로 조회 - 모든 필드 일치`() {
        val job = makeJob(format = "JSON", dryRun = true)
        val inserted = repo.insert(job)
        assertThat(inserted).isEqualTo(job)

        val found = repo.findById(job.id)
        assertThat(found).isNotNull()
        assertThat(found!!.id).isEqualTo(job.id)
        assertThat(found.projectKey).isEqualTo(job.projectKey)
        assertThat(found.format).isEqualTo("JSON")
        assertThat(found.sourceObjectKey).isEqualTo(job.sourceObjectKey)
        assertThat(found.dryRun).isTrue()
        assertThat(found.requesterUserId).isEqualTo(job.requesterUserId)
        assertThat(found.status).isEqualTo(ImportJobStatus.PENDING)
        assertThat(found.progress).isEqualTo(0)
        assertThat(found.totalRows).isNull()
        assertThat(found.succeededRows).isEqualTo(0L)
        assertThat(found.failedRows).isEqualTo(0L)
        assertThat(found.errorCode).isNull()
        assertThat(found.errorLogObjectKey).isNull()
        assertThat(found.expiresAt).isNull()
        assertThat(found.startedAt).isNull()
        assertThat(found.completedAt).isNull()
    }

    @Test
    fun `findById 는 존재하지 않는 id 에 대해 null 반환`() {
        assertThat(repo.findById(ImportJobId(UUID.randomUUID()))).isNull()
    }

    // ── findStatus ────────────────────────────────────────────────────────────────

    @Test
    fun `insert 후 findStatus 로 PENDING 조회`() {
        val job = makeJob()
        repo.insert(job)

        assertThat(repo.findStatus(job.id)).isEqualTo(ImportJobStatus.PENDING)
    }

    @Test
    fun `findStatus 는 존재하지 않는 id 에 대해 null 반환`() {
        assertThat(repo.findStatus(ImportJobId(UUID.randomUUID()))).isNull()
    }

    // ── claimForRun CAS ──────────────────────────────────────────────────────────

    @Test
    fun `claimForRun 은 PENDING 작업을 RUNNING 으로 전환하고 true 반환`() {
        val job = makeJob()
        repo.insert(job)

        assertThat(repo.claimForRun(job.id)).isTrue()
        assertThat(repo.findStatus(job.id)).isEqualTo(ImportJobStatus.RUNNING)
    }

    @Test
    fun `claimForRun 동시 2호출 시 1건만 성공 - 이미 RUNNING 이면 false 반환`() {
        val job = makeJob()
        repo.insert(job)

        val first = repo.claimForRun(job.id)
        val second = repo.claimForRun(job.id)

        assertThat(first).isTrue()
        assertThat(second).isFalse()
    }

    @Test
    fun `claimForRun 은 stale RUNNING 작업을 재청한다`() {
        val job = makeJob()
        val pastClock = Clock.fixed(Instant.parse("2020-01-01T00:00:00Z"), ZoneOffset.UTC)
        val repoWithPastClock = ImportJobRepository(dsl, pastClock)

        repo.insert(job)
        repoWithPastClock.claimForRun(job.id) // started_at = 과거 (stale)

        // 현재 시각 기준 repo 로 재청 → stale threshold 초과이므로 true
        assertThat(repo.claimForRun(job.id)).isTrue()
        assertThat(repo.findStatus(job.id)).isEqualTo(ImportJobStatus.RUNNING)
    }

    @Test
    fun `claimForRun 은 최근 started_at 가진 RUNNING 작업은 재청하지 않는다`() {
        val job = makeJob()
        repo.insert(job)
        repo.claimForRun(job.id) // started_at = 방금 (stale 아님)

        assertThat(repo.claimForRun(job.id)).isFalse()
    }

    // ── updateCounts ──────────────────────────────────────────────────────────────

    @Test
    fun `updateCounts 는 progress, totalRows, succeededRows, failedRows 를 갱신한다`() {
        val job = makeJob()
        repo.insert(job)
        repo.claimForRun(job.id)

        repo.updateCounts(job.id, progress = 50, totalRows = 1_000L, succeededRows = 480L, failedRows = 20L)

        val found = repo.findByIdForRequester(job.id, job.requesterUserId)
        assertThat(found).isNotNull()
        assertThat(found!!.progress).isEqualTo(50)
        assertThat(found.totalRows).isEqualTo(1_000L)
        assertThat(found.succeededRows).isEqualTo(480L)
        assertThat(found.failedRows).isEqualTo(20L)
    }

    // ── markCompleted CAS ─────────────────────────────────────────────────────────

    @Test
    fun `markCompleted 는 RUNNING 작업을 COMPLETED 로 전환하고 true 반환`() {
        val job = makeJob()
        repo.insert(job)
        repo.claimForRun(job.id)

        val expiresAt = Instant.now().plusSeconds(86_400)
        val result = repo.markCompleted(job.id, succeededRows = 900L, failedRows = 100L, "bucket/errors.csv", expiresAt)

        assertThat(result).isTrue()
        val found = repo.findByIdForRequester(job.id, job.requesterUserId)
        assertThat(found!!.status).isEqualTo(ImportJobStatus.COMPLETED)
        assertThat(found.succeededRows).isEqualTo(900L)
        assertThat(found.failedRows).isEqualTo(100L)
        assertThat(found.errorLogObjectKey).isEqualTo("bucket/errors.csv")
        assertThat(found.completedAt).isNotNull()
    }

    @Test
    fun `markCompleted 는 errorLogObjectKey null 도 허용한다 - 전건 성공`() {
        val job = makeJob()
        repo.insert(job)
        repo.claimForRun(job.id)

        val result = repo.markCompleted(job.id, succeededRows = 1_000L, failedRows = 0L, null, Instant.now().plusSeconds(1))

        assertThat(result).isTrue()
        assertThat(repo.findByIdForRequester(job.id, job.requesterUserId)!!.errorLogObjectKey).isNull()
    }

    @Test
    fun `markCompleted 는 PENDING 작업에 대해 false 반환 - RUNNING 아님`() {
        val job = makeJob()
        repo.insert(job)

        assertThat(repo.markCompleted(job.id, 1L, 0L, null, Instant.now().plusSeconds(1))).isFalse()
        assertThat(repo.findStatus(job.id)).isEqualTo(ImportJobStatus.PENDING)
    }

    @Test
    fun `markCompleted 는 이미 COMPLETED 인 작업에 대해 false 반환 - 1회 보장`() {
        val job = makeJob()
        repo.insert(job)
        repo.claimForRun(job.id)
        repo.markCompleted(job.id, 1L, 0L, null, Instant.now().plusSeconds(1))

        assertThat(repo.markCompleted(job.id, 2L, 0L, null, Instant.now().plusSeconds(2))).isFalse()
    }

    // ── markFailed CAS ────────────────────────────────────────────────────────────

    @Test
    fun `markFailed 는 RUNNING 작업을 FAILED 로 전환하고 true 반환`() {
        val job = makeJob()
        repo.insert(job)
        repo.claimForRun(job.id)

        assertThat(repo.markFailed(job.id, "IMPORT_INTERNAL_ERROR")).isTrue()
        val found = repo.findByIdForRequester(job.id, job.requesterUserId)
        assertThat(found!!.status).isEqualTo(ImportJobStatus.FAILED)
        assertThat(found.errorCode).isEqualTo("IMPORT_INTERNAL_ERROR")
        assertThat(found.completedAt).isNotNull()
    }

    @Test
    fun `markFailed 는 PENDING 작업을 FAILED 로 전환하고 true 반환`() {
        val job = makeJob()
        repo.insert(job)

        assertThat(repo.markFailed(job.id, "IMPORT_INTERNAL_ERROR")).isTrue()
        assertThat(repo.findStatus(job.id)).isEqualTo(ImportJobStatus.FAILED)
    }

    @Test
    fun `markFailed 는 이미 COMPLETED 인 작업에 대해 false 반환`() {
        val job = makeJob()
        repo.insert(job)
        repo.claimForRun(job.id)
        repo.markCompleted(job.id, 1L, 0L, null, Instant.now().plusSeconds(1))

        assertThat(repo.markFailed(job.id, "IMPORT_INTERNAL_ERROR")).isFalse()
        assertThat(repo.findStatus(job.id)).isEqualTo(ImportJobStatus.COMPLETED)
    }

    // ── findByIdForRequester ───────────────────────────────────────────────────────

    @Test
    fun `findByIdForRequester 는 소유자 조회 시 ImportJob 반환`() {
        val job = makeJob()
        repo.insert(job)

        val found = repo.findByIdForRequester(job.id, job.requesterUserId)
        assertThat(found).isNotNull()
        assertThat(found!!.id).isEqualTo(job.id)
        assertThat(found.projectKey).isEqualTo(job.projectKey)
        assertThat(found.status).isEqualTo(ImportJobStatus.PENDING)
    }

    @Test
    fun `findByIdForRequester 는 타인 조회 시 null 반환`() {
        val job = makeJob()
        repo.insert(job)

        assertThat(repo.findByIdForRequester(job.id, UUID.randomUUID())).isNull()
    }

    @Test
    fun `findByIdForRequester 는 존재하지 않는 id 에 대해 null 반환`() {
        assertThat(repo.findByIdForRequester(ImportJobId(UUID.randomUUID()), UUID.randomUUID())).isNull()
    }

    // ── findExpired ───────────────────────────────────────────────────────────────

    @Test
    fun `findExpired 는 expires_at 이 now 보다 이전인 작업을 반환`() {
        val job = makeJob()
        repo.insert(job)
        repo.claimForRun(job.id)
        // markCompleted 로 expires_at = 1초 전
        repo.markCompleted(job.id, 1L, 0L, null, Instant.now().minusSeconds(1))

        val expired = repo.findExpired(Instant.now())
        assertThat(expired.map { it.id }).contains(job.id)
    }

    @Test
    fun `findExpired 는 expires_at 이 null 인 작업을 제외`() {
        val job = makeJob()
        repo.insert(job) // expires_at = null

        val expired = repo.findExpired(Instant.now().plusSeconds(3_600))
        assertThat(expired.map { it.id }).doesNotContain(job.id)
    }

    @Test
    fun `findExpired 는 미래 expires_at 가진 작업을 제외`() {
        val job = makeJob()
        repo.insert(job)
        repo.claimForRun(job.id)
        repo.markCompleted(job.id, 1L, 0L, null, Instant.now().plusSeconds(86_400))

        val expired = repo.findExpired(Instant.now())
        assertThat(expired.map { it.id }).doesNotContain(job.id)
    }
}
