// 저장된 필터 서비스 예외를 RFC 7807 ProblemDetail HTTP 응답으로 변환하는 핸들러 (FR-SR-03)

package com.bts.search.savedfilter.web

import com.bts.search.aql.AqlLexException
import com.bts.search.aql.AqlSyntaxException
import com.bts.search.savedfilter.application.SavedFilterConflictException
import com.bts.search.savedfilter.application.SavedFilterDuplicateNameException
import com.bts.search.savedfilter.application.SavedFilterNotFoundException
import com.bts.search.savedfilter.application.SavedFilterValidationException
import com.bts.search.web.SearchErrorCodes
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.server.ResponseStatusException
import java.net.URI
import java.time.Instant

/**
 * 저장된 필터(`com.bts.search.savedfilter.web`) 컨트롤러 예외를 RFC 7807 ProblemDetail로 변환한다.
 *
 * [basePackages]를 savedfilter.web으로 한정하여 [SavedFilterController]와
 * [SavedFilterSearchController] 둘을 동시에 커버하되, 형제 [com.bts.search.web.SearchController]나
 * 타 BC 컨트롤러는 가로채지 않는다(교훈 domain-exception-http-handler-basepackage-scope).
 * `assignableTypes` 대신 `basePackages`를 쓰는 이유 — 두 컨트롤러가 서로 다른 task에서 생성되어
 * 클래스 참조 시 순환 의존이 발생하기 때문(eng-review B1).
 *
 * [ResponseStatusException]은 별도 핸들러로 상태를 전파하여 401/400 등을 500으로 변질시키지 않는다
 * (교훈 catch-all-exceptionhandler-swallows-responsestatusexception).
 *
 * 에러 코드는 [SearchErrorCodes](SEARCH_ 접두사)를 재사용하거나 필터 전용 코드를 사용한다.
 */
@Suppress("TooManyFunctions")
@RestControllerAdvice(basePackages = ["com.bts.search.savedfilter.web"])
class SavedFilterExceptionHandler {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * [SavedFilterValidationException] — AQL 구문 오류 등 — 400.
     */
    @ExceptionHandler(SavedFilterValidationException::class)
    fun handleValidation(ex: SavedFilterValidationException): ProblemDetail {
        log.info("FILTER_400 validation message='{}'", ex.message)
        return problem(
            HttpStatus.BAD_REQUEST,
            "saved-filter-validation-failed",
            "Validation Failed",
            SearchErrorCodes.SEARCH_VALIDATION_FAILED,
            ex.message ?: "요청 값 검증에 실패했습니다.",
        )
    }

    /**
     * [AqlSyntaxException] / [AqlLexException] — 저장된 필터 실행 시 AQL 파싱 오류 — 400.
     *
     * 실행 엔드포인트([SavedFilterSearchController])가 저장된 AQL을 인라인 파싱하므로,
     * 문법 grammar drift 등으로 파싱 실패 시 catch-all 500으로 변질되지 않도록 400으로 매핑한다
     * (형제 [com.bts.search.web.SearchExceptionHandler]와 동일 정책).
     */
    @ExceptionHandler(AqlSyntaxException::class, AqlLexException::class)
    fun handleAqlParse(ex: RuntimeException): ProblemDetail {
        log.info("FILTER_400 aql_parse message='{}'", ex.message)
        return problem(
            HttpStatus.BAD_REQUEST,
            "saved-filter-aql-syntax-error",
            "AQL Syntax Error",
            SearchErrorCodes.SEARCH_SYNTAX_ERROR,
            "저장된 필터의 AQL 구문을 해석할 수 없습니다.",
        )
    }

    /**
     * [IllegalArgumentException] — 도메인 불변식 위반(이름/쿼리 길이·blank) — 400.
     *
     * [com.bts.search.savedfilter.domain.SavedFilter.create] 팩토리가 던진다.
     * 보안 — 내부 메시지를 그대로 노출하지 않고 일반 안내를 사용한다.
     */
    @ExceptionHandler(IllegalArgumentException::class)
    fun handleIllegalArgument(ex: IllegalArgumentException): ProblemDetail {
        log.info("FILTER_400 domain_invariant message='{}'", ex.message)
        return problem(
            HttpStatus.BAD_REQUEST,
            "saved-filter-validation-failed",
            "Validation Failed",
            SearchErrorCodes.SEARCH_VALIDATION_FAILED,
            "필터 이름 또는 쿼리 값이 올바르지 않습니다.",
        )
    }

    /**
     * [SavedFilterDuplicateNameException] — 같은 owner 내 이름 중복 — 409.
     */
    @ExceptionHandler(SavedFilterDuplicateNameException::class)
    fun handleDuplicateName(ex: SavedFilterDuplicateNameException): ProblemDetail {
        log.info("FILTER_409 duplicate_name")
        return problem(
            HttpStatus.CONFLICT,
            "saved-filter-name-conflict",
            "Name Conflict",
            FILTER_NAME_CONFLICT,
            ex.message ?: "이미 사용 중인 필터 이름입니다.",
        )
    }

    /**
     * [SavedFilterConflictException] — OCC 충돌 — 409.
     */
    @ExceptionHandler(SavedFilterConflictException::class)
    fun handleConflict(ex: SavedFilterConflictException): ProblemDetail {
        log.info("FILTER_409 occ_conflict")
        return problem(
            HttpStatus.CONFLICT,
            "saved-filter-conflict",
            "Conflict",
            FILTER_CONFLICT,
            ex.message ?: "필터가 다른 요청으로 수정되었습니다. 최신 버전을 재조회 후 재시도하세요.",
        )
    }

    /**
     * [SavedFilterNotFoundException] — 존재하지 않거나 비가시(존재 은닉) — 404.
     */
    @ExceptionHandler(SavedFilterNotFoundException::class)
    fun handleNotFound(
        @Suppress("UnusedParameter") ex: SavedFilterNotFoundException,
    ): ProblemDetail {
        log.info("FILTER_404 not_found")
        return problem(
            HttpStatus.NOT_FOUND,
            "saved-filter-not-found",
            "Not Found",
            FILTER_NOT_FOUND,
            "저장된 필터를 찾을 수 없습니다.",
        )
    }

    /**
     * [HttpMessageNotReadableException] — 요청 본문 역직렬화 실패 — 400.
     */
    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun handleNotReadable(ex: HttpMessageNotReadableException): ProblemDetail {
        log.info("FILTER_400 not_readable cause='{}'", ex.cause?.message ?: ex.message)
        return problem(
            HttpStatus.BAD_REQUEST,
            "saved-filter-validation-failed",
            "Validation Failed",
            SearchErrorCodes.SEARCH_VALIDATION_FAILED,
            "요청 본문을 읽을 수 없습니다. JSON 형식 또는 필드 값을 확인해 주세요.",
        )
    }

    /**
     * [MethodArgumentTypeMismatchException] — 경로 변수 타입 불일치(잘못된 UUID 등) — 400.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    fun handleTypeMismatch(ex: MethodArgumentTypeMismatchException): ProblemDetail {
        log.info("FILTER_400 type_mismatch param='{}'", ex.name)
        return problem(
            HttpStatus.BAD_REQUEST,
            "saved-filter-validation-failed",
            "Validation Failed",
            SearchErrorCodes.SEARCH_VALIDATION_FAILED,
            "요청 경로 또는 파라미터 형식이 올바르지 않습니다.",
        )
    }

    /**
     * [ResponseStatusException] — actor 추출(401) 등 명시 상태 전파.
     */
    @ExceptionHandler(ResponseStatusException::class)
    fun handleResponseStatus(ex: ResponseStatusException): ProblemDetail {
        val status = HttpStatus.valueOf(ex.statusCode.value())
        log.info("FILTER_{} response_status", status.value())
        val (errorCode, detail) =
            when (status) {
                HttpStatus.UNAUTHORIZED ->
                    SearchErrorCodes.SEARCH_UNAUTHENTICATED to "인증이 필요합니다. 세션이 만료되었을 수 있습니다."
                HttpStatus.FORBIDDEN ->
                    SearchErrorCodes.SEARCH_ACCESS_DENIED to "이 작업을 수행할 권한이 없습니다."
                HttpStatus.BAD_REQUEST ->
                    SearchErrorCodes.SEARCH_VALIDATION_FAILED to "요청 값이 올바르지 않습니다."
                else ->
                    SearchErrorCodes.SEARCH_INTERNAL_ERROR to "요청을 처리할 수 없습니다."
            }
        return problem(status, "saved-filter-response-status", status.reasonPhrase, errorCode, detail)
    }

    /**
     * 분류되지 않은 모든 예외 — 500.
     */
    @ExceptionHandler(Exception::class)
    fun handleInternal(ex: Exception): ProblemDetail {
        log.error("FILTER_500 internal_error", ex)
        return problem(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "saved-filter-internal-error",
            "Internal Server Error",
            SearchErrorCodes.SEARCH_INTERNAL_ERROR,
            "서버 내부 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.",
        )
    }

    // ── private helpers ───────────────────────────────────────────────────────

    /**
     * RFC 7807 [ProblemDetail] 인스턴스를 생성한다([com.bts.search.web.SearchExceptionHandler]와 동일 형식).
     *
     * @param status HTTP 응답 상태.
     * @param type type suffix.
     * @param title 문제 유형 요약.
     * @param errorCode BTS 에러 코드.
     * @param detail 상세 설명.
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

    private companion object {
        /** 이름 중복 충돌 에러 코드. */
        const val FILTER_NAME_CONFLICT = "SEARCH_FILTER_NAME_CONFLICT"

        /** OCC 충돌 에러 코드. */
        const val FILTER_CONFLICT = "SEARCH_FILTER_CONFLICT"

        /** 비가시/미존재 에러 코드. */
        const val FILTER_NOT_FOUND = "SEARCH_FILTER_NOT_FOUND"
    }
}
