// ImportJobService.analyze 단위 테스트 — 공통 검증 재사용(400/403/413) + AWAITING_MAPPING 접수(enqueue 없음) + 헤더/샘플 감지

package com.bts.search.imports.job.application

import com.bts.search.imports.job.domain.ImportJob
import com.bts.search.imports.job.domain.ImportJobStatus
import com.bts.search.imports.job.event.ImportJobEnqueuePublisher
import com.bts.search.imports.job.repository.ImportJobRepository
import com.bts.search.imports.job.storage.ImportObjectStoragePort
import com.bts.search.imports.mapping.TargetField
import com.bts.search.imports.parse.HeaderSample
import com.bts.search.imports.parse.ImportRowParser
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
import org.springframework.transaction.support.TransactionCallback
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.server.ResponseStatusException
import java.io.ByteArrayInputStream
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * [ImportJobService.analyze] 단위 테스트.
 *
 * [ImportJobRepository]/[ImportObjectStoragePort]/[ImportJobEnqueuePublisher]/[IssuePermissionResolver]/
 * [ImportRowParser] 는 MockK 로 격리한다. [TransactionTemplate] 도 MockK 로 대체하되 `execute` 호출 시
 * 전달된 콜백을 즉시 동기 실행하도록 stub 한다([ImportJobServiceTest] 와 동일 패턴).
 *
 * 검증 항목.
 * - (a)~(d) [ImportJobService.accept] 와 동일한 공통 검증(projectKey 공백 400 / CREATE_ISSUE 권한 없음
 *   403 / 크기 초과 413 / 미지원 format 400)이 `analyze` 에도 그대로 적용되는지 — 권한 검증이 새 진입점에서
 *   누락되면 보안 회귀이므로 별도로 확인한다.
 * - (e) 정상 CSV 분석 — storage.put(원본) + repository.insert(status=AWAITING_MAPPING, dryRun=false,
 *   expiresAt=now+ABANDON_TTL_SECONDS) + enqueue 미호출 + parser.readHeaderAndSample(*, 5) 결과 반영.
 * - (f) 정상 JSON 분석 — CSV 헤더 파싱 스킵, canonical [TargetField.key] 고정 목록 반환, sampleRows 빈 목록.
 */
class ImportJobServiceAnalyzeTest {
    private val repository: ImportJobRepository = mockk()
    private val storage: ImportObjectStoragePort = mockk()
    private val enqueuePublisher: ImportJobEnqueuePublisher = mockk()
    private val permissionResolver: IssuePermissionResolver = mockk()
    private val transactionTemplate: TransactionTemplate = mockk()
    private val parser: ImportRowParser = mockk()
    private val fixedClock: Clock = Clock.fixed(Instant.parse("2024-03-15T10:30:45Z"), ZoneOffset.UTC)

    private lateinit var service: ImportJobService

    private val requesterUserId: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private val projectKey = "PROJ"

    @BeforeEach
    fun setUp() {
        service =
            ImportJobService(
                repository,
                storage,
                enqueuePublisher,
                permissionResolver,
                transactionTemplate,
                fixedClock,
                parser = parser,
            )
        every { permissionResolver.hasPermission(any(), any(), any()) } returns true
        justRun { storage.put(any(), any(), any(), any()) }
        every { storage.get(any()) } returns ByteArrayInputStream(ByteArray(0))
        every { repository.insert(any()) } answers { firstArg() }
        every { transactionTemplate.execute(any<TransactionCallback<Any>>()) } answers {
            firstArg<TransactionCallback<Any>>().doInTransaction(mockk<TransactionStatus>(relaxed = true))
        }
        every { parser.readHeaderAndSample(any(), any()) } returns
            HeaderSample(headers = listOf("Summary"), sampleRows = listOf(listOf("Test issue")))
    }

    private fun csvStream(content: String = "summary\nTest issue\n"): ByteArrayInputStream {
        return ByteArrayInputStream(content.toByteArray())
    }

    private fun command(
        format: String = "CSV",
        sizeBytes: Long = 42L,
        requesterUserId: UUID = this.requesterUserId,
        projectKey: String = this.projectKey,
    ): ImportAnalyzeCommand =
        ImportAnalyzeCommand(
            projectKey = projectKey,
            format = format,
            filename = "issues.csv",
            contentType = "text/csv",
            sizeBytes = sizeBytes,
            inputStream = csvStream(),
            requesterUserId = requesterUserId,
        )

    // ── (a) projectKey 공백 → 400, side effect 없음 ─────────────────────────────

    @Test
    fun `analyze throws 400 when projectKey is blank`() {
        val ex = assertThrows<ResponseStatusException> { service.analyze(command(projectKey = "  ")) }

        assertThat(ex.statusCode.value()).isEqualTo(400)
        verify(exactly = 0) { storage.put(any(), any(), any(), any()) }
        verify(exactly = 0) { repository.insert(any()) }
    }

    // ── (b) 권한 없음 → 403, side effect 없음 (보안 회귀 방지) ────────────────────

    @Test
    fun `analyze throws ImportAccessDeniedException when actor lacks CREATE permission`() {
        every {
            permissionResolver.hasPermission(requesterUserId, IssuePermission.CREATE, IssueScope.Project(projectKey))
        } returns false

        assertThrows<ImportAccessDeniedException> { service.analyze(command()) }

        verify(exactly = 0) { storage.put(any(), any(), any(), any()) }
        verify(exactly = 0) { repository.insert(any()) }
    }

    // ── (c) 파일 크기 초과 → 413 ────────────────────────────────────────────────

    @Test
    fun `analyze throws ImportFileTooLargeException when sizeBytes exceeds limit`() {
        val oversized = ImportJobService.MAX_FILE_SIZE_BYTES + 1

        val ex = assertThrows<ImportFileTooLargeException> { service.analyze(command(sizeBytes = oversized)) }

        assertThat(ex.sizeBytes).isEqualTo(oversized)
        assertThat(ex.maxBytes).isEqualTo(ImportJobService.MAX_FILE_SIZE_BYTES)
        verify(exactly = 0) { storage.put(any(), any(), any(), any()) }
        verify(exactly = 0) { repository.insert(any()) }
    }

    // ── (d) 미지원 format → 400 ────────────────────────────────────────────────

    @Test
    fun `analyze throws ImportUnsupportedFormatException for unsupported format`() {
        val ex = assertThrows<ImportUnsupportedFormatException> { service.analyze(command(format = "XML")) }

        assertThat(ex.format).isEqualTo("XML")
        verify(exactly = 0) { storage.put(any(), any(), any(), any()) }
        verify(exactly = 0) { repository.insert(any()) }
    }

    // ── (e) 정상 CSV 분석 ─────────────────────────────────────────────────────

    @Test
    fun `analyze puts CSV to storage inserts AWAITING_MAPPING job without enqueue and returns header sample`() {
        val jobSlot = slot<ImportJob>()
        every { repository.insert(capture(jobSlot)) } answers { firstArg() }
        every { parser.readHeaderAndSample(any(), 5) } returns
            HeaderSample(headers = listOf("Summary", "Priority"), sampleRows = listOf(listOf("Test issue", "High")))

        val result = service.analyze(command(format = "csv", sizeBytes = 42L))

        verify(exactly = 1) {
            storage.put(
                match { it.startsWith("$projectKey/") && it.endsWith(".csv") },
                any(),
                42L,
                "text/csv",
            )
        }
        verify(exactly = 1) { repository.insert(any()) }
        verify(exactly = 0) { enqueuePublisher.enqueue(any()) }
        verify(exactly = 1) { parser.readHeaderAndSample(any(), 5) }

        with(jobSlot.captured) {
            assertThat(status).isEqualTo(ImportJobStatus.AWAITING_MAPPING)
            assertThat(dryRun).isFalse()
            assertThat(this.projectKey).isEqualTo(projectKey)
            assertThat(format).isEqualTo("CSV")
            assertThat(this.requesterUserId).isEqualTo(requesterUserId)
            assertThat(progress).isEqualTo(0)
            assertThat(totalRows).isNull()
            assertThat(succeededRows).isEqualTo(0L)
            assertThat(failedRows).isEqualTo(0L)
            assertThat(createdAt).isEqualTo(Instant.parse("2024-03-15T10:30:45Z"))
            assertThat(expiresAt).isEqualTo(Instant.parse("2024-03-15T10:30:45Z").plusSeconds(24L * 60 * 60))
        }
        assertThat(result.job).isEqualTo(jobSlot.captured)
        assertThat(result.sourceFields).containsExactly("Summary", "Priority")
        assertThat(result.sampleRows).containsExactly(listOf("Test issue", "High"))
    }

    // ── (f) 정상 JSON 분석 — canonical 고정 목록, 매핑 UI 스킵 ─────────────────────

    @Test
    fun `analyze for JSON format skips CSV header parsing and returns canonical target field keys`() {
        val jobSlot = slot<ImportJob>()
        every { repository.insert(capture(jobSlot)) } answers { firstArg() }

        val result = service.analyze(command(format = "json", sizeBytes = 10L))

        verify(exactly = 0) { parser.readHeaderAndSample(any(), any()) }
        verify(exactly = 0) { enqueuePublisher.enqueue(any()) }
        assertThat(jobSlot.captured.status).isEqualTo(ImportJobStatus.AWAITING_MAPPING)
        assertThat(jobSlot.captured.format).isEqualTo("JSON")
        assertThat(result.sourceFields).containsExactlyElementsOf(TargetField.entries.map { it.key })
        assertThat(result.sampleRows).isEmpty()
    }
}
