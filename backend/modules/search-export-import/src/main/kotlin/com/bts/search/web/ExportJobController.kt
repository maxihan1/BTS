// 비동기 Export 작업 REST 컨트롤러 — POST/GET/다운로드 (FR-EX-02 Task 10)

package com.bts.search.web

import com.bts.search.export.job.application.ExportJobService
import com.bts.search.export.job.domain.ExportJobId
import com.bts.search.export.job.domain.ExportJobStatus
import com.bts.search.export.job.repository.ExportJobRepository
import com.bts.search.export.job.storage.ExportObjectStoragePort
import com.bts.search.web.dto.ExportJobResponse
import com.bts.search.web.dto.ExportJobSubmitResponse
import com.bts.search.web.dto.ExportRequest
import jakarta.validation.Valid
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.core.Authentication
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody
import java.util.UUID

/**
 * 비동기 Export 작업 REST 컨트롤러.
 *
 * 엔드포인트.
 * - `POST /api/v1/search/export-jobs` — 비동기 Export 작업을 접수하고 202 ACCEPTED 와 jobId 를 반환한다.
 * - `GET /api/v1/search/export-jobs/{id}` — 작업 상태를 폴링한다.
 * - `GET /api/v1/search/export-jobs/{id}/download` — 완료된 작업의 파일을 스트리밍 다운로드한다.
 *
 * ### 처리 원칙
 *
 * 1. **actor 추출 우선** — [currentActorId]를 리소스 조회보다 먼저 호출하여 존재 probe 를 차단한다
 *    (교훈 auth-extraction-before-resource-lookup).
 * 2. **소유권 검증** — [ExportJobRepository.findByIdForRequester]로 타인 소유 작업을 null 반환하여 404 로 은닉한다.
 * 3. **Content-Disposition 인젝션 방어** — [sanitizeFilename]으로 파일명의 제어 문자를 제거한다.
 * 4. **다운로드 준비 확인** — COMPLETED 상태 + resultObjectKey 존재 시만 스트리밍. 그 외는 409 SEARCH_EXPORT_NOT_READY.
 * 5. **@Transactional 없음** — 컨트롤러 계층은 서비스/레포지토리의 트랜잭션에 위임한다.
 *
 * ### 예외 처리
 *
 * [ExportJobExceptionHandler]([assignableTypes]) 가 이 컨트롤러에서 발생하는 모든 예외를
 * RFC 7807 [org.springframework.http.ProblemDetail]로 변환한다.
 * 주의: [ExportExceptionHandler] 는 [ExportController] 전용으로 이 컨트롤러에 적용되지 않는다
 * (교훈 domain-exception-http-handler-basepackage-scope).
 *
 * @param service [ExportJobService]. 작업 접수(submit) 담당.
 * @param repo [ExportJobRepository]. 작업 조회 담당.
 * @param storage [ExportObjectStoragePort]. 완료된 작업 파일 스트리밍 담당.
 */
@RestController
class ExportJobController(
    private val service: ExportJobService,
    private val repo: ExportJobRepository,
    private val storage: ExportObjectStoragePort,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 비동기 Export 작업을 접수한다.
     *
     * AQL 선검증은 [ExportJobService.submit] 내부에서 수행되며, 실패 시 [com.bts.search.aql.AqlSyntaxException] 을 전파한다.
     * 동일 [ExportRequest] DTO 를 재사용하므로 [jakarta.validation.Valid] 어노테이션으로
     * projectKey 패턴·NotBlank·query 길이 제약이 1차 방어로 작동한다.
     *
     * @param request Export 요청 바디. [ExportRequest] Jakarta Validation 적용.
     * @return 202 Accepted + jobId + 초기 status "PENDING".
     * @throws ResponseStatusException(401) 미인증 시.
     * @throws com.bts.search.aql.AqlSyntaxException AQL 문법 오류 → [ExportJobExceptionHandler] 가 400 으로 변환.
     * @throws SearchValidationException 검증 실패 → [ExportJobExceptionHandler] 가 400 으로 변환.
     */
    @PostMapping("/api/v1/search/export-jobs")
    fun submit(
        @Valid @RequestBody request: ExportRequest,
    ): ResponseEntity<ExportJobSubmitResponse> {
        val actorId = currentActorId()
        validateRequest(request)
        log.info(
            "export_job_submit projectKey={} format={} actor={}",
            request.projectKey,
            request.format,
            actorId,
        )

        val jobId = service.submit(request, actorId)

        val body = ExportJobSubmitResponse(jobId = jobId.value, status = ExportJobStatus.PENDING.name)
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(body)
    }

    /**
     * Export 작업 상태를 폴링한다.
     *
     * [ExportJobRepository.findByIdForRequester]로 소유권을 검증한다.
     * 타인 소유 작업은 null 반환 → 404 (존재 은닉).
     *
     * @param id 작업 UUID (경로 변수).
     * @return 200 OK + [ExportJobResponse] DTO.
     * @throws ResponseStatusException(401) 미인증 시.
     * @throws ResponseStatusException(404) 작업 없음 또는 타인 소유 시.
     */
    @GetMapping("/api/v1/search/export-jobs/{id}")
    fun getJob(
        @PathVariable id: UUID,
    ): ResponseEntity<ExportJobResponse> {
        val actorId = currentActorId()
        val job =
            repo.findByIdForRequester(ExportJobId(id), actorId)
                ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)

        return ResponseEntity.ok(ExportJobResponse.from(job))
    }

    /**
     * 완료된 Export 작업 파일을 스트리밍 다운로드한다.
     *
     * COMPLETED 상태 + resultObjectKey 존재 시만 스트리밍을 제공한다.
     * PENDING/RUNNING/FAILED 상태이면 409 SEARCH_EXPORT_NOT_READY 를 던진다
     * → [ExportJobExceptionHandler.handleResponseStatus] 가 errorCode 를 매핑한다.
     *
     * Content-Disposition 파일명은 [sanitizeFilename]으로 제어 문자를 제거하여
     * CRLF 헤더 인젝션을 차단한다 (교훈 fr-ex-01-content-disposition-injection).
     *
     * @param id 작업 UUID (경로 변수).
     * @return 200 OK + application/octet-stream + Content-Disposition attachment + 파일 스트림.
     * @throws ResponseStatusException(401) 미인증 시.
     * @throws ResponseStatusException(404) 작업 없음 또는 타인 소유 시.
     * @throws ResponseStatusException(409) 작업이 아직 COMPLETED 아닌 경우 — errorCode: SEARCH_EXPORT_NOT_READY.
     */
    @GetMapping("/api/v1/search/export-jobs/{id}/download")
    fun download(
        @PathVariable id: UUID,
    ): ResponseEntity<StreamingResponseBody> {
        val actorId = currentActorId()
        val job =
            repo.findByIdForRequester(ExportJobId(id), actorId)
                ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)

        if (job.status != ExportJobStatus.COMPLETED || job.resultObjectKey == null) {
            throw ResponseStatusException(
                HttpStatus.CONFLICT,
                "Export 작업이 아직 완료되지 않았습니다. 상태: ${job.status.name}",
            )
        }

        val objectKey = job.resultObjectKey
        val filename = sanitizeFilename("${job.projectKey}-issues.${job.format.lowercase()}")

        log.info(
            "export_job_download jobId={} objectKey={} filename={} actor={}",
            id,
            objectKey,
            filename,
            actorId,
        )

        val body =
            StreamingResponseBody { outputStream ->
                storage.openStream(objectKey).use { inputStream ->
                    inputStream.copyTo(outputStream)
                }
            }

        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"$filename\"")
            .body(body)
    }

    // ── private helpers ────────────────────────────────────────────────────────

    /**
     * [ExportRequest] 필드를 명시적으로 검증한다.
     *
     * [jakarta.validation.Valid] 가 Bean Validation provider 없는 환경에서 미동작할 수 있으므로
     * 2차 방어로 수동 검증을 추가한다 (교훈 fr-nt-04-user-subscription-done: @Valid 무동작 패턴 동일).
     * projectKey 패턴 검증은 Content-Disposition 헤더 인젝션 방어의 핵심이다.
     *
     * @param request 검증할 요청 DTO.
     * @throws ResponseStatusException(400) blank 필드 검증 실패 시.
     * @throws SearchValidationException(400) 패턴·길이 검증 실패 시.
     */
    @Suppress("ThrowsCount")
    private fun validateRequest(request: ExportRequest) {
        if (request.projectKey.isBlank()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "projectKey는 필수입니다.")
        }
        if (!PROJECT_KEY_PATTERN.matches(request.projectKey)) {
            throw SearchValidationException("projectKey는 영문자·숫자·하이픈만 허용됩니다.")
        }
        if (request.query.isBlank()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "query는 필수입니다.")
        }
        if (request.query.length > MAX_QUERY_LENGTH) {
            throw SearchValidationException("query는 최대 ${MAX_QUERY_LENGTH}자까지 허용됩니다.")
        }
    }

    /**
     * [SecurityContextHolder]에서 인증 주체를 UUID로 추출한다.
     *
     * 미인증·익명·비-UUID·nil-UUID 주체는 401 [ResponseStatusException]을 던진다.
     * [ExportController.currentActorId]와 동일 로직이지만 BC 격리상 재사용 불가.
     *
     * @return 인증된 사용자의 UUID.
     * @throws ResponseStatusException(401) 미인증 또는 UUID 변환 실패 시.
     */
    private fun currentActorId(): UUID {
        val authentication: Authentication =
            SecurityContextHolder.getContext().authentication
                ?.takeIf { it.isAuthenticated && it !is AnonymousAuthenticationToken }
                ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required")
        return try {
            val uuid = UUID.fromString(authentication.name)
            require(uuid != UUID(0L, 0L)) { "nil UUID는 actor로 허용되지 않습니다." }
            uuid
        } catch (e: IllegalArgumentException) {
            throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required", e)
        }
    }

    /**
     * Content-Disposition 파일명에서 제어 문자를 제거한다.
     *
     * ASCII 제어 문자 (0x00~0x1F, 0x7F) — CRLF 포함 — 를 언더스코어로 대체한다.
     * projectKey 에서 파생된 파일명이 CRLF 헤더 인젝션에 악용되는 것을 차단한다.
     *
     * @param raw 원본 파일명 문자열.
     * @return 제어 문자가 제거된 안전한 파일명.
     */
    private fun sanitizeFilename(raw: String): String = raw.replace(Regex("[\\x00-\\x1F\\x7F]"), "_")

    companion object {
        /**
         * 허용 projectKey 패턴 — 영문자·숫자·하이픈만 허용.
         * Content-Disposition 헤더 인젝션(CRLF 포함) 차단을 위해 엄격히 제한한다.
         */
        private val PROJECT_KEY_PATTERN = Regex("[A-Za-z0-9\\-]+")

        /** AQL 쿼리 최대 길이 — NFR-2 DoS 방어 */
        private const val MAX_QUERY_LENGTH = 2000
    }
}
