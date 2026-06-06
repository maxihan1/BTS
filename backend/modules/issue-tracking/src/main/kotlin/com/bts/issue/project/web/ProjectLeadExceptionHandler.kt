// 프로젝트 리드 BC 도메인 예외를 RFC 7807 ProblemDetail 응답으로 변환하는 핸들러 + 에러 코드 상수 (FR-CM-04)

package com.bts.issue.project.web

import com.bts.issue.project.domain.ProjectLeadAccessDeniedException
import com.bts.issue.project.domain.ProjectLeadNotFoundException
import com.bts.issue.project.domain.ProjectLeadProjectNotFoundException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.net.URI
import java.time.Instant

/**
 * 프로젝트 리드 BC 에러 코드 상수.
 *
 * 모든 코드는 `PROJECT_` 접두사를 사용한다 (BTS 에러 코드 규칙 §1.16, ISSUE_ prefix 계열).
 * [ProjectLeadExceptionHandler] 에서 RFC 7807 ProblemDetail 의 `errorCode` 프로퍼티에 사용한다.
 */
internal object ProjectLeadErrorCodes {
    /** Bean Validation / 바디 파싱 실패 — 400. */
    const val VALIDATION_FAILED = "VALIDATION_FAILED"

    /** 프로젝트 미존재 — 404. */
    const val PROJECT_NOT_FOUND = "PROJECT_NOT_FOUND"

    /** 권한 없음 — 403. */
    const val ACCESS_DENIED = "PROJECT_LEAD_ACCESS_DENIED"

    /** 리드 사용자 미존재 — 422. */
    const val PROJECT_LEAD_NOT_FOUND = "PROJECT_LEAD_NOT_FOUND"

    /** 분류되지 않은 내부 오류 — 500. */
    const val INTERNAL_ERROR = "INTERNAL_ERROR"
}

/**
 * 프로젝트 리드 BC 도메인 예외를 RFC 7807 ProblemDetail 형식으로 변환하는 핸들러.
 *
 * [basePackages] 를 `com.bts.issue.project.web` 로 한정하여 다른 BC 의 예외를 잡지 않는다.
 * [com.bts.issue.component.web.ComponentExceptionHandler] 선례와 동일한 구조를 따른다.
 *
 * 매핑 규칙.
 * - [ProjectLeadAccessDeniedException] → 403 + [ProjectLeadErrorCodes.ACCESS_DENIED]
 * - [ProjectLeadProjectNotFoundException] → 404 + [ProjectLeadErrorCodes.PROJECT_NOT_FOUND]
 * - [ProjectLeadNotFoundException] → 422 + [ProjectLeadErrorCodes.PROJECT_LEAD_NOT_FOUND]
 * - [HttpMessageNotReadableException] → 400 + [ProjectLeadErrorCodes.VALIDATION_FAILED] (잘못된 UUID 등)
 * - [Exception] (fallback) → 500 + [ProjectLeadErrorCodes.INTERNAL_ERROR]
 */
@RestControllerAdvice(basePackages = ["com.bts.issue.project.web"])
class ProjectLeadExceptionHandler {
    private val log = LoggerFactory.getLogger(javaClass)

    // ── 400 VALIDATION_FAILED ─────────────────────────────────────────────────

    /**
     * 잘못된 요청 바디(UUID 파싱 실패 등) — 400.
     *
     * Jackson 이 UUID 파싱에 실패하면 [HttpMessageNotReadableException] 을 던진다.
     *
     * @param ex Spring MVC 가 생성한 요청 바디 파싱 실패 예외.
     */
    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun handleMessageNotReadable(ex: HttpMessageNotReadableException): ProblemDetail {
        log.info("PROJECT_LEAD_400 message_not_readable message='{}'", ex.message)
        return problem(
            status = HttpStatus.BAD_REQUEST,
            type = "project-lead-validation-failed",
            title = "Validation Failed",
            errorCode = ProjectLeadErrorCodes.VALIDATION_FAILED,
            detail = "요청 바디를 파싱할 수 없습니다.",
        )
    }

    // ── 403 ACCESS_DENIED ─────────────────────────────────────────────────────

    /**
     * [ProjectLeadAccessDeniedException] — 권한 없음 — 403.
     *
     * detail 에 내부 식별자(actor/projectId/권한코드)를 포함하지 않는다 — 일반 메시지만 노출하고,
     * 상세는 로그에만 기록한다 (메모리: guard-exception-message-http-leak).
     *
     * prod profile 의 실 권한 판정기(IdentityAccessComponentPermissionResolver)가 거부하면 발생한다.
     * AlwaysAllow stub 환경(non-prod)에서는 발생하지 않으나 핸들러는 등록한다.
     *
     * @param ex 권한 거부 예외 (message 에 내부 식별자 포함 — 로그 전용).
     */
    @ExceptionHandler(ProjectLeadAccessDeniedException::class)
    fun handleAccessDenied(ex: ProjectLeadAccessDeniedException): ProblemDetail {
        log.warn("PROJECT_LEAD_403 access_denied message='{}'", ex.message)
        return problem(
            status = HttpStatus.FORBIDDEN,
            type = "project-lead-access-denied",
            title = "Access Denied",
            errorCode = ProjectLeadErrorCodes.ACCESS_DENIED,
            detail = "이 프로젝트의 리드를 변경할 권한이 없습니다.",
        )
    }

    // ── 404 PROJECT_NOT_FOUND ─────────────────────────────────────────────────

    /**
     * [ProjectLeadProjectNotFoundException] — 프로젝트 미존재 — 404.
     *
     * detail 에 내부 식별자를 포함하지 않는다 (메모리: guard-exception-message-http-leak).
     *
     * @param ex 조회한 projectIdOrKey 정보를 포함하는 예외.
     */
    @ExceptionHandler(ProjectLeadProjectNotFoundException::class)
    fun handleProjectNotFound(ex: ProjectLeadProjectNotFoundException): ProblemDetail {
        log.info("PROJECT_LEAD_404 project_not_found message='{}'", ex.message)
        return problem(
            status = HttpStatus.NOT_FOUND,
            type = "project-lead-project-not-found",
            title = "Project Not Found",
            errorCode = ProjectLeadErrorCodes.PROJECT_NOT_FOUND,
            detail = "프로젝트를 찾을 수 없습니다.",
        )
    }

    // ── 422 PROJECT_LEAD_NOT_FOUND ────────────────────────────────────────────

    /**
     * [ProjectLeadNotFoundException] — 리드 사용자 미존재 — 422.
     *
     * detail 에 내부 식별자를 포함하지 않는다 (메모리: guard-exception-message-http-leak).
     *
     * @param ex 존재하지 않는 리드 userId 를 포함하는 예외.
     */
    @ExceptionHandler(ProjectLeadNotFoundException::class)
    fun handleLeadNotFound(ex: ProjectLeadNotFoundException): ProblemDetail {
        log.info("PROJECT_LEAD_422 lead_not_found message='{}'", ex.message)
        return problem(
            status = HttpStatus.UNPROCESSABLE_ENTITY,
            type = "project-lead-not-found",
            title = "Project Lead Not Found",
            errorCode = ProjectLeadErrorCodes.PROJECT_LEAD_NOT_FOUND,
            detail = "리드로 지정한 사용자를 찾을 수 없습니다.",
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
        log.error("PROJECT_LEAD_500 internal_error", ex)
        return problem(
            status = HttpStatus.INTERNAL_SERVER_ERROR,
            type = "project-lead-internal-error",
            title = "Internal Server Error",
            errorCode = ProjectLeadErrorCodes.INTERNAL_ERROR,
            detail = "서버 내부 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.",
        )
    }

    // ── private helper ────────────────────────────────────────────────────────

    /**
     * RFC 7807 [ProblemDetail] 인스턴스를 생성하는 헬퍼.
     *
     * 표준 필드 외에 커스텀 `errorCode` 와 `timestamp` 를 추가한다.
     *
     * @param status HTTP 응답 상태 코드.
     * @param type `https://bts.example.com/problems/` 뒤에 붙는 type suffix.
     * @param title 사람이 읽을 수 있는 문제 유형 요약.
     * @param errorCode BTS 에러 코드 상수 ([ProjectLeadErrorCodes]).
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
