// ExportJobProcessor 단위 테스트 — count-first/페이지순회/스토리지실패/임시파일정리 (FR-EX-02)

package com.bts.search.export.job.application

import com.bts.search.export.job.domain.ExportJob
import com.bts.search.export.job.domain.ExportJobId
import com.bts.search.export.job.domain.ExportJobStatus
import com.bts.search.export.job.repository.ExportJobRepository
import com.bts.search.export.job.serialize.StreamingExportSerializer
import com.bts.search.export.job.storage.ExportObjectStoragePort
import com.bts.search.export.job.storage.MinioExportStorageException
import com.bts.search.web.SearchErrorCodes
import com.bts.shared.search.IssueSearchHit
import com.bts.shared.search.IssueSearchPage
import com.bts.shared.search.IssueSearchPort
import com.bts.shared.search.IssueSearchQuery
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * [ExportJobProcessor] 단위 테스트.
 *
 * [IssueSearchPort], [ExportJobRepository], [ExportObjectStoragePort], [StreamingExportSerializer] 는 MockK 로 격리한다.
 * [Clock] 은 고정 인스턴스로 expiresAt 결정성을 보장한다.
 *
 * 검증 항목.
 * - (a) count-first total≤10만: 페이지 순회 → appendBatch → finish → storage.put → markCompleted + progress 갱신.
 * - (b) total>10만: serializer/storage 미호출(exactly=0) + markFailed(SEARCH_EXPORT_LIMIT_EXCEEDED).
 * - (c) storage 실패: markFailed(SEARCH_EXPORT_STORAGE_ERROR) + 임시파일 삭제.
 * - (d) viewerUserId = job.requesterUserId (slot capture 보안 상속).
 * - (e) 빈 결과(total=0): progress=100 + markCompleted.
 */
class ExportJobProcessorTest {
    private val searchPort: IssueSearchPort = mockk()
    private val repository: ExportJobRepository = mockk()
    private val storage: ExportObjectStoragePort = mockk()
    private val serializer: StreamingExportSerializer = mockk()
    private val serializerFactory: ExportSerializerFactory = mockk()
    private val fixedClock: Clock = Clock.fixed(Instant.parse("2024-03-15T10:30:45Z"), ZoneOffset.UTC)

    private lateinit var processor: ExportJobProcessor

    private val jobId = ExportJobId(UUID.fromString("00000000-0000-0000-0000-000000000010"))
    private val requesterUserId = UUID.fromString("00000000-0000-0000-0000-000000000001")

    /** 기본 테스트용 ExportJob. */
    @Suppress("LongParameterList")
    private fun makeJob(
        id: ExportJobId = jobId,
        projectKey: String = "ATLAS",
        query: String = "status = OPEN",
        format: String = "CSV",
        columns: List<String> = emptyList(),
        userId: UUID = requesterUserId,
    ): ExportJob =
        ExportJob(
            id = id,
            projectKey = projectKey,
            query = query,
            format = format,
            columns = columns,
            requesterUserId = userId,
            status = ExportJobStatus.RUNNING,
            progress = 0,
            rowCount = null,
            resultObjectKey = null,
            errorCode = null,
            expiresAt = null,
            createdAt = Instant.parse("2024-03-15T10:30:45Z"),
            startedAt = Instant.parse("2024-03-15T10:30:46Z"),
            completedAt = null,
        )

    private fun makeHit(key: String = "ATLAS-1"): IssueSearchHit =
        IssueSearchHit(
            key = key,
            summary = "Test issue",
            typeKey = "task",
            currentStateKey = "open",
            assigneeId = null,
            priority = 3,
            priorityName = "Medium",
            projectKey = "ATLAS",
            updatedAt = Instant.parse("2024-03-15T10:30:45Z"),
        )

    @BeforeEach
    fun setUp() {
        processor = ExportJobProcessor(searchPort, repository, storage, serializerFactory, fixedClock)
        every { serializerFactory.create(any(), any()) } returns serializer
        justRun { serializer.appendBatch(any()) }
        justRun { serializer.close() }
        every { repository.markCompleted(any(), any(), any()) } returns true
        every { repository.markFailed(any(), any()) } returns true
        justRun { repository.updateProgress(any(), any(), any()) }
    }

    // ── (a) 정상 처리 흐름 ────────────────────────────────────────────────────────

    @Test
    fun `normal flow appends all pages and completes the job`() {
        val hit = makeHit()
        every { searchPort.search(match { it.page == 0 }) } returns
            IssueSearchPage(List(100) { hit }, 150L, 0, ExportJobProcessor.PAGE_SIZE)
        every { searchPort.search(match { it.page == 1 }) } returns
            IssueSearchPage(List(50) { hit }, 150L, 1, ExportJobProcessor.PAGE_SIZE)
        val tempFile = File.createTempFile("bts-test", ".csv")
        every { serializer.finish() } returns tempFile
        justRun { storage.put(any(), any(), any(), any()) }

        processor.process(makeJob())

        verify(exactly = 2) { serializer.appendBatch(any()) }
        verify(exactly = 1) { serializer.finish() }
        verify(exactly = 1) { storage.put(any(), any(), any(), any()) }
        verify { repository.markCompleted(jobId, "ATLAS/$jobId.csv", Instant.parse("2024-03-16T10:30:45Z")) }
        verify { repository.updateProgress(jobId, 100, 150L) }
    }

    @Test
    fun `object key follows projectKey slash jobId dot ext pattern`() {
        every { searchPort.search(any()) } returns
            IssueSearchPage(listOf(makeHit()), 1L, 0, ExportJobProcessor.PAGE_SIZE)
        val tempFile = File.createTempFile("bts-test", ".csv")
        every { serializer.finish() } returns tempFile
        val keySlot = slot<String>()
        justRun { storage.put(capture(keySlot), any(), any(), any()) }

        processor.process(makeJob())

        assertThat(keySlot.captured).isEqualTo("ATLAS/$jobId.csv")
    }

    @Test
    fun `expiresAt is set to now plus 24 hours`() {
        every { searchPort.search(any()) } returns
            IssueSearchPage(listOf(makeHit()), 1L, 0, ExportJobProcessor.PAGE_SIZE)
        val tempFile = File.createTempFile("bts-test", ".csv")
        every { serializer.finish() } returns tempFile
        justRun { storage.put(any(), any(), any(), any()) }
        val expiresAtSlot = slot<Instant>()
        every { repository.markCompleted(any(), any(), capture(expiresAtSlot)) } returns true

        processor.process(makeJob())

        assertThat(expiresAtSlot.captured).isEqualTo(Instant.parse("2024-03-16T10:30:45Z"))
    }

    // ── (b) 행 상한 초과 ──────────────────────────────────────────────────────────

    @Test
    fun `total over MAX_ROWS marks job failed with limit exceeded code`() {
        every { searchPort.search(any()) } returns
            IssueSearchPage(emptyList(), ExportJob.MAX_ROWS + 1, 0, ExportJobProcessor.PAGE_SIZE)

        processor.process(makeJob())

        verify { repository.markFailed(jobId, SearchErrorCodes.SEARCH_EXPORT_LIMIT_EXCEEDED) }
    }

    @Test
    fun `serializer appendBatch is never called when total exceeds MAX_ROWS`() {
        every { searchPort.search(any()) } returns
            IssueSearchPage(emptyList(), ExportJob.MAX_ROWS + 1, 0, ExportJobProcessor.PAGE_SIZE)

        processor.process(makeJob())

        verify(exactly = 0) { serializer.appendBatch(any()) }
        verify(exactly = 0) { storage.put(any(), any(), any(), any()) }
    }

    // ── (c) 스토리지 실패 + 임시파일 정리 ───────────────────────────────────────

    @Test
    fun `storage error marks job failed with storage error code`() {
        every { searchPort.search(any()) } returns
            IssueSearchPage(listOf(makeHit()), 1L, 0, ExportJobProcessor.PAGE_SIZE)
        val tempFile = File.createTempFile("bts-test", ".csv")
        every { serializer.finish() } returns tempFile
        every { storage.put(any(), any(), any(), any()) } throws
            MinioExportStorageException("upload failed", RuntimeException("cause"))

        processor.process(makeJob())

        verify { repository.markFailed(jobId, SearchErrorCodes.SEARCH_EXPORT_STORAGE_ERROR) }
    }

    @Test
    fun `temp file is deleted after storage error`() {
        every { searchPort.search(any()) } returns
            IssueSearchPage(listOf(makeHit()), 1L, 0, ExportJobProcessor.PAGE_SIZE)
        val tempFile = File.createTempFile("bts-test-del", ".csv")
        every { serializer.finish() } returns tempFile
        every { storage.put(any(), any(), any(), any()) } throws
            MinioExportStorageException("upload failed", RuntimeException("cause"))

        processor.process(makeJob())

        assertThat(tempFile.exists()).isFalse
    }

    // ── (d) viewerUserId 보안 상속 ───────────────────────────────────────────────

    @Test
    fun `IssueSearchPort is called with job requesterUserId as viewerUserId`() {
        val querySlot = slot<IssueSearchQuery>()
        every { searchPort.search(capture(querySlot)) } returns
            IssueSearchPage(listOf(makeHit()), 1L, 0, ExportJobProcessor.PAGE_SIZE)
        val tempFile = File.createTempFile("bts-test", ".csv")
        every { serializer.finish() } returns tempFile
        justRun { storage.put(any(), any(), any(), any()) }

        processor.process(makeJob())

        assertThat(querySlot.captured.viewerUserId).isEqualTo(requesterUserId)
    }

    @Test
    fun `IssueSearchPort is called with the jobs projectKey`() {
        val querySlot = slot<IssueSearchQuery>()
        every { searchPort.search(capture(querySlot)) } returns
            IssueSearchPage(listOf(makeHit()), 1L, 0, ExportJobProcessor.PAGE_SIZE)
        val tempFile = File.createTempFile("bts-test", ".csv")
        every { serializer.finish() } returns tempFile
        justRun { storage.put(any(), any(), any(), any()) }

        processor.process(makeJob())

        assertThat(querySlot.captured.projectKey).isEqualTo("ATLAS")
    }

    // ── (e) 빈 결과 (total=0) ───────────────────────────────────────────────────

    @Test
    fun `empty result completes with progress 100 and headers-only file`() {
        every { searchPort.search(any()) } returns IssueSearchPage(emptyList(), 0L, 0, ExportJobProcessor.PAGE_SIZE)
        val tempFile = File.createTempFile("bts-test", ".csv")
        every { serializer.finish() } returns tempFile
        justRun { storage.put(any(), any(), any(), any()) }

        processor.process(makeJob())

        verify { repository.markCompleted(any(), any(), any()) }
        verify { repository.updateProgress(jobId, 100, 0L) }
    }

    // ── (f) serializer.close() 호출 보장 — 리소스 누수 방지 회귀 가드 ─────────────

    @Test
    fun `limit exceeded path closes serializer to prevent resource leak`() {
        every { searchPort.search(any()) } returns
            IssueSearchPage(emptyList(), ExportJob.MAX_ROWS + 1, 0, ExportJobProcessor.PAGE_SIZE)

        processor.process(makeJob())

        // 상한 초과 조기 return 경로에서도 use{} 가 serializer.close() 를 호출해야 한다
        verify(exactly = 1) { serializer.close() }
    }

    @Test
    fun `search exception path closes serializer to prevent resource leak`() {
        every { searchPort.search(any()) } throws RuntimeException("search engine error")

        processor.process(makeJob())

        // 검색 예외 경로에서도 use{} 가 serializer.close() 를 호출해야 한다
        verify(exactly = 1) { serializer.close() }
    }
}
