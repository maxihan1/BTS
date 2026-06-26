// 타임라인 REST API 예외를 RFC 7807 ProblemDetail HTTP 응답으로 변환하는 핸들러 — agile-planning BC

package com.bts.agileplanning.web

import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.server.ResponseStatusException
import java.net.URI
import java.time.Instant

/**
 * 타임라인 REST API 의 예외를 RFC 7807 ProblemDetail 형식으로 변환하는 핸들러.
 *
 * [assignableTypes] 를 [TimelineController] 로 한정해 다른 컨트롤러 예외를 잡지 않는다
 * (memory: fr-bl-02-sprint-backend-done — 형제 @RestControllerAdvice 가 신규 컨트롤러 예외를
 * 가로채거나 못 잡는 함정. assignableTypes 한정으로 스코프를 명확히 제한한다).
 *
 * catch-all [Exception] 핸들러를 두되, [ResponseStatusException] 은 별도 핸들러로 상태를 전파해
 * catch-all 이 401/403 을 500 으로 변질시키지 못하게 한다
 * (memory: catch-all-exceptionhandler-swallows-responsestatusexception 교훈).
 *
 * 에러 코드 접두사는 `AGILE_` 로 고정한다 (BTS 에러 코드 규칙).
 *
 * ### 매핑 규칙
 * - [MissingServletRequestParameterException] → 400 + AGILE_VALIDATION_FAILED
 *   (`project` 쿼리 파라미터 누락 시 500 이 아닌 400 반환 — B2 핵심).
 * - [ResponseStatusException] → 명시 상태 전파(401/403 등, 일반 메시지).
 * - [Exception] (fallback) → 500 + AGILE_INTERNAL_ERROR.
 */
@RestControllerAdvice(assignableTypes = [TimelineController::class])
class TimelineExceptionHandler {
    private val log = LoggerFactory.getLogger(javaClass)

    // ── 400 VALIDATION_FAILED ─────────────────────────────────────────────────

    /**
     * 필수 쿼리 파라미터 누락 — 400.
     *
     * `project` 등 required=true 파라미터가 없을 때 발생한다.
     * 핸들러가 없으면 Spring MVC 가 기본 500 을 반환할 수 있으므로 명시 등록이 필요하다.
     *
     * @param ex 필수 파라미터 누락 예외.
     */
    @ExceptionHandler(MissingServletRequestParameterException::class)
    fun handleMissingServletRequestParameter(ex: MissingServletRequestParameterException): ProblemDetail {
        log.info("AGILE_400 timeline_missing_param name='{}'", ex.parameterName)
        return problem(
            status = HttpStatus.BAD_REQUEST,
            type = "agile-validation-failed",
            title = "Validation Failed",
            errorCode = AGILE_VALIDATION_FAILED,
            detail = "필수 요청 파라미터가 누락되었습니다.",
        )
    }

    // ── ResponseStatusException 상태 전파 (catch-all 변질 차단) ─────────────

    /**
     * [ResponseStatusException] — 컨트롤러/서비스가 명시한 HTTP 상태를 그대로 전파한다.
     *
     * 서비스가 던지는 403(권한 거부), 401(미인증) 등이 catch-all 에 가로채여
     * 500 으로 변질되는 것을 차단한다.
     * 보안 — detail 에 내부 사유를 노출하지 않는다.
     *
     * @param ex 명시 상태 코드를 보유한 예외.
     */
    @ExceptionHandler(ResponseStatusException::class)
    fun handleResponseStatus(ex: ResponseStatusException): ProblemDetail {
        val status = HttpStatus.valueOf(ex.statusCode.value())
        log.info("AGILE_{} timeline_response_status reason='{}'", status.value(), ex.reason)
        val (errorCode, detail) =
            when (status) {
                HttpStatus.UNAUTHORIZED ->
                    AGILE_UNAUTHENTICATED to "인증이 필요합니다. 세션이 만료되었을 수 있습니다."
                HttpStatus.FORBIDDEN ->
                    AGILE_ACCESS_DENIED to "이 작업을 수행할 권한이 없습니다."
                HttpStatus.NOT_FOUND ->
                    AGILE_NOT_FOUND to "타임라인 항목을 찾을 수 없습니다."
                HttpStatus.BAD_REQUEST ->
                    AGILE_VALIDATION_FAILED to "요청 값이 올바르지 않습니다."
                else ->
                    AGILE_INTERNAL_ERROR to "요청을 처리할 수 없습니다."
            }
        return problem(
            status = status,
            type = "agile-timeline-response-status",
            title = status.reasonPhrase,
            errorCode = errorCode,
            detail = detail,
        )
    }

    // ── 500 INTERNAL_ERROR (fallback) ─────────────────────────────────────────

    /**
     * 분류되지 않은 모든 예외 — 500.
     *
     * @param ex 처리되지 않은 예외.
     */
    @ExceptionHandler(Exception::class)
    fun handleInternalError(ex: Exception): ProblemDetail {
        log.error("AGILE_500 timeline_internal_error", ex)
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
     *
     * @param status HTTP 응답 상태 코드.
     * @param type `https://bts.example.com/problems/` 뒤에 붙는 type suffix.
     * @param title 사람이 읽을 수 있는 문제 유형 요약.
     * @param errorCode BTS 에러 코드 상수 (AGILE_ 접두사).
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

    /** agile-planning BC 타임라인 에러 코드 상수. 모두 AGILE_ 접두사를 사용한다. */
    private companion object {
        const val AGILE_VALIDATION_FAILED = "AGILE_VALIDATION_FAILED"
        const val AGILE_UNAUTHENTICATED = "AGILE_UNAUTHENTICATED"
        const val AGILE_ACCESS_DENIED = "AGILE_ACCESS_DENIED"
        const val AGILE_NOT_FOUND = "AGILE_NOT_FOUND"
        const val AGILE_INTERNAL_ERROR = "AGILE_INTERNAL_ERROR"
    }
}
