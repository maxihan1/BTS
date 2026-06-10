// 버전 BC 도메인 예외를 RFC 7807 ProblemDetail 응답으로 변환하는 핸들러 (FR-VR-01 + FR-VR-02)

package com.bts.issue.version.web

import com.bts.issue.version.domain.DuplicateVersionNameException
import com.bts.issue.version.domain.VersionAccessDeniedException
import com.bts.issue.version.domain.VersionNotFoundException
import com.bts.issue.version.domain.VersionProjectNotFoundException
import com.bts.issue.version.domain.VersionTransitionNotAllowedException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.net.URI
import java.time.Instant

/**
 * 버전 BC 도메인 예외를 RFC 7807 ProblemDetail 형식으로 변환하는 핸들러.
 *
 * [basePackages] 를 `com.bts.issue.version.web` 로 한정하여 다른 BC 의 예외를 잡지 않는다.
 * ComponentExceptionHandler 선례와 동일한 구조를 따른다.
 *
 * 매핑 규칙.
 * - [MethodArgumentNotValidException] → 400 + [VersionErrorCodes.VALIDATION_FAILED]
 * - [HttpMessageNotReadableException] → 400 + [VersionErrorCodes.VALIDATION_FAILED] (enum 역직렬화 실패 포함)
 * - [VersionProjectNotFoundException] → 404 + [VersionErrorCodes.PROJECT_NOT_FOUND]
 * - [VersionNotFoundException] → 404 + [VersionErrorCodes.VERSION_NOT_FOUND]
 * - [DuplicateVersionNameException] → 409 + [VersionErrorCodes.VERSION_NAME_DUPLICATE]
 * - [VersionTransitionNotAllowedException] → 409 + [VersionErrorCodes.VERSION_TRANSITION_NOT_ALLOWED]
 * - [VersionAccessDeniedException] → 403 + [VersionErrorCodes.ACCESS_DENIED]
 * - [Exception] (fallback) → 500 + [VersionErrorCodes.INTERNAL_ERROR]
 */
@RestControllerAdvice(basePackages = ["com.bts.issue.version.web"])
class VersionExceptionHandler {
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
        log.info("VERSION_400 validation_failed fields='{}'", fieldErrors)
        return problem(
            status = HttpStatus.BAD_REQUEST,
            type = "version-validation-failed",
            title = "Validation Failed",
            errorCode = VersionErrorCodes.VALIDATION_FAILED,
            detail = fieldErrors.ifBlank { "요청 값 검증에 실패했습니다." },
        )
    }

    /**
     * Jackson 역직렬화 실패 — 400.
     *
     * status 필드에 정의되지 않은 enum 문자열("FOO" 등)이 전달되면 이 핸들러가 잡는다.
     *
     * @param ex 메시지 읽기 실패 예외.
     */
    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun handleMessageNotReadable(ex: HttpMessageNotReadableException): ProblemDetail {
        log.info("VERSION_400 message_not_readable message='{}'", ex.message)
        return problem(
            status = HttpStatus.BAD_REQUEST,
            type = "version-validation-failed",
            title = "Validation Failed",
            errorCode = VersionErrorCodes.VALIDATION_FAILED,
            detail = "요청 바디를 읽을 수 없습니다. 필드 값을 확인해 주세요.",
        )
    }

    // ── 403 ACCESS_DENIED ─────────────────────────────────────────────────────

    /**
     * [VersionAccessDeniedException] — 권한 없음 — 403.
     *
     * prod profile 에서 실 권한 판정기가 도입되면 실제로 발생한다.
     * AlwaysAllow stub 환경(non-prod) 에서는 발생하지 않으나 핸들러는 등록한다.
     *
     * @param ex 권한 거부 예외.
     */
    @ExceptionHandler(VersionAccessDeniedException::class)
    fun handleAccessDenied(ex: VersionAccessDeniedException): ProblemDetail {
        log.warn("VERSION_403 access_denied message='{}'", ex.message)
        return problem(
            status = HttpStatus.FORBIDDEN,
            type = "version-access-denied",
            title = "Access Denied",
            errorCode = VersionErrorCodes.ACCESS_DENIED,
            detail = ex.message,
        )
    }

    // ── 404 PROJECT_NOT_FOUND ─────────────────────────────────────────────────

    /**
     * [VersionProjectNotFoundException] — 프로젝트 미존재 — 404.
     *
     * @param ex 조회한 projectIdOrKey 정보를 포함하는 예외.
     */
    @ExceptionHandler(VersionProjectNotFoundException::class)
    fun handleProjectNotFound(ex: VersionProjectNotFoundException): ProblemDetail {
        log.info("VERSION_404 project_not_found message='{}'", ex.message)
        return problem(
            status = HttpStatus.NOT_FOUND,
            type = "version-project-not-found",
            title = "Project Not Found",
            errorCode = VersionErrorCodes.PROJECT_NOT_FOUND,
            detail = ex.message,
        )
    }

    // ── 404 VERSION_NOT_FOUND ─────────────────────────────────────────────────

    /**
     * [VersionNotFoundException] — 버전 미존재 — 404.
     *
     * @param ex 조회한 versionId 정보를 포함하는 예외.
     */
    @ExceptionHandler(VersionNotFoundException::class)
    fun handleNotFound(ex: VersionNotFoundException): ProblemDetail {
        log.info("VERSION_404 not_found message='{}'", ex.message)
        return problem(
            status = HttpStatus.NOT_FOUND,
            type = "version-not-found",
            title = "Version Not Found",
            errorCode = VersionErrorCodes.VERSION_NOT_FOUND,
            detail = ex.message,
        )
    }

    // ── 409 VERSION_TRANSITION_NOT_ALLOWED ───────────────────────────────────

    /**
     * [VersionTransitionNotAllowedException] — 불허 전이 또는 ARCHIVED 읽기전용 위반 — 409.
     *
     * @param ex 전이 거부 사유를 포함하는 예외.
     */
    @ExceptionHandler(VersionTransitionNotAllowedException::class)
    fun handleTransitionNotAllowed(ex: VersionTransitionNotAllowedException): ProblemDetail {
        log.info("VERSION_409 transition_not_allowed message='{}'", ex.message)
        return problem(
            status = HttpStatus.CONFLICT,
            type = "version-transition-not-allowed",
            title = "Version Transition Not Allowed",
            errorCode = VersionErrorCodes.VERSION_TRANSITION_NOT_ALLOWED,
            detail = ex.message,
        )
    }

    // ── 409 VERSION_NAME_DUPLICATE ────────────────────────────────────────────

    /**
     * [DuplicateVersionNameException] — 이름 중복 — 409.
     *
     * @param ex 중복된 이름 정보를 포함하는 예외.
     */
    @ExceptionHandler(DuplicateVersionNameException::class)
    fun handleNameDuplicate(ex: DuplicateVersionNameException): ProblemDetail {
        log.info("VERSION_409 name_duplicate message='{}'", ex.message)
        return problem(
            status = HttpStatus.CONFLICT,
            type = "version-name-duplicate",
            title = "Version Name Duplicate",
            errorCode = VersionErrorCodes.VERSION_NAME_DUPLICATE,
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
        log.error("VERSION_500 internal_error", ex)
        return problem(
            status = HttpStatus.INTERNAL_SERVER_ERROR,
            type = "version-internal-error",
            title = "Internal Server Error",
            errorCode = VersionErrorCodes.INTERNAL_ERROR,
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
     * @param errorCode BTS 에러 코드 상수 ([VersionErrorCodes]).
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
