// Export 작업 처리기 — count-first 페이지 순회 + MinIO 업로드 + 상태 갱신 (FR-EX-02)

package com.bts.search.export.job.application

import com.bts.search.aql.AqlLexer
import com.bts.search.aql.AqlParseResult
import com.bts.search.aql.AqlParser
import com.bts.search.export.ExportColumn
import com.bts.search.export.ExportFormat
import com.bts.search.export.job.domain.ExportJob
import com.bts.search.export.job.repository.ExportJobRepository
import com.bts.search.export.job.serialize.StreamingExportSerializer
import com.bts.search.export.job.storage.ExportObjectStoragePort
import com.bts.search.export.job.storage.MinioExportStorageException
import com.bts.search.web.SearchErrorCodes
import com.bts.shared.search.IssueSearchPort
import com.bts.shared.search.IssueSearchQuery
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.io.File
import java.time.Clock

/**
 * [ExportJob] 처리기 — count-first 페이지 순회 + MinIO 업로드 + 상태 갱신.
 *
 * ## @Transactional 의도적 생략
 *
 * [process] 는 수천 페이지 조회 + MinIO 업로드로 **분 단위 소요**되는 I/O 집약 작업이다.
 * @Transactional 을 붙이면 분 단위 DB 커넥션을 점유하여 커넥션 풀 고갈을 유발한다.
 * 상태 변경([ExportJobRepository.markCompleted] / [ExportJobRepository.markFailed] / [ExportJobRepository.updateProgress])은
 * 각 메서드 단독 @Transactional 이 처리한다.
 * FR-AC-01 "100MB I/O @Transactional 밖" 선례 준수.
 * codereview rule 9 false-positive — **의도적 생략**.
 *
 * ## 보안 상속
 *
 * [IssueSearchPort.search] 에 [ExportJob.requesterUserId] 를 viewerUserId 로 전달하여
 * 작업 접수 시점의 visibility 보안 술어를 그대로 상속한다.
 *
 * @param searchPort AQL 이슈 검색 포트(issue-tracking 어댑터가 런타임 주입).
 * @param repository Export 작업 상태 관리 저장소.
 * @param storage MinIO 오브젝트 스토리지 포트.
 * @param serializerFactory [StreamingExportSerializer] 생성 팩토리. 테스트에서 mock 주입.
 * @param clock expiresAt 결정용 시계. search 모듈에 Clock 빈이 없으므로 기본값 [Clock.systemUTC] 사용.
 */
@Component
class ExportJobProcessor(
    private val searchPort: IssueSearchPort,
    private val repository: ExportJobRepository,
    private val storage: ExportObjectStoragePort,
    private val serializerFactory: ExportSerializerFactory,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * [job] 을 처리한다. 워커가 [ExportJobRepository.claimForRun] 으로 RUNNING 전환 후 호출한다.
     *
     * @Transactional **의도적 생략** — KDoc 클래스 주석 참조.
     *
     * @param job 처리할 Export 작업. RUNNING 상태임이 보장되어야 한다.
     */
    // TooGenericExceptionCaught: catch-all 의도적 — 미분류 예외도 job 을 FAILED 로 전환해야 한다.
    @Suppress("TooGenericExceptionCaught")
    fun process(job: ExportJob) {
        log.info("export_job_process_start jobId={} projectKey={} format={}", job.id, job.projectKey, job.format)
        val format = parseFormat(job.format)
        val columns = ExportColumn.parse(job.columns.ifEmpty { null })
        val parseResult = parseAql(job.query)
        val serializer = serializerFactory.create(format, columns)
        var tempFile: File? = null

        try {
            val firstPage = searchPort.search(buildQuery(job, parseResult, 0))
            val total = firstPage.total

            if (total > ExportJob.MAX_ROWS) {
                log.warn("export_limit_exceeded jobId={} total={}", job.id, total)
                repository.markFailed(job.id, SearchErrorCodes.SEARCH_EXPORT_LIMIT_EXCEEDED)
                return
            }

            serializer.appendBatch(firstPage.items)
            traverseRemainingPages(job, parseResult, total, serializer)
            tempFile = serializer.finish()
            finalizeJob(job, tempFile, format, total)
        } catch (e: MinioExportStorageException) {
            log.error("export_storage_error jobId={}", job.id, e)
            repository.markFailed(job.id, SearchErrorCodes.SEARCH_EXPORT_STORAGE_ERROR)
        } catch (e: Exception) {
            log.error("export_job_error jobId={}", job.id, e)
            repository.markFailed(job.id, SearchErrorCodes.SEARCH_INTERNAL_ERROR)
        } finally {
            tempFile?.let { if (it.exists()) it.delete() }
        }
    }

    /**
     * 2페이지 이후의 나머지 페이지를 순회하며 직렬화하고 진행률을 갱신한다.
     *
     * 진행률은 매 페이지 완료 시 throttle 없이 갱신한다(REFACTOR 메모: 고빈도 small job 에는
     * 페이지 단위 업데이트로 충분히 부드러운 진행률 표시가 가능하다).
     */
    private fun traverseRemainingPages(
        job: ExportJob,
        parseResult: AqlParseResult,
        total: Long,
        serializer: StreamingExportSerializer,
    ) {
        val totalPages = calculateTotalPages(total)
        val initialProcessed = minOf(PAGE_SIZE.toLong(), total)
        repository.updateProgress(job.id, job.progressPercent(initialProcessed, total), total)

        for (page in 1 until totalPages) {
            val result = searchPort.search(buildQuery(job, parseResult, page))
            serializer.appendBatch(result.items)
            val processed = minOf((page + 1).toLong() * PAGE_SIZE, total)
            repository.updateProgress(job.id, job.progressPercent(processed, total), total)
        }
    }

    /**
     * 직렬화 완료 파일을 MinIO 에 업로드하고 작업을 COMPLETED 로 전환한다.
     *
     * [ExportObjectStoragePort.put] 이 실패하면 [MinioExportStorageException] 이 전파되어
     * 호출자([process]) 의 catch 블록이 STORAGE_ERROR 로 markFailed 한다.
     */
    private fun finalizeJob(
        job: ExportJob,
        tempFile: File,
        format: ExportFormat,
        total: Long,
    ) {
        val objectKey = buildObjectKey(job, format)
        tempFile.inputStream().use { storage.put(objectKey, it, tempFile.length(), format.contentType) }
        val expiresAt = clock.instant().plusSeconds(RESULT_TTL_SECONDS)
        repository.markCompleted(job.id, objectKey, expiresAt)
        repository.updateProgress(job.id, COMPLETED_PROGRESS_PERCENT, total)
        log.info("export_job_completed jobId={} objectKey={}", job.id, objectKey)
    }

    private fun buildQuery(
        job: ExportJob,
        parseResult: AqlParseResult,
        page: Int,
    ): IssueSearchQuery =
        IssueSearchQuery(
            projectKey = job.projectKey,
            ast = parseResult.ast,
            sort = parseResult.sort,
            viewerUserId = job.requesterUserId,
            page = page,
            size = PAGE_SIZE,
        )

    private fun buildObjectKey(
        job: ExportJob,
        format: ExportFormat,
    ): String = "${job.projectKey}/${job.id}.${format.fileExtension}"

    private fun calculateTotalPages(total: Long): Int =
        if (total == 0L) 1 else ((total + PAGE_SIZE - 1) / PAGE_SIZE).toInt()

    private fun parseFormat(formatStr: String): ExportFormat =
        ExportFormat.entries.firstOrNull { it.name == formatStr } ?: ExportFormat.CSV

    private fun parseAql(query: String): AqlParseResult {
        val tokens = AqlLexer(query).tokenize()
        return AqlParser(tokens).parse()
    }

    companion object {
        /** 결과 파일 TTL(초) — 24시간(24 * 60 * 60 = 86400). */
        @Suppress("MagicNumber")
        const val RESULT_TTL_SECONDS: Long = 24 * 60 * 60L

        /** [IssueSearchPort] 페이지 순회 단위. [com.bts.search.export.ExportService.PAGE_SIZE] 와 동일. */
        const val PAGE_SIZE = 100

        /** 작업 완료 시 설정하는 진행률 (100%). */
        const val COMPLETED_PROGRESS_PERCENT = 100
    }
}

/**
 * [StreamingExportSerializer] 생성 팩토리 인터페이스.
 *
 * [ExportJobProcessor] 가 직렬화기를 직접 생성하지 않고 팩토리를 통해 주입받으므로
 * 테스트에서 [StreamingExportSerializer] 를 MockK 로 교체할 수 있다.
 *
 * [DefaultExportSerializerFactory] 가 [org.springframework.stereotype.Component] 로 등록되어
 * 런타임에 [ExportJobProcessor] 에 주입된다.
 */
fun interface ExportSerializerFactory {
    /** [format] 과 [columns] 로 [StreamingExportSerializer] 를 생성한다. */
    fun create(
        format: ExportFormat,
        columns: List<ExportColumn>,
    ): StreamingExportSerializer
}

/**
 * [ExportSerializerFactory] 의 기본(프로덕션) 구현체.
 *
 * [StreamingExportSerializer] 를 직접 인스턴스화한다.
 */
@Component
class DefaultExportSerializerFactory : ExportSerializerFactory {
    override fun create(
        format: ExportFormat,
        columns: List<ExportColumn>,
    ): StreamingExportSerializer = StreamingExportSerializer(format, columns)
}
