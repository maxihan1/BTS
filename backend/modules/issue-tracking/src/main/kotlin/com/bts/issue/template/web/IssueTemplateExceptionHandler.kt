// 이슈 템플릿 BC 도메인 예외를 RFC 7807 ProblemDetail 응답으로 변환하는 핸들러 (FR-TM-01 Task 6)

package com.bts.issue.template.web

import com.bts.issue.template.domain.DuplicateIssueTemplateException
import com.bts.issue.template.domain.InvalidIssueTemplateException
import com.bts.issue.template.domain.IssueTemplateAccessDeniedException
import com.bts.issue.template.domain.IssueTemplateNotFoundException
import com.bts.issue.type.domain.IssueTypeNotFoundException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.net.URI
import java.time.Instant

/**
 * 이슈 템플릿 BC 도메인 예외를 RFC 7807 ProblemDetail 형식으로 변환하는 핸들러.
 *
 * [basePackages] 를 `com.bts.issue.template.web` 로 한정하여 다른 BC 의 예외를 잡지 않는다.
 * [com.bts.issue.customfield.web.CustomFieldExceptionHandler] 선례와 동일한 구조를 따른다.
 *
 * **동명 예외 교차패키지 상태코드 주의.**
 * `IssueTypeNotFoundException` 은 `com.bts.issue.type.domain` 패키지 소속이다.
 * 별도 핸들러로 명시적으로 처리하여 catch-all 에 의한 500 변질을 방지한다.
 *
 * **catch-all [Exception] 핸들러 — [org.springframework.web.server.ResponseStatusException] 변질 주의.**
 * [Exception] 핸들러에서 [org.springframework.web.server.ResponseStatusException] 을 먼저
 * re-throw 하여 Spring 기본 처리에 위임한다
 * (메모리 catch-all-exceptionhandler-swallows-responsestatusexception).
 *
 * 매핑 규칙.
 * - [MethodArgumentNotValidException] → 400 + [IssueTemplateErrorCodes.VALIDATION_FAILED]
 * - [IssueTemplateAccessDeniedException] → 403 + [IssueTemplateErrorCodes.ACCESS_DENIED]
 * - [IssueTemplateNotFoundException] → 404 + [IssueTemplateErrorCodes.TEMPLATE_NOT_FOUND]
 * - [IssueTypeNotFoundException] → 404 + [IssueTemplateErrorCodes.ISSUE_TYPE_NOT_FOUND]
 * - [DuplicateIssueTemplateException] → 409 + [IssueTemplateErrorCodes.TEMPLATE_DUPLICATE]
 * - [InvalidIssueTemplateException] → 422 + [IssueTemplateErrorCodes.TEMPLATE_INVALID]
 * - [Exception] (fallback) → 500 + [IssueTemplateErrorCodes.INTERNAL_ERROR]
 */
@RestControllerAdvice(basePackages = ["com.bts.issue.template.web"])
class IssueTemplateExceptionHandler {
    private val log = LoggerFactory.getLogger(javaClass)

    // ── 400 VALIDATION_FAILED ─────────────────────────────────────────────────

    /**
     * Bean Validation (`@Valid`) 실패 — 400.
     *
     * @param ex Spring MVC 가 생성한 검증 실패 예외.
     */
    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidationFailed(ex: MethodArgumentNotValidException): ProblemDetail {
        val fieldErrors =
            ex.bindingResult.fieldErrors.joinToString("; ") { "${it.field}: ${it.defaultMessage}" }
        log.info("ISSUE_TEMPLATE_400 validation_failed fields='{}'", fieldErrors)
        return problem(
            status = HttpStatus.BAD_REQUEST,
            type = "issue-template-validation-failed",
            title = "Validation Failed",
            errorCode = IssueTemplateErrorCodes.VALIDATION_FAILED,
            detail = fieldErrors.ifBlank { "요청 값 검증에 실패했습니다." },
        )
    }

    // ── 403 ACCESS_DENIED ─────────────────────────────────────────────────────

    /**
     * [IssueTemplateAccessDeniedException] — 권한 없음 — 403.
     *
     * @param ex 권한 거부 예외.
     */
    @ExceptionHandler(IssueTemplateAccessDeniedException::class)
    fun handleAccessDenied(ex: IssueTemplateAccessDeniedException): ProblemDetail {
        log.warn("ISSUE_TEMPLATE_403 access_denied message='{}'", ex.message)
        return problem(
            status = HttpStatus.FORBIDDEN,
            type = "issue-template-access-denied",
            title = "Access Denied",
            errorCode = IssueTemplateErrorCodes.ACCESS_DENIED,
            detail = "이슈 템플릿 관리 권한이 없습니다.",
        )
    }

    // ── 404 TEMPLATE_NOT_FOUND ────────────────────────────────────────────────

    /**
     * [IssueTemplateNotFoundException] — 이슈 템플릿 미존재 — 404.
     *
     * @param ex 조회한 templateId 정보를 포함하는 예외.
     */
    @ExceptionHandler(IssueTemplateNotFoundException::class)
    fun handleTemplateNotFound(ex: IssueTemplateNotFoundException): ProblemDetail {
        log.info("ISSUE_TEMPLATE_404 template_not_found message='{}'", ex.message)
        return problem(
            status = HttpStatus.NOT_FOUND,
            type = "issue-template-not-found",
            title = "Issue Template Not Found",
            errorCode = IssueTemplateErrorCodes.TEMPLATE_NOT_FOUND,
            detail = ex.message,
        )
    }

    // ── 404 ISSUE_TYPE_NOT_FOUND ──────────────────────────────────────────────

    /**
     * [IssueTypeNotFoundException] — issueType 미존재 — 404.
     *
     * create 시 issueTypeId 선검증 실패로 발생한다.
     * 동명 예외 교차패키지 주의(메모리 duplicate-exception-name-cross-package-status):
     * `com.bts.issue.type.domain.IssueTypeNotFoundException` 을 명시 import.
     *
     * @param ex 조회한 issueTypeId 정보를 포함하는 예외.
     */
    @ExceptionHandler(IssueTypeNotFoundException::class)
    fun handleIssueTypeNotFound(ex: IssueTypeNotFoundException): ProblemDetail {
        log.info("ISSUE_TEMPLATE_404 issue_type_not_found message='{}'", ex.message)
        return problem(
            status = HttpStatus.NOT_FOUND,
            type = "issue-template-issue-type-not-found",
            title = "Issue Type Not Found",
            errorCode = IssueTemplateErrorCodes.ISSUE_TYPE_NOT_FOUND,
            detail = ex.message,
        )
    }

    // ── 409 TEMPLATE_DUPLICATE ────────────────────────────────────────────────

    /**
     * [DuplicateIssueTemplateException] — (project, issueType) 중복 — 409.
     *
     * @param ex 중복된 name 정보를 포함하는 예외.
     */
    @ExceptionHandler(DuplicateIssueTemplateException::class)
    fun handleTemplateDuplicate(ex: DuplicateIssueTemplateException): ProblemDetail {
        log.info("ISSUE_TEMPLATE_409 template_duplicate message='{}'", ex.message)
        return problem(
            status = HttpStatus.CONFLICT,
            type = "issue-template-duplicate",
            title = "Issue Template Duplicate",
            errorCode = IssueTemplateErrorCodes.TEMPLATE_DUPLICATE,
            detail = ex.message,
        )
    }

    // ── 422 TEMPLATE_INVALID ──────────────────────────────────────────────────

    /**
     * [InvalidIssueTemplateException] — 도메인 불변식 위반(name/content blank 등) — 422.
     *
     * PATCH 로 blank 값이 전달되면 도메인 [com.bts.issue.template.domain.IssueTemplate.withChanges]
     * 가 이 예외를 던진다. DTO 검증(@field:*)은 1차 방어이며 도메인 검증이 최종 보루다.
     *
     * @param ex 불변식 위반 내용을 포함하는 예외.
     */
    @ExceptionHandler(InvalidIssueTemplateException::class)
    fun handleTemplateInvalid(ex: InvalidIssueTemplateException): ProblemDetail {
        log.info("ISSUE_TEMPLATE_422 template_invalid message='{}'", ex.message)
        return problem(
            status = HttpStatus.UNPROCESSABLE_ENTITY,
            type = "issue-template-invalid",
            title = "Invalid Issue Template",
            errorCode = IssueTemplateErrorCodes.TEMPLATE_INVALID,
            detail = ex.message,
        )
    }

    // ── 500 INTERNAL_ERROR (fallback) ─────────────────────────────────────────

    /**
     * 분류되지 않은 모든 예외 — 500.
     *
     * [org.springframework.web.server.ResponseStatusException] 은 Spring 이 처리하도록
     * re-throw 한다(401/403 이 500 으로 변질되는 것을 방지).
     *
     * @param ex 처리되지 않은 예외.
     */
    @ExceptionHandler(Exception::class)
    fun handleInternalError(ex: Exception): ProblemDetail {
        if (ex is org.springframework.web.server.ResponseStatusException) {
            throw ex
        }
        log.error("ISSUE_TEMPLATE_500 internal_error", ex)
        return problem(
            status = HttpStatus.INTERNAL_SERVER_ERROR,
            type = "issue-template-internal-error",
            title = "Internal Server Error",
            errorCode = IssueTemplateErrorCodes.INTERNAL_ERROR,
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
     * @param errorCode BTS 에러 코드 상수 ([IssueTemplateErrorCodes]).
     * @param detail 이 특정 발생에 대한 상세 설명. null 이면 생략.
     * @return 완성된 [ProblemDetail] 인스턴스.
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
