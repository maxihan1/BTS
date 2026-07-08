// Slack App 설치 컨트롤러 예외를 RFC 7807 ProblemDetail 로 변환 — 식별자·비밀값 누출 차단 (FR-SL-01 Task 9)

package com.bts.slack.web

import com.bts.slack.application.SlackForbiddenException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.server.ResponseStatusException
import java.net.URI
import java.time.Instant

/**
 * [SlackInstallController] 예외를 RFC 7807 [ProblemDetail] 로 변환한다 (FR-SL-01 Task 9).
 *
 * [assignableTypes] 를 [SlackInstallController] 와 [SlackInstallQueryController] 두 Slack 설치 컨트롤러로
 * 한정해 형제/타 BC 컨트롤러를 가로채지 않는다(교훈 domain-exception-http-handler-basepackage-scope). 이
 * 핸들러는 **개시(`GET /slack/install`) 계열** 예외와 **SPA 상태 조회(`GET /api/v1/slack/…`) 계열** 예외를
 * 담당한다 — 콜백의 실패는 컨트롤러가 결과 경로 302 로 직접 처리하므로 여기 도달하지 않는다.
 *
 * ## 쿼리 컨트롤러도 반드시 포함 (교훈 domain-exception-http-handler-basepackage-scope)
 * [SlackInstallQueryController] 를 [assignableTypes] 에 넣지 않으면, 그 컨트롤러의 관리자 가드가 던지는
 * [SlackForbiddenException] 이 이 핸들러의 403 매핑을 타지 못하고 스프링 기본 500 으로 변질된다(내부 사정
 * 누출·오상태). 두 컨트롤러의 예외 → HTTP 매핑을 한 핸들러가 일관되게 담당한다.
 *
 * ## 처리 대상
 * - [SlackForbiddenException] — 시스템 관리자 아닌 사용자의 설치 시도(S5) → **403**(일반 메시지).
 * - [ResponseStatusException] — [SlackActorExtractor] 의 미인증 401 등 명시 상태 → 상태 전파.
 * - 그 외 모든 예외 — state 키/암호화 키/credentials 미설정 시 서비스가 던지는 [IllegalStateException](EC6)
 *   포함 → **500**. 스택트레이스는 서버 로그 전용, 응답 detail 에는 비밀값·내부 정보를 담지 않는다.
 *
 * ## 누출 차단 (§1.1.2 / 교훈 fr-pm-04-guard-exception-message-http-leak)
 * 어떤 경로도 예외 `message`·`cause`·행위자 id·봇 토큰을 detail 로 노출하지 않고 일반 메시지로 치환한다.
 *
 * ## catch-all 안티패턴 회피 (교훈 catch-all-exceptionhandler-swallows-responsestatusexception)
 * [ResponseStatusException] 은 전용 핸들러가 상태를 전파하고, 분류되지 않은 예외만 [handleInternal] 이
 * 500 으로 매핑한다(401 이 500 으로 변질되지 않게 한다).
 */
@RestControllerAdvice(assignableTypes = [SlackInstallController::class, SlackInstallQueryController::class])
class SlackInstallExceptionHandler {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * [SlackForbiddenException] — 시스템 관리자 아님(S5) — 403.
     *
     * 관리자 게이트에서 발생하므로 내부 정책/식별자를 노출하지 않는 일반 메시지를 쓴다.
     */
    @ExceptionHandler(SlackForbiddenException::class)
    fun handleForbidden(
        @Suppress("UnusedParameter") ex: SlackForbiddenException,
    ): ProblemDetail {
        log.info("SLACK_403 forbidden")
        return problem(
            HttpStatus.FORBIDDEN,
            "slack-install-forbidden",
            "Forbidden",
            SLACK_ACCESS_DENIED,
            "이 작업을 수행할 권한이 없습니다.",
        )
    }

    /**
     * [ResponseStatusException] — actor 추출 401 등 명시 상태 전파.
     */
    @ExceptionHandler(ResponseStatusException::class)
    fun handleResponseStatus(ex: ResponseStatusException): ProblemDetail {
        val status = HttpStatus.valueOf(ex.statusCode.value())
        log.info("SLACK_{} response_status", status.value())
        val (errorCode, detail) =
            when (status) {
                HttpStatus.UNAUTHORIZED ->
                    SLACK_UNAUTHENTICATED to "인증이 필요합니다. 세션이 만료되었을 수 있습니다."
                HttpStatus.FORBIDDEN ->
                    SLACK_ACCESS_DENIED to "이 작업을 수행할 권한이 없습니다."
                HttpStatus.BAD_REQUEST ->
                    SLACK_VALIDATION_FAILED to "요청 값이 올바르지 않습니다."
                else ->
                    SLACK_INTERNAL_ERROR to "요청을 처리할 수 없습니다."
            }
        return problem(status, "slack-install-response-status", status.reasonPhrase, errorCode, detail)
    }

    /**
     * 분류되지 않은 모든 예외 — 500.
     *
     * 설정 미비(EC6)로 서비스가 전파하는 [IllegalStateException] 도 여기서 처리하며, detail 에는 비밀값·
     * 내부 정보를 포함하지 않는 일반 메시지만 반환한다.
     */
    @ExceptionHandler(Exception::class)
    fun handleInternal(ex: Exception): ProblemDetail {
        log.error("SLACK_500 internal_error", ex)
        return problem(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "slack-install-internal-error",
            "Internal Server Error",
            SLACK_INTERNAL_ERROR,
            "서버 내부 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.",
        )
    }

    // ── private helpers ───────────────────────────────────────────────────────

    /**
     * RFC 7807 [ProblemDetail] 인스턴스를 생성한다(형제 webhook/search 핸들러와 동일 형식).
     *
     * @param status HTTP 응답 상태.
     * @param type type suffix.
     * @param title 문제 유형 요약.
     * @param errorCode BTS 에러 코드.
     * @param detail 상세 설명(식별자·비밀값 미포함).
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
        const val SLACK_ACCESS_DENIED = "SLACK_ACCESS_DENIED"
        const val SLACK_UNAUTHENTICATED = "SLACK_UNAUTHENTICATED"
        const val SLACK_VALIDATION_FAILED = "SLACK_VALIDATION_FAILED"
        const val SLACK_INTERNAL_ERROR = "SLACK_INTERNAL_ERROR"
    }
}
