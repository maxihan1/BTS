// ExportJobRepository 통합 테스트 (FR-EX-02)
// 검증 범위: insert/CAS(claimForRun/markCompleted/markFailed)/updateProgress/findByIdForRequester/findExpired/deleteById

package com.bts.search.export.job.repository

import com.bts.search.export.job.domain.ExportJob
import com.bts.search.export.job.domain.ExportJobId
import com.bts.search.export.job.domain.ExportJobStatus
import com.bts.search.savedfilter.persistence.SearchPersistenceTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * ExportJobRepository 통합 테스트.
 *
 * Testcontainers (테스트용 DB 를 도커로 자동 실행하는 라이브러리) PG16-pgmq 위에서
 * Flyway V600~V602 마이그레이션 체인 적용 후 Repository 동작을 검증한다.
 *
 * 테스트 DSLContext 는 Spring 없이 수동 구성되므로 [SearchPersistenceTestBase] 의 [dsl] 을 재사용한다.
 *
 * ## 검증 범위
 *
 * - insert → findStatus PENDING 라운드트립
 * - claimForRun: PENDING→RUNNING CAS, 동시 2호출 시 1성공, stale RUNNING 재청
 * - updateProgress: progress/row_count 갱신
 * - markCompleted: RUNNING→COMPLETED CAS (resultObjectKey/expiresAt/completedAt 설정)
 * - markFailed: RUNNING/PENDING→FAILED CAS (errorCode/completedAt 설정)
 * - findByIdForRequester: 소유자 성공 / 타인 null
 * - findExpired: expires_at < now 포함 / expires_at NULL 제외 / 미래 expires_at 제외
 * - deleteById: 하드삭제 후 findStatus = null
 * - columns round-trip: 빈 목록 ↔ null / 비어있지 않은 목록 ↔ 콤마 구분 문자열
 */
class ExportJobRepositoryTest : SearchPersistenceTestBase() {
    private val repo get() = ExportJobRepository(dsl)

    @AfterEach
    fun clean() {
        dsl.execute("DELETE FROM export_jobs")
    }

    @Suppress("LongParameterList")
    private fun makeJob(
        id: ExportJobId = ExportJobId(UUID.randomUUID()),
        projectKey: String = "ATLAS",
        query: String = "status = OPEN",
        format: String = "CSV",
        columns: List<String> = emptyList(),
        requesterUserId: UUID = UUID.randomUUID(),
    ): ExportJob =
        ExportJob(
            id = id,
            projectKey = projectKey,
            query = query,
            format = format,
            columns = columns,
            requesterUserId = requesterUserId,
            status = ExportJobStatus.PENDING,
            progress = 0,
            rowCount = null,
            resultObjectKey = null,
            errorCode = null,
            expiresAt = null,
            createdAt = Instant.now(),
            startedAt = null,
            completedAt = null,
        )

    // ── insert + findStatus ──────────────────────────────────────────────────────

    @Test
    fun `insert 후 findStatus 로 PENDING 조회`() {
        val job = makeJob()
        repo.insert(job)

        assertThat(repo.findStatus(job.id)).isEqualTo(ExportJobStatus.PENDING)
    }

    @Test
    fun `findStatus 는 존재하지 않는 id 에 대해 null 반환`() {
        assertThat(repo.findStatus(ExportJobId(UUID.randomUUID()))).isNull()
    }

    // ── claimForRun CAS ──────────────────────────────────────────────────────────

    @Test
    fun `claimForRun 은 PENDING 작업을 RUNNING 으로 전환하고 true 반환`() {
        val job = makeJob()
        repo.insert(job)

        assertThat(repo.claimForRun(job.id)).isTrue()
        assertThat(repo.findStatus(job.id)).isEqualTo(ExportJobStatus.RUNNING)
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
        val repoWithPastClock = ExportJobRepository(dsl, pastClock)

        repo.insert(job)
        repoWithPastClock.claimForRun(job.id) // started_at = 과거 (stale)

        // 현재 시각 기준 repo 로 재청 → stale threshold 초과이므로 true
        assertThat(repo.claimForRun(job.id)).isTrue()
        assertThat(repo.findStatus(job.id)).isEqualTo(ExportJobStatus.RUNNING)
    }

    @Test
    fun `claimForRun 은 최근 started_at 가진 RUNNING 작업은 재청하지 않는다`() {
        val job = makeJob()
        repo.insert(job)
        repo.claimForRun(job.id) // started_at = 방금 (stale 아님)

        assertThat(repo.claimForRun(job.id)).isFalse()
    }

    // ── updateProgress ────────────────────────────────────────────────────────────

    @Test
    fun `updateProgress 는 progress 와 row_count 를 갱신한다`() {
        val job = makeJob()
        repo.insert(job)
        repo.claimForRun(job.id)

        repo.updateProgress(job.id, percent = 50, rowCount = 5_000L)

        val found = repo.findByIdForRequester(job.id, job.requesterUserId)
        assertThat(found).isNotNull()
        assertThat(found!!.progress).isEqualTo(50)
        assertThat(found.rowCount).isEqualTo(5_000L)
    }

    // ── markCompleted CAS ─────────────────────────────────────────────────────────

    @Test
    fun `markCompleted 는 RUNNING 작업을 COMPLETED 로 전환하고 true 반환`() {
        val job = makeJob()
        repo.insert(job)
        repo.claimForRun(job.id)

        val expiresAt = Instant.now().plusSeconds(86_400)
        val result = repo.markCompleted(job.id, "bucket/key.csv", expiresAt)

        assertThat(result).isTrue()
        assertThat(repo.findStatus(job.id)).isEqualTo(ExportJobStatus.COMPLETED)
    }

    @Test
    fun `markCompleted 는 PENDING 작업에 대해 false 반환 - RUNNING 아님`() {
        val job = makeJob()
        repo.insert(job)

        assertThat(repo.markCompleted(job.id, "key", Instant.now().plusSeconds(1))).isFalse()
        assertThat(repo.findStatus(job.id)).isEqualTo(ExportJobStatus.PENDING)
    }

    @Test
    fun `markCompleted 는 이미 COMPLETED 인 작업에 대해 false 반환 - 1회 보장`() {
        val job = makeJob()
        repo.insert(job)
        repo.claimForRun(job.id)
        repo.markCompleted(job.id, "key", Instant.now().plusSeconds(1))

        assertThat(repo.markCompleted(job.id, "key2", Instant.now().plusSeconds(2))).isFalse()
    }

    // ── markFailed CAS ────────────────────────────────────────────────────────────

    @Test
    fun `markFailed 는 RUNNING 작업을 FAILED 로 전환하고 true 반환`() {
        val job = makeJob()
        repo.insert(job)
        repo.claimForRun(job.id)

        assertThat(repo.markFailed(job.id, "SEARCH_INTERNAL_ERROR")).isTrue()
        assertThat(repo.findStatus(job.id)).isEqualTo(ExportJobStatus.FAILED)
    }

    @Test
    fun `markFailed 는 PENDING 작업을 FAILED 로 전환하고 true 반환`() {
        val job = makeJob()
        repo.insert(job)

        assertThat(repo.markFailed(job.id, "SEARCH_INTERNAL_ERROR")).isTrue()
        assertThat(repo.findStatus(job.id)).isEqualTo(ExportJobStatus.FAILED)
    }

    @Test
    fun `markFailed 는 이미 COMPLETED 인 작업에 대해 false 반환`() {
        val job = makeJob()
        repo.insert(job)
        repo.claimForRun(job.id)
        repo.markCompleted(job.id, "key", Instant.now().plusSeconds(1))

        assertThat(repo.markFailed(job.id, "ERROR")).isFalse()
        assertThat(repo.findStatus(job.id)).isEqualTo(ExportJobStatus.COMPLETED)
    }

    // ── findByIdForRequester ───────────────────────────────────────────────────────

    @Test
    fun `findByIdForRequester 는 소유자 조회 시 ExportJob 반환`() {
        val job = makeJob(columns = listOf("title", "status"))
        repo.insert(job)

        val found = repo.findByIdForRequester(job.id, job.requesterUserId)
        assertThat(found).isNotNull()
        assertThat(found!!.id).isEqualTo(job.id)
        assertThat(found.projectKey).isEqualTo(job.projectKey)
        assertThat(found.columns).containsExactly("title", "status")
        assertThat(found.status).isEqualTo(ExportJobStatus.PENDING)
    }

    @Test
    fun `findByIdForRequester 는 타인 조회 시 null 반환`() {
        val job = makeJob()
        repo.insert(job)

        assertThat(repo.findByIdForRequester(job.id, UUID.randomUUID())).isNull()
    }

    @Test
    fun `findByIdForRequester 는 존재하지 않는 id 에 대해 null 반환`() {
        assertThat(repo.findByIdForRequester(ExportJobId(UUID.randomUUID()), UUID.randomUUID())).isNull()
    }

    // ── findExpired ───────────────────────────────────────────────────────────────

    @Test
    fun `findExpired 는 expires_at 이 now 보다 이전인 작업을 반환`() {
        val job = makeJob()
        repo.insert(job)
        repo.claimForRun(job.id)
        // markCompleted 로 expires_at = 1초 전
        repo.markCompleted(job.id, "key", Instant.now().minusSeconds(1))

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
        repo.markCompleted(job.id, "key", Instant.now().plusSeconds(86_400))

        val expired = repo.findExpired(Instant.now())
        assertThat(expired.map { it.id }).doesNotContain(job.id)
    }

    // ── deleteById ────────────────────────────────────────────────────────────────

    @Test
    fun `deleteById 는 작업을 하드삭제하고 findStatus 가 null 반환`() {
        val job = makeJob()
        repo.insert(job)

        repo.deleteById(job.id)

        assertThat(repo.findStatus(job.id)).isNull()
    }

    // ── columns round-trip ────────────────────────────────────────────────────────

    @Test
    fun `빈 columns 는 DB null 로 저장되고 빈 목록으로 복원된다`() {
        val job = makeJob(columns = emptyList())
        repo.insert(job)

        val found = repo.findByIdForRequester(job.id, job.requesterUserId)
        assertThat(found!!.columns).isEmpty()
    }

    @Test
    fun `비어있지 않은 columns 는 콤마 구분 저장 후 원래 목록으로 복원된다`() {
        val cols = listOf("title", "status", "assignee")
        val job = makeJob(columns = cols)
        repo.insert(job)

        val found = repo.findByIdForRequester(job.id, job.requesterUserId)
        assertThat(found!!.columns).containsExactlyElementsOf(cols)
    }
}
