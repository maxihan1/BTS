// 댓글 컨트롤러 전용 예외 핸들러 — CommentController 스코프 한정 (FR-IM-01 PR3)

package com.bts.issue.comment.web

import com.bts.issue.comment.domain.CommentBodyBlankException
import com.bts.issue.comment.domain.CommentBodyTooLongException
import com.bts.issue.comment.domain.CommentNotFoundException
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
 * 댓글 컨트롤러 예외 → HTTP 응답 변환 핸들러.
 *
 * [CommentController] 에만 스코프를 한정한다.
 * [com.bts.issue.adapter.inbound.rest.IssueExceptionHandler] 의 basePackages 가
 * `com.bts.issue.adapter.inbound.rest` 로 고정되어 있어 `com.bts.issue.comment.web` 패키지를
 * 커버하지 않는다. 따라서 이 핸들러에서 공통 예외를 직접 처리한다
 * ([com.bts.issue.worklog.web.WorklogExceptionHandler] 와 동일 근거로 4종을 그대로 미러한다).
 *
 * 매핑 규칙.
 * - [MethodArgumentTypeMismatchException] → 400 Bad Request (경로 UUID 형식 오류)
 * - [HttpMessageNotReadableException] → 400 Bad Request (JSON 역직렬화 실패)
 * - [CommentBodyBlankException] / [CommentBodyTooLongException] → 400 Bad Request
 * - [IssueNotFoundException] → 404 Not Found
 * - [CommentNotFoundException] → 404 Not Found
 * - [IssueAccessDeniedException] → 403 Forbidden (detail 에 내부 정보 비노출)
 * - [ResponseStatusException] → 상태 코드 전파 (401 등 catch-all 변질 차단)
 * - [Exception] (fallback) → 500 Internal Server Error
 *
 * 아카이브된 프로젝트에 대한 쓰기(409)는 여기가 아니라
 * [com.bts.issue.project.archive.web.ProjectArchivedExceptionHandler] 가 처리한다
 * (`@Order(HIGHEST_PRECEDENCE)` 전역 advice — 이 핸들러보다 우선한다).
 *
 * ### catch-all 설계 원칙 (FR-WT-01 교훈, worklog 미러)
 * catch-all [handleInternalError] 를 최후 fallback 으로 두되, [ResponseStatusException] 을
 * 구체 핸들러로 먼저 처리하여 [com.bts.issue.adapter.inbound.rest.CurrentActor.current] 가 미인증 시
 * 던지는 401 이 500 으로 변질되지 않도록 한다
 * (learnings: catch-all-exceptionhandler-swallows-responsestatusexception).
 *
 * ### 400 핸들러 2종의 도입 경위 (FR-CO-02 — 생략 사유가 만료됐다)
 * FR-CO-01 까지 [CommentController] 는 문자열 path variable `{key}` 와 컬렉션 경로만 가져
 * `MethodArgumentTypeMismatchException`/`HttpMessageNotReadableException` 이 발생할 수 없었고,
 * 이 KDoc 은 그래서 두 핸들러를 "대상이 없어 생략" 한다고 적어뒀다. FR-CO-02 가
 * `@PathVariable commentId: UUID` 와 `@RequestBody` 를 추가하면서 **그 전제가 깨졌다** —
 * `/comments/abc` 나 깨진 JSON 이 catch-all 에 떨어져 400 이 아니라 500 이 된다.
 * 따라서 두 핸들러를 추가하고 생략 사유 문장을 이 경위로 대체한다.
 */
@RestControllerAdvice(assignableTypes = [CommentController::class])
class CommentExceptionHandler {
    private val log = LoggerFactory.getLogger(javaClass)

    // ── 400 COMMENT_VALIDATION_FAILED (요청 형식, FR-CO-02) ────────────────────

    /**
     * [MethodArgumentTypeMismatchException] — 경로 변수 타입 불일치 — 400.
     *
     * `PATCH`/`DELETE /comments/{commentId}` 에 UUID 형식이 아닌 값이 오면 발생한다.
     * 이 핸들러가 없으면 catch-all 이 삼켜 500 이 된다 (클래스 KDoc "400 핸들러 2종의 도입 경위").
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    fun handleMethodArgumentTypeMismatch(ex: MethodArgumentTypeMismatchException): ProblemDetail {
        log.info("COMMENT_400 type_mismatch param='{}'", ex.name)
        return problem(
            status = HttpStatus.BAD_REQUEST,
            type = "comment-validation-failed",
            title = "Bad Request",
            errorCode = COMMENT_VALIDATION_FAILED,
            detail = "요청 경로 또는 파라미터 형식이 올바르지 않습니다.",
        )
    }

    /**
     * [HttpMessageNotReadableException] — 요청 본문 역직렬화 실패 — 400.
     *
     * 깨진 JSON 또는 필드 타입 불일치 시 Jackson 이 발생시킨다.
     * detail 에 파서 메시지를 싣지 않는다 — 내부 클래스명·필드 경로가 노출되기 때문이다
     * (403 과 동일한 정보 은닉 관례).
     */
    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun handleHttpMessageNotReadable(ex: HttpMessageNotReadableException): ProblemDetail {
        log.info("COMMENT_400 message_not_readable cause='{}'", ex.cause?.message ?: ex.message)
        return problem(
            status = HttpStatus.BAD_REQUEST,
            type = "comment-validation-failed",
            title = "Bad Request",
            errorCode = COMMENT_VALIDATION_FAILED,
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

    // ── 404 COMMENT_NOT_FOUND (FR-CO-02) ──────────────────────────────────────

    /**
     * 댓글 미존재·이미 삭제됨·다른 이슈 소속 — 404.
     *
     * 세 경우를 구분해 알리지 않는다 — "그 id 는 존재하지만 다른 이슈 소속" 이라는 사실 자체가
     * 정보 누출이다([CommentNotFoundException] KDoc). 예외 message 의 `commentId` 도 응답에 싣지
     * 않고 로그에만 남긴다(메모리 fr-pm-04-guard-exception-message-http-leak).
     */
    @ExceptionHandler(CommentNotFoundException::class)
    fun handleCommentNotFound(ex: CommentNotFoundException): ProblemDetail {
        log.info("COMMENT_404 comment_not_found commentId={}", ex.commentId)
        return problem(
            status = HttpStatus.NOT_FOUND,
            type = "comment-not-found",
            title = "Comment Not Found",
            errorCode = "COMMENT_NOT_FOUND",
            detail = "댓글을 찾을 수 없습니다.",
        )
    }

    // ── 400 COMMENT_BODY_* (FR-CO-01) ─────────────────────────────────────────

    /**
     * 본문이 공백만 — 400.
     *
     * 서비스 계층([com.bts.issue.comment.application.CommentApplicationService.create])이 던지는
     * **도메인 예외**를 여기서 HTTP 상태로 번역한다. 서비스가 `ResponseStatusException` 을 던지지
     * 않는 이유는 그 서비스를 automation 도 호출하며 그 경로는 HTTP 를 모르기 때문이다
     * ([com.bts.issue.comment.domain.CommentBodyTooLongException] KDoc 참조).
     */
    @ExceptionHandler(CommentBodyBlankException::class)
    fun handleBodyBlank(ex: CommentBodyBlankException): ProblemDetail {
        log.info("COMMENT_400 body_blank message='{}'", ex.message)
        return problem(
            status = HttpStatus.BAD_REQUEST,
            type = "comment-body-blank",
            title = "Bad Request",
            errorCode = "COMMENT_BODY_BLANK",
            detail = "댓글 본문은 비어 있을 수 없습니다.",
        )
    }

    /**
     * 본문이 허용 길이 초과 — 400.
     *
     * detail 에 상한과 실제 길이를 담는다 — 사용자가 얼마를 줄여야 하는지 알 수 있어야 한다.
     * 내부 구조가 아닌 입력 정책 값이라 노출해도 무해하다(403 과 달리 정보 은닉 대상이 아니다).
     */
    @ExceptionHandler(CommentBodyTooLongException::class)
    fun handleBodyTooLong(ex: CommentBodyTooLongException): ProblemDetail {
        log.info("COMMENT_400 body_too_long actual={} max={}", ex.actual, ex.max)
        return problem(
            status = HttpStatus.BAD_REQUEST,
            type = "comment-body-too-long",
            title = "Bad Request",
            errorCode = "COMMENT_BODY_TOO_LONG",
            detail = "댓글 본문은 ${ex.max}자를 넘을 수 없습니다. (현재 ${ex.actual}자)",
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

    companion object {
        /**
         * 댓글 API 의 클라이언트 입력 형식 오류 에러 코드 (비-UUID 경로 변수 · 깨진 JSON 공용).
         *
         * 두 핸들러가 같은 코드를 쓰는 이유 — 프론트 입장에서 처방이 같다("요청을 고쳐 다시 보내라").
         * 어느 쪽이었는지는 서버 로그의 `type_mismatch`/`message_not_readable` 로 구분한다.
         */
        const val COMMENT_VALIDATION_FAILED = "COMMENT_VALIDATION_FAILED"
    }
}
