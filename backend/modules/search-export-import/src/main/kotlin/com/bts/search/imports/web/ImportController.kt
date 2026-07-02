// Import 접수 REST 컨트롤러 — POST 파일 업로드 / GET 폴링 / GET 에러로그 스트리밍 (FR-IM-01 PR1 Task 11)

package com.bts.search.imports.web

import com.bts.search.imports.job.application.ImportAcceptCommand
import com.bts.search.imports.job.application.ImportJobService
import com.bts.search.imports.job.domain.ImportJobId
import com.bts.search.imports.web.dto.ImportJobResponse
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.core.Authentication
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody
import java.util.UUID

/**
 * Import 접수 REST 컨트롤러.
 *
 * 엔드포인트.
 * - `POST /api/v1/imports` — CSV/JSON 파일을 multipart 로 접수하고 202 ACCEPTED + jobId 를 반환한다.
 * - `GET /api/v1/imports/{jobId}` — 작업 상태를 폴링한다.
 * - `GET /api/v1/imports/{jobId}/errors` — 완료된 작업의 실패행 에러 로그를 CSV 로 스트리밍한다.
 *
 * ### 처리 원칙
 *
 * 1. **actor 추출 우선** — [currentActorId] 를 리소스 조회보다 먼저 호출해 미인증자의 존재 probe 를 차단한다
 *    (교훈 auth-extraction-before-resource-lookup).
 * 2. **비즈니스 로직은 [ImportJobService] 에 위임** — 소유권 검증·권한 fail-fast·크기/형식 검증·MinIO I/O 는
 *    모두 서비스 계층 책임이다. 컨트롤러는 HTTP 계약(경로/파라미터/상태코드) 변환만 담당한다.
 * 3. **Content-Disposition 인젝션 방어** — [sanitizeFilename] 으로 파일명의 제어 문자를 제거한다.
 * 4. **projectKey 패턴 검증** — [validateProjectKey] 로 영문자·숫자·하이픈만 허용해 CRLF 헤더
 *    인젝션·MinIO 오브젝트 키 경로 조작을 원천 차단한다([com.bts.search.web.ExportJobController] 동형).
 * 5. **@Transactional 없음** — 컨트롤러 계층은 서비스의 트랜잭션 경계에 위임한다.
 *
 * ### 예외 처리
 *
 * [ImportExceptionHandler]([assignableTypes]) 가 이 컨트롤러에서 발생하는 모든 예외를
 * RFC 7807 [org.springframework.http.ProblemDetail] 로 변환한다.
 *
 * @param service Import 작업 접수/조회 유스케이스 서비스.
 */
@RestController
class ImportController(
    private val service: ImportJobService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Import 작업을 접수한다.
     *
     * 권한 fail-fast·파일 크기·format 검증은 [ImportJobService.accept] 내부에서 수행된다.
     *
     * @param file 업로드할 CSV/JSON 파일. `name="file"` part 필수.
     * @param projectKey Import 대상 프로젝트 키.
     * @param format 파일 형식 문자열(`"CSV"` 또는 `"JSON"`, 대소문자 무관).
     * @param dryRun 검증 전용 실행 여부. 기본값 false.
     * @return 202 Accepted + [ImportJobResponse](jobId, status="PENDING" 등).
     * @throws ResponseStatusException(401) 미인증 시.
     * @throws ResponseStatusException(400) projectKey 공백/미지원 형식(경로 인젝션 방어 패턴) 시.
     * @throws com.bts.search.imports.job.application.ImportAccessDeniedException CREATE_ISSUE 권한 없을 때 → 403.
     * @throws com.bts.search.imports.job.application.ImportFileTooLargeException 파일 크기 초과 → 413.
     * @throws com.bts.search.imports.job.application.ImportUnsupportedFormatException 미지원 format → 400.
     */
    @PostMapping("/api/v1/imports")
    fun accept(
        @RequestParam("file") file: MultipartFile,
        @RequestParam("projectKey") projectKey: String,
        @RequestParam("format") format: String,
        @RequestParam(name = "dryRun", required = false, defaultValue = "false") dryRun: Boolean,
    ): ResponseEntity<ImportJobResponse> {
        val actorId = currentActorId()
        validateProjectKey(projectKey)
        log.info(
            "import_accept_request projectKey={} format={} dryRun={} filename={} actor={}",
            projectKey,
            format,
            dryRun,
            file.originalFilename,
            actorId,
        )

        val job =
            service.accept(
                ImportAcceptCommand(
                    projectKey = projectKey,
                    format = format,
                    dryRun = dryRun,
                    filename = file.originalFilename ?: file.name,
                    contentType = file.contentType,
                    sizeBytes = file.size,
                    inputStream = file.inputStream,
                    requesterUserId = actorId,
                ),
            )

        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ImportJobResponse.from(job))
    }

    /**
     * Import 작업 상태를 폴링한다.
     *
     * [ImportJobService.getForRequester] 로 소유권을 검증한다. 타인 소유 작업은 null 반환 → 404(존재 은닉).
     *
     * @param jobId 작업 UUID (경로 변수).
     * @return 200 OK + [ImportJobResponse].
     * @throws ResponseStatusException(401) 미인증 시.
     * @throws ResponseStatusException(404) 작업 없음 또는 타인 소유 시.
     */
    @GetMapping("/api/v1/imports/{jobId}")
    fun getJob(
        @PathVariable jobId: UUID,
    ): ResponseEntity<ImportJobResponse> {
        val actorId = currentActorId()
        val job =
            service.getForRequester(ImportJobId(jobId), actorId)
                ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)

        return ResponseEntity.ok(ImportJobResponse.from(job))
    }

    /**
     * 완료된 Import 작업의 실패행 에러 로그를 CSV 로 스트리밍한다.
     *
     * 미완료·타인 소유·에러 로그 미준비 상태는 [ImportJobService.getErrorLog] 가 모두 404 로 통일 처리한다.
     * Content-Disposition 파일명은 [sanitizeFilename] 으로 제어 문자를 제거해 CRLF 헤더 인젝션을 차단한다.
     *
     * @param jobId 작업 UUID (경로 변수).
     * @return 200 OK + text/csv + Content-Disposition attachment + 에러 로그 스트림.
     * @throws ResponseStatusException(401) 미인증 시.
     * @throws ResponseStatusException(404) 작업 없음, 타인 소유, 또는 에러 로그 미준비 시.
     */
    @GetMapping("/api/v1/imports/{jobId}/errors")
    fun getErrors(
        @PathVariable jobId: UUID,
    ): ResponseEntity<StreamingResponseBody> {
        val actorId = currentActorId()
        val result = service.getErrorLog(ImportJobId(jobId), actorId)

        val filename = sanitizeFilename("${result.job.projectKey}-${result.job.id.value}-errors.csv")
        log.info(
            "import_errors_download jobId={} filename={} actor={}",
            jobId,
            filename,
            actorId,
        )

        val body = StreamingResponseBody { output -> result.stream.use { it.copyTo(output) } }
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"$filename\"")
            .contentType(MediaType.valueOf(ERROR_LOG_CONTENT_TYPE))
            .body(body)
    }

    // ── private helpers ────────────────────────────────────────────────────────

    /**
     * projectKey 를 검증한다. 공백이면 400, 허용 패턴([PROJECT_KEY_PATTERN]) 을 벗어나면
     * [ImportValidationException](400) 을 던진다.
     *
     * projectKey 는 MinIO 오브젝트 키·에러 로그 파일명 생성에 그대로 사용되므로,
     * 여기서 영문자·숫자·하이픈만 허용해 경로 조작·CRLF 헤더 인젝션을 원천 차단한다.
     *
     * @param projectKey 검증할 요청 프로젝트 키.
     * @throws ResponseStatusException(400) 공백인 경우.
     * @throws ImportValidationException 허용 패턴을 벗어난 경우.
     */
    private fun validateProjectKey(projectKey: String) {
        if (projectKey.isBlank()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "projectKey는 필수입니다.")
        }
        if (!PROJECT_KEY_PATTERN.matches(projectKey)) {
            throw ImportValidationException("projectKey는 영문자·숫자·하이픈만 허용됩니다.")
        }
    }

    /**
     * [SecurityContextHolder] 에서 인증 주체를 UUID 로 추출한다.
     *
     * 미인증·익명·비-UUID·nil-UUID 주체는 401 [ResponseStatusException] 을 던진다.
     * [com.bts.search.web.ExportJobController.currentActorId] 와 동일 로직이지만 BC 격리상 재사용하지 않는다.
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
     * ASCII 제어 문자(0x00~0x1F, 0x7F) — CRLF 포함 — 를 언더스코어로 대체한다.
     * projectKey 에서 파생된 파일명이 CRLF 헤더 인젝션에 악용되는 것을 차단한다.
     *
     * @param raw 원본 파일명 문자열.
     * @return 제어 문자가 제거된 안전한 파일명.
     */
    private fun sanitizeFilename(raw: String): String = raw.replace(Regex("[\\x00-\\x1F\\x7F]"), "_")

    companion object {
        /**
         * 허용 projectKey 패턴 — 영문자·숫자·하이픈만 허용.
         * Content-Disposition 헤더 인젝션(CRLF 포함)·MinIO 오브젝트 키 경로 조작 차단을 위해 엄격히 제한한다.
         */
        private val PROJECT_KEY_PATTERN = Regex("[A-Za-z0-9\\-]+")

        /** 에러 로그 CSV Content-Type. */
        private const val ERROR_LOG_CONTENT_TYPE = "text/csv; charset=UTF-8"
    }
}

/**
 * [ImportController] 요청 검증 실패 예외.
 *
 * [com.bts.search.web.SearchValidationException] 동형 — projectKey 패턴 위반 등 컨트롤러 수준
 * 요청-형태 검증 실패를 나타낸다. [ImportJobService] 가 던지는 비즈니스 예외(권한/크기/형식)와
 * 구분하기 위해 web 계층에 별도로 정의한다.
 *
 * [ImportExceptionHandler] 가 400 IMPORT_VALIDATION_FAILED 로 변환한다.
 */
class ImportValidationException(message: String) : RuntimeException(message)
