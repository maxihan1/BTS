// 댓글 컨트롤러 전용 예외 핸들러 — CommentController 스코프 한정 (FR-IM-01 PR3)

package com.bts.issue.comment.web

import com.bts.issue.domain.IssueAccessDeniedException
import com.bts.issue.domain.IssueNotFoundException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.server.ResponseStatusException
import java.net.URI
import java.time.Instant

/**
 * 댓글 컨트롤러 예외 → HTTP 응답 변환 핸들러.
 *
 * [CommentController] 에만 스코프를 한정한다.
 * [com.bts.issue.adapter.inbound.rest.IssueExceptionHandler] 의 basePackages 가
 * `com.bts.issue.adapter.inbound.rest` 로 고정되어 있어 `com.bts.issue.comment.web` 패키지를
 * 커버하지 않는다. 따라서 이 핸들러에서 공통 예외를 직접 처리한다
 * ([com.bts.issue.worklog.web.WorklogExceptionHandler] 와 동일 근거로 4종을 그대로 미러한다).
 *
 * 매핑 규칙.
 * - [IssueNotFoundException] → 404 Not Found
 * - [IssueAccessDeniedException] → 403 Forbidden (detail 에 내부 정보 비노출)
 * - [ResponseStatusException] → 상태 코드 전파 (401 등 catch-all 변질 차단)
 * - [Exception] (fallback) → 500 Internal Server Error
 *
 * ### catch-all 설계 원칙 (FR-WT-01 교훈, worklog 미러)
 * catch-all [handleInternalError] 를 최후 fallback 으로 두되, [ResponseStatusException] 을
 * 구체 핸들러로 먼저 처리하여 [com.bts.issue.adapter.inbound.rest.CurrentActor.current] 가 미인증 시
 * 던지는 401 이 500 으로 변질되지 않도록 한다
 * (learnings: catch-all-exceptionhandler-swallows-responsestatusexception).
 *
 * [CommentController] 는 GET 단건(문자열 path variable {key})만 가지므로
 * `MethodArgumentTypeMismatchException`/`HttpMessageNotReadableException` 핸들러는 대상이 없어 생략한다.
 */
@RestControllerAdvice(assignableTypes = [CommentController::class])
class CommentExceptionHandler {
    private val log = LoggerFactory.getLogger(javaClass)

    // ── 403 ACCESS_DENIED ─────────────────────────────────────────────────────

    /**
     * 권한 없음 — 403.
     *
     * 응답 detail 에 행위자 UUID, 권한명, 범위 등 내부 정보를 노출하지 않는다.
     */
    @ExceptionHandler(IssueAccessDeniedException::class)
    fun handleAccessDenied(ex: IssueAccessDeniedException): ProblemDetail {
        log.info("COMMENT_403 access_denied message='{}'", ex.message)
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
        log.info("COMMENT_404 issue_not_found message='{}'", ex.message)
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
        log.info("COMMENT_{} response_status reason='{}'", status.value(), ex.reason)
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
        log.error("COMMENT_500 internal_error", ex)
        return problem(
            status = HttpStatus.INTERNAL_SERVER_ERROR,
            type = "comment-internal-error",
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
}
