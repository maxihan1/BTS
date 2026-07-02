// ImportJobProcessor 단위 테스트 — 스트리밍 행 처리/부분실패/행상한/파싱실패/dryRun/에러로그 정화 (FR-IM-01 PR1 Task 9)

package com.bts.search.imports.job.application

import com.bts.search.imports.job.domain.ImportJob
import com.bts.search.imports.job.domain.ImportJobId
import com.bts.search.imports.job.domain.ImportJobStatus
import com.bts.search.imports.job.repository.ImportJobRepository
import com.bts.search.imports.job.storage.ImportObjectStoragePort
import com.bts.search.imports.parse.ImportParseException
import com.bts.search.imports.parse.ImportRowParser
import com.bts.search.imports.parse.ParsedImportRow
import com.bts.shared.issue.IssueImportCommand
import com.bts.shared.issue.IssueImportPort
import com.bts.shared.issue.IssueImportResult
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * [ImportJobProcessor] 단위 테스트.
 *
 * [IssueImportPort], [ImportObjectStoragePort], [ImportJobRepository], [ImportRowParser] 는 MockK 로 격리한다.
 * [ImportErrorLogWriter] 는 formula injection 정화(sanitize) 결과를 실제 바이트로 검증해야 하므로
 * MockK 대신 실제 인스턴스를 사용한다(mockk any() 가짜그린 함정 회피).
 * [Clock] 은 고정 인스턴스로 expiresAt 결정성을 보장한다.
 *
 * 검증 항목.
 * - (a) 정상 흐름: 3행 모두 성공 → markCompleted(succeeded=3, failed=0, errorLogObjectKey=null).
 * - (b) 부분 실패: 3행 중 1행 실패 → succeeded=2, failed=1, 에러 로그 CSV 업로드(errorLogObjectKey 채워짐).
 * - (c) MAX_ROWS 초과: 카운터가 초과하는 시점에 중단 → markFailed(IMPORT_ROW_LIMIT_EXCEEDED), 초과 이후 행은 importIssue 미호출.
 * - (d) 파싱 실패: [ImportParseException] → markFailed(IMPORT_PARSE_FAILED).
 * - (e) dryRun: [IssueImportCommand.dryRun] 에 위임 + 집계는 동일하게 수행.
 * - (f) 에러 로그 정화: 실패 메시지에 formula injection 시작 문자가 있으면 sanitize 된 값이 CSV 에 기록.
 * - (g) 분류되지 않은 예외 → markFailed(IMPORT_INTERNAL_ERROR)(작업이 RUNNING 에 방치되지 않도록).
 */
class ImportJobProcessorTest {
    private val issueImportPort: IssueImportPort = mockk()
    private val storage: ImportObjectStoragePort = mockk()
    private val repository: ImportJobRepository = mockk()
    private val errorLogWriter: ImportErrorLogWriter = ImportErrorLogWriter()
    private val parser: ImportRowParser = mockk()
    private val fixedClock: Clock = Clock.fixed(Instant.parse("2024-03-15T10:30:45Z"), ZoneOffset.UTC)

    private lateinit var processor: ImportJobProcessor

    private val jobId = ImportJobId(UUID.fromString("00000000-0000-0000-0000-000000000020"))
    private val requesterUserId = UUID.fromString("00000000-0000-0000-0000-000000000002")

    @BeforeEach
    fun setUp() {
        processor = ImportJobProcessor(issueImportPort, storage, repository, errorLogWriter, parser, fixedClock)
        every { storage.get(any()) } returns ByteArrayInputStream(ByteArray(0))
        justRun { storage.put(any(), any(), any(), any()) }
        every { repository.markCompleted(any(), any(), any(), any(), any()) } returns true
        every { repository.markFailed(any(), any()) } returns true
        justRun { repository.updateCounts(any(), any(), any(), any(), any()) }
    }

    /** 기본 테스트용 ImportJob. */
    private fun makeJob(
        projectKey: String = "PROJ",
        format: String = "CSV",
        dryRun: Boolean = false,
    ): ImportJob =
        ImportJob(
            id = jobId,
            projectKey = projectKey,
            format = format,
            sourceObjectKey = "$projectKey/$jobId.csv",
            dryRun = dryRun,
            requesterUserId = requesterUserId,
            status = ImportJobStatus.RUNNING,
            progress = 0,
            totalRows = null,
            succeededRows = 0,
            failedRows = 0,
            errorCode = null,
            errorLogObjectKey = null,
            expiresAt = null,
            createdAt = Instant.parse("2024-03-15T10:30:45Z"),
            startedAt = Instant.parse("2024-03-15T10:30:46Z"),
            completedAt = null,
        )

    private fun makeRow(rowNumber: Int): ParsedImportRow =
        ParsedImportRow(
            rowNumber = rowNumber,
            summary = "Row $rowNumber",
            description = null,
            typeName = null,
            priorityName = null,
            reporterEmail = null,
            assigneeEmail = null,
            labels = emptyList(),
            componentNames = emptyList(),
        )

    /** [parser].parseCsv 호출 시 [rows] 를 순서대로 콜백으로 전달하도록 스텁한다. */
    private fun stubParserWithRows(rows: List<ParsedImportRow>) {
        val callbackSlot = slot<(ParsedImportRow) -> Unit>()
        every { parser.parseCsv(any(), capture(callbackSlot)) } answers {
            rows.forEach { row -> callbackSlot.captured(row) }
        }
    }

    /** [count] 개의 행을 즉석에서 생성해 콜백으로 전달한다 (MAX_ROWS 초과 시나리오 — 리스트 사전 적재 없이 반복). */
    private fun stubParserWithRowCount(count: Long) {
        val callbackSlot = slot<(ParsedImportRow) -> Unit>()
        every { parser.parseCsv(any(), capture(callbackSlot)) } answers {
            for (i in 1..count) {
                callbackSlot.captured(makeRow(i.toInt()))
            }
        }
    }

    // ── (a) 정상 흐름 ────────────────────────────────────────────────────────────

    @Test
    fun `all rows succeed marks job completed with succeeded and failed counts and no error log`() {
        stubParserWithRows(listOf(makeRow(1), makeRow(2), makeRow(3)))
        every { issueImportPort.importIssue(any()) } returns IssueImportResult.success("PROJ-1")

        processor.process(makeJob())

        verify { repository.markCompleted(jobId, 3L, 0L, null, Instant.parse("2024-03-16T10:30:45Z")) }
        verify(exactly = 3) { issueImportPort.importIssue(any()) }
        verify(exactly = 0) { storage.put(any(), any(), any(), any()) }
        verify(exactly = 0) { repository.markFailed(any(), any()) }
    }

    // ── (b) 부분 실패 ────────────────────────────────────────────────────────────

    @Test
    fun `one failing row among three tracks partial success and uploads error log`() {
        stubParserWithRows(listOf(makeRow(1), makeRow(2), makeRow(3)))
        every { issueImportPort.importIssue(match { it.summary == "Row 1" }) } returns
            IssueImportResult.success("PROJ-1")
        every { issueImportPort.importIssue(match { it.summary == "Row 2" }) } returns
            IssueImportResult.failure(IssueImportResult.VALIDATION, "summary too short")
        every { issueImportPort.importIssue(match { it.summary == "Row 3" }) } returns
            IssueImportResult.success("PROJ-3")
        val keySlot = slot<String>()
        val bytesSlot = slot<InputStream>()
        justRun { storage.put(capture(keySlot), capture(bytesSlot), any(), any()) }

        processor.process(makeJob())

        verify {
            repository.markCompleted(jobId, 2L, 1L, "PROJ/$jobId-errors.csv", Instant.parse("2024-03-16T10:30:45Z"))
        }
        assertThat(keySlot.captured).isEqualTo("PROJ/$jobId-errors.csv")
        val csv = bytesSlot.captured.readBytes().toString(Charsets.UTF_8)
        assertThat(csv).contains("2,,VALIDATION,summary too short")
    }

    // ── (c) MAX_ROWS 초과 ────────────────────────────────────────────────────────

    @Test
    fun `row count exceeding MAX_ROWS stops mid-stream and marks job failed with row limit code`() {
        stubParserWithRowCount(ImportJob.MAX_ROWS + 1)
        every { issueImportPort.importIssue(any()) } returns IssueImportResult.success("PROJ-1")

        processor.process(makeJob())

        verify { repository.markFailed(jobId, ImportJobProcessor.IMPORT_ROW_LIMIT_EXCEEDED) }
        verify(exactly = ImportJob.MAX_ROWS.toInt()) { issueImportPort.importIssue(any()) }
        verify(exactly = 0) { repository.markCompleted(any(), any(), any(), any(), any()) }
    }

    // ── (d) 파싱 실패 ────────────────────────────────────────────────────────────

    @Test
    fun `parser throwing ImportParseException marks job failed with parse failed code`() {
        every { parser.parseCsv(any(), any()) } throws ImportParseException("broken csv")

        processor.process(makeJob())

        verify { repository.markFailed(jobId, ImportJobProcessor.IMPORT_PARSE_FAILED) }
        verify(exactly = 0) { issueImportPort.importIssue(any()) }
        verify(exactly = 0) { repository.markCompleted(any(), any(), any(), any(), any()) }
    }

    // ── (e) dryRun 위임 ──────────────────────────────────────────────────────────

    @Test
    fun `dryRun job delegates dryRun flag to importIssue command and aggregates normally`() {
        stubParserWithRows(listOf(makeRow(1), makeRow(2)))
        val cmdSlots = mutableListOf<IssueImportCommand>()
        every { issueImportPort.importIssue(capture(cmdSlots)) } returns IssueImportResult.success("dry-run-ok")

        processor.process(makeJob(dryRun = true))

        assertThat(cmdSlots).hasSize(2)
        assertThat(cmdSlots).allMatch { it.dryRun }
        verify { repository.markCompleted(jobId, 2L, 0L, null, Instant.parse("2024-03-16T10:30:45Z")) }
    }

    // ── (f) 에러 로그 정화 ───────────────────────────────────────────────────────

    @Test
    fun `formula injection prefix in failure message is sanitized before writing to error log CSV`() {
        stubParserWithRows(listOf(makeRow(1)))
        every { issueImportPort.importIssue(any()) } returns
            IssueImportResult.failure(IssueImportResult.VALIDATION, "=cmd()")
        val bytesSlot = slot<InputStream>()
        justRun { storage.put(any(), capture(bytesSlot), any(), any()) }

        processor.process(makeJob())

        val csv = bytesSlot.captured.readBytes().toString(Charsets.UTF_8)
        assertThat(csv).contains("'=cmd()")
    }

    // ── (g) 분류되지 않은 예외 ───────────────────────────────────────────────────

    @Test
    fun `unclassified exception marks job failed with internal error code`() {
        every { storage.get(any()) } throws RuntimeException("minio unreachable")

        processor.process(makeJob())

        verify { repository.markFailed(jobId, ImportJobProcessor.IMPORT_INTERNAL_ERROR) }
    }
}
