// 컴포넌트 BC 도메인 예외를 RFC 7807 ProblemDetail 응답으로 변환하는 핸들러 (FR-CM-01)

package com.bts.issue.component.web

import com.bts.issue.component.domain.ComponentAccessDeniedException
import com.bts.issue.component.domain.ComponentLeadNotFoundException
import com.bts.issue.component.domain.ComponentNotFoundException
import com.bts.issue.component.domain.ComponentProjectNotFoundException
import com.bts.issue.component.domain.DuplicateComponentNameException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.net.URI
import java.time.Instant

/**
 * 컴포넌트 BC 도메인 예외를 RFC 7807 ProblemDetail 형식으로 변환하는 핸들러.
 *
 * [basePackages] 를 `com.bts.issue.component.web` 로 한정하여 다른 BC 의 예외를 잡지 않는다.
 * IssueTypeExceptionHandler 선례와 동일한 구조를 따른다.
 *
 * 매핑 규칙.
 * - [MethodArgumentNotValidException] → 400 + [ComponentErrorCodes.VALIDATION_FAILED]
 * - [ComponentProjectNotFoundException] → 404 + [ComponentErrorCodes.PROJECT_NOT_FOUND]
 * - [ComponentNotFoundException] → 404 + [ComponentErrorCodes.COMPONENT_NOT_FOUND]
 * - [DuplicateComponentNameException] → 409 + [ComponentErrorCodes.COMPONENT_NAME_DUPLICATE]
 * - [ComponentAccessDeniedException] → 403 + [ComponentErrorCodes.ACCESS_DENIED]
 * - [ComponentLeadNotFoundException] → 422 + [ComponentErrorCodes.COMPONENT_LEAD_NOT_FOUND]
 * - [Exception] (fallback) → 500 + [ComponentErrorCodes.INTERNAL_ERROR]
 */
@RestControllerAdvice(basePackages = ["com.bts.issue.component.web"])
class ComponentExceptionHandler {
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
        log.info("COMPONENT_400 validation_failed fields='{}'", fieldErrors)
        return problem(
            status = HttpStatus.BAD_REQUEST,
            type = "component-validation-failed",
            title = "Validation Failed",
            errorCode = ComponentErrorCodes.VALIDATION_FAILED,
            detail = fieldErrors.ifBlank { "요청 값 검증에 실패했습니다." },
        )
    }

    // ── 403 ACCESS_DENIED ─────────────────────────────────────────────────────

    /**
     * [ComponentAccessDeniedException] — 권한 없음 — 403.
     *
     * prod profile 에서 실 권한 판정기가 도입되면 실제로 발생한다.
     * AlwaysAllow stub 환경(non-prod) 에서는 발생하지 않으나 핸들러는 등록한다.
     *
     * @param ex 권한 거부 예외.
     */
    @ExceptionHandler(ComponentAccessDeniedException::class)
    fun handleAccessDenied(ex: ComponentAccessDeniedException): ProblemDetail {
        log.warn("COMPONENT_403 access_denied message='{}'", ex.message)
        return problem(
            status = HttpStatus.FORBIDDEN,
            type = "component-access-denied",
            title = "Access Denied",
            errorCode = ComponentErrorCodes.ACCESS_DENIED,
            detail = ex.message,
        )
    }

    // ── 404 PROJECT_NOT_FOUND ─────────────────────────────────────────────────

    /**
     * [ComponentProjectNotFoundException] — 프로젝트 미존재 — 404.
     *
     * @param ex 조회한 projectIdOrKey 정보를 포함하는 예외.
     */
    @ExceptionHandler(ComponentProjectNotFoundException::class)
    fun handleProjectNotFound(ex: ComponentProjectNotFoundException): ProblemDetail {
        log.info("COMPONENT_404 project_not_found message='{}'", ex.message)
        return problem(
            status = HttpStatus.NOT_FOUND,
            type = "component-project-not-found",
            title = "Project Not Found",
            errorCode = ComponentErrorCodes.PROJECT_NOT_FOUND,
            detail = ex.message,
        )
    }

    // ── 404 COMPONENT_NOT_FOUND ───────────────────────────────────────────────

    /**
     * [ComponentNotFoundException] — 컴포넌트 미존재 — 404.
     *
     * @param ex 조회한 componentId 정보를 포함하는 예외.
     */
    @ExceptionHandler(ComponentNotFoundException::class)
    fun handleNotFound(ex: ComponentNotFoundException): ProblemDetail {
        log.info("COMPONENT_404 not_found message='{}'", ex.message)
        return problem(
            status = HttpStatus.NOT_FOUND,
            type = "component-not-found",
            title = "Component Not Found",
            errorCode = ComponentErrorCodes.COMPONENT_NOT_FOUND,
            detail = ex.message,
        )
    }

    // ── 409 COMPONENT_NAME_DUPLICATE ─────────────────────────────────────────

    /**
     * [DuplicateComponentNameException] — 이름 중복 — 409.
     *
     * @param ex 중복된 이름 정보를 포함하는 예외.
     */
    @ExceptionHandler(DuplicateComponentNameException::class)
    fun handleNameDuplicate(ex: DuplicateComponentNameException): ProblemDetail {
        log.info("COMPONENT_409 name_duplicate message='{}'", ex.message)
        return problem(
            status = HttpStatus.CONFLICT,
            type = "component-name-duplicate",
            title = "Component Name Duplicate",
            errorCode = ComponentErrorCodes.COMPONENT_NAME_DUPLICATE,
            detail = ex.message,
        )
    }

    // ── 422 COMPONENT_LEAD_NOT_FOUND ──────────────────────────────────────────

    /**
     * [ComponentLeadNotFoundException] — 리드 사용자 미존재 — 422.
     *
     * @param ex 존재하지 않는 리드 userId 를 포함하는 예외.
     */
    @ExceptionHandler(ComponentLeadNotFoundException::class)
    fun handleLeadNotFound(ex: ComponentLeadNotFoundException): ProblemDetail {
        log.info("COMPONENT_422 lead_not_found message='{}'", ex.message)
        return problem(
            status = HttpStatus.UNPROCESSABLE_ENTITY,
            type = "component-lead-not-found",
            title = "Component Lead Not Found",
            errorCode = ComponentErrorCodes.COMPONENT_LEAD_NOT_FOUND,
            detail = ex.message,
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
        log.error("COMPONENT_500 internal_error", ex)
        return problem(
            status = HttpStatus.INTERNAL_SERVER_ERROR,
            type = "component-internal-error",
            title = "Internal Server Error",
            errorCode = ComponentErrorCodes.INTERNAL_ERROR,
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
     * @param errorCode BTS 에러 코드 상수 ([ComponentErrorCodes]).
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
