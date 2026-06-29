// Export 작업 접수 서비스 — AQL 선검증 + 도메인 생성 + 영속 + enqueue (FR-EX-02)

package com.bts.search.export.job.application

import com.bts.search.aql.AqlLexer
import com.bts.search.aql.AqlParser
import com.bts.search.export.ExportColumn
import com.bts.search.export.ExportFormat
import com.bts.search.export.job.domain.ExportJob
import com.bts.search.export.job.domain.ExportJobId
import com.bts.search.export.job.domain.ExportJobStatus
import com.bts.search.export.job.event.ExportJobEnqueuePublisher
import com.bts.search.export.job.repository.ExportJobRepository
import com.bts.search.web.SearchValidationException
import com.bts.search.web.dto.ExportRequest
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.time.Clock
import java.util.UUID

/**
 * Export 작업 접수 서비스.
 *
 * 요청 DTO 검증 → AQL 선검증 → 도메인 생성 → 영속 → enqueue 를 단일 @Transactional 경계 안에서 처리한다.
 *
 * ## AQL 선검증
 *
 * [AqlLexer] + [AqlParser] 로 AQL 문법을 사전에 검증한다.
 * 파싱 실패 시 [com.bts.search.aql.AqlSyntaxException] 을 전파하고 insert 는 수행하지 않는다.
 *
 * ## @Transactional 경계
 *
 * [ExportJobRepository.insert] 와 [ExportJobEnqueuePublisher.enqueue] 가 동일 트랜잭션에서 실행된다.
 * publisher 는 [Propagation.MANDATORY][org.springframework.transaction.annotation.Propagation.MANDATORY] 이므로
 * 이 서비스의 @Transactional 이 없으면 [org.springframework.transaction.IllegalTransactionStateException] 이 발생한다.
 *
 * @param repository Export 작업 영속 저장소.
 * @param enqueuePublisher pgmq 큐에 작업 ID 를 발행하는 아웃바운드 어댑터.
 * @param clock createdAt 결정용 시계. search 모듈에 Clock 빈이 없으므로 기본값 [Clock.systemUTC] 를 사용한다.
 */
@Service
class ExportJobService(
    private val repository: ExportJobRepository,
    private val enqueuePublisher: ExportJobEnqueuePublisher,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Export 작업을 접수한다.
     *
     * AQL 선검증 → 도메인 생성 → repo.insert → enqueue 순으로 실행된다.
     * 단일 @Transactional 경계로 insert 와 enqueue 의 원자성을 보장한다(outbox 패턴 — DATA.md §7.2).
     *
     * @param request Export 요청 DTO. projectKey/query 가 blank 이면 즉시 거부된다.
     * @param requesterUserId 작업을 요청한 사용자 UUID.
     * @return 생성된 Export 작업 식별자.
     * @throws ResponseStatusException(400) [request.projectKey] 또는 [request.query] 가 blank 인 경우.
     * @throws SearchValidationException [request.format] 이 올바르지 않거나 미지원 컬럼인 경우.
     * @throws com.bts.search.aql.AqlSyntaxException AQL 문법 오류 — insert 수행하지 않음.
     */
    @Transactional
    fun submit(
        request: ExportRequest,
        requesterUserId: UUID,
    ): ExportJobId {
        validateRequest(request)

        // AQL 선검증 — 문법 오류 시 AqlSyntaxException 전파, insert 수행하지 않음
        val tokens = AqlLexer(request.query).tokenize()
        AqlParser(tokens).parse()

        val format = parseFormat(request.format)
        val columns = ExportColumn.parse(request.columns)

        val jobId = ExportJobId(UUID.randomUUID())
        val job =
            ExportJob(
                id = jobId,
                projectKey = request.projectKey,
                query = request.query,
                format = format.name,
                columns = columns.map { it.name },
                requesterUserId = requesterUserId,
                status = ExportJobStatus.PENDING,
                progress = 0,
                rowCount = null,
                resultObjectKey = null,
                errorCode = null,
                expiresAt = null,
                createdAt = clock.instant(),
                startedAt = null,
                completedAt = null,
            )

        repository.insert(job)
        enqueuePublisher.enqueue(jobId)

        log.info(
            "export_job_submitted jobId={} projectKey={} format={} cols={}",
            jobId,
            request.projectKey,
            format,
            columns.size,
        )
        return jobId
    }

    // ── private helpers ──────────────────────────────────────────────────────────

    /**
     * [ExportRequest] 필드를 명시적으로 검증한다.
     *
     * Hibernate Validator 가 없는 환경에서도 동작하도록 수동 검증을 수행한다
     * ([com.bts.search.web.SearchController.validateRequest] 선례).
     *
     * @throws ResponseStatusException(400) blank 필드 검증 실패 시.
     * @throws SearchValidationException query 길이 초과 시.
     */
    @Suppress("ThrowsCount")
    private fun validateRequest(request: ExportRequest) {
        if (request.projectKey.isBlank()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "projectKey는 필수입니다.")
        }
        if (request.query.isBlank()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "query는 필수입니다.")
        }
        if (request.query.length > MAX_QUERY_LENGTH) {
            throw SearchValidationException("query는 최대 ${MAX_QUERY_LENGTH}자까지 허용됩니다.")
        }
    }

    /**
     * format 문자열을 [ExportFormat] 으로 파싱한다.
     *
     * null 이면 [ExportFormat.CSV] 를 기본값으로 반환한다(클라이언트 미지정 = CSV).
     *
     * @throws SearchValidationException 지원하지 않는 format 문자열인 경우.
     */
    private fun parseFormat(formatStr: String?): ExportFormat {
        if (formatStr == null) return ExportFormat.CSV
        return ExportFormat.entries.firstOrNull { it.name == formatStr.uppercase() }
            ?: throw SearchValidationException("format은 CSV 또는 XLSX여야 합니다.")
    }

    companion object {
        /** query 최대 길이 — DoS 방어(NFR-2). [com.bts.search.web.ExportController] 와 동일. */
        private const val MAX_QUERY_LENGTH = 2000
    }
}
