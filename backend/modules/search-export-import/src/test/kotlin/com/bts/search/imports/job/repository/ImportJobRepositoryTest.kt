// ImportJobRepository 통합 테스트 (FR-IM-01, FR-IM-02)
// 검증 범위: insert/findById/CAS(claimForRun/markCompleted/markFailed)/updateCounts/findByIdForRequester/findExpired
//           /transitionToPending(FR-IM-02)/deleteIfExpired(FR-IM-02)

package com.bts.search.imports.job.repository

import com.bts.search.imports.job.domain.ImportJob
import com.bts.search.imports.job.domain.ImportJobId
import com.bts.search.imports.job.domain.ImportJobStatus
import com.bts.search.jooq.tables.references.IMPORT_JOBS
import com.bts.search.savedfilter.persistence.SearchPersistenceTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * ImportJobRepository 통합 테스트.
 *
 * Testcontainers (테스트용 DB 를 도커로 자동 실행하는 라이브러리) PG16-pgmq 위에서
 * Flyway V600~V605 마이그레이션 체인 적용 후 Repository 동작을 검증한다.
 *
 * 테스트 DSLContext 는 Spring 없이 수동 구성되므로 [SearchPersistenceTestBase] 의 [dsl] 을 재사용한다.
 * `export` BC [com.bts.search.export.job.repository.ExportJobRepositoryTest] 를 1:1 미러하되
 * import 전용 메서드(updateCounts, markCompleted 확장 파라미터)로 조정했다.
 *
 * ## 검증 범위
 *
 * - insert → findById 라운드트립 (모든 필드 일치, attachmentsObjectKey 값/null 포함 — V605)
 * - claimForRun: PENDING→RUNNING CAS, 동시 2호출 시 1성공, stale RUNNING 재청
 * - updateCounts: progress/totalRows/succeededRows/failedRows 갱신
 * - markCompleted: RUNNING→COMPLETED CAS (succeededRows/failedRows/errorLogObjectKey/expiresAt/completedAt 설정)
 * - markFailed: RUNNING/PENDING→FAILED CAS (errorCode/completedAt 설정)
 * - findByIdForRequester: 소유자 성공 / 타인 null
 * - findExpired: expires_at < now 포함 / expires_at NULL 제외 / 미래 expires_at 제외 / AWAITING_MAPPING 도 포함(status 무관)
 * - transitionToPending(FR-IM-02): AWAITING_MAPPING→PENDING CAS + expires_at=NULL + dry_run 확정
 *   (confirm 파라미터 영속, dryrun-fix) / 다른 상태 false(멱등)
 * - deleteIfExpired(FR-IM-02): expires_at 지난 행만 가드 삭제 / NULL·미래 expires_at 은 보존
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
        attachmentsObjectKey: String? = null,
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
            attachmentsObjectKey = attachmentsObjectKey,
            expiresAt = null,
            createdAt = Instant.now(),
            startedAt = null,
            completedAt = null,
        )

    /**
     * status/expires_at/dry_run 을 직접 지정해 import_jobs 행을 시드한다 (FR-IM-02).
     *
     * [ImportJobRepository.insert] 는 접수 시점 expires_at 을 세팅하지 않으므로,
     * AWAITING_MAPPING TTL·만료·확정 레이스 시나리오는 dsl 로 직접 시드한다.
     * [dryRun] 은 [ImportJobRepository.transitionToPending] 의 dry_run 확정 검증에서
     * 시드 값과 다른 값으로 전이해 실제로 컬럼이 갱신됨(기본값이 아님)을 확인하는 데 쓴다.
     */
    private fun seedJob(
        status: ImportJobStatus,
        expiresAt: Instant?,
        id: ImportJobId = ImportJobId(UUID.randomUUID()),
        dryRun: Boolean = false,
    ): ImportJobId {
        dsl.insertInto(IMPORT_JOBS)
            .set(IMPORT_JOBS.ID, id.value)
            .set(IMPORT_JOBS.PROJECT_KEY, "ATLAS")
            .set(IMPORT_JOBS.FORMAT, "CSV")
            .set(IMPORT_JOBS.SOURCE_OBJECT_KEY, "imports/raw/${UUID.randomUUID()}.csv")
            .set(IMPORT_JOBS.REQUESTER_USER_ID, UUID.randomUUID())
            .set(IMPORT_JOBS.STATUS, status.name)
            .set(IMPORT_JOBS.EXPIRES_AT, expiresAt?.let { OffsetDateTime.ofInstant(it, ZoneOffset.UTC) })
            .set(IMPORT_JOBS.DRY_RUN, dryRun)
            .execute()
        return id
    }

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
        assertThat(found.attachmentsObjectKey).isNull()
        assertThat(found.expiresAt).isNull()
        assertThat(found.startedAt).isNull()
        assertThat(found.completedAt).isNull()
    }

    @Test
    fun `findById 는 존재하지 않는 id 에 대해 null 반환`() {
        assertThat(repo.findById(ImportJobId(UUID.randomUUID()))).isNull()
    }

    // ── attachmentsObjectKey round-trip (V605) ──────────────────────────────────

    @Test
    fun `insert 후 findById 로 조회 - attachmentsObjectKey 값이 있으면 그대로 라운드트립`() {
        val job = makeJob(attachmentsObjectKey = "imports/atlas/${UUID.randomUUID()}-attachments.zip")
        repo.insert(job)

        val found = repo.findById(job.id)
        assertThat(found).isNotNull()
        assertThat(found!!.attachmentsObjectKey).isEqualTo(job.attachmentsObjectKey)
    }

    @Test
    fun `insert 후 findById 로 조회 - attachmentsObjectKey 미지정이면 null 라운드트립`() {
        val job = makeJob(attachmentsObjectKey = null)
        repo.insert(job)

        val found = repo.findById(job.id)
        assertThat(found).isNotNull()
        assertThat(found!!.attachmentsObjectKey).isNull()
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

        val result =
            repo.markCompleted(job.id, succeededRows = 1_000L, failedRows = 0L, null, Instant.now().plusSeconds(1))

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

    @Test
    fun `findExpired 는 만료된 AWAITING_MAPPING 작업도 반환 - status 무관`() {
        val id = seedJob(ImportJobStatus.AWAITING_MAPPING, Instant.now().minusSeconds(1))

        val expired = repo.findExpired(Instant.now())
        assertThat(expired.map { it.id }).contains(id)
    }

    // ── transitionToPending CAS (FR-IM-02) ──────────────────────────────────────────

    @Test
    fun `transitionToPending 은 AWAITING_MAPPING 작업을 PENDING 으로 전환하고 expires_at 을 NULL 로 만든 뒤 true 반환`() {
        val id = seedJob(ImportJobStatus.AWAITING_MAPPING, Instant.now().plusSeconds(3_600))

        assertThat(repo.transitionToPending(id, dryRun = false)).isTrue()
        val found = repo.findById(id)
        assertThat(found).isNotNull()
        assertThat(found!!.status).isEqualTo(ImportJobStatus.PENDING)
        assertThat(found.expiresAt).isNull()
    }

    @Test
    fun `transitionToPending 은 이미 PENDING 인 작업에 대해 false 반환 - 멱등`() {
        val id = seedJob(ImportJobStatus.PENDING, null)

        assertThat(repo.transitionToPending(id, dryRun = false)).isFalse()
        assertThat(repo.findStatus(id)).isEqualTo(ImportJobStatus.PENDING)
    }

    @Test
    fun `transitionToPending 은 RUNNING 작업에 대해 false 반환 - AWAITING_MAPPING 아님`() {
        val id = seedJob(ImportJobStatus.RUNNING, null)

        assertThat(repo.transitionToPending(id, dryRun = false)).isFalse()
        assertThat(repo.findStatus(id)).isEqualTo(ImportJobStatus.RUNNING)
    }

    @Test
    fun `transitionToPending 은 dryRun=true 전달 시 dry_run 컬럼을 true 로 확정한다`() {
        // 시드는 dry_run=false — 전이 호출이 실제로 컬럼을 갱신함(단순 유지가 아님)을 확인한다.
        val id = seedJob(ImportJobStatus.AWAITING_MAPPING, Instant.now().plusSeconds(3_600), dryRun = false)

        assertThat(repo.transitionToPending(id, dryRun = true)).isTrue()
        val found = repo.findById(id)
        assertThat(found).isNotNull()
        assertThat(found!!.status).isEqualTo(ImportJobStatus.PENDING)
        assertThat(found.expiresAt).isNull()
        assertThat(found.dryRun).isTrue()
    }

    @Test
    fun `transitionToPending 은 dryRun=false 전달 시 dry_run 컬럼을 false 로 확정한다`() {
        // 시드는 dry_run=true — 전이 호출이 실제로 false 로 덮어씀을 확인한다.
        val id = seedJob(ImportJobStatus.AWAITING_MAPPING, Instant.now().plusSeconds(3_600), dryRun = true)

        assertThat(repo.transitionToPending(id, dryRun = false)).isTrue()
        val found = repo.findById(id)
        assertThat(found).isNotNull()
        assertThat(found!!.dryRun).isFalse()
    }

    // ── deleteIfExpired 가드 삭제 (FR-IM-02) ─────────────────────────────────────────

    @Test
    fun `deleteIfExpired 는 expires_at 지난 행을 삭제하고 true 반환`() {
        val id = seedJob(ImportJobStatus.AWAITING_MAPPING, Instant.now().minusSeconds(1))

        assertThat(repo.deleteIfExpired(id, Instant.now())).isTrue()
        assertThat(repo.findById(id)).isNull()
    }

    @Test
    fun `deleteIfExpired 는 expires_at 이 NULL 인 확정 작업을 삭제하지 않고 false 반환`() {
        // confirm(transitionToPending) 으로 expires_at 이 NULL 이 된 job — cleanup 레이스에서 보존.
        val id = seedJob(ImportJobStatus.PENDING, null)

        assertThat(repo.deleteIfExpired(id, Instant.now())).isFalse()
        assertThat(repo.findById(id)).isNotNull()
    }

    @Test
    fun `deleteIfExpired 는 미래 expires_at 행을 삭제하지 않고 false 반환`() {
        val id = seedJob(ImportJobStatus.AWAITING_MAPPING, Instant.now().plusSeconds(3_600))

        assertThat(repo.deleteIfExpired(id, Instant.now())).isFalse()
        assertThat(repo.findById(id)).isNotNull()
    }
}
