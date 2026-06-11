// require_2fa 도메인 예외를 RFC 7807 ProblemDetail 응답으로 변환하는 핸들러 (FR-MF-04 Task 3)

package com.bts.issue.project.web

import com.bts.issue.project.domain.Require2faForbiddenException
import com.bts.issue.project.domain.Require2faProjectNotFoundException
import org.slf4j.LoggerFactory
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.server.ResponseStatusException
import java.net.URI
import java.time.Instant

/**
 * require_2fa 토글 에러 코드 상수.
 *
 * 모든 코드는 `ISSUE_` 접두사를 사용한다 (BTS 에러 코드 규칙 §1.16).
 */
internal object Require2faErrorCodes {
    /** SYSTEM_ADMIN 아님 — 403. */
    const val FORBIDDEN = "ISSUE_REQUIRE_2FA_FORBIDDEN"

    /** 프로젝트 미존재 — 404. */
    const val PROJECT_NOT_FOUND = "ISSUE_REQUIRE_2FA_PROJECT_NOT_FOUND"
}

/**
 * require_2fa 토글 BC 도메인 예외를 RFC 7807 ProblemDetail 형식으로 변환하는 핸들러.
 *
 * `assignableTypes = [ProjectRequire2faController::class]` 로 스코프를 좁혀 다른 컨트롤러의
 * 예외를 잡지 않는다 (memory: catch-all-exceptionhandler-swallows-responsestatusexception).
 *
 * `@Order(Ordered.HIGHEST_PRECEDENCE)` — [ProjectLeadExceptionHandler] 의 `Exception::class`
 * fallback 이 `Require2faForbiddenException` 을 500 으로 삼키는 것을 방지한다.
 * 이 핸들러가 먼저 매칭되어 도메인 예외와 [ResponseStatusException] 을 올바른 상태 코드로 변환한다.
 *
 * ## 매핑 규칙
 * - [ResponseStatusException] → 원래 상태 코드 (401 등)
 * - [Require2faForbiddenException] → 403 + [Require2faErrorCodes.FORBIDDEN]
 * - [Require2faProjectNotFoundException] → 404 + [Require2faErrorCodes.PROJECT_NOT_FOUND]
 */
@RestControllerAdvice(assignableTypes = [ProjectRequire2faController::class])
@Order(Ordered.HIGHEST_PRECEDENCE)
class ProjectRequire2faExceptionHandler {
    private val log = LoggerFactory.getLogger(javaClass)

    // ── ResponseStatusException 재전파 — 401/403 프레임워크 예외 삼킴 방지 ─────────

    /**
     * [ResponseStatusException] — 프레임워크 상태 코드 예외를 그대로 전달한다.
     *
     * [CurrentActor.current] 가 미인증 시 던지는 401 [ResponseStatusException] 이
     * catch-all fallback 에 잡혀 500 으로 변질되는 것을 막는다.
     * (memory: catch-all-exceptionhandler-swallows-responsestatusexception)
     *
     * @param ex 프레임워크가 생성한 ResponseStatusException.
     */
    @ExceptionHandler(ResponseStatusException::class)
    fun handleResponseStatusException(ex: ResponseStatusException): ProblemDetail {
        log.debug("REQUIRE_2FA ResponseStatusException status={} message='{}'", ex.statusCode, ex.message)
        val pd = ProblemDetail.forStatus(ex.statusCode.value())
        pd.detail = ex.reason
        return pd
    }

    // ── 403 FORBIDDEN ─────────────────────────────────────────────────────────

    /**
     * [Require2faForbiddenException] — SYSTEM_ADMIN 아님 — 403.
     *
     * HTTP 응답 detail 에 actorId 등 내부 상세를 노출하지 않는다
     * (memory: guard-exception-message-http-leak).
     *
     * @param ex 권한 거부 예외 (message 에 actorId 포함 — 로그 전용).
     */
    @ExceptionHandler(Require2faForbiddenException::class)
    fun handleForbidden(ex: Require2faForbiddenException): ProblemDetail {
        log.warn("REQUIRE_2FA_403 forbidden message='{}'", ex.message)
        return problem(
            status = HttpStatus.FORBIDDEN,
            type = "require-2fa-forbidden",
            title = "Forbidden",
            errorCode = Require2faErrorCodes.FORBIDDEN,
            detail = "require_2fa 를 변경할 권한이 없습니다.",
        )
    }

    // ── 404 PROJECT_NOT_FOUND ─────────────────────────────────────────────────

    /**
     * [Require2faProjectNotFoundException] — 프로젝트 미존재 — 404.
     *
     * @param ex 조회한 projectIdOrKey 정보를 포함하는 예외.
     */
    @ExceptionHandler(Require2faProjectNotFoundException::class)
    fun handleProjectNotFound(ex: Require2faProjectNotFoundException): ProblemDetail {
        log.info("REQUIRE_2FA_404 project_not_found message='{}'", ex.message)
        return problem(
            status = HttpStatus.NOT_FOUND,
            type = "require-2fa-project-not-found",
            title = "Project Not Found",
            errorCode = Require2faErrorCodes.PROJECT_NOT_FOUND,
            detail = "프로젝트를 찾을 수 없습니다.",
        )
    }

    // ── private helper ────────────────────────────────────────────────────────

    /**
     * RFC 7807 [ProblemDetail] 인스턴스를 생성하는 헬퍼.
     *
     * @param status HTTP 응답 상태 코드.
     * @param type URI type suffix.
     * @param title 사람이 읽을 수 있는 문제 유형 요약.
     * @param errorCode BTS 에러 코드 상수 ([Require2faErrorCodes]).
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
