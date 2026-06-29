// ExportJobController 전용 예외 핸들러 — RFC 7807 ProblemDetail 변환 (FR-EX-02 Task 10)

package com.bts.search.web

import com.bts.search.aql.AqlErrorCode
import com.bts.search.aql.AqlLexException
import com.bts.search.aql.AqlSyntaxException
import com.bts.search.web.SearchErrorCodes.SEARCH_ACCESS_DENIED
import com.bts.search.web.SearchErrorCodes.SEARCH_EXPORT_NOT_READY
import com.bts.search.web.SearchErrorCodes.SEARCH_INTERNAL_ERROR
import com.bts.search.web.SearchErrorCodes.SEARCH_NOT_FOUND
import com.bts.search.web.SearchErrorCodes.SEARCH_UNAUTHENTICATED
import com.bts.search.web.SearchErrorCodes.SEARCH_VALIDATION_FAILED
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.server.ResponseStatusException
import java.net.URI
import java.time.Instant

/**
 * [ExportJobController] 전용 예외 핸들러.
 *
 * [assignableTypes] 를 [ExportJobController] 로 한정하여 다른 컨트롤러 예외를 가로채지 않는다
 * (교훈 domain-exception-http-handler-basepackage-scope, fr-bl-02-sprint-backend-done).
 * catch-all [Exception] 핸들러를 두되 [ResponseStatusException] 은 별도 핸들러로 상태를 전파하여
 * 401/409 등을 500 으로 변질시키지 않는다
 * (교훈 catch-all-exceptionhandler-swallows-responsestatusexception).
 *
 * ### 매핑 규칙
 *
 * - [AqlSyntaxException] → 400 + AQL 에러코드 매핑 + position
 * - [AqlLexException] → 400 + SEARCH_SYNTAX_ERROR + position
 * - [SearchValidationException] → 400 + [SEARCH_VALIDATION_FAILED]
 * - [MethodArgumentNotValidException] → 400 + [SEARCH_VALIDATION_FAILED]
 * - [HttpMessageNotReadableException] → 400 + [SEARCH_VALIDATION_FAILED]
 * - [MethodArgumentTypeMismatchException] → 400 + [SEARCH_VALIDATION_FAILED]
 * - [ResponseStatusException](401) → [SEARCH_UNAUTHENTICATED]
 * - [ResponseStatusException](404) → [SEARCH_NOT_FOUND]
 * - [ResponseStatusException](409) → [SEARCH_EXPORT_NOT_READY]
 * - [Exception] (fallback) → 500 + [SEARCH_INTERNAL_ERROR]
 */
@Suppress("TooManyFunctions")
@RestControllerAdvice(assignableTypes = [ExportJobController::class])
class ExportJobExceptionHandler {
    private val log = LoggerFactory.getLogger(javaClass)

    // ── 400 AQL 문법/필드 오류 ────────────────────────────────────────────────

    /**
     * [AqlSyntaxException] — AQL 구문 분석 오류 — 400.
     *
     * @param ex AQL 파서가 발생시킨 구문 오류 예외.
     */
    @ExceptionHandler(AqlSyntaxException::class)
    fun handleAqlSyntaxException(ex: AqlSyntaxException): ProblemDetail {
        val errorCode = mapAqlErrorCode(ex.errorCode)
        log.info("EXPORTJOB_400 aql_syntax errorCode={} position={}", errorCode, ex.position)
        val pd =
            problem(
                status = HttpStatus.BAD_REQUEST,
                type = "search-aql-syntax-error",
                title = "AQL Syntax Error",
                errorCode = errorCode,
                detail = ex.message ?: "AQL 쿼리에 구문 오류가 있습니다.",
            )
        pd.setProperty("position", ex.position)
        return pd
    }

    /**
     * [AqlLexException] — AQL 렉싱 오류 — 400.
     *
     * @param ex 렉서가 발생시킨 렉싱 오류 예외.
     */
    @ExceptionHandler(AqlLexException::class)
    fun handleAqlLexException(ex: AqlLexException): ProblemDetail {
        log.info("EXPORTJOB_400 aql_lex position={}", ex.position)
        val pd =
            problem(
                status = HttpStatus.BAD_REQUEST,
                type = "search-aql-syntax-error",
                title = "AQL Syntax Error",
                errorCode = SearchErrorCodes.SEARCH_SYNTAX_ERROR,
                detail = ex.message ?: "AQL 쿼리에 렉싱 오류가 있습니다.",
            )
        pd.setProperty("position", ex.position)
        return pd
    }

    // ── 400 검증 실패 ─────────────────────────────────────────────────────────

    /**
     * [SearchValidationException] — 컨트롤러 명시 검증 실패 — 400.
     *
     * @param ex 검증 오류 예외.
     */
    @ExceptionHandler(SearchValidationException::class)
    fun handleSearchValidationException(ex: SearchValidationException): ProblemDetail {
        log.info("EXPORTJOB_400 validation_failed message='{}'", ex.message)
        return problem(
            HttpStatus.BAD_REQUEST,
            "search-validation-failed",
            "Validation Failed",
            SEARCH_VALIDATION_FAILED,
            ex.message ?: "요청 값 검증에 실패했습니다.",
        )
    }

    /**
     * [MethodArgumentNotValidException] — Bean Validation(@Valid) 실패 — 400.
     *
     * @param ex Spring MVC 가 생성한 검증 실패 예외.
     */
    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidationFailed(ex: MethodArgumentNotValidException): ProblemDetail {
        val fields = ex.bindingResult.fieldErrors.joinToString("; ") { "${it.field}: ${it.defaultMessage}" }
        log.info("EXPORTJOB_400 bean_validation fields='{}'", fields)
        return problem(
            HttpStatus.BAD_REQUEST,
            "search-validation-failed",
            "Validation Failed",
            SEARCH_VALIDATION_FAILED,
            "요청 값 검증에 실패했습니다.",
        )
    }

    /**
     * [HttpMessageNotReadableException] — 요청 본문 역직렬화 실패 — 400.
     *
     * @param ex 역직렬화 실패 예외.
     */
    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun handleHttpMessageNotReadable(ex: HttpMessageNotReadableException): ProblemDetail {
        log.info("EXPORTJOB_400 message_not_readable cause='{}'", ex.cause?.message ?: ex.message)
        return problem(
            HttpStatus.BAD_REQUEST,
            "search-validation-failed",
            "Validation Failed",
            SEARCH_VALIDATION_FAILED,
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
        log.info("EXPORTJOB_400 type_mismatch param='{}'", ex.name)
        return problem(
            HttpStatus.BAD_REQUEST,
            "search-validation-failed",
            "Validation Failed",
            SEARCH_VALIDATION_FAILED,
            "요청 경로 또는 파라미터 형식이 올바르지 않습니다.",
        )
    }

    // ── ResponseStatusException 상태 전파 ─────────────────────────────────────

    /**
     * [ResponseStatusException] — 명시 HTTP 상태 전파.
     *
     * 401(미인증)/404(not found)/409(not ready) 등이 catch-all 에 가로채여
     * 500 으로 변질되는 문제를 차단한다.
     *
     * @param ex 상태 코드를 보유한 예외.
     */
    @ExceptionHandler(ResponseStatusException::class)
    fun handleResponseStatus(ex: ResponseStatusException): ProblemDetail {
        val status = HttpStatus.valueOf(ex.statusCode.value())
        log.info("EXPORTJOB_{} response_status reason='{}'", status.value(), ex.reason)
        val (errorCode, detail) =
            when (status) {
                HttpStatus.UNAUTHORIZED -> SEARCH_UNAUTHENTICATED to "인증이 필요합니다."
                HttpStatus.FORBIDDEN -> SEARCH_ACCESS_DENIED to "이 작업을 수행할 권한이 없습니다."
                HttpStatus.NOT_FOUND -> SEARCH_NOT_FOUND to "요청한 리소스를 찾을 수 없습니다."
                HttpStatus.CONFLICT -> SEARCH_EXPORT_NOT_READY to "Export 작업이 아직 완료되지 않았습니다. 잠시 후 다시 시도해 주세요."
                HttpStatus.BAD_REQUEST -> SEARCH_VALIDATION_FAILED to "요청 값이 올바르지 않습니다."
                else -> SEARCH_INTERNAL_ERROR to "요청을 처리할 수 없습니다."
            }
        return problem(status, "search-response-status", status.reasonPhrase, errorCode, detail)
    }

    // ── 500 INTERNAL_ERROR (fallback) ─────────────────────────────────────────

    /**
     * 분류되지 않은 모든 예외 — 500.
     *
     * @param ex 처리되지 않은 예외.
     */
    @ExceptionHandler(Exception::class)
    fun handleInternalError(ex: Exception): ProblemDetail {
        log.error("EXPORTJOB_500 internal_error", ex)
        return problem(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "search-internal-error",
            "Internal Server Error",
            SEARCH_INTERNAL_ERROR,
            "서버 내부 오류가 발생했습니다.",
        )
    }

    // ── private helpers ───────────────────────────────────────────────────────

    /**
     * [AqlErrorCode] 를 [SearchErrorCodes] 문자열 상수로 매핑한다.
     */
    private fun mapAqlErrorCode(code: AqlErrorCode): String =
        when (code) {
            AqlErrorCode.SEARCH_SYNTAX_ERROR -> SearchErrorCodes.SEARCH_SYNTAX_ERROR
            AqlErrorCode.SEARCH_UNKNOWN_FIELD -> SearchErrorCodes.SEARCH_UNKNOWN_FIELD
            AqlErrorCode.SEARCH_FIELD_NOT_YET_SUPPORTED -> SearchErrorCodes.SEARCH_FIELD_NOT_YET_SUPPORTED
        }

    /**
     * RFC 7807 [ProblemDetail] 인스턴스를 생성하는 헬퍼. [ExportExceptionHandler.problem] 과 동일 형식.
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
}
