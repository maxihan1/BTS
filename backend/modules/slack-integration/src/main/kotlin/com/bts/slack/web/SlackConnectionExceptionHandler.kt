// SlackConnectionController 예외를 HTTP 상태로 변환 — 컨트롤러 전용 스코프 (FR-SL-02 D6 Task 4)

package com.bts.slack.web

import com.bts.slack.application.EmailUnavailableException
import com.bts.slack.application.SlackScopeMissingException
import com.bts.slack.application.SlackTemporarilyUnavailableException
import com.bts.slack.application.SlackUserNotFoundException
import com.bts.slack.application.WorkspaceNotInstalledException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.server.ResponseStatusException

/**
 * [SlackConnectionController] 예외를 HTTP 상태로 변환한다 (FR-SL-02 D6 Task 4).
 *
 * [assignableTypes] 를 [SlackConnectionController] 하나로 한정해([SlackInstallExceptionHandler] 동형)
 * 형제/타 BC 컨트롤러를 가로채지 않는다(교훈 domain-exception-http-handler-basepackage-scope).
 *
 * ## catch-all 안티패턴 회피 (교훈 catch-all-exceptionhandler-swallows-responsestatusexception)
 * [ResponseStatusException] 은 [handleResponseStatus] 가 상태를 그대로 전파한다 — `SlackConnectionController
 * .currentUserId` 가 던지는 401 이 [handleInternal] catch-all 에 삼켜져 500 으로 변질되지 않는다.
 *
 * ## 에러 코드 — apps/web `api/slack.ts` 계약과 1:1 (spec §API)
 * [EmailUnavailableException] 422 `EMAIL_UNAVAILABLE` · [WorkspaceNotInstalledException] 409
 * `WORKSPACE_NOT_INSTALLED` · [SlackScopeMissingException] 409 `SLACK_SCOPE_MISSING` ·
 * [SlackUserNotFoundException] 404 `SLACK_USER_NOT_FOUND` · [SlackTemporarilyUnavailableException] 503
 * `SLACK_TEMPORARILY_UNAVAILABLE`. 프론트가 이미 이 문자열을 소비하므로 코드값을 임의로 바꾸지 않는다.
 *
 * ## 누출 차단 (§1.1.2)
 * `SlackConnectionExceptions.kt` 의 예외 message 는 이메일·slack_user_id·봇 토큰·Slack 원본 에러 문자열을
 * 담지 않는 고정 일반 문구이므로 그대로 노출하지 않고, 이 핸들러가 별도로 작성한 사용자 노출용 메시지만 담는다.
 */
@RestControllerAdvice(assignableTypes = [SlackConnectionController::class])
class SlackConnectionExceptionHandler {
    private val log = LoggerFactory.getLogger(javaClass)

    /** 행위자의 BTS 계정에 이메일이 없음 → 422. */
    @ExceptionHandler(EmailUnavailableException::class)
    fun handleEmailUnavailable(): ResponseEntity<Map<String, String>> {
        log.info("SLACK_CONNECTION_422 email_unavailable")
        return errorResponse(HttpStatus.UNPROCESSABLE_ENTITY, CODE_EMAIL_UNAVAILABLE, "이메일이 설정되어 있지 않아 연결할 수 없습니다.")
    }

    /** Slack 워크스페이스 미설치(TOCTOU 삭제 레이스 포함) → 409. */
    @ExceptionHandler(WorkspaceNotInstalledException::class)
    fun handleWorkspaceNotInstalled(): ResponseEntity<Map<String, String>> {
        log.info("SLACK_CONNECTION_409 workspace_not_installed")
        return errorResponse(HttpStatus.CONFLICT, CODE_WORKSPACE_NOT_INSTALLED, "Slack 워크스페이스가 설치되어 있지 않습니다.")
    }

    /** 봇 토큰에 `users:read.email` 스코프 없음(운영자가 재연결 필요) → 409. */
    @ExceptionHandler(SlackScopeMissingException::class)
    fun handleScopeMissing(): ResponseEntity<Map<String, String>> {
        log.info("SLACK_CONNECTION_409 scope_missing")
        return errorResponse(HttpStatus.CONFLICT, CODE_SCOPE_MISSING, "Slack 워크스페이스를 다시 연결해야 합니다.")
    }

    /** 이메일에 매칭되는 Slack 사용자 없음 → 404. */
    @ExceptionHandler(SlackUserNotFoundException::class)
    fun handleUserNotFound(): ResponseEntity<Map<String, String>> {
        log.info("SLACK_CONNECTION_404 user_not_found")
        return errorResponse(HttpStatus.NOT_FOUND, CODE_USER_NOT_FOUND, "일치하는 Slack 계정을 찾을 수 없습니다.")
    }

    /** Slack 이 일시적으로 응답하지 못함(429/5xx/네트워크, 재시도 가능) → 503. */
    @ExceptionHandler(SlackTemporarilyUnavailableException::class)
    fun handleTemporarilyUnavailable(): ResponseEntity<Map<String, String>> {
        log.info("SLACK_CONNECTION_503 temporarily_unavailable")
        return errorResponse(
            HttpStatus.SERVICE_UNAVAILABLE,
            CODE_TEMPORARILY_UNAVAILABLE,
            "Slack이 일시적으로 응답하지 않습니다. 잠시 후 다시 시도해 주세요.",
        )
    }

    /** [ResponseStatusException] — `currentUserId` 의 401 등 명시 상태를 그대로 전파한다. */
    @ExceptionHandler(ResponseStatusException::class)
    fun handleResponseStatus(ex: ResponseStatusException): ResponseEntity<Map<String, String>> {
        val status = HttpStatus.valueOf(ex.statusCode.value())
        log.info("SLACK_CONNECTION_{} response_status", status.value())
        return errorResponse(status, CODE_UNAUTHENTICATED, "인증이 필요합니다. 세션이 만료되었을 수 있습니다.")
    }

    /** 분류되지 않은 모든 예외 → 500. */
    @ExceptionHandler(Exception::class)
    fun handleInternal(ex: Exception): ResponseEntity<Map<String, String>> {
        log.error("SLACK_CONNECTION_500 internal_error", ex)
        return errorResponse(HttpStatus.INTERNAL_SERVER_ERROR, CODE_INTERNAL_ERROR, "서버 내부 오류가 발생했습니다.")
    }

    // ── private helpers ───────────────────────────────────────────────────────

    /** `{"code":..., "message":...}` 본문을 가진 [status] 응답을 생성한다(UserProfileController 형식 일관). */
    private fun errorResponse(
        status: HttpStatus,
        code: String,
        message: String,
    ): ResponseEntity<Map<String, String>> = ResponseEntity.status(status).body(mapOf("code" to code, "message" to message))

    private companion object {
        const val CODE_EMAIL_UNAVAILABLE = "EMAIL_UNAVAILABLE"
        const val CODE_WORKSPACE_NOT_INSTALLED = "WORKSPACE_NOT_INSTALLED"
        const val CODE_SCOPE_MISSING = "SLACK_SCOPE_MISSING"
        const val CODE_USER_NOT_FOUND = "SLACK_USER_NOT_FOUND"
        const val CODE_TEMPORARILY_UNAVAILABLE = "SLACK_TEMPORARILY_UNAVAILABLE"
        const val CODE_UNAUTHENTICATED = "SLACK_UNAUTHENTICATED"
        const val CODE_INTERNAL_ERROR = "SLACK_INTERNAL_ERROR"
    }
}
