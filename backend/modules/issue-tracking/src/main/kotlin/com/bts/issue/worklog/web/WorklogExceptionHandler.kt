// 워크로그 컨트롤러 전용 예외 핸들러 — WorklogController 스코프 한정 (FR-TT-01)

package com.bts.issue.worklog.web

import com.bts.issue.domain.IssueAccessDeniedException
import com.bts.issue.domain.IssueNotFoundException
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
 * 워크로그 컨트롤러 예외 → HTTP 응답 변환 핸들러.
 *
 * [WorklogController] 에만 스코프를 한정한다.
 * [com.bts.issue.adapter.inbound.rest.IssueExceptionHandler] 의 basePackages 가
 * `com.bts.issue.adapter.inbound.rest` 로 고정되어 있어 `com.bts.issue.worklog.web` 패키지를
 * 커버하지 않는다. 따라서 이 핸들러에서 공통 예외를 직접 처리한다.
 *
 * 매핑 규칙.
 * - [MethodArgumentTypeMismatchException] → 400 Bad Request (경로 UUID 형식 오류)
 * - [HttpMessageNotReadableException] → 400 Bad Request (JSON 역직렬화 실패)
 * - [IssueNotFoundException] → 404 Not Found
 * - [IssueAccessDeniedException] → 403 Forbidden (detail 에 내부 정보 비노출)
 * - [ResponseStatusException] → 상태 코드 전파 (401 등 catch-all 변질 차단)
 * - [Exception] (fallback) → 500 Internal Server Error
 *
 * ### catch-all 설계 원칙 (FR-WT-01 교훈)
 * catch-all [handleInternalError] 를 최후 fallback 으로 두되,
 * [MethodArgumentTypeMismatchException]/[HttpMessageNotReadableException]/[ResponseStatusException] 을
 * 구체 핸들러로 먼저 처리하여 400·401 이 500 으로 변질되지 않도록 한다.
 */
@RestControllerAdvice(assignableTypes = [WorklogController::class])
class WorklogExceptionHandler {
    private val log = LoggerFactory.getLogger(javaClass)

    // ── 400 WORKLOG_VALIDATION_FAILED ─────────────────────────────────────────

    /**
     * [MethodArgumentTypeMismatchException] — 경로 변수 타입 불일치 — 400.
     *
     * PATCH/DELETE /worklogs/{worklogId} 에 UUID 형식이 아닌 값이 전달되면 발생한다.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    fun handleMethodArgumentTypeMismatch(ex: MethodArgumentTypeMismatchException): ProblemDetail {
        log.info("WORKLOG_400 type_mismatch param='{}' value='{}'", ex.name, ex.value)
        return problem(
            status = HttpStatus.BAD_REQUEST,
            type = "worklog-validation-failed",
            title = "Bad Request",
            errorCode = WORKLOG_VALIDATION_FAILED,
            detail = "요청 경로 또는 파라미터 형식이 올바르지 않습니다.",
        )
    }

    /**
     * [HttpMessageNotReadableException] — 요청 본문 역직렬화 실패 — 400.
     *
     * JSON 형식 오류 또는 타입 불일치 시 Jackson 이 발생시킨다.
     */
    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun handleHttpMessageNotReadable(ex: HttpMessageNotReadableException): ProblemDetail {
        log.info("WORKLOG_400 message_not_readable cause='{}'", ex.cause?.message ?: ex.message)
        return problem(
            status = HttpStatus.BAD_REQUEST,
            type = "worklog-validation-failed",
            title = "Bad Request",
            errorCode = WORKLOG_VALIDATION_FAILED,
            detail = "요청 본문을 읽을 수 없습니다. JSON 형식 또는 필드 값을 확인해 주세요.",
        )
    }

    // ── 403 ACCESS_DENIED ─────────────────────────────────────────────────────

    /**
     * 권한 없음 — 403.
     *
     * 응답 detail 에 행위자 UUID, 권한명, 범위 등 내부 정보를 노출하지 않는다.
     */
    @ExceptionHandler(IssueAccessDeniedException::class)
    fun handleAccessDenied(ex: IssueAccessDeniedException): ProblemDetail {
        log.info("WORKLOG_403 access_denied message='{}'", ex.message)
        return problem(
            status = HttpStatus.FORBIDDEN,
            type = "access-denied",
            title = "Access Denied",
            errorCode = "ISSUE_ACCESS_DENIED",
            detail = "이 작업을 수행할 권한이 없습니다.",
        )
    }

    // ── 404 ISSUE_NOT_FOUND ───────────────────────────────────────────────────

    /**
     * 이슈 미존재 또는 소프트 삭제 — 404.
     */
    @ExceptionHandler(IssueNotFoundException::class)
    fun handleIssueNotFound(ex: IssueNotFoundException): ProblemDetail {
        log.info("WORKLOG_404 issue_not_found message='{}'", ex.message)
        return problem(
            status = HttpStatus.NOT_FOUND,
            type = "issue-not-found",
            title = "Issue Not Found",
            errorCode = "ISSUE_NOT_FOUND",
            detail = "이슈를 찾을 수 없습니다.",
        )
    }

    // ── ResponseStatusException 상태 전파 (401 catch-all 변질 차단) ─────────────

    /**
     * [ResponseStatusException] — 컨트롤러/헬퍼가 명시한 HTTP 상태를 그대로 전파한다.
     *
     * [com.bts.issue.adapter.inbound.rest.CurrentActor.current] 가 미인증 시 던지는 401
     * [ResponseStatusException] 이 catch-all [handleInternalError] 에 가로채여
     * 500 으로 변질되는 것을 차단한다.
     */
    @ExceptionHandler(ResponseStatusException::class)
    fun handleResponseStatus(ex: ResponseStatusException): ProblemDetail {
        val status = HttpStatus.valueOf(ex.statusCode.value())
        log.info("WORKLOG_{} response_status reason='{}'", status.value(), ex.reason)
        val (errorCode, detail) =
            when (status) {
                HttpStatus.UNAUTHORIZED -> "UNAUTHENTICATED" to "인증이 필요합니다. 세션이 만료되었을 수 있습니다."
                HttpStatus.FORBIDDEN -> "ISSUE_ACCESS_DENIED" to "이 작업을 수행할 권한이 없습니다."
                else -> "ISSUE_INTERNAL_ERROR" to "요청을 처리할 수 없습니다."
            }
        return problem(
            status = status,
            type = "response-status",
            title = status.reasonPhrase,
            errorCode = errorCode,
            detail = detail,
        )
    }

    // ── 500 INTERNAL_ERROR (fallback) ─────────────────────────────────────────

    /**
     * 분류되지 않은 모든 예외 — 500.
     *
     * DB I/O 실패 등 인프라 오류가 여기로 도달한다.
     */
    @ExceptionHandler(Exception::class)
    fun handleInternalError(ex: Exception): ProblemDetail {
        log.error("WORKLOG_500 internal_error", ex)
        return problem(
            status = HttpStatus.INTERNAL_SERVER_ERROR,
            type = "worklog-internal-error",
            title = "Internal Server Error",
            errorCode = "ISSUE_INTERNAL_ERROR",
            detail = "서버 내부 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.",
        )
    }

    // ── private helper ────────────────────────────────────────────────────────

    /**
     * [ProblemDetail] (RFC 7807) 인스턴스를 생성하는 헬퍼.
     */
    private fun problem(
        status: HttpStatus,
        type: String,
        title: String,
        errorCode: String,
        detail: String?,
    ): ProblemDetail {
        val pd = ProblemDetail.forStatus(status)
        pd.type = URI.create("https://bts.example.com/problems/$type")
        pd.title = title
        if (detail != null) pd.detail = detail
        pd.setProperty("errorCode", errorCode)
        pd.setProperty("timestamp", Instant.now().toString())
        return pd
    }

    companion object {
        /** 워크로그 BC 클라이언트 입력 검증 실패 에러 코드. */
        const val WORKLOG_VALIDATION_FAILED = "ISSUE_WORKLOG_VALIDATION_FAILED"
    }
}
