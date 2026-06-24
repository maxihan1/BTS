// 백로그 REST API 예외를 RFC 7807 ProblemDetail HTTP 응답으로 변환하는 핸들러 — agile-planning BC

package com.bts.agileplanning.web

import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.server.ResponseStatusException
import java.net.URI
import java.time.Instant

/**
 * 백로그 REST API 의 예외를 RFC 7807 ProblemDetail 형식으로 변환하는 핸들러.
 *
 * [assignableTypes] 를 [BacklogController] 로 한정해 다른 컨트롤러 예외를 잡지 않는다
 * (memory: fr-bl-02-sprint-backend-done — 형제 @RestControllerAdvice basePackages 가 새 컨트롤러
 * 예외를 가로채거나 못 잡는 함정. assignableTypes 한정으로 스코프를 명확히 제한한다).
 *
 * catch-all [Exception] 핸들러를 두되, [ResponseStatusException] 은 별도 핸들러로 상태를 전파하여
 * catch-all 이 401/403/404/409 를 500 으로 변질시키지 못하게 한다
 * (memory: catch-all-exceptionhandler-swallows-responsestatusexception).
 *
 * ### 매핑 규칙
 * - [MethodArgumentTypeMismatchException] → 400 + AGILE_VALIDATION_FAILED
 * - [ResponseStatusException] → 명시 상태 전파(401/403 등, 일반 메시지)
 * - [Exception] (fallback) → 500 + AGILE_INTERNAL_ERROR
 */
@RestControllerAdvice(assignableTypes = [BacklogController::class])
class BacklogExceptionHandler {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 경로 변수 타입 불일치 — 400.
     *
     * projectKey 자리에 타입 불일치 값이 전달될 때 발생한다.
     *
     * @param ex 타입 불일치 예외.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    fun handleMethodArgumentTypeMismatch(ex: MethodArgumentTypeMismatchException): ProblemDetail {
        log.info("AGILE_400 backlog_type_mismatch param='{}'", ex.name)
        return problem(
            status = HttpStatus.BAD_REQUEST,
            type = "agile-validation-failed",
            title = "Validation Failed",
            errorCode = AGILE_VALIDATION_FAILED,
            detail = "요청 경로 또는 파라미터 형식이 올바르지 않습니다.",
        )
    }

    /**
     * [ResponseStatusException] — 명시된 HTTP 상태를 그대로 전파한다.
     *
     * 서비스가 던지는 401 · 403 이 catch-all 에 가로채여 500 으로 변질되는 것을 차단한다.
     * 보안 — detail 에 내부 사유를 노출하지 않는다.
     *
     * @param ex 명시 상태 코드를 보유한 예외.
     */
    @ExceptionHandler(ResponseStatusException::class)
    fun handleResponseStatus(ex: ResponseStatusException): ProblemDetail {
        val status = HttpStatus.valueOf(ex.statusCode.value())
        log.info("AGILE_{} backlog_response_status reason='{}'", status.value(), ex.reason)
        val (errorCode, detail) =
            when (status) {
                HttpStatus.UNAUTHORIZED ->
                    AGILE_UNAUTHENTICATED to "인증이 필요합니다. 세션이 만료되었을 수 있습니다."
                HttpStatus.FORBIDDEN ->
                    AGILE_ACCESS_DENIED to "이 작업을 수행할 권한이 없습니다."
                HttpStatus.NOT_FOUND ->
                    AGILE_NOT_FOUND to "리소스를 찾을 수 없습니다."
                HttpStatus.BAD_REQUEST ->
                    AGILE_VALIDATION_FAILED to "요청 값이 올바르지 않습니다."
                else ->
                    AGILE_INTERNAL_ERROR to "요청을 처리할 수 없습니다."
            }
        return problem(
            status = status,
            type = "agile-backlog-response-status",
            title = status.reasonPhrase,
            errorCode = errorCode,
            detail = detail,
        )
    }

    /**
     * 분류되지 않은 모든 예외 — 500.
     *
     * @param ex 처리되지 않은 예외.
     */
    @ExceptionHandler(Exception::class)
    fun handleInternalError(ex: Exception): ProblemDetail {
        log.error("AGILE_500 backlog_internal_error", ex)
        return problem(
            status = HttpStatus.INTERNAL_SERVER_ERROR,
            type = "agile-internal-error",
            title = "Internal Server Error",
            errorCode = AGILE_INTERNAL_ERROR,
            detail = "서버 내부 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.",
        )
    }

    // ── private helper ────────────────────────────────────────────────────────

    /**
     * RFC 7807 [ProblemDetail] 인스턴스를 생성하는 헬퍼.
     *
     * 표준 필드 외에 커스텀 errorCode 와 timestamp 를 추가한다.
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
        const val AGILE_VALIDATION_FAILED = "AGILE_VALIDATION_FAILED"
        const val AGILE_UNAUTHENTICATED = "AGILE_UNAUTHENTICATED"
        const val AGILE_ACCESS_DENIED = "AGILE_ACCESS_DENIED"
        const val AGILE_NOT_FOUND = "AGILE_NOT_FOUND"
        const val AGILE_INTERNAL_ERROR = "AGILE_INTERNAL_ERROR"
    }
}
