// 워처 컨트롤러 전용 예외 핸들러 — IssueWatcherController 스코프 한정 (FR-WT-01)

package com.bts.issue.watcher.web

import com.bts.issue.domain.IssueAccessDeniedException
import com.bts.issue.domain.IssueNotFoundException
import com.bts.issue.watcher.application.WatcherUserNotFoundException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.server.ResponseStatusException
import java.net.URI
import java.time.Instant

/**
 * 워처 컨트롤러 예외 → HTTP 응답 변환 핸들러.
 *
 * [IssueWatcherController] 에만 스코프를 한정한다.
 * [com.bts.issue.adapter.inbound.rest.IssueExceptionHandler] 의 basePackages 가
 * `com.bts.issue.adapter.inbound.rest` 로 고정되어 있어 `com.bts.issue.watcher.web` 패키지를
 * 커버하지 않는다. 따라서 이 핸들러에서 공통 예외를 직접 처리한다.
 *
 * 매핑 규칙.
 * - [IssueNotFoundException] → 404 Not Found
 * - [IssueAccessDeniedException] → 403 Forbidden (detail 에 내부 정보 비노출)
 * - [WatcherUserNotFoundException] → 422 Unprocessable Entity (userId 비노출)
 * - [ResponseStatusException] → 상태 코드 전파 (401 등 catch-all 변질 차단)
 * - [Exception] (fallback) → 500 Internal Server Error
 */
@RestControllerAdvice(assignableTypes = [IssueWatcherController::class])
class WatcherExceptionHandler {
    private val log = LoggerFactory.getLogger(javaClass)

    // ── 404 ISSUE_NOT_FOUND ───────────────────────────────────────────────────

    /**
     * 이슈 미존재 또는 소프트 삭제 — 404.
     *
     * @param ex 조회한 이슈 키 정보를 포함하는 예외.
     */
    @ExceptionHandler(IssueNotFoundException::class)
    fun handleIssueNotFound(ex: IssueNotFoundException): ProblemDetail {
        log.info("WATCHER_404 issue_not_found message='{}'", ex.message)
        return problem(
            status = HttpStatus.NOT_FOUND,
            type = "issue-not-found",
            title = "Issue Not Found",
            errorCode = "ISSUE_NOT_FOUND",
            detail = "이슈를 찾을 수 없습니다.",
        )
    }

    // ── 403 ACCESS_DENIED ─────────────────────────────────────────────────────

    /**
     * 권한 없음 — 403.
     *
     * 응답 detail 에 행위자 UUID, 권한명, 범위 등 내부 정보를 노출하지 않는다.
     *
     * @param ex 행위자/권한/범위 정보를 포함하는 예외.
     */
    @ExceptionHandler(IssueAccessDeniedException::class)
    fun handleAccessDenied(ex: IssueAccessDeniedException): ProblemDetail {
        log.info("WATCHER_403 access_denied message='{}'", ex.message)
        return problem(
            status = HttpStatus.FORBIDDEN,
            type = "access-denied",
            title = "Access Denied",
            errorCode = "ISSUE_ACCESS_DENIED",
            detail = "이 작업을 수행할 권한이 없습니다.",
        )
    }

    // ── 422 WATCHER_USER_NOT_FOUND ────────────────────────────────────────────

    /**
     * 워처로 추가하려는 대상 사용자 미존재 — 422.
     *
     * 응답 detail 에 userId 를 노출하지 않는다. userId 는 로그에만 기록한다.
     *
     * @param ex 존재하지 않는 userId 를 포함하는 예외 (로그 전용).
     */
    @ExceptionHandler(WatcherUserNotFoundException::class)
    fun handleWatcherUserNotFound(ex: WatcherUserNotFoundException): ProblemDetail {
        log.info("WATCHER_422 user_not_found userId='{}'", ex.userId)
        return problem(
            status = HttpStatus.UNPROCESSABLE_ENTITY,
            type = "watcher-user-not-found",
            title = "Watcher User Not Found",
            errorCode = "ISSUE_WATCHER_USER_NOT_FOUND",
            detail = "워처로 추가할 사용자를 찾을 수 없습니다.",
        )
    }

    // ── ResponseStatusException 상태 전파 (401 catch-all 변질 차단) ─────────────

    /**
     * [ResponseStatusException] — 컨트롤러/헬퍼가 명시한 HTTP 상태를 그대로 전파한다.
     *
     * [com.bts.issue.adapter.inbound.rest.CurrentActor.current] 가 미인증 시 던지는 401
     * [ResponseStatusException] 이 catch-all [handleInternalError] 에 가로채여
     * 500 으로 변질되는 것을 차단한다.
     *
     * @param ex 상태 코드 보유 예외.
     */
    @ExceptionHandler(ResponseStatusException::class)
    fun handleResponseStatus(ex: ResponseStatusException): ProblemDetail {
        val status = HttpStatus.valueOf(ex.statusCode.value())
        log.info("WATCHER_{} response_status reason='{}'", status.value(), ex.reason)
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
     *
     * @param ex 처리되지 않은 예외.
     */
    @ExceptionHandler(Exception::class)
    fun handleInternalError(ex: Exception): ProblemDetail {
        log.error("WATCHER_500 internal_error", ex)
        return problem(
            status = HttpStatus.INTERNAL_SERVER_ERROR,
            type = "watcher-internal-error",
            title = "Internal Server Error",
            errorCode = "ISSUE_INTERNAL_ERROR",
            detail = "서버 내부 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.",
        )
    }

    // ── private helper ────────────────────────────────────────────────────────

    /**
     * [ProblemDetail] (RFC 7807) 인스턴스를 생성하는 헬퍼.
     *
     * @param status HTTP 응답 상태 코드.
     * @param type type suffix.
     * @param title 문제 유형 요약.
     * @param errorCode BTS 에러 코드.
     * @param detail 상세 설명. null 이면 생략.
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
