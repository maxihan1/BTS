// ImportJobProcessor 단위 테스트 — 스트리밍 행 처리/부분실패/행상한/파싱실패/dryRun/에러로그 정화 (FR-IM-01 PR1 Task 9)

package com.bts.search.imports.job.application

import com.bts.search.imports.job.domain.ImportJob
import com.bts.search.imports.job.domain.ImportJobId
import com.bts.search.imports.job.domain.ImportJobStatus
import com.bts.search.imports.job.repository.ImportJobRepository
import com.bts.search.imports.job.storage.ImportObjectStoragePort
import com.bts.search.imports.mapping.ValueTargetField
import com.bts.search.imports.mapping.repository.ImportMappingRepository
import com.bts.search.imports.mapping.repository.ImportUserMappingRepository
import com.bts.search.imports.mapping.repository.ImportValueMappingRepository
import com.bts.search.imports.parse.ImportParseException
import com.bts.search.imports.parse.ImportRowParser
import com.bts.search.imports.parse.ParsedImportAttachment
import com.bts.search.imports.parse.ParsedImportChangeGroup
import com.bts.search.imports.parse.ParsedImportChangeItem
import com.bts.search.imports.parse.ParsedImportComment
import com.bts.search.imports.parse.ParsedImportRow
import com.bts.search.imports.parse.ParsedImportWorklog
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
 * - (a) 정상 흐름: 3행 모두 성공 → markCompleted(succeeded=3, failed=0, errorLogObjectKey=null),
 *   완료 직전 updateCounts(progress=100, totalRows=3) 로 진행률/전체행수 확정.
 * - (b) 부분 실패: 3행 중 1행 실패 → succeeded=2, failed=1, 에러 로그 CSV 업로드(errorLogObjectKey 채워짐).
 * - (c) MAX_ROWS 초과: 카운터가 초과하는 시점에 중단 → markFailed(IMPORT_ROW_LIMIT_EXCEEDED), 초과 이후 행은 importIssue 미호출.
 * - (d) 파싱 실패: [ImportParseException] → markFailed(IMPORT_PARSE_FAILED).
 * - (e) dryRun: [IssueImportCommand.dryRun] 에 위임 + 집계는 동일하게 수행.
 * - (f) 에러 로그 정화: 실패 메시지에 formula injection 시작 문자가 있으면 sanitize 된 값이 CSV 에 기록.
 * - (g) 분류되지 않은 예외 → markFailed(IMPORT_INTERNAL_ERROR)(작업이 RUNNING 에 방치되지 않도록).
 * - (h) 100 행 미만(PROGRESS_UPDATE_INTERVAL_ROWS 주기 미도달) 완료: handleRow 내부 주기 갱신이 한 번도
 *   발생하지 않아도 finalizeCompleted 가 progress=100·totalRows=실제 처리 행수를 확정하는지 검증
 *   (PR1 코드리뷰 C2 회귀 — 100 미만 파일은 완료 후 progress=0 로 남던 결함).
 * - (i) toCommand 매핑: statusName/fixVersionNames/affectsVersionNames 가 [IssueImportCommand] 로 전달되는지 (PR2 Task 5, G1).
 * - (j) 경고 노출: [IssueImportResult.Success.warnings] 가 있으면 severity=WARNING 행으로 결과 로그에 기록되고,
 *   실패행이 0 이어도 로그가 업로드되며(errorLogObjectKey non-null), 해당 행은 여전히 succeeded 로 집계되는지
 *   (PR1 은 warnings 를 폐기했다 — G1 회귀 방지, PR2 Task 5).
 * - (l) toCommand 매핑: sourceKey 관통 + attachments/changelog 가 각각 [com.bts.shared.issue.ImportAttachment]/
 *   [com.bts.shared.issue.ImportChangeGroup] 로 변환되는지 — 시각 문자열→[Instant], 이메일 소문자화,
 *   changelog item 은 BTS 필드로 매핑하지 않고 raw field 를 그대로 운반하는지 (FR-IM-01 PR4 Task 5).
 * - (m) 매핑 기반 CSV 파싱 로드: job 의 저장된 매핑([ImportMappingRepository.findByJobId])이 있으면
 *   [ImportRowParser.parseCsv] 3-인자(mapped) 오버로드로, 없으면(빈 Map) 기존 2-인자(canonical)
 *   오버로드로 위임하는지 — mockk verify 로 두 오버로드 호출을 구분해 단언 (FR-IM-02 PR-A Task 9).
 * - (n) 사용자 매핑 해석: job 의 저장된 사용자 매핑([ImportUserMappingRepository.findByJobId])을 행 수와
 *   무관하게 job 당 1 회만 로드하고, reporter/assignee/댓글/worklog/첨부/changelog 각 식별자를
 *   [com.bts.search.imports.mapping.UserMappingNormalizer.normalize] 로 정규화해 조회한 뒤 커맨드/각 VO
 *   의 userId 필드에 세팅하는지, 매핑에 없는 식별자·값이 null 인 매핑·매핑 자체가 없는 job(회귀)은
 *   모두 userId null 폴백인지 검증한다 (FR-IM-02 PR-B Task 6).
 * - (o) 값 매핑 치환: job 의 저장된 값 매핑([ImportValueMappingRepository.findByJobId])을 행 수와 무관하게
 *   job 당 1 회만 로드하고, 행별 statusName/typeName/priorityName 을
 *   [com.bts.search.imports.mapping.ValueMappingNormalizer.normalize] 로 정규화한 키로 조회해 저장된
 *   대상 값으로 치환하는지, 미매핑 값은 원본을 그대로 유지하는지, null 소스값은 치환을 시도하지 않는지
 *   검증한다. priorityName 치환은 [ImportMappingService][com.bts.search.imports.mapping.ImportMappingService]
 *   가 저장 시 canonical 정확 표기로 치환해 두므로, 치환된 값이 그대로
 *   `PRIORITY_NUMBER_BY_NAME` 조회에 성공하는지도 함께 검증한다 (C1 회귀, FR-IM-02 PR-C Task 6).
 */
class ImportJobProcessorTest {
    private val issueImportPort: IssueImportPort = mockk()
    private val storage: ImportObjectStoragePort = mockk()
    private val repository: ImportJobRepository = mockk()
    private val mappingRepo: ImportMappingRepository = mockk()
    private val userMappingRepo: ImportUserMappingRepository = mockk()
    private val valueMappingRepo: ImportValueMappingRepository = mockk()
    private val errorLogWriter: ImportErrorLogWriter = ImportErrorLogWriter()
    private val parser: ImportRowParser = mockk()
    private val fixedClock: Clock = Clock.fixed(Instant.parse("2024-03-15T10:30:45Z"), ZoneOffset.UTC)

    private lateinit var processor: ImportJobProcessor

    private val jobId = ImportJobId(UUID.fromString("00000000-0000-0000-0000-000000000020"))
    private val requesterUserId = UUID.fromString("00000000-0000-0000-0000-000000000002")

    @BeforeEach
    fun setUp() {
        processor =
            ImportJobProcessor(
                issueImportPort,
                storage,
                repository,
                mappingRepo,
                userMappingRepo,
                valueMappingRepo,
                errorLogWriter,
                parser,
                fixedClock,
            )
        every { storage.get(any()) } returns ByteArrayInputStream(ByteArray(0))
        justRun { storage.put(any(), any(), any(), any()) }
        every { repository.markCompleted(any(), any(), any(), any(), any()) } returns true
        every { repository.markFailed(any(), any()) } returns true
        justRun { repository.updateCounts(any(), any(), any(), any(), any()) }
        // canonical 회귀 불변 — 매핑을 명시적으로 스텁하지 않는 기존 테스트는 빈 Map(매핑 없음)이
        // 기본값이라 CSV 분기가 기존 2-인자 canonical parseCsv 오버로드를 계속 사용한다.
        every { mappingRepo.findByJobId(any()) } returns emptyMap()
        // 사용자 매핑 회귀 불변 — 명시적으로 스텁하지 않는 기존 테스트는 빈 Map(매핑 없음)이 기본값이라
        // reporterUserId/assigneeUserId/각 VO authorUserId 가 전부 null 로 남는다.
        every { userMappingRepo.findByJobId(any()) } returns emptyMap()
        // 값 매핑 회귀 불변 — 명시적으로 스텁하지 않는 기존 테스트는 빈 Map(매핑 없음)이 기본값이라
        // statusName/typeName/priorityName 이 파싱된 원본 그대로 유지된다.
        every { valueMappingRepo.findByJobId(any()) } returns emptyMap()
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
        verify(exactly = 1) { repository.updateCounts(jobId, 100, 3L, 3L, 0L) }
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

    // ── (h) 100 행 미만 완료 — 주기 갱신 미도달 시에도 progress/totalRows 확정 ──────

    @Test
    fun `fewer rows than progress update interval still completes with progress 100 and totalRows set`() {
        val rowCount = 50L
        stubParserWithRowCount(rowCount)
        every { issueImportPort.importIssue(any()) } returns IssueImportResult.success("PROJ-1")

        processor.process(makeJob())

        // 50 < PROGRESS_UPDATE_INTERVAL_ROWS(100) 이므로 handleRow 내부 주기 갱신은 한 번도 발생하지
        // 않는다 — finalizeCompleted 가 확정한 updateCounts 호출 1건만 존재해야 한다.
        verify(exactly = 1) { repository.updateCounts(any(), any(), any(), any(), any()) }
        verify(exactly = 1) { repository.updateCounts(jobId, 100, rowCount, rowCount, 0L) }
        verify { repository.markCompleted(jobId, rowCount, 0L, null, Instant.parse("2024-03-16T10:30:45Z")) }
    }

    // ── (i) toCommand 매핑 — statusName/fixVersionNames/affectsVersionNames ────────

    @Test
    fun `toCommand maps statusName fixVersionNames and affectsVersionNames from parsed row`() {
        val row =
            makeRow(1).copy(
                statusName = "In Progress",
                fixVersionNames = listOf("1.0"),
                affectsVersionNames = listOf("0.9"),
            )
        stubParserWithRows(listOf(row))
        val cmdSlot = slot<IssueImportCommand>()
        every { issueImportPort.importIssue(capture(cmdSlot)) } returns IssueImportResult.success("PROJ-1")

        processor.process(makeJob())

        assertThat(cmdSlot.captured.statusName).isEqualTo("In Progress")
        assertThat(cmdSlot.captured.fixVersionNames).containsExactly("1.0")
        assertThat(cmdSlot.captured.affectsVersionNames).containsExactly("0.9")
    }

    // ── (j) 경고 노출 — G1: PR1 이 폐기하던 warnings 를 severity=WARNING 행으로 결과 로그에 기록 ──

    @Test
    fun `success result with warnings records WARNING severity row, uploads log, still counts succeeded`() {
        stubParserWithRows(listOf(makeRow(1)))
        every { issueImportPort.importIssue(any()) } returns
            IssueImportResult.success("PROJ-1", warnings = listOf("컴포넌트 'X' 을(를) 찾을 수 없어 건너뛰었습니다."))
        val keySlot = slot<String>()
        val bytesSlot = slot<InputStream>()
        justRun { storage.put(capture(keySlot), capture(bytesSlot), any(), any()) }

        processor.process(makeJob())

        // 실패행은 0 이지만 경고행이 있으므로 로그가 업로드된다 (errorLogObjectKey non-null).
        verify {
            repository.markCompleted(jobId, 1L, 0L, "PROJ/$jobId-errors.csv", Instant.parse("2024-03-16T10:30:45Z"))
        }
        assertThat(keySlot.captured).isEqualTo("PROJ/$jobId-errors.csv")
        val csv = bytesSlot.captured.readBytes().toString(Charsets.UTF_8)
        assertThat(csv).contains("1,,IMPORT_WARNING,컴포넌트 'X' 을(를) 찾을 수 없어 건너뛰었습니다.,WARNING")
    }

    // ── (k) toCommand 매핑 — comments/worklogs (FR-IM-01 PR3 Task 6) ───────────────

    @Test
    fun `toCommand maps parsed comments to ImportComment with ISO string parsed to Instant and email lowercased`() {
        val row =
            makeRow(1).copy(
                comments =
                    listOf(
                        ParsedImportComment(
                            body = "First comment",
                            authorEmail = "Bob@Corp.com",
                            createdAt = "2024-01-15T10:00:00.000+0000",
                        ),
                    ),
            )
        stubParserWithRows(listOf(row))
        val cmdSlot = slot<IssueImportCommand>()
        every { issueImportPort.importIssue(capture(cmdSlot)) } returns IssueImportResult.success("PROJ-1")

        processor.process(makeJob())

        val comment = cmdSlot.captured.comments.single()
        assertThat(comment.body).isEqualTo("First comment")
        assertThat(comment.authorEmail).isEqualTo("bob@corp.com")
        assertThat(comment.createdAt).isEqualTo(Instant.parse("2024-01-15T10:00:00.000Z"))
    }

    @Test
    fun `toCommand maps parsed worklogs to ImportWorklog with ISO string parsed to Instant and email lowercased`() {
        val row =
            makeRow(1).copy(
                worklogs =
                    listOf(
                        ParsedImportWorklog(
                            timeSpentSeconds = 3600,
                            startedAt = "2024-01-15T09:00:00.000+0000",
                            authorEmail = "Bob@Corp.com",
                            comment = "Investigated bug",
                        ),
                    ),
            )
        stubParserWithRows(listOf(row))
        val cmdSlot = slot<IssueImportCommand>()
        every { issueImportPort.importIssue(capture(cmdSlot)) } returns IssueImportResult.success("PROJ-1")

        processor.process(makeJob())

        val worklog = cmdSlot.captured.worklogs.single()
        assertThat(worklog.timeSpentSeconds).isEqualTo(3600)
        assertThat(worklog.startedAt).isEqualTo(Instant.parse("2024-01-15T09:00:00.000Z"))
        assertThat(worklog.authorEmail).isEqualTo("bob@corp.com")
        assertThat(worklog.comment).isEqualTo("Investigated bug")
    }

    @Test
    fun `toCommand leaves createdAt startedAt null when the parsed date string is unparseable`() {
        val row =
            makeRow(1).copy(
                comments = listOf(ParsedImportComment(body = "댓글", authorEmail = null, createdAt = "not-a-date")),
                worklogs =
                    listOf(ParsedImportWorklog(timeSpentSeconds = 60, startedAt = "not-a-date", authorEmail = null)),
            )
        stubParserWithRows(listOf(row))
        val cmdSlot = slot<IssueImportCommand>()
        every { issueImportPort.importIssue(capture(cmdSlot)) } returns IssueImportResult.success("PROJ-1")

        processor.process(makeJob())

        assertThat(cmdSlot.captured.comments.single().createdAt).isNull()
        assertThat(cmdSlot.captured.worklogs.single().startedAt).isNull()
    }

    @Test
    fun `toCommand maps empty parsed comments and worklogs to empty lists`() {
        stubParserWithRows(listOf(makeRow(1)))
        val cmdSlot = slot<IssueImportCommand>()
        every { issueImportPort.importIssue(capture(cmdSlot)) } returns IssueImportResult.success("PROJ-1")

        processor.process(makeJob())

        assertThat(cmdSlot.captured.comments).isEmpty()
        assertThat(cmdSlot.captured.worklogs).isEmpty()
    }

    // ── (l) toCommand 매핑 — sourceKey/attachments/changelog (FR-IM-01 PR4 Task 5) ─

    @Test
    fun `toCommand passes through sourceKey from parsed row`() {
        val row = makeRow(1).copy(sourceKey = "JIRA-123")
        stubParserWithRows(listOf(row))
        val cmdSlot = slot<IssueImportCommand>()
        every { issueImportPort.importIssue(capture(cmdSlot)) } returns IssueImportResult.success("PROJ-1")

        processor.process(makeJob())

        assertThat(cmdSlot.captured.sourceKey).isEqualTo("JIRA-123")
    }

    @Test
    fun `toCommand maps parsed attachments to ImportAttachment with Instant email lowercased and size preserved`() {
        val row =
            makeRow(1).copy(
                attachments =
                    listOf(
                        ParsedImportAttachment(
                            filename = "screenshot.png",
                            authorEmail = "Bob@Corp.com",
                            created = "2024-01-15T10:00:00.000+0000",
                            mimeType = "image/png",
                            sizeBytes = 2048L,
                        ),
                    ),
            )
        stubParserWithRows(listOf(row))
        val cmdSlot = slot<IssueImportCommand>()
        every { issueImportPort.importIssue(capture(cmdSlot)) } returns IssueImportResult.success("PROJ-1")

        processor.process(makeJob())

        val attachment = cmdSlot.captured.attachments.single()
        assertThat(attachment.filename).isEqualTo("screenshot.png")
        assertThat(attachment.authorEmail).isEqualTo("bob@corp.com")
        assertThat(attachment.createdAt).isEqualTo(Instant.parse("2024-01-15T10:00:00.000Z"))
        assertThat(attachment.mimeType).isEqualTo("image/png")
        assertThat(attachment.sizeBytes).isEqualTo(2048L)
    }

    @Test
    fun `toCommand maps parsed changelog groups to ImportChangeGroup with author lowercased and raw field preserved`() {
        val row =
            makeRow(1).copy(
                changelog =
                    listOf(
                        ParsedImportChangeGroup(
                            authorEmail = "Alice@Corp.com",
                            created = "2024-01-16T08:30:00.000+0000",
                            items =
                                listOf(
                                    ParsedImportChangeItem(
                                        field = "status",
                                        fromValue = "To Do",
                                        toValue = "In Progress",
                                    ),
                                    ParsedImportChangeItem(field = "assignee", fromValue = null, toValue = "bob"),
                                ),
                        ),
                    ),
            )
        stubParserWithRows(listOf(row))
        val cmdSlot = slot<IssueImportCommand>()
        every { issueImportPort.importIssue(capture(cmdSlot)) } returns IssueImportResult.success("PROJ-1")

        processor.process(makeJob())

        val group = cmdSlot.captured.changelog.single()
        assertThat(group.authorEmail).isEqualTo("alice@corp.com")
        assertThat(group.occurredAt).isEqualTo(Instant.parse("2024-01-16T08:30:00.000Z"))
        assertThat(group.items).hasSize(2)
        assertThat(group.items[0].field).isEqualTo("status")
        assertThat(group.items[0].fromValue).isEqualTo("To Do")
        assertThat(group.items[0].toValue).isEqualTo("In Progress")
        assertThat(group.items[1].field).isEqualTo("assignee")
        assertThat(group.items[1].fromValue).isNull()
        assertThat(group.items[1].toValue).isEqualTo("bob")
    }

    @Test
    fun `toCommand maps empty parsed attachments and changelog to empty lists and null sourceKey`() {
        stubParserWithRows(listOf(makeRow(1)))
        val cmdSlot = slot<IssueImportCommand>()
        every { issueImportPort.importIssue(capture(cmdSlot)) } returns IssueImportResult.success("PROJ-1")

        processor.process(makeJob())

        assertThat(cmdSlot.captured.attachments).isEmpty()
        assertThat(cmdSlot.captured.changelog).isEmpty()
        assertThat(cmdSlot.captured.sourceKey).isNull()
    }

    // ── (m) 매핑 기반 CSV 파싱 로드 (FR-IM-02 PR-A Task 9) ──────────────────────────

    @Test
    fun `job with saved field mapping loads mapping and calls mapped 3-arg parseCsv overload`() {
        val fieldMapping = mapOf("제목" to "summary")
        every { mappingRepo.findByJobId(jobId) } returns fieldMapping
        val callbackSlot = slot<(ParsedImportRow) -> Unit>()
        val mappingSlot = slot<Map<String, String>>()
        every { parser.parseCsv(any(), capture(mappingSlot), capture(callbackSlot)) } answers {
            callbackSlot.captured(makeRow(1))
        }
        every { issueImportPort.importIssue(any()) } returns IssueImportResult.success("PROJ-1")

        processor.process(makeJob())

        verify(exactly = 1) { mappingRepo.findByJobId(jobId) }
        verify(exactly = 1) { parser.parseCsv(any(), any(), any()) }
        verify(exactly = 0) { parser.parseCsv(any(), any()) }
        assertThat(mappingSlot.captured).isEqualTo(fieldMapping)
    }

    @Test
    fun `job with no saved field mapping loads empty map and calls canonical 2-arg parseCsv overload`() {
        every { mappingRepo.findByJobId(jobId) } returns emptyMap()
        stubParserWithRows(listOf(makeRow(1)))
        every { issueImportPort.importIssue(any()) } returns IssueImportResult.success("PROJ-1")

        processor.process(makeJob())

        verify(exactly = 1) { mappingRepo.findByJobId(jobId) }
        verify(exactly = 1) { parser.parseCsv(any(), any()) }
        verify(exactly = 0) { parser.parseCsv(any(), any(), any()) }
    }

    // ── (n) 사용자 매핑 해석 (FR-IM-02 PR-B Task 6) ─────────────────────────────────

    private val aliceId = UUID.fromString("00000000-0000-0000-0000-0000000000a1")
    private val bobId = UUID.fromString("00000000-0000-0000-0000-0000000000b1")

    @Test
    fun `job with saved user mapping resolves reporter and assignee userId via normalized email lookup`() {
        val row = makeRow(1).copy(reporterEmail = "Alice@Corp.com", assigneeEmail = "Bob@Corp.com")
        stubParserWithRows(listOf(row))
        every { userMappingRepo.findByJobId(jobId) } returns
            mapOf("alice@corp.com" to aliceId, "bob@corp.com" to bobId)
        val cmdSlot = slot<IssueImportCommand>()
        every { issueImportPort.importIssue(capture(cmdSlot)) } returns IssueImportResult.success("PROJ-1")

        processor.process(makeJob())

        assertThat(cmdSlot.captured.reporterUserId).isEqualTo(aliceId)
        assertThat(cmdSlot.captured.assigneeUserId).isEqualTo(bobId)
        // 이메일 필드는 그대로 보존 — 어댑터 폴백용(userId 우선 규칙, IssueImportCommand KDoc).
        assertThat(cmdSlot.captured.reporterEmail).isEqualTo("Alice@Corp.com")
        assertThat(cmdSlot.captured.assigneeEmail).isEqualTo("Bob@Corp.com")
    }

    @Test
    fun `user mapping is loaded exactly once per job regardless of row count`() {
        stubParserWithRows(listOf(makeRow(1), makeRow(2), makeRow(3)))
        every { issueImportPort.importIssue(any()) } returns IssueImportResult.success("PROJ-1")

        processor.process(makeJob())

        verify(exactly = 1) { userMappingRepo.findByJobId(jobId) }
    }

    @Test
    fun `identifier absent from saved user mapping resolves to null userId fallback`() {
        val row = makeRow(1).copy(reporterEmail = "unknown@corp.com")
        stubParserWithRows(listOf(row))
        every { userMappingRepo.findByJobId(jobId) } returns mapOf("alice@corp.com" to aliceId)
        val cmdSlot = slot<IssueImportCommand>()
        every { issueImportPort.importIssue(capture(cmdSlot)) } returns IssueImportResult.success("PROJ-1")

        processor.process(makeJob())

        assertThat(cmdSlot.captured.reporterUserId).isNull()
    }

    @Test
    fun `identifier mapped to explicit null target value resolves to null userId fallback`() {
        val row = makeRow(1).copy(reporterEmail = "Alice@Corp.com")
        stubParserWithRows(listOf(row))
        every { userMappingRepo.findByJobId(jobId) } returns mapOf("alice@corp.com" to null)
        val cmdSlot = slot<IssueImportCommand>()
        every { issueImportPort.importIssue(capture(cmdSlot)) } returns IssueImportResult.success("PROJ-1")

        processor.process(makeJob())

        assertThat(cmdSlot.captured.reporterUserId).isNull()
    }

    @Test
    fun `job without any saved user mapping leaves all userId fields null across all VOs (regression)`() {
        val row =
            makeRow(1).copy(
                reporterEmail = "alice@corp.com",
                assigneeEmail = "bob@corp.com",
                comments = listOf(ParsedImportComment(body = "댓글", authorEmail = "bob@corp.com", createdAt = null)),
                worklogs =
                    listOf(
                        ParsedImportWorklog(timeSpentSeconds = 60, startedAt = null, authorEmail = "bob@corp.com"),
                    ),
                attachments =
                    listOf(
                        ParsedImportAttachment(
                            filename = "a.png",
                            authorEmail = "alice@corp.com",
                            created = null,
                            mimeType = null,
                            sizeBytes = null,
                        ),
                    ),
                changelog =
                    listOf(
                        ParsedImportChangeGroup(
                            authorEmail = "alice@corp.com",
                            created = null,
                            items =
                                listOf(
                                    ParsedImportChangeItem(field = "status", fromValue = null, toValue = "Done"),
                                ),
                        ),
                    ),
            )
        // userMappingRepo.findByJobId 는 setUp() 기본값(emptyMap) 그대로 — 매핑을 저장한 적 없는 job.
        stubParserWithRows(listOf(row))
        val cmdSlot = slot<IssueImportCommand>()
        every { issueImportPort.importIssue(capture(cmdSlot)) } returns IssueImportResult.success("PROJ-1")

        processor.process(makeJob())

        val command = cmdSlot.captured
        assertThat(command.reporterUserId).isNull()
        assertThat(command.assigneeUserId).isNull()
        assertThat(command.comments.single().authorUserId).isNull()
        assertThat(command.worklogs.single().authorUserId).isNull()
        assertThat(command.attachments.single().authorUserId).isNull()
        assertThat(command.changelog.single().authorUserId).isNull()
        // 기존 이메일 필드는 회귀 없이 그대로 보존된다.
        assertThat(command.reporterEmail).isEqualTo("alice@corp.com")
        assertThat(command.assigneeEmail).isEqualTo("bob@corp.com")
    }

    @Test
    fun `toCommand resolves authorUserId across comments worklogs attachments and changelog`() {
        val row =
            makeRow(1).copy(
                comments = listOf(ParsedImportComment(body = "댓글", authorEmail = "Bob@Corp.com", createdAt = null)),
                worklogs =
                    listOf(
                        ParsedImportWorklog(timeSpentSeconds = 60, startedAt = null, authorEmail = "Bob@Corp.com"),
                    ),
                attachments =
                    listOf(
                        ParsedImportAttachment(
                            filename = "a.png",
                            authorEmail = "Alice@Corp.com",
                            created = null,
                            mimeType = null,
                            sizeBytes = null,
                        ),
                    ),
                changelog =
                    listOf(
                        ParsedImportChangeGroup(
                            authorEmail = "Alice@Corp.com",
                            created = null,
                            items =
                                listOf(
                                    ParsedImportChangeItem(field = "status", fromValue = null, toValue = "Done"),
                                ),
                        ),
                    ),
            )
        stubParserWithRows(listOf(row))
        every { userMappingRepo.findByJobId(jobId) } returns
            mapOf("bob@corp.com" to bobId, "alice@corp.com" to aliceId)
        val cmdSlot = slot<IssueImportCommand>()
        every { issueImportPort.importIssue(capture(cmdSlot)) } returns IssueImportResult.success("PROJ-1")

        processor.process(makeJob())

        val command = cmdSlot.captured
        assertThat(command.comments.single().authorUserId).isEqualTo(bobId)
        assertThat(command.worklogs.single().authorUserId).isEqualTo(bobId)
        assertThat(command.attachments.single().authorUserId).isEqualTo(aliceId)
        assertThat(command.changelog.single().authorUserId).isEqualTo(aliceId)
    }

    // ── (o) 값 매핑 치환 (FR-IM-02 PR-C Task 6) ─────────────────────────────────────

    @Test
    fun `job with saved value mapping substitutes statusName to mapped target value`() {
        val row = makeRow(1).copy(statusName = "Open")
        stubParserWithRows(listOf(row))
        every { valueMappingRepo.findByJobId(jobId) } returns
            mapOf((ValueTargetField.STATUS to "open") to "In Progress")
        val cmdSlot = slot<IssueImportCommand>()
        every { issueImportPort.importIssue(capture(cmdSlot)) } returns IssueImportResult.success("PROJ-1")

        processor.process(makeJob())

        assertThat(cmdSlot.captured.statusName).isEqualTo("In Progress")
    }

    @Test
    fun `job with saved value mapping substitutes typeName to mapped target value`() {
        val row = makeRow(1).copy(typeName = "Story")
        stubParserWithRows(listOf(row))
        every { valueMappingRepo.findByJobId(jobId) } returns
            mapOf((ValueTargetField.TYPE to "story") to "Task")
        val cmdSlot = slot<IssueImportCommand>()
        every { issueImportPort.importIssue(capture(cmdSlot)) } returns IssueImportResult.success("PROJ-1")

        processor.process(makeJob())

        assertThat(cmdSlot.captured.typeName).isEqualTo("Task")
    }

    @Test
    fun `job with saved value mapping substitutes priorityName to canonical target and resolves priority number (C1)`() {
        val row = makeRow(1).copy(priorityName = "urgent")
        stubParserWithRows(listOf(row))
        // ImportMappingService.confirm 이 저장 시 이미 canonical 정확 표기("Medium")로 치환해 두므로
        // 프로세서는 그 값을 그대로 PRIORITY_NUMBER_BY_NAME 조회 키로 쓸 수 있어야 한다 (C1 회귀).
        every { valueMappingRepo.findByJobId(jobId) } returns
            mapOf((ValueTargetField.PRIORITY to "urgent") to "Medium")
        val cmdSlot = slot<IssueImportCommand>()
        every { issueImportPort.importIssue(capture(cmdSlot)) } returns IssueImportResult.success("PROJ-1")

        processor.process(makeJob())

        assertThat(cmdSlot.captured.priority).isEqualTo(3)
    }

    @Test
    fun `unmapped source value falls back to original parsed value`() {
        val row = makeRow(1).copy(typeName = "Bug", statusName = "Custom Status")
        stubParserWithRows(listOf(row))
        every { valueMappingRepo.findByJobId(jobId) } returns
            mapOf((ValueTargetField.TYPE to "story") to "Task")
        val cmdSlot = slot<IssueImportCommand>()
        every { issueImportPort.importIssue(capture(cmdSlot)) } returns IssueImportResult.success("PROJ-1")

        processor.process(makeJob())

        assertThat(cmdSlot.captured.typeName).isEqualTo("Bug")
        assertThat(cmdSlot.captured.statusName).isEqualTo("Custom Status")
    }

    @Test
    fun `null source value is left unmapped even when a mapping exists for the target field`() {
        stubParserWithRows(listOf(makeRow(1)))
        every { valueMappingRepo.findByJobId(jobId) } returns
            mapOf((ValueTargetField.TYPE to "") to "Task")
        val cmdSlot = slot<IssueImportCommand>()
        every { issueImportPort.importIssue(capture(cmdSlot)) } returns IssueImportResult.success("PROJ-1")

        processor.process(makeJob())

        assertThat(cmdSlot.captured.typeName).isNull()
    }

    @Test
    fun `value mapping is loaded exactly once per job regardless of row count`() {
        stubParserWithRows(listOf(makeRow(1), makeRow(2), makeRow(3)))
        every { issueImportPort.importIssue(any()) } returns IssueImportResult.success("PROJ-1")

        processor.process(makeJob())

        verify(exactly = 1) { valueMappingRepo.findByJobId(jobId) }
    }
}
