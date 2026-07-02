// ImportJobWorker 통합 테스트 — 실 pgmq 큐(Testcontainers) 위에서 폴링/CAS클레임/삭제/poison/dead-letter 검증 (FR-IM-01 PR1 Task 10)

package com.bts.search.imports.job.worker

import com.bts.search.imports.job.application.ImportJobProcessor
import com.bts.search.imports.job.domain.ImportJob
import com.bts.search.imports.job.domain.ImportJobId
import com.bts.search.imports.job.domain.ImportJobStatus
import com.bts.search.imports.job.event.ImportJobEnqueuePublisher
import com.bts.search.imports.job.repository.ImportJobRepository
import com.bts.search.savedfilter.persistence.SearchPersistenceTestBase
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * ImportJobWorker 통합 테스트.
 *
 * `export` BC [com.bts.search.export.job.worker.ExportJobWorkerTest] 를 시나리오 구조상 미러하되,
 * **MockK 로 `DSLContext.fetch/execute` 를 스텁하는 대신 Testcontainers 실 pgmq 큐를 사용**한다.
 *
 * ## vacuous 금지 (CONCERN #4)
 * `dsl.fetch`/`dsl.execute` 를 MockK 로 스텁하면 SQL 문 자체의 정확성(큐 이름, VT, 파라미터
 * 바인딩 순서)이 검증되지 않은 채로 통과할 수 있다. 이 테스트는 [SearchPersistenceTestBase] 의
 * 실 pgmq 컨테이너 위에서 [ImportJobEnqueuePublisher] 로 실제 메시지를 발행하고,
 * `pgmq.metrics(queue)` 로 큐 길이(가시+비가시 포함)를 조회해 "메시지가 실제로 삭제/보관/유지됐는지"를
 * DB 메타데이터로 직접 단언한다. [ImportJobProcessor] 만 MockK 로 대체한다(행 파싱/이슈 생성은
 * Task 9 [com.bts.search.imports.job.application.ImportJobProcessorTest] 책임 범위).
 *
 * ## Spring 컨텍스트 없이 직접 인스턴스화
 * [ImportJobRepository], [ImportJobEnqueuePublisher], [ImportJobWorker] 를 [SearchPersistenceTestBase.dsl]
 * 로 직접 생성한다. `@Transactional` 어노테이션은 Spring 프록시 미적용으로 무시되며 모든 SQL 은
 * auto-commit 모드로 실행된다 (`ExportJobEndToEndIntegrationTest` 동일 패턴).
 */
class ImportJobWorkerTest : SearchPersistenceTestBase() {
    private val repo get() = ImportJobRepository(dsl)
    private val publisher get() = ImportJobEnqueuePublisher(dsl)
    private val processor = mockk<ImportJobProcessor>()
    private val worker get() = ImportJobWorker(dsl, repo, processor)

    @AfterEach
    fun clean() {
        dsl.execute("DELETE FROM import_jobs")
        runCatching { dsl.execute("SELECT pgmq.purge_queue(?)", ImportJobWorker.QUEUE_NAME) }
    }

    private fun makeJob(
        id: ImportJobId = ImportJobId(UUID.randomUUID()),
        projectKey: String = "ATLAS",
    ): ImportJob =
        ImportJob(
            id = id,
            projectKey = projectKey,
            format = "CSV",
            sourceObjectKey = "imports/raw/${id.value}.csv",
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

    /** [ImportJobWorker.QUEUE_NAME] 의 pgmq 큐 길이(가시+비가시 메시지 총합)를 조회한다. */
    private fun pgmqQueueLength(): Long =
        dsl.fetchOne("SELECT queue_length FROM pgmq.metrics(?)", ImportJobWorker.QUEUE_NAME)!!
            .get(0, Long::class.java)

    // ── (a) 정상 흐름 — enqueue→poll→claim→process→delete ───────────────────────

    @Test
    fun `enqueue 후 poll 하면 claimForRun 성공 시 processor 를 호출하고 성공 후 메시지를 삭제한다`() {
        val job = makeJob()
        repo.insert(job)
        publisher.enqueue(job.id)

        val capturedJob = slot<ImportJob>()
        every { processor.process(capture(capturedJob)) } returns Unit

        worker.pollAndProcess()

        verify(exactly = 1) { processor.process(any()) }
        assertThat(capturedJob.captured.id).isEqualTo(job.id)
        assertThat(capturedJob.captured.status).isEqualTo(ImportJobStatus.RUNNING)
        assertThat(repo.findStatus(job.id)).isEqualTo(ImportJobStatus.RUNNING)
        assertThat(pgmqQueueLength()).isEqualTo(0L)
    }

    // ── (b) claimForRun false — 종단 상태(COMPLETED/FAILED) ──────────────────────

    @Test
    fun `claimForRun false - COMPLETED 종단 상태이면 processor 호출 없이 메시지를 삭제한다`() {
        val job = makeJob()
        repo.insert(job)
        publisher.enqueue(job.id)
        repo.claimForRun(job.id)
        repo.markCompleted(job.id, succeededRows = 0L, failedRows = 0L, null, Instant.now().plusSeconds(86_400))

        worker.pollAndProcess()

        verify(exactly = 0) { processor.process(any()) }
        assertThat(pgmqQueueLength()).isEqualTo(0L)
    }

    @Test
    fun `claimForRun false - FAILED 종단 상태이면 메시지를 삭제한다`() {
        val job = makeJob()
        repo.insert(job)
        publisher.enqueue(job.id)
        repo.claimForRun(job.id)
        repo.markFailed(job.id, "IMPORT_INTERNAL_ERROR")

        worker.pollAndProcess()

        verify(exactly = 0) { processor.process(any()) }
        assertThat(pgmqQueueLength()).isEqualTo(0L)
    }

    // ── (c) claimForRun false — RUNNING(not stale) → skip ────────────────────────

    @Test
    fun `claimForRun false - RUNNING(not stale) 이면 메시지를 삭제하지 않는다`() {
        val job = makeJob()
        repo.insert(job)
        publisher.enqueue(job.id)
        repo.claimForRun(job.id) // 다른 워커가 이미 RUNNING 선점(방금 claim → stale 아님)

        worker.pollAndProcess()

        verify(exactly = 0) { processor.process(any()) }
        assertThat(pgmqQueueLength()).isEqualTo(1L)
    }

    // ── (d) poison — 파싱 불가 메시지 ────────────────────────────────────────────

    @Test
    fun `파싱 불가 메시지(poison) - read_ct 5 이하이면 메시지를 유지한다 (재전달 대기)`() {
        dsl.execute("SELECT pgmq.send(?, ?::jsonb)", ImportJobWorker.QUEUE_NAME, """{"not_an_import":"garbage"}""")

        worker.pollAndProcess()

        verify(exactly = 0) { processor.process(any()) }
        assertThat(pgmqQueueLength()).isEqualTo(1L)
    }

    @Test
    fun `파싱 불가 메시지(poison) - read_ct 5 초과이면 archive 로 dead-letter 처리한다`() {
        dsl.execute("SELECT pgmq.send(?, ?::jsonb)", ImportJobWorker.QUEUE_NAME, """{"not_an_import":"garbage"}""")

        // read_ct 를 MAX_RECEIVE_COUNT(5) 까지 미리 소진 — vt=0 즉시 재가시화로 반복 read
        repeat(ImportJobWorker.MAX_RECEIVE_COUNT) {
            dsl.fetch("SELECT * FROM pgmq.read(?, ?, ?)", ImportJobWorker.QUEUE_NAME, 0, 5)
        }

        // worker 자신의 poll 이 read_ct 를 6 으로 올려 MAX_RECEIVE_COUNT 초과 → archive
        worker.pollAndProcess()

        verify(exactly = 0) { processor.process(any()) }
        assertThat(pgmqQueueLength()).isEqualTo(0L)
    }

    // ── (e) processor 예외 — at-least-once 재전달 ─────────────────────────────────

    @Test
    fun `processor process 가 예외를 던지면 메시지를 삭제하지 않는다 (at-least-once)`() {
        val job = makeJob()
        repo.insert(job)
        publisher.enqueue(job.id)
        every { processor.process(any()) } throws RuntimeException("처리 오류")

        worker.pollAndProcess()

        verify(exactly = 1) { processor.process(any()) }
        assertThat(pgmqQueueLength()).isEqualTo(1L)
        assertThat(repo.findStatus(job.id)).isEqualTo(ImportJobStatus.RUNNING)
    }
}
