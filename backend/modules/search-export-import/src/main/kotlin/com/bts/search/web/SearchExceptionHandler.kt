// search BC 도메인/권한 예외를 RFC 7807 ProblemDetail HTTP 응답으로 변환하는 핸들러 (FR-SR-02 NFR-5)

package com.bts.search.web

import com.bts.search.aql.AqlErrorCode
import com.bts.search.aql.AqlLexException
import com.bts.search.aql.AqlSyntaxException
import com.bts.search.web.SearchErrorCodes.SEARCH_ACCESS_DENIED
import com.bts.search.web.SearchErrorCodes.SEARCH_INTERNAL_ERROR
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
 * search-export-import BC 예외를 RFC 7807 ProblemDetail 형식으로 변환하는 핸들러.
 *
 * [assignableTypes]를 [SearchController]로 한정하여 다른 컨트롤러의 예외를 가로채지 않는다
 * (교훈 domain-exception-http-handler-basepackage-scope).
 *
 * catch-all [Exception] 핸들러를 두되 [ResponseStatusException]은 별도 핸들러로 상태를 전파하여
 * 401/403/404 등을 500으로 변질시키지 못하게 한다
 * (교훈 catch-all-exceptionhandler-swallows-responsestatusexception).
 *
 * 에러 코드 접두사는 `SEARCH_`로 고정한다([SearchErrorCodes] 참조).
 *
 * ### 매핑 규칙
 *
 * - [AqlSyntaxException] → 400 + [AqlErrorCode] 매핑 + position(RFC 7807 extension)
 * - [MethodArgumentNotValidException] → 400 + [SearchErrorCodes.SEARCH_VALIDATION_FAILED]
 * - [HttpMessageNotReadableException] → 400 + [SearchErrorCodes.SEARCH_VALIDATION_FAILED]
 * - [MethodArgumentTypeMismatchException] → 400 + [SearchErrorCodes.SEARCH_VALIDATION_FAILED]
 * - [SecurityException] → 403 + [SearchErrorCodes.SEARCH_ACCESS_DENIED] (BROWSE 권한 없음)
 * - [ResponseStatusException] → 명시 상태 전파(401/404 등)
 * - [Exception] (fallback) → 500 + [SearchErrorCodes.SEARCH_INTERNAL_ERROR]
 *
 * TooManyFunctions: 예외 종류별 @ExceptionHandler가 필요하므로 함수 수가 임계치를 넘는다.
 * 단일 관심사(예외→HTTP 변환)라 클래스 단위로 억제한다.
 */
@Suppress("TooManyFunctions")
@RestControllerAdvice(assignableTypes = [SearchController::class])
class SearchExceptionHandler {
    private val log = LoggerFactory.getLogger(javaClass)

    // ── 400 AQL 문법/필드 오류 ────────────────────────────────────────────────

    /**
     * [AqlSyntaxException] — AQL 구문 분석 오류 — 400.
     *
     * [AqlErrorCode]를 [SearchErrorCodes] 상수로 매핑하고,
     * 오류 위치([AqlSyntaxException.position])를 RFC 7807 extension `position` 필드에 포함한다.
     *
     * @param ex AQL 파서가 발생시킨 구문 오류 예외.
     */
    @ExceptionHandler(AqlSyntaxException::class)
    fun handleAqlSyntaxException(ex: AqlSyntaxException): ProblemDetail {
        val errorCode = mapAqlErrorCode(ex.errorCode)
        log.info(
            "SEARCH_400 aql_syntax errorCode={} position={} message='{}'",
            errorCode,
            ex.position,
            ex.message,
        )
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
     * [AqlLexException] — AQL 렉싱 오류(닫히지 않은 따옴표, 인식 불가 문자 등) — 400.
     *
     * [AqlLexException] 은 [AqlSyntaxException] 과 별개의 RuntimeException 계층이다.
     * 별도 핸들러가 없으면 catch-all 500으로 변질된다(교훈 B1).
     *
     * [AqlSyntaxException] 핸들러와 동일한 응답 형식을 사용한다.
     * errorCode 는 항상 SEARCH_SYNTAX_ERROR, position 은 렉서가 보고한 컬럼 인덱스.
     *
     * @param ex 렉서가 발생시킨 렉싱 오류 예외.
     */
    @ExceptionHandler(AqlLexException::class)
    fun handleAqlLexException(ex: AqlLexException): ProblemDetail {
        log.info(
            "SEARCH_400 aql_lex_error position={} message='{}'",
            ex.position,
            ex.message,
        )
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
     * size 초과 / query 길이 초과 등 [SearchController.validateRequest]에서 던지는 예외를 처리한다.
     *
     * @param ex 검증 오류 예외.
     */
    @ExceptionHandler(SearchValidationException::class)
    fun handleSearchValidationException(ex: SearchValidationException): ProblemDetail {
        log.info("SEARCH_400 validation_failed message='{}'", ex.message)
        return problem(
            status = HttpStatus.BAD_REQUEST,
            type = "search-validation-failed",
            title = "Validation Failed",
            errorCode = SEARCH_VALIDATION_FAILED,
            detail = ex.message ?: "요청 값 검증에 실패했습니다.",
        )
    }

    /**
     * [MethodArgumentNotValidException] — Bean Validation(@Valid) 실패 — 400.
     *
     * @param ex Spring MVC가 생성한 검증 실패 예외.
     */
    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidationFailed(ex: MethodArgumentNotValidException): ProblemDetail {
        val fieldErrors =
            ex.bindingResult.fieldErrors.joinToString("; ") { "${it.field}: ${it.defaultMessage}" }
        log.info("SEARCH_400 validation_failed fields='{}'", fieldErrors)
        return problem(
            status = HttpStatus.BAD_REQUEST,
            type = "search-validation-failed",
            title = "Validation Failed",
            errorCode = SEARCH_VALIDATION_FAILED,
            detail = "요청 값 검증에 실패했습니다.",
        )
    }

    /**
     * [HttpMessageNotReadableException] — 요청 본문 역직렬화 실패 — 400.
     *
     * JSON 형식 오류 또는 타입 불일치 시 발생한다.
     * 보안 — 역직렬화 오류 상세는 응답에 포함하지 않고 로그에만 기록한다.
     *
     * @param ex 역직렬화 실패를 나타내는 Spring HTTP 메시지 변환 예외.
     */
    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun handleHttpMessageNotReadable(ex: HttpMessageNotReadableException): ProblemDetail {
        log.info("SEARCH_400 message_not_readable cause='{}'", ex.cause?.message ?: ex.message)
        return problem(
            status = HttpStatus.BAD_REQUEST,
            type = "search-validation-failed",
            title = "Validation Failed",
            errorCode = SEARCH_VALIDATION_FAILED,
            detail = "요청 본문을 읽을 수 없습니다. JSON 형식 또는 필드 값을 확인해 주세요.",
        )
    }

    /**
     * [MethodArgumentTypeMismatchException] — 경로 변수/파라미터 타입 불일치 — 400.
     *
     * 보안 — 파라미터 이름·요청값 등 내부 정보는 로그에만 기록한다.
     *
     * @param ex 파라미터 이름·요청값·목표 타입 정보를 포함하는 예외.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    fun handleMethodArgumentTypeMismatch(ex: MethodArgumentTypeMismatchException): ProblemDetail {
        log.info("SEARCH_400 type_mismatch param='{}'", ex.name)
        return problem(
            status = HttpStatus.BAD_REQUEST,
            type = "search-validation-failed",
            title = "Validation Failed",
            errorCode = SEARCH_VALIDATION_FAILED,
            detail = "요청 경로 또는 파라미터 형식이 올바르지 않습니다.",
        )
    }

    // ── 403 ACCESS_DENIED ─────────────────────────────────────────────────────

    /**
     * [SecurityException] — BROWSE 권한 없음 — 403.
     *
     * IssueSearchPort 구현체가 BROWSE 권한이 없을 때 던진다.
     * 보안 — 프로젝트 존재 여부를 노출하지 않기 위해 일반 메시지만 반환한다
     * (교훈 fr-pm-04-guard-exception-message-http-leak).
     *
     * @param ex 권한 거부 예외.
     */
    @ExceptionHandler(SecurityException::class)
    fun handleSecurityException(
        @Suppress("UnusedParameter") ex: SecurityException,
    ): ProblemDetail {
        log.info("SEARCH_403 access_denied")
        return problem(
            status = HttpStatus.FORBIDDEN,
            type = "search-access-denied",
            title = "Access Denied",
            errorCode = SEARCH_ACCESS_DENIED,
            detail = "이 작업을 수행할 권한이 없습니다.",
        )
    }

    // ── ResponseStatusException 상태 전파 ─────────────────────────────────────

    /**
     * [ResponseStatusException] — 명시 HTTP 상태 전파.
     *
     * [SearchController]의 actor 추출(401) 등이 catch-all에 가로채여 500으로 변질되던
     * 문제를 차단한다.
     * 보안 — detail에 `ex.reason` 등 내부 정보를 노출하지 않고 상태 기반 일반 메시지를 사용한다.
     *
     * @param ex 상태 코드를 보유한 예외.
     */
    @ExceptionHandler(ResponseStatusException::class)
    fun handleResponseStatus(ex: ResponseStatusException): ProblemDetail {
        val status = HttpStatus.valueOf(ex.statusCode.value())
        log.info(
            "SEARCH_{} response_status reason='{}'",
            status.value(),
            ex.reason,
        )
        val (errorCode, detail) =
            when (status) {
                HttpStatus.UNAUTHORIZED ->
                    SEARCH_UNAUTHENTICATED to "인증이 필요합니다. 세션이 만료되었을 수 있습니다."
                HttpStatus.FORBIDDEN ->
                    SEARCH_ACCESS_DENIED to "이 작업을 수행할 권한이 없습니다."
                HttpStatus.BAD_REQUEST ->
                    SEARCH_VALIDATION_FAILED to "요청 값이 올바르지 않습니다."
                else ->
                    SEARCH_INTERNAL_ERROR to "요청을 처리할 수 없습니다."
            }
        return problem(
            status,
            "search-response-status",
            status.reasonPhrase,
            errorCode,
            detail,
        )
    }

    // ── 500 INTERNAL_ERROR (fallback) ──────────────────────────────────────────

    /**
     * 분류되지 않은 모든 예외 — 500.
     *
     * @param ex 처리되지 않은 예외.
     */
    @ExceptionHandler(Exception::class)
    fun handleInternalError(ex: Exception): ProblemDetail {
        log.error("SEARCH_500 internal_error", ex)
        return problem(
            status = HttpStatus.INTERNAL_SERVER_ERROR,
            type = "search-internal-error",
            title = "Internal Server Error",
            errorCode = SEARCH_INTERNAL_ERROR,
            detail = "서버 내부 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.",
        )
    }

    // ── private helpers ───────────────────────────────────────────────────────

    /**
     * [AqlErrorCode]를 [SearchErrorCodes] 문자열 상수로 매핑한다.
     *
     * @param code AQL 파서가 설정한 에러 코드.
     * @return [SearchErrorCodes] 문자열 상수.
     */
    private fun mapAqlErrorCode(code: AqlErrorCode): String =
        when (code) {
            AqlErrorCode.SEARCH_SYNTAX_ERROR -> SearchErrorCodes.SEARCH_SYNTAX_ERROR
            AqlErrorCode.SEARCH_UNKNOWN_FIELD -> SearchErrorCodes.SEARCH_UNKNOWN_FIELD
            AqlErrorCode.SEARCH_FIELD_NOT_YET_SUPPORTED -> SearchErrorCodes.SEARCH_FIELD_NOT_YET_SUPPORTED
        }

    /**
     * RFC 7807 [ProblemDetail] 인스턴스를 생성하는 헬퍼.
     *
     * 표준 필드 외에 커스텀 `errorCode`와 `timestamp`를 추가한다.
     * [BoardExceptionHandler][com.bts.agileplanning.web.BoardExceptionHandler]와 동일한 형식을 따른다.
     *
     * @param status HTTP 응답 상태 코드.
     * @param type `https://bts.example.com/problems/` 뒤에 붙는 type suffix.
     * @param title 사람이 읽을 수 있는 문제 유형 요약.
     * @param errorCode BTS 에러 코드 상수(SEARCH_ 접두사).
     * @param detail 이 특정 발생에 대한 상세 설명.
     * @return 완성된 [ProblemDetail] 인스턴스.
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

/**
 * search BC DTO 검증 실패를 나타내는 예외.
 *
 * [SearchController.validateRequest]가 Bean Validation 외에 명시적 검증에서
 * 오류를 발견했을 때 던진다.
 * [SearchExceptionHandler.handleSearchValidationException]이 400 + [SearchErrorCodes.SEARCH_VALIDATION_FAILED]로 변환한다.
 *
 * @param message 사용자에게 안내할 검증 오류 메시지.
 */
class SearchValidationException(message: String) : RuntimeException(message)
