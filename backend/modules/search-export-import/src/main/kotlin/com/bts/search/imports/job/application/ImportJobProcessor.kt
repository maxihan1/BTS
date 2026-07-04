// 비동기 Import 작업 처리기 — 스트리밍 파싱 + 행별 IssueImportPort 위임 + 상태 갱신 (FR-IM-01 PR1 Task 9)

package com.bts.search.imports.job.application

import com.bts.search.imports.job.domain.ImportJob
import com.bts.search.imports.job.repository.ImportJobRepository
import com.bts.search.imports.job.storage.ImportObjectStoragePort
import com.bts.search.imports.mapping.UserMappingNormalizer
import com.bts.search.imports.mapping.ValueMappingNormalizer
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
import com.bts.shared.issue.ImportAttachment
import com.bts.shared.issue.ImportChangeGroup
import com.bts.shared.issue.ImportChangeItem
import com.bts.shared.issue.ImportComment
import com.bts.shared.issue.ImportWorklog
import com.bts.shared.issue.IssueImportCommand
import com.bts.shared.issue.IssueImportPort
import com.bts.shared.issue.IssueImportResult
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

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
 * ## 매핑 기반 CSV 파싱 로드 (FR-IM-02 PR-A)
 *
 * [processRows] 가 CSV 포맷일 때만 [parseCsv] 헬퍼로 [mappingRepo] 에서 [job] 의 확정 매핑
 * ([ImportMappingRepository.findByJobId])을 로드한다. 매핑이 하나라도 있으면(Map 비어있지 않음)
 * [ImportRowParser.parseCsv] 3-인자(mapped) 오버로드로, 없으면(빈 Map — 매핑을 저장한 적 없는 job,
 * 기존 canonical 흐름) 2-인자(canonical) 오버로드로 위임한다. JSON 포맷은 이 로드를 거치지 않는다
 * (canonical 흐름 불변 — FR-IM-02 매핑 UI 는 CSV 임의 헤더 전용).
 *
 * ## 댓글/worklog 매핑 (PR3)
 *
 * [toCommand] 가 [ParsedImportRow.comments]/[ParsedImportRow.worklogs](원본 문자열 raw 값)를
 * [ImportComment]/[ImportWorklog](shared-kernel VO) 로 변환한다 — [toImportComment]/[toImportWorklog].
 * 작성자 이메일은 소문자화하고, 원본 시각 문자열은 [parseInstantOrNull] 로 [Instant] 변환을 시도한다.
 * 값이 없거나 파싱에 실패하면 null 을 담는다(created=now 대체, worklog 스킵 등 best-effort 폴백은
 * 이 클래스가 아니라 어댑터(Task 7, issue-tracking) 책임 — 이 클래스는 순수 변환만 한다).
 *
 * ## 사용자 매핑 로드 및 해석 (FR-IM-02 PR-B)
 *
 * [processRows] 가 CSV/JSON 포맷 공통으로 job 당 [userMappingRepo] 를 **1 회만** 호출해
 * ([ImportUserMappingRepository.findByJobId]) 확정 사용자 매핑(source_identifier → target_user_id?)을
 * 로드하고, 행 순회 전체에 재사용한다(행마다 조회하면 대량 파일에서 N+1 쿼리가 된다). [toCommand] 가
 * 각 행의 reporter/assignee 이메일과 댓글/worklog/첨부/changelog 의 authorEmail 을
 * [UserMappingNormalizer.normalize] 로 정규화한 뒤 이 맵에서 조회해 [IssueImportCommand.reporterUserId]/
 * [IssueImportCommand.assigneeUserId]/각 VO 의 `authorUserId` 를 세팅한다([resolveUserId]). 정규화는
 * [UserMappingNormalizer] 하나만 사용한다 — 저장(`ImportMappingService` 의 distinct 식별자 수집)과 조회
 * (이 클래스)가 서로 다른 정규화 규칙을 쓰면 같은 사용자가 다른 키로 취급되어 조용한 오배정이 발생한다.
 * 매핑에 없는 식별자, 매핑 값이 명시적으로 null(미매핑으로 저장됨), 매핑을 저장한 적 없는 job(빈 Map)
 * 은 모두 동일하게 userId null 로 귀결된다 — 기존 `reporterEmail`/`assigneeEmail`/`authorEmail` 필드는
 * 그대로 보존되어 구현체(어댑터)의 이메일 폴백 경로가 살아있다.
 *
 * ## 첨부/이력 매핑 (PR4)
 *
 * [toCommand] 가 [ParsedImportRow.sourceKey] 를 그대로 관통시키고, [ParsedImportRow.attachments]/
 * [ParsedImportRow.changelog](원본 문자열 raw 값)를 [ImportAttachment]/[ImportChangeGroup](shared-kernel
 * VO) 로 변환한다 — [toImportAttachment]/[toImportChangeGroup]/[toImportChangeItem]. 댓글/worklog
 * 매핑과 동일하게 작성자 이메일은 소문자화하고 시각 문자열은 [parseInstantOrNull] 로 변환한다.
 * **[ImportChangeItem.field] 는 원본(Jira 등)의 raw 필드명을 그대로 옮긴다** — BTS 내부 필드명으로의
 * 매핑은 issue-tracking BC 가 소유하는 도메인 지식이라(예: 전이 가능 상태, 담당자 개념) 이 클래스가
 * 대신 수행하면 그 지식이 BC 경계를 넘어 흩어진다. 실제 매핑은 [ImportChangeGroup] 을 소비하는
 * issue-tracking `IssueImportAdapter`(Task 9)가 담당한다.
 *
 * ## 값 매핑 로드 및 치환 (FR-IM-02 PR-C)
 *
 * [processRows] 가 사용자 매핑과 동일한 패턴으로 job 당 [valueMappingRepo] 를 **1 회만** 호출해
 * ([ImportValueMappingRepository.findByJobId]) 확정 값 매핑((대상 필드, source_value) → target_value)을
 * 로드하고, 행 순회 전체에 재사용한다(행마다 조회하면 N+1 쿼리가 된다). [toCommand] 가 각 행의
 * statusName/typeName/priorityName 을 [resolveMappedValue] 로 [ValueMappingNormalizer.normalize] 정규화한
 * 뒤 이 맵에서 조회해 저장된 대상 값으로 치환한다. 저장(`ImportMappingService` 의 `collectValues`/`confirm`
 * 검증)과 조회(이 클래스)가 서로 다른 정규화 규칙을 쓰면 같은 소스값이 다른 키로 취급되어 조용한
 * 오치환이 발생하므로 [ValueMappingNormalizer] 하나만 정규화 진실원천으로 쓴다.
 *
 * 소스값이 null 이면(파싱된 행에 해당 필드 값 자체가 없음) 치환을 시도하지 않고 null 을 그대로 유지한다.
 * 정규화 후 매핑에 없는 값(미매핑) 또는 값 매핑을 저장한 적 없는 job(빈 Map, 기존 canonical 흐름)은
 * 원본 값을 그대로 유지한다 — 하위호환.
 *
 * **priorityName 치환과 [PRIORITY_NUMBER_BY_NAME] 조회 순서(C1)** — `ImportMappingService.confirm` 이
 * PRIORITY 대상 값을 저장 시점에 이미 canonical 5 종의 정확한 표기(`"Highest"`/`"High"`/`"Medium"`/
 * `"Low"`/`"Lowest"`)로 치환해 두므로, [valueMappingRepo] 가 반환하는 target_value 는 항상 이 정확한
 * 표기다. 따라서 [resolveMappedValue] 로 치환된 priorityName 을 그대로 [PRIORITY_NUMBER_BY_NAME] 의
 * 조회 키로 써도 안전하다 — 대소문자가 어긋나 조회가 조용히 실패(null)하는 일이 없다.
 *
 * @param issueImportPort 이슈 생성 cross-BC 쓰기 포트.
 * @param storage 원본 파일 조회 + 에러 로그 업로드용 오브젝트 스토리지 포트.
 * @param repository Import 작업 상태 관리 저장소.
 * @param mappingRepo CSV 확정 매핑(source_field → target_field) 조회 저장소(FR-IM-02 PR-A).
 * @param userMappingRepo 확정 사용자 매핑(source_identifier → target_user_id?) 조회 저장소(FR-IM-02 PR-B).
 * @param valueMappingRepo 확정 값 매핑((대상 필드, source_value) → target_value) 조회 저장소(FR-IM-02 PR-C).
 * @param errorLogWriter 실패행 CSV 에러 로그 직렬화기.
 * @param parser CSV/JSON 스트리밍 파서. [ImportRowParser] 는 Spring 빈으로 등록되어 있지 않으므로
 *   ([ImportJobRepository] 의 Clock 기본값 패턴과 동일하게) 기본값으로 직접 인스턴스화한다.
 * @param clock expiresAt 결정용 시계. search 모듈에 Clock 빈이 없으므로 기본값 [Clock.systemUTC] 사용.
 */
@Component
// TooManyFunctions: PR3 comments/worklogs 매핑 헬퍼 추가로 임계 초과 — 단일 행 변환 책임 응집, 분리 시 오히려 산개.
// LongParameterList: FR-IM-02 PR-B userMappingRepo 추가로 8개 — 각각 단일 책임 협력자, ImportJobService 와 동일 선례.
@Suppress("TooManyFunctions", "LongParameterList")
class ImportJobProcessor(
    private val issueImportPort: IssueImportPort,
    private val storage: ImportObjectStoragePort,
    private val repository: ImportJobRepository,
    private val mappingRepo: ImportMappingRepository,
    private val userMappingRepo: ImportUserMappingRepository,
    private val valueMappingRepo: ImportValueMappingRepository,
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
     * [openZipSourceOrNull] 로 첨부 zip 소스를 job 당 1회만 열어 모든 행이 재사용하고, 파싱이 끝나면
     * (성공/실패 무관) 닫는다 — [ZipImportAttachmentSource] 가 [java.io.Closeable] 이므로 nullable
     * 수신자에도 안전한 Kotlin stdlib `use` 를 그대로 사용한다(source 가 null 이어도 block 은 실행된다).
     *
     * @throws ImportParseException 파일 구조가 깨졌을 때(헤더 없음, JSON 구문 오류 등).
     * @throws ImportRowLimitExceededException 행 카운터가 [ImportJob.MAX_ROWS] 를 초과했을 때.
     */
    private fun processRows(job: ImportJob) {
        val state = RowProcessingState()
        val userMappings = userMappingRepo.findByJobId(job.id)
        val valueMappings = valueMappingRepo.findByJobId(job.id)
        openZipSourceOrNull(job).use { attachmentSource ->
            storage.get(job.sourceObjectKey).use { input ->
                val onRow: (ParsedImportRow) -> Unit = { row ->
                    handleRow(job, row, state, attachmentSource, userMappings, valueMappings)
                }
                when (job.format) {
                    FORMAT_CSV -> parseCsv(job, input, onRow)
                    FORMAT_JSON -> parser.parseJson(input, onRow)
                    else -> error("unsupported import format: ${job.format}") // DB CHECK 제약으로 도달불가 — 방어적 guard
                }
            }
        }
        finalizeCompleted(job, state)
    }

    /**
     * CSV 포맷 전용 분기 — [mappingRepo] 로 [job] 의 확정 매핑을 로드해 [ImportRowParser.parseCsv]
     * 2-인자(canonical)/3-인자(mapped) 오버로드 중 하나로 위임한다(클래스 KDoc §매핑 기반 CSV 파싱 로드).
     *
     * @param job 처리 중인 Import 작업 — [ImportMappingRepository.findByJobId] 조회 키(`job.id`)로 사용.
     * @param input CSV 원본 스트림.
     * @param onRow 파싱된 행 1건을 전달받는 콜백.
     */
    private fun parseCsv(
        job: ImportJob,
        input: InputStream,
        onRow: (ParsedImportRow) -> Unit,
    ) {
        val fieldMapping = mappingRepo.findByJobId(job.id)
        if (fieldMapping.isNotEmpty()) {
            parser.parseCsv(input, fieldMapping, onRow)
        } else {
            parser.parseCsv(input, onRow)
        }
    }

    /**
     * [job.attachmentsObjectKey][ImportJob.attachmentsObjectKey] 가 있고 dry-run 이 아니면
     * [ZipImportAttachmentSource] 를 연다.
     *
     * dry-run 은 실제 생성 없는 검증 미리보기라 최대 500MB 첨부 zip 다운로드가 낭비이므로 스킵한다
     * (첨부는 dry-run 결과에 반영되지 않는다 — 어댑터가 dry-run 이면 첨부/이력 자체를 적용하지 않는다).
     */
    private fun openZipSourceOrNull(job: ImportJob): ZipImportAttachmentSource? {
        if (job.dryRun) return null
        return job.attachmentsObjectKey?.let { objectKey -> ZipImportAttachmentSource(storage, objectKey) }
    }

    /**
     * 행 1건을 처리한다 — 카운터 증가/상한 검사, [IssueImportPort] 위임, 결과 집계, 주기적 진행률 갱신.
     *
     * [attachmentSource] 가 null 이 아니면 [IssueImportPort.importIssue] 2-arg 오버로드로 위임하고,
     * null 이면(첨부 zip 미첨부·dry-run) 기존 1-arg 오버로드를 그대로 호출한다 — 어댑터 기준으로는
     * 두 경로 모두 동일하게 첨부 소스 null 로 귀결되므로 결과는 동등하다(1-arg default 가 내부적으로
     * `importIssue(cmd, null)` 로 위임하기 때문. [IssueImportPort] KDoc 참조).
     *
     * @throws ImportRowLimitExceededException 이 행 포함 누적 카운트가 [ImportJob.MAX_ROWS] 를
     *   초과했을 때. 이 행 자체는 [issueImportPort] 에 위임되지 않는다.
     */
    private fun handleRow(
        job: ImportJob,
        row: ParsedImportRow,
        state: RowProcessingState,
        attachmentSource: ZipImportAttachmentSource?,
        userMappings: Map<String, UUID?>,
        valueMappings: Map<Pair<ValueTargetField, String>, String>,
    ) {
        state.rowCount++
        if (state.rowCount > ImportJob.MAX_ROWS) {
            throw ImportRowLimitExceededException()
        }
        val command = toCommand(job, row, userMappings, valueMappings)
        val result =
            if (attachmentSource != null) {
                issueImportPort.importIssue(command, attachmentSource)
            } else {
                issueImportPort.importIssue(command)
            }
        when (result) {
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

    /**
     * [ParsedImportRow] 1건을 [IssueImportCommand] 로 변환한다.
     *
     * statusName/typeName/priorityName 은 [resolveMappedValue] 로 [valueMappings] 치환을 먼저 거친 뒤
     * 커맨드에 반영한다(클래스 KDoc §값 매핑 로드 및 치환 참조). priorityName 은 치환 결과를
     * [PRIORITY_NUMBER_BY_NAME] 으로 숫자 변환하는 기존 순서를 그대로 유지한다.
     */
    private fun toCommand(
        job: ImportJob,
        row: ParsedImportRow,
        userMappings: Map<String, UUID?>,
        valueMappings: Map<Pair<ValueTargetField, String>, String>,
    ): IssueImportCommand {
        val typeName = resolveMappedValue(ValueTargetField.TYPE, row.typeName, valueMappings)
        val statusName = resolveMappedValue(ValueTargetField.STATUS, row.statusName, valueMappings)
        val priorityName = resolveMappedValue(ValueTargetField.PRIORITY, row.priorityName, valueMappings)
        return IssueImportCommand(
            projectKey = job.projectKey,
            requesterUserId = job.requesterUserId,
            summary = row.summary.orEmpty(),
            typeName = typeName,
            description = row.description,
            priority = priorityName?.let { PRIORITY_NUMBER_BY_NAME[it] },
            reporterEmail = row.reporterEmail,
            assigneeEmail = row.assigneeEmail,
            labels = row.labels,
            componentNames = row.componentNames,
            dryRun = job.dryRun,
            statusName = statusName,
            fixVersionNames = row.fixVersionNames,
            affectsVersionNames = row.affectsVersionNames,
            comments = row.comments.map { toImportComment(it, userMappings) },
            worklogs = row.worklogs.map { toImportWorklog(it, userMappings) },
            sourceKey = row.sourceKey,
            attachments = row.attachments.map { toImportAttachment(it, userMappings) },
            changelog = row.changelog.map { toImportChangeGroup(it, userMappings) },
            reporterUserId = resolveUserId(row.reporterEmail, userMappings),
            assigneeUserId = resolveUserId(row.assigneeEmail, userMappings),
        )
    }

    /**
     * 소스 값(상태/유형/우선순위 이름) [rawValue] 를 [ValueMappingNormalizer.normalize] 로 정규화한 뒤
     * [targetField] 와 조합한 키로 [valueMappings] 에서 조회한다. [rawValue] 가 null 이면 치환을 시도하지
     * 않고 null 을 그대로 반환한다(클래스 KDoc §값 매핑 로드 및 치환 참조). 매핑에 없으면(미매핑) [rawValue]
     * 원본을 그대로 반환한다 — 기존 canonical 흐름(값 매핑을 저장한 적 없는 job, 빈 Map)의 하위호환이다.
     */
    private fun resolveMappedValue(
        targetField: ValueTargetField,
        rawValue: String?,
        valueMappings: Map<Pair<ValueTargetField, String>, String>,
    ): String? {
        if (rawValue == null) return null
        val normalized = ValueMappingNormalizer.normalize(rawValue)
        return valueMappings[targetField to normalized] ?: rawValue
    }

    /**
     * 소스 식별자(이메일) [email] 을 [UserMappingNormalizer.normalize] 로 정규화한 뒤 [userMappings] 에서
     * 조회한다. [email] 이 없거나 공백뿐이면, 매핑에 키 자체가 없으면, 매핑 값이 명시적으로 null(미매핑으로
     * 저장됨)이면 전부 동일하게 null 을 반환한다(userId 폴백 — 클래스 KDoc §사용자 매핑 로드 및 해석).
     */
    private fun resolveUserId(
        email: String?,
        userMappings: Map<String, UUID?>,
    ): UUID? {
        if (email.isNullOrBlank()) return null
        return userMappings[UserMappingNormalizer.normalize(email)]
    }

    /** [ParsedImportComment](raw 문자열) 를 [ImportComment](shared VO, `createdAt` 이 [Instant]) 로 변환한다. */
    private fun toImportComment(
        comment: ParsedImportComment,
        userMappings: Map<String, UUID?>,
    ): ImportComment =
        ImportComment(
            body = comment.body,
            authorEmail = comment.authorEmail?.lowercase(),
            createdAt = parseInstantOrNull(comment.createdAt),
            authorUserId = resolveUserId(comment.authorEmail, userMappings),
        )

    /** [ParsedImportWorklog](raw 문자열) 를 [ImportWorklog](shared VO, `startedAt` 이 [Instant]) 로 변환한다. */
    private fun toImportWorklog(
        worklog: ParsedImportWorklog,
        userMappings: Map<String, UUID?>,
    ): ImportWorklog =
        ImportWorklog(
            timeSpentSeconds = worklog.timeSpentSeconds,
            startedAt = parseInstantOrNull(worklog.startedAt),
            authorEmail = worklog.authorEmail?.lowercase(),
            comment = worklog.comment,
            authorUserId = resolveUserId(worklog.authorEmail, userMappings),
        )

    /**
     * [ParsedImportAttachment](raw 문자열) 를 [ImportAttachment](shared VO, `createdAt` 이 [Instant]) 로
     * 변환한다. [ParsedImportAttachment.mimeType]/[ParsedImportAttachment.sizeBytes] 는 값 변환 없이 그대로
     * 옮긴다(재판정/재계산은 어댑터 책임).
     */
    private fun toImportAttachment(
        attachment: ParsedImportAttachment,
        userMappings: Map<String, UUID?>,
    ): ImportAttachment =
        ImportAttachment(
            filename = attachment.filename,
            authorEmail = attachment.authorEmail?.lowercase(),
            createdAt = parseInstantOrNull(attachment.created),
            mimeType = attachment.mimeType,
            sizeBytes = attachment.sizeBytes,
            authorUserId = resolveUserId(attachment.authorEmail, userMappings),
        )

    /**
     * [ParsedImportChangeGroup](raw 문자열) 를 [ImportChangeGroup](shared VO, `occurredAt` 이 [Instant]) 로
     * 변환한다. [ParsedImportChangeGroup.items] 는 [toImportChangeItem] 로 원소별 변환한다.
     */
    private fun toImportChangeGroup(
        group: ParsedImportChangeGroup,
        userMappings: Map<String, UUID?>,
    ): ImportChangeGroup =
        ImportChangeGroup(
            authorEmail = group.authorEmail?.lowercase(),
            occurredAt = parseInstantOrNull(group.created),
            items = group.items.map(::toImportChangeItem),
            authorUserId = resolveUserId(group.authorEmail, userMappings),
        )

    /**
     * [ParsedImportChangeItem] 을 [ImportChangeItem] 으로 변환한다.
     *
     * [ParsedImportChangeItem.field] 는 원본(Jira 등)의 raw 필드명을 **그대로** 옮긴다 — BTS 내부
     * 필드명으로의 매핑은 이 클래스가 수행하지 않는다. BTS 필드는 issue-tracking BC 가 소유하는
     * 도메인 지식(예: 전이 가능 상태 목록, 담당자 개념)이라 search 모듈(BC 격리)에서 매핑 테이블을
     * 들고 있으면 그 지식이 두 곳에 흩어진다. 실제 매핑은 [ImportChangeGroup] 을 소비하는
     * issue-tracking `IssueImportAdapter`(Task 9)가 담당한다.
     */
    private fun toImportChangeItem(item: ParsedImportChangeItem): ImportChangeItem =
        ImportChangeItem(
            field = item.field,
            fromValue = item.fromValue,
            toValue = item.toValue,
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
