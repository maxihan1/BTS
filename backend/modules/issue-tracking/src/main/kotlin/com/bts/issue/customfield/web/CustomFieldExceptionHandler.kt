// 커스텀 필드 BC 도메인 예외를 RFC 7807 ProblemDetail 응답으로 변환하는 핸들러 (FR-IS-10 Task 8)

package com.bts.issue.customfield.web

import com.bts.issue.customfield.domain.CustomFieldAccessDeniedException
import com.bts.issue.customfield.domain.CustomFieldNotFoundException
import com.bts.issue.customfield.domain.CustomFieldProjectNotFoundException
import com.bts.issue.customfield.domain.DuplicateCustomFieldKeyException
import com.bts.issue.customfield.domain.ImmutableFieldTypeChangeException
import com.bts.issue.customfield.domain.InvalidFieldDefinitionException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.net.URI
import java.time.Instant

/**
 * 커스텀 필드 BC 도메인 예외를 RFC 7807 ProblemDetail 형식으로 변환하는 핸들러.
 *
 * [basePackages] 를 `com.bts.issue.customfield.web` 로 한정하여 다른 BC 의 예외를 잡지 않는다.
 * [ComponentExceptionHandler] 선례와 동일한 구조를 따른다.
 *
 * **401 은 이 핸들러가 처리하지 않는다.**
 * SecurityConfig 의 `anyRequest().authenticated()` 가 미인증 요청을 401 로 거부한다.
 * 슬라이스 테스트에서는 SecurityConfig 가 로드되지 않으므로 401 검증은
 * prod Testcontainers 통합(T10) 에서 수행한다.
 *
 * **catch-all [Exception] 핸들러 — [org.springframework.web.server.ResponseStatusException] 변질 주의.**
 * catch-all 이 [org.springframework.web.server.ResponseStatusException] 을 삼키면
 * 401/403 이 500 으로 변질된다(메모리 catch-all-exceptionhandler-swallows-responsestatusexception).
 * [Exception] 핸들러에서 [org.springframework.web.server.ResponseStatusException] 을 먼저
 * re-throw 하여 Spring 기본 처리에 위임한다.
 *
 * 매핑 규칙.
 * - [MethodArgumentNotValidException] → 400 + [CustomFieldErrorCodes.VALIDATION_FAILED]
 * - [CustomFieldAccessDeniedException] → 403 + [CustomFieldErrorCodes.ACCESS_DENIED]
 * - [CustomFieldProjectNotFoundException] → 404 + [CustomFieldErrorCodes.PROJECT_NOT_FOUND]
 * - [CustomFieldNotFoundException] → 404 + [CustomFieldErrorCodes.CUSTOM_FIELD_NOT_FOUND]
 * - [DuplicateCustomFieldKeyException] → 409 + [CustomFieldErrorCodes.CUSTOM_FIELD_KEY_DUPLICATE]
 * - [InvalidFieldDefinitionException] → 422 + [CustomFieldErrorCodes.CUSTOM_FIELD_INVALID_DEFINITION]
 * - [ImmutableFieldTypeChangeException] → 422 + [CustomFieldErrorCodes.CUSTOM_FIELD_IMMUTABLE_CHANGE]
 * - [Exception] (fallback) → 500 + [CustomFieldErrorCodes.INTERNAL_ERROR]
 */
@RestControllerAdvice(basePackages = ["com.bts.issue.customfield.web"])
class CustomFieldExceptionHandler {
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
        log.info("CUSTOM_FIELD_400 validation_failed fields='{}'", fieldErrors)
        return problem(
            status = HttpStatus.BAD_REQUEST,
            type = "custom-field-validation-failed",
            title = "Validation Failed",
            errorCode = CustomFieldErrorCodes.VALIDATION_FAILED,
            detail = fieldErrors.ifBlank { "요청 값 검증에 실패했습니다." },
        )
    }

    // ── 403 ACCESS_DENIED ─────────────────────────────────────────────────────

    /**
     * [CustomFieldAccessDeniedException] — 권한 없음 — 403.
     *
     * prod profile 에서 실 권한 판정기가 도입되면 실제로 발생한다.
     * AlwaysAllow stub 환경(non-prod) 에서는 발생하지 않으나 핸들러는 등록한다.
     *
     * @param ex 권한 거부 예외.
     */
    @ExceptionHandler(CustomFieldAccessDeniedException::class)
    fun handleAccessDenied(ex: CustomFieldAccessDeniedException): ProblemDetail {
        log.warn("CUSTOM_FIELD_403 access_denied message='{}'", ex.message)
        return problem(
            status = HttpStatus.FORBIDDEN,
            type = "custom-field-access-denied",
            title = "Access Denied",
            errorCode = CustomFieldErrorCodes.ACCESS_DENIED,
            detail = "커스텀 필드 관리 권한이 없습니다.",
        )
    }

    // ── 404 PROJECT_NOT_FOUND ─────────────────────────────────────────────────

    /**
     * [CustomFieldProjectNotFoundException] — 프로젝트 미존재 — 404.
     *
     * @param ex 조회한 projectIdOrKey 정보를 포함하는 예외.
     */
    @ExceptionHandler(CustomFieldProjectNotFoundException::class)
    fun handleProjectNotFound(ex: CustomFieldProjectNotFoundException): ProblemDetail {
        log.info("CUSTOM_FIELD_404 project_not_found message='{}'", ex.message)
        return problem(
            status = HttpStatus.NOT_FOUND,
            type = "custom-field-project-not-found",
            title = "Project Not Found",
            errorCode = CustomFieldErrorCodes.PROJECT_NOT_FOUND,
            detail = ex.message,
        )
    }

    // ── 404 CUSTOM_FIELD_NOT_FOUND ────────────────────────────────────────────

    /**
     * [CustomFieldNotFoundException] — 커스텀 필드 미존재 — 404.
     *
     * @param ex 조회한 fieldId 정보를 포함하는 예외.
     */
    @ExceptionHandler(CustomFieldNotFoundException::class)
    fun handleNotFound(ex: CustomFieldNotFoundException): ProblemDetail {
        log.info("CUSTOM_FIELD_404 not_found message='{}'", ex.message)
        return problem(
            status = HttpStatus.NOT_FOUND,
            type = "custom-field-not-found",
            title = "Custom Field Not Found",
            errorCode = CustomFieldErrorCodes.CUSTOM_FIELD_NOT_FOUND,
            detail = ex.message,
        )
    }

    // ── 409 CUSTOM_FIELD_KEY_DUPLICATE ────────────────────────────────────────

    /**
     * [DuplicateCustomFieldKeyException] — key 중복 — 409.
     *
     * @param ex 중복된 key 정보를 포함하는 예외.
     */
    @ExceptionHandler(DuplicateCustomFieldKeyException::class)
    fun handleKeyDuplicate(ex: DuplicateCustomFieldKeyException): ProblemDetail {
        log.info("CUSTOM_FIELD_409 key_duplicate message='{}'", ex.message)
        return problem(
            status = HttpStatus.CONFLICT,
            type = "custom-field-key-duplicate",
            title = "Custom Field Key Duplicate",
            errorCode = CustomFieldErrorCodes.CUSTOM_FIELD_KEY_DUPLICATE,
            detail = ex.message,
        )
    }

    // ── 422 CUSTOM_FIELD_INVALID_DEFINITION ───────────────────────────────────

    /**
     * [InvalidFieldDefinitionException] — 필드 정의 불변식 위반 — 422.
     *
     * @param ex 위반 내용을 포함하는 예외.
     */
    @ExceptionHandler(InvalidFieldDefinitionException::class)
    fun handleInvalidDefinition(ex: InvalidFieldDefinitionException): ProblemDetail {
        log.info("CUSTOM_FIELD_422 invalid_definition message='{}'", ex.message)
        return problem(
            status = HttpStatus.UNPROCESSABLE_ENTITY,
            type = "custom-field-invalid-definition",
            title = "Invalid Field Definition",
            errorCode = CustomFieldErrorCodes.CUSTOM_FIELD_INVALID_DEFINITION,
            detail = ex.message,
        )
    }

    // ── 422 CUSTOM_FIELD_IMMUTABLE_CHANGE ─────────────────────────────────────

    /**
     * [ImmutableFieldTypeChangeException] — 불변 속성 변경 시도 — 422.
     *
     * @param ex 변경 시도한 속성 이름을 포함하는 예외.
     */
    @ExceptionHandler(ImmutableFieldTypeChangeException::class)
    fun handleImmutableChange(ex: ImmutableFieldTypeChangeException): ProblemDetail {
        log.info("CUSTOM_FIELD_422 immutable_change message='{}'", ex.message)
        return problem(
            status = HttpStatus.UNPROCESSABLE_ENTITY,
            type = "custom-field-immutable-change",
            title = "Immutable Field Change Attempt",
            errorCode = CustomFieldErrorCodes.CUSTOM_FIELD_IMMUTABLE_CHANGE,
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
        log.error("CUSTOM_FIELD_500 internal_error", ex)
        return problem(
            status = HttpStatus.INTERNAL_SERVER_ERROR,
            type = "custom-field-internal-error",
            title = "Internal Server Error",
            errorCode = CustomFieldErrorCodes.INTERNAL_ERROR,
            detail = "서버 내부 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.",
        )
    }

    // ── private helper ────────────────────────────────────────────────────────

    /**
     * RFC 7807 [ProblemDetail] 인스턴스를 생성하는 헬퍼.
     *
     * 표준 필드 외에 커스텀 `errorCode` 와 `timestamp` 를 추가한다.
     * `message` 필드는 BTS 에러 응답 규칙에 따라 사용하지 않는다
     * (메모리 catch-all-exceptionhandler-swallows-responsestatusexception 참조).
     *
     * @param status HTTP 응답 상태 코드.
     * @param type `https://bts.example.com/problems/` 뒤에 붙는 type suffix.
     * @param title 사람이 읽을 수 있는 문제 유형 요약.
     * @param errorCode BTS 에러 코드 상수 ([CustomFieldErrorCodes]).
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
