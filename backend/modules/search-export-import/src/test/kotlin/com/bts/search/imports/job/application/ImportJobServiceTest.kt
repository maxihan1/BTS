// ImportJobService 단위 테스트 — MockK repository/storage/enqueue/permission, 접수 권한 fail-fast/크기/형식 검증 (FR-IM-01 PR1 Task 11)

package com.bts.search.imports.job.application

import com.bts.search.imports.job.domain.ImportJob
import com.bts.search.imports.job.domain.ImportJobId
import com.bts.search.imports.job.domain.ImportJobStatus
import com.bts.search.imports.job.event.ImportJobEnqueuePublisher
import com.bts.search.imports.job.repository.ImportJobRepository
import com.bts.search.imports.job.storage.ImportObjectStoragePort
import com.bts.shared.permission.IssuePermission
import com.bts.shared.permission.IssuePermissionResolver
import com.bts.shared.permission.IssueScope
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.transaction.TransactionStatus
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.server.ResponseStatusException
import java.io.ByteArrayInputStream
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.function.Consumer

/**
 * [ImportJobService] 단위 테스트.
 *
 * [ImportJobRepository]/[ImportObjectStoragePort]/[ImportJobEnqueuePublisher]/[IssuePermissionResolver] 는
 * MockK 로 격리한다. [TransactionTemplate] 도 MockK 로 대체하되 `executeWithoutResult` 호출 시
 * 전달된 콜백을 즉시 동기 실행하도록 stub 한다(단위 테스트에서 실제 DB 트랜잭션은 불필요).
 * [Clock] 은 고정 인스턴스로 createdAt 결정성을 보장한다.
 *
 * 검증 항목.
 * - (a) 권한 없음 → [ImportAccessDeniedException], storage.put/repository.insert/enqueue 모두 미호출.
 * - (b) 파일 크기 초과 → [ImportFileTooLargeException], side effect 없음.
 * - (c) 미지원 format → [ImportUnsupportedFormatException], side effect 없음.
 * - (d) 정상 접수 → storage.put(구체 인자) + repository.insert(PENDING) + enqueue(동일 jobId) 호출, job 반환.
 * - (e) getForRequester: 소유자 반환, 타인 null.
 * - (f) getErrorLog: 준비된 로그 스트림 반환 / 미완료·타인 404.
 */
class ImportJobServiceTest {
    private val repository: ImportJobRepository = mockk()
    private val storage: ImportObjectStoragePort = mockk()
    private val enqueuePublisher: ImportJobEnqueuePublisher = mockk()
    private val permissionResolver: IssuePermissionResolver = mockk()
    private val transactionTemplate: TransactionTemplate = mockk()
    private val fixedClock: Clock = Clock.fixed(Instant.parse("2024-03-15T10:30:45Z"), ZoneOffset.UTC)

    private lateinit var service: ImportJobService

    private val requesterUserId: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private val otherUserId: UUID = UUID.fromString("00000000-0000-0000-0000-000000000002")
    private val projectKey = "PROJ"

    @BeforeEach
    fun setUp() {
        service = ImportJobService(repository, storage, enqueuePublisher, permissionResolver, transactionTemplate, fixedClock)
        every {
            permissionResolver.hasPermission(any(), any(), any())
        } returns true
        justRun { storage.put(any(), any(), any(), any()) }
        justRun { repository.insert(any()) }
        justRun { enqueuePublisher.enqueue(any()) }
        every { transactionTemplate.executeWithoutResult(any()) } answers {
            firstArg<Consumer<TransactionStatus>>().accept(mockk(relaxed = true))
        }
    }

    private fun csvStream(content: String = "summary\nTest issue\n"): ByteArrayInputStream = ByteArrayInputStream(content.toByteArray())

    private fun command(
        format: String = "CSV",
        sizeBytes: Long = 42L,
        requesterUserId: UUID = this.requesterUserId,
    ): ImportAcceptCommand =
        ImportAcceptCommand(
            projectKey = projectKey,
            format = format,
            dryRun = false,
            filename = "issues.csv",
            contentType = "text/csv",
            sizeBytes = sizeBytes,
            inputStream = csvStream(),
            requesterUserId = requesterUserId,
        )

    // ── (a) 권한 없음 → 403, side effect 없음 ──────────────────────────────────

    @Test
    fun `accept throws ImportAccessDeniedException when actor lacks CREATE permission`() {
        every {
            permissionResolver.hasPermission(requesterUserId, IssuePermission.CREATE, IssueScope.Project(projectKey))
        } returns false

        assertThrows<ImportAccessDeniedException> { service.accept(command()) }

        verify(exactly = 0) { storage.put(any(), any(), any(), any()) }
        verify(exactly = 0) { repository.insert(any()) }
        verify(exactly = 0) { enqueuePublisher.enqueue(any()) }
    }

    // ── (b) 파일 크기 초과 → 413 ─────────────────────────────────────────────

    @Test
    fun `accept throws ImportFileTooLargeException when sizeBytes exceeds limit`() {
        val oversized = ImportJobService.MAX_FILE_SIZE_BYTES + 1

        val ex = assertThrows<ImportFileTooLargeException> { service.accept(command(sizeBytes = oversized)) }

        assertThat(ex.sizeBytes).isEqualTo(oversized)
        assertThat(ex.maxBytes).isEqualTo(ImportJobService.MAX_FILE_SIZE_BYTES)
        verify(exactly = 0) { storage.put(any(), any(), any(), any()) }
        verify(exactly = 0) { repository.insert(any()) }
        verify(exactly = 0) { enqueuePublisher.enqueue(any()) }
    }

    // ── (c) 미지원 format → 400 ──────────────────────────────────────────────

    @Test
    fun `accept throws ImportUnsupportedFormatException for unsupported format`() {
        val ex = assertThrows<ImportUnsupportedFormatException> { service.accept(command(format = "XML")) }

        assertThat(ex.format).isEqualTo("XML")
        verify(exactly = 0) { storage.put(any(), any(), any(), any()) }
        verify(exactly = 0) { repository.insert(any()) }
        verify(exactly = 0) { enqueuePublisher.enqueue(any()) }
    }

    // ── (d) 정상 접수 ────────────────────────────────────────────────────────

    @Test
    fun `accept puts file to storage inserts PENDING job and enqueues same jobId`() {
        val jobSlot = slot<ImportJob>()
        justRun { repository.insert(capture(jobSlot)) }

        val result = service.accept(command(format = "csv", sizeBytes = 42L))

        verify(exactly = 1) {
            storage.put(
                match { it.startsWith("$projectKey/") && it.endsWith(".csv") },
                any(),
                42L,
                "text/csv",
            )
        }
        verify(exactly = 1) { repository.insert(any()) }
        verify(exactly = 1) { enqueuePublisher.enqueue(result.id) }

        with(jobSlot.captured) {
            assertThat(status).isEqualTo(ImportJobStatus.PENDING)
            assertThat(this.projectKey).isEqualTo(projectKey)
            assertThat(format).isEqualTo("CSV")
            assertThat(this.requesterUserId).isEqualTo(requesterUserId)
            assertThat(progress).isEqualTo(0)
            assertThat(succeededRows).isEqualTo(0L)
            assertThat(failedRows).isEqualTo(0L)
            assertThat(createdAt).isEqualTo(Instant.parse("2024-03-15T10:30:45Z"))
        }
        assertThat(result).isEqualTo(jobSlot.captured)
    }

    // ── (e) getForRequester ──────────────────────────────────────────────────

    @Test
    fun `getForRequester returns job for owner`() {
        val jobId = ImportJobId(UUID.randomUUID())
        val job = makeJob(jobId, requesterUserId)
        every { repository.findByIdForRequester(jobId, requesterUserId) } returns job

        val result = service.getForRequester(jobId, requesterUserId)

        assertThat(result).isEqualTo(job)
    }

    @Test
    fun `getForRequester returns null for non-owner`() {
        val jobId = ImportJobId(UUID.randomUUID())
        every { repository.findByIdForRequester(jobId, otherUserId) } returns null

        val result = service.getForRequester(jobId, otherUserId)

        assertThat(result).isNull()
    }

    // ── (f) getErrorLog ──────────────────────────────────────────────────────

    @Test
    fun `getErrorLog returns stream when job completed with error log`() {
        val jobId = ImportJobId(UUID.randomUUID())
        val errorLogKey = "$projectKey/${jobId.value}-errors.csv"
        val job =
            makeJob(jobId, requesterUserId).copy(
                status = ImportJobStatus.COMPLETED,
                errorLogObjectKey = errorLogKey,
            )
        every { repository.findByIdForRequester(jobId, requesterUserId) } returns job
        every { storage.get(errorLogKey) } returns ByteArrayInputStream("row,reason\n1,VALIDATION\n".toByteArray())

        val result = service.getErrorLog(jobId, requesterUserId)

        assertThat(result.job).isEqualTo(job)
        verify(exactly = 1) { storage.get(errorLogKey) }
    }

    @Test
    fun `getErrorLog throws 404 when job not completed`() {
        val jobId = ImportJobId(UUID.randomUUID())
        val job = makeJob(jobId, requesterUserId)
        every { repository.findByIdForRequester(jobId, requesterUserId) } returns job

        val ex = assertThrows<ResponseStatusException> { service.getErrorLog(jobId, requesterUserId) }

        assertThat(ex.statusCode.value()).isEqualTo(404)
        verify(exactly = 0) { storage.get(any()) }
    }

    @Test
    fun `getErrorLog throws 404 for non-owner`() {
        val jobId = ImportJobId(UUID.randomUUID())
        every { repository.findByIdForRequester(jobId, otherUserId) } returns null

        assertThrows<ResponseStatusException> { service.getErrorLog(jobId, otherUserId) }
    }

    // ── private helpers ──────────────────────────────────────────────────────

    private fun makeJob(
        id: ImportJobId,
        requesterUserId: UUID,
    ): ImportJob =
        ImportJob(
            id = id,
            projectKey = projectKey,
            format = "CSV",
            sourceObjectKey = "$projectKey/${id.value}.csv",
            dryRun = false,
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
}
