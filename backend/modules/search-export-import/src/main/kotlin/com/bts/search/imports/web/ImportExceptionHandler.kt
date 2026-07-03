// ImportController 전용 예외 핸들러 — RFC 7807 ProblemDetail 변환, IMPORT_ prefix 에러코드 (FR-IM-01 PR1 Task 11)

package com.bts.search.imports.web

import com.bts.search.imports.job.application.ImportAccessDeniedException
import com.bts.search.imports.job.application.ImportFileTooLargeException
import com.bts.search.imports.job.application.ImportUnsupportedFormatException
import com.bts.search.imports.mapping.ImportMappingInvalidException
import com.bts.search.imports.mapping.ImportMappingStateConflictException
import com.bts.search.imports.web.dto.MappingValidationResponse.MappingIssueItem
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.multipart.MaxUploadSizeExceededException
import org.springframework.web.server.ResponseStatusException
import java.net.URI
import java.time.Instant

/**
 * [ImportController]·[ImportMappingController] 전용 예외 핸들러.
 *
 * [assignableTypes] 를 [ImportController]·[ImportMappingController] 로 한정해 다른 컨트롤러의 예외를
 * 가로채지 않는다(교훈 domain-exception-http-handler-basepackage-scope). `assignableTypes` 는 클래스
 * 목록이지 패키지 스코프가 아니므로, 같은 패키지에 신규 컨트롤러를 추가할 때마다 이 목록에도 명시적으로
 * 추가해야 한다(BLOCKER-2 — [ImportMappingController] 추가 시 실제로 누락되어 발견된 회귀).
 * catch-all [Exception] 핸들러를 두되 [ResponseStatusException] 은 별도 핸들러로 상태를 전파해
 * 401/404 등이 500 으로 변질되지 않도록 한다(교훈 catch-all-exceptionhandler-swallows-responsestatusexception).
 *
 * ### 에러코드 카탈로그 — `IMPORT_` prefix
 *
 * - [ImportAccessDeniedException] → 403 [IMPORT_ACCESS_DENIED]
 * - [ImportFileTooLargeException] / [MaxUploadSizeExceededException] → 413 [IMPORT_FILE_TOO_LARGE]
 * - [ImportUnsupportedFormatException] → 400 [IMPORT_UNSUPPORTED_FORMAT]
 * - [ImportValidationException] → 400 [IMPORT_VALIDATION_FAILED]
 * - [ImportMappingInvalidException] → 422 [IMPORT_MAPPING_INVALID]
 * - [ImportMappingStateConflictException] → 409 [IMPORT_MAPPING_STATE_CONFLICT]
 * - [ResponseStatusException](401/404/기타) → 상태 전파
 * - [Exception] (fallback) → 500 [IMPORT_INTERNAL_ERROR]
 *
 * `IMPORT_ROW_LIMIT_EXCEEDED`/`IMPORT_PARSE_FAILED` 는 이 컨트롤러가 던지는 HTTP 예외가 아니다 —
 * [com.bts.search.imports.job.application.ImportJobProcessor](워커) 가 비동기 처리 실패 시
 * `ImportJob.errorCode` 컬럼에 기록하는 값으로, [com.bts.search.imports.web.dto.ImportJobResponse.errorCode]
 * 필드를 통해 폴링 응답에 노출될 뿐 이 핸들러의 매핑 대상이 아니다.
 */
@RestControllerAdvice(assignableTypes = [ImportController::class, ImportMappingController::class])
@Suppress("TooManyFunctions")
class ImportExceptionHandler {
    private val log = LoggerFactory.getLogger(javaClass)

    // ── 403 권한 없음 ─────────────────────────────────────────────────────────

    /**
     * [ImportAccessDeniedException] — CREATE_ISSUE 권한 없음(접수 fail-fast) — 403.
     *
     * @param ex 권한 거부 예외.
     */
    @ExceptionHandler(ImportAccessDeniedException::class)
    fun handleAccessDenied(
        @Suppress("UnusedParameter") ex: ImportAccessDeniedException,
    ): ProblemDetail {
        log.warn("IMPORT_403 access_denied")
        return problem(
            HttpStatus.FORBIDDEN,
            "import-access-denied",
            "Access Denied",
            IMPORT_ACCESS_DENIED,
            "이 작업을 수행할 권한이 없습니다.",
        )
    }

    // ── 413 파일 크기 초과 ────────────────────────────────────────────────────

    /**
     * [ImportFileTooLargeException] — 업로드 파일 크기 상한 초과 — 413.
     *
     * ProblemDetail extension property 에 sizeBytes/maxBytes 를 포함한다.
     *
     * @param ex 크기 초과 예외. 실제 크기와 상한 정보를 포함한다.
     */
    @ExceptionHandler(ImportFileTooLargeException::class)
    fun handleFileTooLarge(ex: ImportFileTooLargeException): ProblemDetail {
        log.warn("IMPORT_413 file_too_large sizeBytes={} maxBytes={}", ex.sizeBytes, ex.maxBytes)
        val pd =
            problem(
                HttpStatus.PAYLOAD_TOO_LARGE,
                "import-file-too-large",
                "Payload Too Large",
                IMPORT_FILE_TOO_LARGE,
                "업로드 파일 크기가 상한(${ex.maxBytes}B)을 초과합니다.",
            )
        pd.setProperty("sizeBytes", ex.sizeBytes)
        pd.setProperty("maxBytes", ex.maxBytes)
        return pd
    }

    /**
     * [MaxUploadSizeExceededException] — 서블릿/Spring multipart 설정 상한 초과 — 413.
     *
     * [ImportJobService] 의 자체 크기 검증([ImportFileTooLargeException])보다 앞서 서블릿
     * 컨테이너/Spring multipart 설정(`spring.servlet.multipart.max-file-size`) 이 먼저 요청을
     * 거부하는 경우를 대비한 백스톱이다([com.bts.issue.attachment.web.AttachmentExceptionHandler] 동형).
     *
     * @param ex 업로드 크기 초과 예외.
     */
    @ExceptionHandler(MaxUploadSizeExceededException::class)
    fun handleMaxUploadSizeExceeded(
        @Suppress("UnusedParameter") ex: MaxUploadSizeExceededException,
    ): ProblemDetail {
        log.warn("IMPORT_413 max_upload_size_exceeded")
        return problem(
            HttpStatus.PAYLOAD_TOO_LARGE,
            "import-file-too-large",
            "Payload Too Large",
            IMPORT_FILE_TOO_LARGE,
            "업로드 파일 크기가 상한을 초과합니다.",
        )
    }

    // ── 400 미지원 format / 검증 실패 ────────────────────────────────────────

    /**
     * [ImportUnsupportedFormatException] — CSV/JSON 이외 format — 400.
     *
     * @param ex 미지원 format 예외.
     */
    @ExceptionHandler(ImportUnsupportedFormatException::class)
    fun handleUnsupportedFormat(ex: ImportUnsupportedFormatException): ProblemDetail {
        log.info("IMPORT_400 unsupported_format format='{}'", ex.format)
        return problem(
            HttpStatus.BAD_REQUEST,
            "import-unsupported-format",
            "Unsupported Format",
            IMPORT_UNSUPPORTED_FORMAT,
            "지원하지 않는 파일 형식입니다. CSV 또는 JSON만 허용됩니다.",
        )
    }

    /**
     * [ImportValidationException] — 컨트롤러 명시 검증 실패(projectKey 패턴 등) — 400.
     *
     * @param ex 검증 오류 예외.
     */
    @ExceptionHandler(ImportValidationException::class)
    fun handleValidation(ex: ImportValidationException): ProblemDetail {
        log.info("IMPORT_400 validation_failed message='{}'", ex.message)
        return problem(
            HttpStatus.BAD_REQUEST,
            "import-validation-failed",
            "Validation Failed",
            IMPORT_VALIDATION_FAILED,
            ex.message ?: "요청 값 검증에 실패했습니다.",
        )
    }

    /**
     * [HttpMessageNotReadableException] — 요청 본문 역직렬화 실패 — 400.
     *
     * @param ex 역직렬화 실패 예외.
     */
    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun handleHttpMessageNotReadable(
        @Suppress("UnusedParameter") ex: HttpMessageNotReadableException,
    ): ProblemDetail {
        log.info("IMPORT_400 message_not_readable")
        return problem(
            HttpStatus.BAD_REQUEST,
            "import-validation-failed",
            "Validation Failed",
            IMPORT_VALIDATION_FAILED,
            "요청 본문을 읽을 수 없습니다.",
        )
    }

    /**
     * [MethodArgumentTypeMismatchException] — 경로 변수/파라미터 타입 불일치 — 400.
     *
     * @param ex 파라미터 타입 불일치 예외.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    fun handleMethodArgumentTypeMismatch(ex: MethodArgumentTypeMismatchException): ProblemDetail {
        log.info("IMPORT_400 type_mismatch param='{}'", ex.name)
        return problem(
            HttpStatus.BAD_REQUEST,
            "import-validation-failed",
            "Validation Failed",
            IMPORT_VALIDATION_FAILED,
            "요청 경로 또는 파라미터 형식이 올바르지 않습니다.",
        )
    }

    // ── 422 필드 매핑 검증 실패 ────────────────────────────────────────────────

    /**
     * [ImportMappingInvalidException] — [com.bts.search.imports.mapping.ImportMappingService.confirm]
     * 필드 매핑 검증 실패 — 422.
     *
     * [ImportMappingInvalidException.errors] 를 그대로 ProblemDetail extension property 에 담아
     * 클라이언트가 어느 대상 필드가 왜 실패했는지 손실 없이 렌더링할 수 있게 한다
     * ([com.bts.search.imports.mapping.ImportMappingInvalidException] KDoc §errors 참조).
     *
     * @param ex 필드 매핑 검증 실패 예외. 실패 사유 목록을 포함한다.
     */
    @ExceptionHandler(ImportMappingInvalidException::class)
    fun handleMappingInvalid(ex: ImportMappingInvalidException): ProblemDetail {
        log.info("IMPORT_422 mapping_invalid errorCount={}", ex.errors.size)
        val pd =
            problem(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "import-mapping-invalid",
                "Mapping Invalid",
                IMPORT_MAPPING_INVALID,
                "필드 매핑 검증에 실패했습니다.",
            )
        val errorItems = ex.errors.map { MappingIssueItem(code = it.code, message = it.message, field = it.field) }
        pd.setProperty("errors", errorItems)
        return pd
    }

    // ── 409 매핑 상태 충돌 ────────────────────────────────────────────────────

    /**
     * [ImportMappingStateConflictException] — 작업 상태가
     * [com.bts.search.imports.job.domain.ImportJobStatus.AWAITING_MAPPING] 이 아닐 때(사전확인 또는
     * CAS 시점의 TOCTOU 포함) [com.bts.search.imports.mapping.ImportMappingService] 가 던지는 예외 — 409.
     *
     * @param ex 매핑 상태 충돌 예외.
     */
    @ExceptionHandler(ImportMappingStateConflictException::class)
    fun handleMappingStateConflict(
        @Suppress("UnusedParameter") ex: ImportMappingStateConflictException,
    ): ProblemDetail {
        log.info("IMPORT_409 mapping_state_conflict")
        return problem(
            HttpStatus.CONFLICT,
            "import-mapping-state-conflict",
            "Conflict",
            IMPORT_MAPPING_STATE_CONFLICT,
            "Import 작업이 매핑 대기(AWAITING_MAPPING) 상태가 아닙니다.",
        )
    }

    // ── ResponseStatusException 상태 전파 ─────────────────────────────────────

    /**
     * [ResponseStatusException] — 명시 HTTP 상태 전파.
     *
     * 401(미인증)/404(not found/not ready) 등이 catch-all 에 가로채여 500 으로 변질되는 것을 막는다.
     *
     * @param ex 상태 코드를 보유한 예외.
     */
    @ExceptionHandler(ResponseStatusException::class)
    fun handleResponseStatus(ex: ResponseStatusException): ProblemDetail {
        val status = HttpStatus.valueOf(ex.statusCode.value())
        log.info("IMPORT_{} response_status reason='{}'", status.value(), ex.reason)
        val (errorCode, detail) =
            when (status) {
                HttpStatus.UNAUTHORIZED -> IMPORT_UNAUTHENTICATED to "인증이 필요합니다."
                HttpStatus.FORBIDDEN -> IMPORT_ACCESS_DENIED to "이 작업을 수행할 권한이 없습니다."
                HttpStatus.NOT_FOUND -> IMPORT_NOT_FOUND to "요청한 작업을 찾을 수 없습니다."
                HttpStatus.BAD_REQUEST -> IMPORT_VALIDATION_FAILED to "요청 값이 올바르지 않습니다."
                else -> IMPORT_INTERNAL_ERROR to "요청을 처리할 수 없습니다."
            }
        return problem(status, "import-response-status", status.reasonPhrase, errorCode, detail)
    }

    // ── 500 INTERNAL_ERROR (fallback) ─────────────────────────────────────────

    /**
     * 분류되지 않은 모든 예외 — 500.
     *
     * @param ex 처리되지 않은 예외.
     */
    @ExceptionHandler(Exception::class)
    fun handleInternalError(ex: Exception): ProblemDetail {
        log.error("IMPORT_500 internal_error", ex)
        return problem(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "import-internal-error",
            "Internal Server Error",
            IMPORT_INTERNAL_ERROR,
            "서버 내부 오류가 발생했습니다.",
        )
    }

    // ── private helpers ───────────────────────────────────────────────────────

    /**
     * RFC 7807 [ProblemDetail] 인스턴스를 생성하는 헬퍼.
     * [com.bts.search.web.ExportJobExceptionHandler.problem] 과 동일한 형식을 따른다.
     */
    private fun problem(
        status: HttpStatus,
        type: String,
        title: String,
        errorCode: String,
        detail: String,
    ): ProblemDetail {
        val pd = ProblemDetail.forStatus(status)
        pd.type = URI.create("https://bts.example.com/problems/$type")
        pd.title = title
        pd.detail = detail
        pd.setProperty("errorCode", errorCode)
        pd.setProperty("timestamp", Instant.now().toString())
        return pd
    }

    companion object {
        const val IMPORT_ACCESS_DENIED = "IMPORT_ACCESS_DENIED"
        const val IMPORT_FILE_TOO_LARGE = "IMPORT_FILE_TOO_LARGE"
        const val IMPORT_UNSUPPORTED_FORMAT = "IMPORT_UNSUPPORTED_FORMAT"
        const val IMPORT_VALIDATION_FAILED = "IMPORT_VALIDATION_FAILED"
        const val IMPORT_UNAUTHENTICATED = "IMPORT_UNAUTHENTICATED"
        const val IMPORT_NOT_FOUND = "IMPORT_NOT_FOUND"
        const val IMPORT_INTERNAL_ERROR = "IMPORT_INTERNAL_ERROR"
        const val IMPORT_MAPPING_INVALID = "IMPORT_MAPPING_INVALID"
        const val IMPORT_MAPPING_STATE_CONFLICT = "IMPORT_MAPPING_STATE_CONFLICT"
    }
}
