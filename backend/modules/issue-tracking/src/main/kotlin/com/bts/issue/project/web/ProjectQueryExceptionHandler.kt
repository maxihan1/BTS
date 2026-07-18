// ProjectQueryController 전용 예외 핸들러 — ResponseStatusException 재전파 + 404/403 (FR-PJ PR-3 Task 3/4)

package com.bts.issue.project.web

import com.bts.issue.project.query.ProjectBrowseForbiddenException
import com.bts.issue.project.query.ProjectQueryNotFoundException
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
 * 프로젝트 단건 조회 에러 코드 상수 (Task 4).
 *
 * 모든 코드는 `ISSUE_` 접두사를 사용한다 (BTS 에러 코드 규칙 §1.16).
 */
internal object ProjectQueryErrorCodes {
    /** 프로젝트 미존재 — 404. */
    const val NOT_FOUND = "ISSUE_PROJECT_NOT_FOUND"

    /** BROWSE 권한 없음 — 403. */
    const val FORBIDDEN = "ISSUE_PROJECT_FORBIDDEN"
}

/**
 * [ProjectQueryController] 전용 예외 핸들러.
 *
 * `assignableTypes = [ProjectQueryController::class]` 로 스코프를 좁혀 다른 컨트롤러의 예외를 잡지
 * 않는다. `@Order(Ordered.HIGHEST_PRECEDENCE)` — 같은 패키지 [ProjectLeadExceptionHandler] 의
 * `basePackages = ["com.bts.issue.project.web"]` 전역 스코프 + catch-all `Exception::class` fallback
 * 이 이 컨트롤러의 401/403/404 를 500 으로 삼키는 것을 방지한다([ProjectCreateExceptionHandler] 선례,
 * 메모리 catch-all-exceptionhandler-swallows-responsestatusexception).
 *
 * ## 매핑 규칙
 * - [ResponseStatusException] → 원 상태 코드 (미인증 401 등) 그대로 통과
 * - [ProjectQueryNotFoundException] → 404 + [ProjectQueryErrorCodes.NOT_FOUND] (Task 4)
 * - [ProjectBrowseForbiddenException] → 403 + [ProjectQueryErrorCodes.FORBIDDEN] (내부구조 미노출, Task 4)
 */
@RestControllerAdvice(assignableTypes = [ProjectQueryController::class])
@Order(Ordered.HIGHEST_PRECEDENCE)
class ProjectQueryExceptionHandler {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * [ResponseStatusException] — 프레임워크 상태 코드 예외를 그대로 전달한다.
     *
     * [com.bts.issue.adapter.inbound.rest.CurrentActor] 가 미인증 시 던지는 401 이 catch-all
     * fallback 에 잡혀 500 으로 변질되는 것을 막는다.
     *
     * @param ex 프레임워크가 생성한 ResponseStatusException.
     */
    @ExceptionHandler(ResponseStatusException::class)
    fun handleResponseStatusException(ex: ResponseStatusException): ProblemDetail {
        log.debug("PROJECT_QUERY ResponseStatusException status={} message='{}'", ex.statusCode, ex.message)
        val pd = ProblemDetail.forStatus(ex.statusCode.value())
        pd.detail = ex.reason
        return pd
    }

    /**
     * [ProjectQueryNotFoundException] — 프로젝트 미존재(또는 조회 시점 경합으로 소프트삭제) — 404.
     *
     * @param ex 조회한 projectIdOrKey 정보를 포함하는 예외.
     */
    @ExceptionHandler(ProjectQueryNotFoundException::class)
    fun handleNotFound(ex: ProjectQueryNotFoundException): ProblemDetail {
        log.info("PROJECT_QUERY_404 not_found message='{}'", ex.message)
        return problem(
            status = HttpStatus.NOT_FOUND,
            type = "project-query-not-found",
            title = "Project Not Found",
            errorCode = ProjectQueryErrorCodes.NOT_FOUND,
            detail = "프로젝트를 찾을 수 없습니다.",
        )
    }

    /**
     * [ProjectBrowseForbiddenException] — BROWSE 권한 없음 — 403.
     *
     * HTTP 응답 detail 에 actorId/projectKey 등 내부 상세를 노출하지 않는다
     * (메모리 guard-exception-message-http-leak).
     *
     * @param ex 권한 거부 예외 (message 에 내부 식별자 포함 — 로그 전용).
     */
    @ExceptionHandler(ProjectBrowseForbiddenException::class)
    fun handleForbidden(ex: ProjectBrowseForbiddenException): ProblemDetail {
        log.warn("PROJECT_QUERY_403 forbidden message='{}'", ex.message)
        return problem(
            status = HttpStatus.FORBIDDEN,
            type = "project-query-forbidden",
            title = "Forbidden",
            errorCode = ProjectQueryErrorCodes.FORBIDDEN,
            detail = "이 프로젝트를 조회할 권한이 없습니다.",
        )
    }

    // ── private helper ────────────────────────────────────────────────────────

    /**
     * RFC 7807 [ProblemDetail] 인스턴스를 생성하는 헬퍼.
     *
     * @param status HTTP 응답 상태 코드.
     * @param type URI type suffix.
     * @param title 사람이 읽을 수 있는 문제 유형 요약.
     * @param errorCode BTS 에러 코드 상수 ([ProjectQueryErrorCodes]).
     * @param detail 이 특정 발생에 대한 상세 설명(내부구조 미노출 일반 메시지).
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
