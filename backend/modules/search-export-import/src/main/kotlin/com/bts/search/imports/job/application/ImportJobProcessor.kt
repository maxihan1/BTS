// 비동기 Import 작업 처리기 — 스트리밍 파싱 + 행별 IssueImportPort 위임 + 상태 갱신 (FR-IM-01 PR1 Task 9)

package com.bts.search.imports.job.application

import com.bts.search.imports.job.domain.ImportJob
import com.bts.search.imports.job.repository.ImportJobRepository
import com.bts.search.imports.job.storage.ImportObjectStoragePort
import com.bts.search.imports.parse.ImportParseException
import com.bts.search.imports.parse.ImportRowParser
import com.bts.search.imports.parse.ParsedImportComment
import com.bts.search.imports.parse.ParsedImportRow
import com.bts.search.imports.parse.ParsedImportWorklog
import com.bts.shared.issue.ImportComment
import com.bts.shared.issue.ImportWorklog
import com.bts.shared.issue.IssueImportCommand
import com.bts.shared.issue.IssueImportPort
import com.bts.shared.issue.IssueImportResult
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime

/**
 * [ImportJob] 처리기 — 스트리밍 CSV/JSON 파싱 + 행별 [IssueImportPort] 위임 + 상태 갱신.
 *
 * `export` BC [com.bts.search.export.job.application.ExportJobProcessor] 를 구조적으로 미러하되,
 * count-first 페이지 순회 대신 [ImportRowParser] 콜백 기반 행 순회를 사용한다.
 *
 * ## @Transactional 의도적 생략
 *
 * [process] 는 최대 10만 행을 스트리밍 파싱하며 행마다 [IssueImportPort.importIssue](자체
 * `@Transactional` 트랜잭션 1건)를 호출하는 **분 단위** I/O 집약 작업이다. 이 메서드에
 * `@Transactional` 을 붙이면 상위 트랜잭션이 하위 행 트랜잭션을 감싸면서 분 단위로 DB 커넥션을
 * 점유해 커넥션 풀 고갈을 유발한다. 상태 변경([ImportJobRepository.updateCounts]/
 * [ImportJobRepository.markCompleted]/[ImportJobRepository.markFailed])은 각 메서드 단독
 * `@Transactional` 이 처리한다. FR-AC-01 "100MB I/O @Transactional 밖", `export` BC FR-EX-02
 * ExportJobProcessor 선례를 따른다.
 *
 * ## 행 상한 처리 — 사전 카운트 불가, 처리 중 카운터 초과 시 중단
 *
 * [ImportRowParser] 는 OOM 방지를 위해 파일 전체를 적재하지 않는 스트리밍 콜백 API 다
 * (해당 클래스 KDoc §스트리밍 설계 참조). 따라서 `export` BC 의 count-first 방식(총 건수를 먼저
 * 조회한 뒤 상한을 판정)과 달리, 이 처리기는 콜백이 호출될 때마다 행 카운터를 증가시키고
 * [ImportJob.MAX_ROWS] 초과 시점에 [ImportRowLimitExceededException] 을 던져 파싱을 즉시
 * 중단한다 — 이미 처리된 행이 있더라도 작업 전체를 FAILED 로 판정한다(부분 완료로 COMPLETED 되지
 * 않는다).
 *
 * ## dryRun 위임
 *
 * [ImportJob.dryRun] 을 이 처리기가 별도로 분기하지 않고 [IssueImportCommand.dryRun] 에 그대로
 * 전달한다. 실제 생성 여부는 [IssueImportPort] 구현체(어댑터) 책임이며, dryRun 결과도 성공/실패
 * 집계에 동일하게 반영된다(검증 리포트 용도).
 *
 * ## 댓글/worklog 매핑 (PR3)
 *
 * [toCommand] 가 [ParsedImportRow.comments]/[ParsedImportRow.worklogs](원본 문자열 raw 값)를
 * [ImportComment]/[ImportWorklog](shared-kernel VO) 로 변환한다 — [toImportComment]/[toImportWorklog].
 * 작성자 이메일은 소문자화하고, 원본 시각 문자열은 [parseInstantOrNull] 로 [Instant] 변환을 시도한다.
 * 값이 없거나 파싱에 실패하면 null 을 담는다(created=now 대체, worklog 스킵 등 best-effort 폴백은
 * 이 클래스가 아니라 어댑터(Task 7, issue-tracking) 책임 — 이 클래스는 순수 변환만 한다).
 *
 * @param issueImportPort 이슈 생성 cross-BC 쓰기 포트.
 * @param storage 원본 파일 조회 + 에러 로그 업로드용 오브젝트 스토리지 포트.
 * @param repository Import 작업 상태 관리 저장소.
 * @param errorLogWriter 실패행 CSV 에러 로그 직렬화기.
 * @param parser CSV/JSON 스트리밍 파서. [ImportRowParser] 는 Spring 빈으로 등록되어 있지 않으므로
 *   ([ImportJobRepository] 의 Clock 기본값 패턴과 동일하게) 기본값으로 직접 인스턴스화한다.
 * @param clock expiresAt 결정용 시계. search 모듈에 Clock 빈이 없으므로 기본값 [Clock.systemUTC] 사용.
 */
@Component
@Suppress("TooManyFunctions") // PR3 comments/worklogs 매핑 헬퍼 추가로 임계 초과 — 단일 행 변환 책임 응집, 분리 시 오히려 산개
class ImportJobProcessor(
    private val issueImportPort: IssueImportPort,
    private val storage: ImportObjectStoragePort,
    private val repository: ImportJobRepository,
    private val errorLogWriter: ImportErrorLogWriter,
    private val parser: ImportRowParser = ImportRowParser(),
    private val clock: Clock = Clock.systemUTC(),
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * [job] 을 처리한다. 워커가 [ImportJobRepository.claimForRun] 으로 RUNNING 전환 후 호출한다.
     *
     * `@Transactional` **의도적 생략** — 클래스 KDoc 참조.
     * TooGenericExceptionCaught: catch-all 의도적 — 인프라 오류로 작업이 RUNNING 에 방치되는 것을
     * 막기 위해 분류되지 않은 예외도 [IMPORT_INTERNAL_ERROR] 로 FAILED 전환한다
     * (`ExportJobProcessor` 의 `SEARCH_INTERNAL_ERROR` catch-all 과 동형).
     *
     * @param job 처리할 Import 작업. RUNNING 상태임이 보장되어야 한다.
     */
    @Suppress("TooGenericExceptionCaught")
    fun process(job: ImportJob) {
        log.info("import_job_process_start jobId={} projectKey={} format={}", job.id, job.projectKey, job.format)
        try {
            processRows(job)
        } catch (e: ImportParseException) {
            log.warn("import_parse_failed jobId={} message={}", job.id, e.message)
            repository.markFailed(job.id, IMPORT_PARSE_FAILED)
        } catch (e: ImportRowLimitExceededException) {
            log.warn("import_row_limit_exceeded jobId={}", job.id, e)
            repository.markFailed(job.id, IMPORT_ROW_LIMIT_EXCEEDED)
        } catch (e: Exception) {
            log.error("import_job_error jobId={}", job.id, e)
            repository.markFailed(job.id, IMPORT_INTERNAL_ERROR)
        }
    }

    /**
     * 원본 파일을 열어 형식에 맞는 파서로 행을 순회하고, 완료 후 [finalizeCompleted] 로 전환한다.
     *
     * @throws ImportParseException 파일 구조가 깨졌을 때(헤더 없음, JSON 구문 오류 등).
     * @throws ImportRowLimitExceededException 행 카운터가 [ImportJob.MAX_ROWS] 를 초과했을 때.
     */
    private fun processRows(job: ImportJob) {
        val state = RowProcessingState()
        storage.get(job.sourceObjectKey).use { input ->
            val onRow: (ParsedImportRow) -> Unit = { row -> handleRow(job, row, state) }
            when (job.format) {
                FORMAT_CSV -> parser.parseCsv(input, onRow)
                FORMAT_JSON -> parser.parseJson(input, onRow)
                else -> error("unsupported import format: ${job.format}") // DB CHECK 제약으로 도달불가 — 방어적 guard
            }
        }
        finalizeCompleted(job, state)
    }

    /**
     * 행 1건을 처리한다 — 카운터 증가/상한 검사, [IssueImportPort] 위임, 결과 집계, 주기적 진행률 갱신.
     *
     * @throws ImportRowLimitExceededException 이 행 포함 누적 카운트가 [ImportJob.MAX_ROWS] 를
     *   초과했을 때. 이 행 자체는 [issueImportPort] 에 위임되지 않는다.
     */
    private fun handleRow(
        job: ImportJob,
        row: ParsedImportRow,
        state: RowProcessingState,
    ) {
        state.rowCount++
        if (state.rowCount > ImportJob.MAX_ROWS) {
            throw ImportRowLimitExceededException()
        }
        when (val result = issueImportPort.importIssue(toCommand(job, row))) {
            is IssueImportResult.Success -> {
                state.succeededRows++
                result.warnings.forEach { warning ->
                    state.warningRecords +=
                        FailedRowRecord(
                            rowNumber = row.rowNumber,
                            reasonCode = WARNING_REASON_CODE,
                            message = warning,
                            severity = FailedRowRecord.SEVERITY_WARNING,
                        )
                }
            }
            is IssueImportResult.Failure -> {
                state.failedRows++
                state.failedRecords += FailedRowRecord(row.rowNumber, result.reasonCode, result.message)
            }
        }
        if (state.rowCount % PROGRESS_UPDATE_INTERVAL_ROWS == 0L) {
            repository.updateCounts(
                job.id,
                progress = job.progressPercent(state.rowCount, ImportJob.MAX_ROWS),
                totalRows = state.rowCount,
                succeededRows = state.succeededRows,
                failedRows = state.failedRows,
            )
        }
    }

    /**
     * 파싱/생성이 모두 끝난 뒤 작업을 COMPLETED 로 전환한다.
     *
     * 실패행 또는 경고행(둘 중 하나라도)이 있으면 [uploadErrorLog] 로 CSV 를 업로드해 errorLogObjectKey
     * 를 확보한다. 경고행 노출은 PR2 Task 5(G1) — PR1 은 [IssueImportResult.Success.warnings] 를
     * 폐기했다. rowNumber 오름차순으로 정렬해 업로드한다.
     *
     * ## progress/totalRows 확정 (PR1 코드리뷰 C2 수정)
     *
     * [handleRow] 의 주기 갱신은 [PROGRESS_UPDATE_INTERVAL_ROWS] 배수 행에서만
     * [ImportJobRepository.updateCounts] 를 호출한다. 처리 행 수가 그 배수에 못 미치는 파일
     * (예: 50 행)은 완료 시점까지 단 한 번도 갱신되지 않아 progress=0·totalRows=null 로 남았다.
     * 100 행 이상인 파일도 마지막 배수 이후 처리된 잔여 행이 반영되지 않아 progress/totalRows 가
     * 실제보다 낮게 stale 된다. 이를 막기 위해 [markCompleted][ImportJobRepository.markCompleted]
     * 직전에 진행률을 100·totalRows 를 실제 처리 행수([RowProcessingState.rowCount])로 확정하는
     * [ImportJobRepository.updateCounts] 를 한 번 더 호출한다.
     */
    private fun finalizeCompleted(
        job: ImportJob,
        state: RowProcessingState,
    ) {
        val logRecords = (state.failedRecords + state.warningRecords).sortedBy { it.rowNumber }
        val errorLogObjectKey = if (logRecords.isEmpty()) null else uploadErrorLog(job, logRecords)
        val expiresAt = clock.instant().plusSeconds(RESULT_TTL_SECONDS)
        repository.updateCounts(
            job.id,
            progress = COMPLETED_PROGRESS_PERCENT,
            totalRows = state.rowCount,
            succeededRows = state.succeededRows,
            failedRows = state.failedRows,
        )
        val marked =
            repository.markCompleted(job.id, state.succeededRows, state.failedRows, errorLogObjectKey, expiresAt)
        if (!marked) {
            log.warn("import_mark_completed_skipped jobId={} reason=already_in_terminal_state", job.id)
        }
        log.info(
            "import_job_completed jobId={} succeeded={} failed={}",
            job.id,
            state.succeededRows,
            state.failedRows,
        )
    }

    /** 실패/경고행 CSV 를 만들어 MinIO 에 업로드하고 오브젝트 키를 반환한다. */
    private fun uploadErrorLog(
        job: ImportJob,
        records: List<FailedRowRecord>,
    ): String {
        val bytes = ByteArrayOutputStream().apply { errorLogWriter.write(this, records) }.toByteArray()
        val objectKey = buildErrorLogObjectKey(job)
        ByteArrayInputStream(bytes).use { storage.put(objectKey, it, bytes.size.toLong(), ERROR_LOG_CONTENT_TYPE) }
        return objectKey
    }

    private fun buildErrorLogObjectKey(job: ImportJob): String = "${job.projectKey}/${job.id}-errors.csv"

    /** [ParsedImportRow] 1건을 [IssueImportCommand] 로 변환한다. */
    private fun toCommand(
        job: ImportJob,
        row: ParsedImportRow,
    ): IssueImportCommand =
        IssueImportCommand(
            projectKey = job.projectKey,
            requesterUserId = job.requesterUserId,
            summary = row.summary.orEmpty(),
            typeName = row.typeName,
            description = row.description,
            priority = row.priorityName?.let { PRIORITY_NUMBER_BY_NAME[it] },
            reporterEmail = row.reporterEmail,
            assigneeEmail = row.assigneeEmail,
            labels = row.labels,
            componentNames = row.componentNames,
            dryRun = job.dryRun,
            statusName = row.statusName,
            fixVersionNames = row.fixVersionNames,
            affectsVersionNames = row.affectsVersionNames,
            comments = row.comments.map(::toImportComment),
            worklogs = row.worklogs.map(::toImportWorklog),
        )

    /** [ParsedImportComment](raw 문자열) 를 [ImportComment](shared VO, `createdAt` 이 [Instant]) 로 변환한다. */
    private fun toImportComment(comment: ParsedImportComment): ImportComment =
        ImportComment(
            body = comment.body,
            authorEmail = comment.authorEmail?.lowercase(),
            createdAt = parseInstantOrNull(comment.createdAt),
        )

    /** [ParsedImportWorklog](raw 문자열) 를 [ImportWorklog](shared VO, `startedAt` 이 [Instant]) 로 변환한다. */
    private fun toImportWorklog(worklog: ParsedImportWorklog): ImportWorklog =
        ImportWorklog(
            timeSpentSeconds = worklog.timeSpentSeconds,
            startedAt = parseInstantOrNull(worklog.startedAt),
            authorEmail = worklog.authorEmail?.lowercase(),
            comment = worklog.comment,
        )

    /**
     * Jira 소스(comment/worklog) 의 ISO-8601 시각 문자열을 [Instant] 로 변환한다.
     *
     * [OffsetDateTime.parse] 는 콜론 포함 오프셋(`+09:00`)과 `Z` 는 그대로 받아들이지만, Jira 레거시
     * export 의 콜론 없는 오프셋(`+0000`)은 [normalizeNoColonOffset] 으로 콜론을 삽입한 뒤 파싱한다.
     * 파싱 불가(값 없음/형식 오류)면 null — 이후 폴백(예: import 실행 시각 사용)은 다음 단계
     * (Task 7 어댑터) 책임이다.
     */
    private fun parseInstantOrNull(raw: String?): Instant? {
        if (raw.isNullOrBlank()) return null
        return runCatching { OffsetDateTime.parse(normalizeNoColonOffset(raw)).toInstant() }.getOrNull()
    }

    /** 콜론 없는 오프셋(`+0000`)을 [OffsetDateTime.parse] 가 요구하는 콜론 포함 형식(`+00:00`)으로 정규화한다. */
    private fun normalizeNoColonOffset(raw: String): String =
        NO_COLON_OFFSET_REGEX.replace(raw) { match -> "${match.groupValues[1]}:${match.groupValues[2]}" }

    companion object {
        private const val FORMAT_CSV = "CSV"
        private const val FORMAT_JSON = "JSON"

        /** 진행률 갱신 주기(행 수 단위). `ExportJobProcessor.PAGE_SIZE`(100) 갱신 빈도를 미러한다. */
        const val PROGRESS_UPDATE_INTERVAL_ROWS = 100L

        /**
         * COMPLETED 전환 시 확정하는 진행률(%).
         *
         * [finalizeCompleted] 가 [PROGRESS_UPDATE_INTERVAL_ROWS] 주기 갱신 누락분을 보정하기 위해
         * 항상 이 값으로 진행률을 확정한다 (PR1 코드리뷰 C2 수정).
         */
        const val COMPLETED_PROGRESS_PERCENT = 100

        /** 결과(에러 로그) 파일 TTL(초) — 24시간. `ExportJobProcessor.RESULT_TTL_SECONDS` 와 동일 값. */
        @Suppress("MagicNumber")
        const val RESULT_TTL_SECONDS: Long = 24 * 60 * 60L

        /** 에러 로그 CSV Content-Type. `ExportFormat.CSV.contentType` 과 동일 값. */
        private const val ERROR_LOG_CONTENT_TYPE = "text/csv; charset=UTF-8"

        /** CSV/JSON 파일 구조 오류([ImportParseException]) 발생 시 오류 코드. */
        const val IMPORT_PARSE_FAILED = "IMPORT_PARSE_FAILED"

        /** 처리 행 수가 [ImportJob.MAX_ROWS] 를 초과했을 때의 오류 코드. */
        const val IMPORT_ROW_LIMIT_EXCEEDED = "IMPORT_ROW_LIMIT_EXCEEDED"

        /** 분류되지 않은 내부 오류 코드. */
        const val IMPORT_INTERNAL_ERROR = "IMPORT_INTERNAL_ERROR"

        /**
         * [IssueImportResult.Success.warnings] 를 [FailedRowRecord.reasonCode] 로 담을 때 쓰는 고정
         * 사유 코드(PR2 Task 5, G1). 개별 경고 문구는 [FailedRowRecord.message] 에 담기고, 이 코드는
         * 결과 로그 CSV 에서 "경고행" 임을 식별하는 용도다(사유별 세분화 코드는 [IssueImportResult] 가
         * 아직 제공하지 않는다 — 자유 텍스트 [List] 하나뿐).
         */
        const val WARNING_REASON_CODE = "IMPORT_WARNING"

        /**
         * [ParsedImportRow.priorityName] 정규화 이름 → [IssueImportCommand.priority] 숫자 매핑.
         *
         * `ImportRowParser.PRIORITY_NAME_BY_NUMBER` 의 역방향 표다. search 모듈은 issue-tracking
         * 의 `IssuePriority` 를 직접 import 할 수 없으므로(BC 격리) 이 클래스 안에 로컬로 유지한다.
         */
        private val PRIORITY_NUMBER_BY_NAME: Map<String, Int> =
            mapOf(
                "Highest" to 1,
                "High" to 2,
                "Medium" to 3,
                "Low" to 4,
                "Lowest" to 5,
            )

        /** 콜론 없는 오프셋(`+0000`/`-0500`, 문자열 끝) 매칭 — [normalizeNoColonOffset] 정규화용. */
        private val NO_COLON_OFFSET_REGEX = Regex("""([+-]\d{2})(\d{2})$""")
    }
}

/** 행 순회 중 누적되는 가변 상태 — [ImportJobProcessor.processRows] 지역 스코프에서만 사용. */
private class RowProcessingState {
    var rowCount: Long = 0
    var succeededRows: Long = 0
    var failedRows: Long = 0
    val failedRecords: MutableList<FailedRowRecord> = mutableListOf()

    /** 성공했지만 best-effort 로 일부 필드를 반영하지 못한 경고행([FailedRowRecord.severity] = WARNING). */
    val warningRecords: MutableList<FailedRowRecord> = mutableListOf()
}

/** 행 카운터가 [ImportJob.MAX_ROWS] 를 초과했을 때 파싱을 중단시키는 내부 제어 신호. */
private class ImportRowLimitExceededException : RuntimeException()
