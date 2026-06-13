// 이슈 링크 BC 도메인 예외를 RFC 7807 ProblemDetail 응답으로 변환하는 핸들러

package com.bts.issue.link.web

import com.bts.issue.link.domain.DuplicateLinkException
import com.bts.issue.link.domain.InvalidLinkTypeCodeException
import com.bts.issue.link.domain.LinkCycleException
import com.bts.issue.link.domain.LinkNotFoundException
import com.bts.issue.link.domain.LinkSelfReferenceException
import com.bts.issue.link.domain.LinkedIssueNotFoundException
import com.bts.issue.link.domain.ParentCycleException
import com.bts.issue.link.domain.ParentSelfReferenceException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.net.URI
import java.time.Instant

/**
 * 이슈 링크 BC 도메인 예외를 RFC 7807 ProblemDetail 형식으로 변환하는 핸들러.
 *
 * [basePackages] 를 `com.bts.issue.link.web` 로 한정하여 타 컨트롤러 경로의 예외를 잡지 않는다.
 * catch-all [Exception] 핸들러는 이 스코프 안에서만 동작하므로 다른 BC 의
 * ResponseStatusException(401 등)을 삼키지 않는다.
 *
 * 매핑 규칙.
 * - [MethodArgumentNotValidException] → 400 + [LinkErrorCodes.VALIDATION_FAILED]
 * - [InvalidLinkTypeCodeException] → 400 + [LinkErrorCodes.INVALID_LINK_TYPE]
 * - [LinkedIssueNotFoundException] → 404 + [LinkErrorCodes.ISSUE_NOT_FOUND]
 * - [LinkNotFoundException] → 404 + [LinkErrorCodes.LINK_NOT_FOUND]
 * - [DuplicateLinkException] → 409 + [LinkErrorCodes.DUPLICATE_LINK]
 * - [LinkCycleException] → 409 + [LinkErrorCodes.LINK_CYCLE]
 * - [ParentCycleException] → 409 + [LinkErrorCodes.PARENT_CYCLE]
 * - [LinkSelfReferenceException] → 422 + [LinkErrorCodes.LINK_SELF_REFERENCE]
 * - [ParentSelfReferenceException] → 422 + [LinkErrorCodes.PARENT_SELF_REFERENCE]
 * - [Exception] (fallback) → 500 + [LinkErrorCodes.INTERNAL_ERROR]
 */
@RestControllerAdvice(basePackages = ["com.bts.issue.link.web"])
@Suppress("TooManyFunctions") // 예외 타입별 1핸들러 — 링크/부모 도메인 예외 8종 + validation + fallback. 분리 시 응집도 저하.
class LinkExceptionHandler {
    private val log = LoggerFactory.getLogger(javaClass)

    // ── 400 ──────────────────────────────────────────────────────────────────

    /** Bean Validation 실패 — 400. */
    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidationFailed(ex: MethodArgumentNotValidException): ProblemDetail {
        val fieldErrors =
            ex.bindingResult.fieldErrors.joinToString("; ") { "${it.field}: ${it.defaultMessage}" }
        log.info("LINK_400 validation_failed fields='{}'", fieldErrors)
        return problem(
            status = HttpStatus.BAD_REQUEST,
            type = "link-validation-failed",
            title = "Validation Failed",
            errorCode = LinkErrorCodes.VALIDATION_FAILED,
            detail = fieldErrors.ifBlank { "요청 값 검증에 실패했습니다." },
        )
    }

    /** [InvalidLinkTypeCodeException] — 알 수 없는 linkType 코드 — 400. */
    @ExceptionHandler(InvalidLinkTypeCodeException::class)
    fun handleInvalidLinkType(ex: InvalidLinkTypeCodeException): ProblemDetail {
        log.info("LINK_400 invalid_link_type message='{}'", ex.message)
        return problem(
            status = HttpStatus.BAD_REQUEST,
            type = "link-invalid-type",
            title = "Invalid Link Type",
            errorCode = LinkErrorCodes.INVALID_LINK_TYPE,
            detail = ex.message,
        )
    }

    // ── 404 ──────────────────────────────────────────────────────────────────

    /** [LinkedIssueNotFoundException] — 이슈 미존재 — 404. */
    @ExceptionHandler(LinkedIssueNotFoundException::class)
    fun handleIssueNotFound(ex: LinkedIssueNotFoundException): ProblemDetail {
        log.info("LINK_404 issue_not_found message='{}'", ex.message)
        return problem(
            status = HttpStatus.NOT_FOUND,
            type = "link-issue-not-found",
            title = "Issue Not Found",
            errorCode = LinkErrorCodes.ISSUE_NOT_FOUND,
            detail = ex.message,
        )
    }

    /** [LinkNotFoundException] — 링크 미존재 — 404. */
    @ExceptionHandler(LinkNotFoundException::class)
    fun handleLinkNotFound(ex: LinkNotFoundException): ProblemDetail {
        log.info("LINK_404 link_not_found message='{}'", ex.message)
        return problem(
            status = HttpStatus.NOT_FOUND,
            type = "link-not-found",
            title = "Link Not Found",
            errorCode = LinkErrorCodes.LINK_NOT_FOUND,
            detail = ex.message,
        )
    }

    // ── 409 ──────────────────────────────────────────────────────────────────

    /** [DuplicateLinkException] — 중복 링크 — 409. */
    @ExceptionHandler(DuplicateLinkException::class)
    fun handleDuplicateLink(ex: DuplicateLinkException): ProblemDetail {
        log.info("LINK_409 duplicate_link message='{}'", ex.message)
        return problem(
            status = HttpStatus.CONFLICT,
            type = "link-duplicate",
            title = "Duplicate Link",
            errorCode = LinkErrorCodes.DUPLICATE_LINK,
            detail = ex.message,
        )
    }

    /** [LinkCycleException] — 링크 순환 탐지 — 409. */
    @ExceptionHandler(LinkCycleException::class)
    fun handleLinkCycle(ex: LinkCycleException): ProblemDetail {
        log.info("LINK_409 link_cycle message='{}'", ex.message)
        return problem(
            status = HttpStatus.CONFLICT,
            type = "link-cycle",
            title = "Link Cycle Detected",
            errorCode = LinkErrorCodes.LINK_CYCLE,
            detail = ex.message,
        )
    }

    /** [ParentCycleException] — 부모 계층 순환 탐지 — 409. */
    @ExceptionHandler(ParentCycleException::class)
    fun handleParentCycle(ex: ParentCycleException): ProblemDetail {
        log.info("LINK_409 parent_cycle message='{}'", ex.message)
        return problem(
            status = HttpStatus.CONFLICT,
            type = "parent-cycle",
            title = "Parent Cycle Detected",
            errorCode = LinkErrorCodes.PARENT_CYCLE,
            detail = ex.message,
        )
    }

    // ── 422 ──────────────────────────────────────────────────────────────────

    /** [LinkSelfReferenceException] — 자기 자신 링크 — 422. */
    @ExceptionHandler(LinkSelfReferenceException::class)
    fun handleLinkSelfReference(ex: LinkSelfReferenceException): ProblemDetail {
        log.info("LINK_422 self_reference message='{}'", ex.message)
        return problem(
            status = HttpStatus.UNPROCESSABLE_ENTITY,
            type = "link-self-reference",
            title = "Link Self Reference",
            errorCode = LinkErrorCodes.LINK_SELF_REFERENCE,
            detail = ex.message,
        )
    }

    /** [ParentSelfReferenceException] — 자기 자신 부모 지정 — 422. */
    @ExceptionHandler(ParentSelfReferenceException::class)
    fun handleParentSelfReference(ex: ParentSelfReferenceException): ProblemDetail {
        log.info("LINK_422 parent_self_reference message='{}'", ex.message)
        return problem(
            status = HttpStatus.UNPROCESSABLE_ENTITY,
            type = "parent-self-reference",
            title = "Parent Self Reference",
            errorCode = LinkErrorCodes.PARENT_SELF_REFERENCE,
            detail = ex.message,
        )
    }

    // ── 500 fallback ──────────────────────────────────────────────────────────

    /** 분류되지 않은 모든 예외 — 500. */
    @ExceptionHandler(Exception::class)
    fun handleInternalError(ex: Exception): ProblemDetail {
        log.error("LINK_500 internal_error", ex)
        return problem(
            status = HttpStatus.INTERNAL_SERVER_ERROR,
            type = "link-internal-error",
            title = "Internal Server Error",
            errorCode = LinkErrorCodes.INTERNAL_ERROR,
            detail = "서버 내부 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.",
        )
    }

    // ── private helper ────────────────────────────────────────────────────────

    /**
     * RFC 7807 [ProblemDetail] 인스턴스를 생성하는 헬퍼.
     *
     * 표준 필드 외에 커스텀 `errorCode` 와 `timestamp` 를 추가한다.
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
