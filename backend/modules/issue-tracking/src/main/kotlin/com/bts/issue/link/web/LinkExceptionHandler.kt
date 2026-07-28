// 이슈 링크 BC 도메인 예외를 RFC 7807 ProblemDetail 응답으로 변환하는 핸들러

package com.bts.issue.link.web

import com.bts.issue.domain.IssueAccessDeniedException
import com.bts.issue.link.domain.DuplicateLinkException
import com.bts.issue.link.domain.InvalidGraphDepthException
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
import org.springframework.web.server.ResponseStatusException
import java.net.URI
import java.time.Instant

/**
 * 이슈 링크 BC 도메인 예외를 RFC 7807 ProblemDetail 형식으로 변환하는 핸들러.
 *
 * [basePackages] 를 `com.bts.issue.link.web` 로 한정하여 타 컨트롤러 경로의 예외를 잡지 않는다.
 * catch-all [Exception] 핸들러는 이 스코프(link 컨트롤러) 안에서만 동작한다.
 * ⚠️ **2026-07-27 — 이 전제가 뒤집혔다.** 예전 이 자리에는 *"IssueLinkController 는 actor 를
 * 추출하지 않아 401 ResponseStatusException 을 던지지 않으며, 따라서 catch-all 이 401 을 500 으로
 * 변질시킬 경로가 구조적으로 없다"* 라고 적혀 있었다. 권한 가드를 붙이면서 컨트롤러가
 * [com.bts.issue.adapter.inbound.rest.CurrentActor] 로 actor 를 추출하게 됐고, **그 순간 401 경로가 생겼다.**
 * 실제로 인증 컨텍스트 없는 요청이 catch-all 에 걸려 401 이 500 으로 나갔다
 * ([[catch-all-exceptionhandler-swallows-responsestatusexception]] 재현).
 * 그래서 [ResponseStatusException] 전파 핸들러를 catch-all 보다 우선하도록 명시 등록한다 —
 * 형제 핸들러(Watcher · Comment)가 모두 갖고 있던 것이다.
 *
 * 매핑 규칙.
 * - [MethodArgumentNotValidException] → 400 + [LinkErrorCodes.VALIDATION_FAILED]
 * - [InvalidLinkTypeCodeException] → 400 + [LinkErrorCodes.INVALID_LINK_TYPE]
 * - [InvalidGraphDepthException] → 400 + [LinkErrorCodes.INVALID_DEPTH]
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

    /** [InvalidGraphDepthException] — 유효하지 않은 graph depth — 400. */
    @ExceptionHandler(InvalidGraphDepthException::class)
    fun handleInvalidGraphDepth(ex: InvalidGraphDepthException): ProblemDetail {
        log.info("LINK_400 invalid_graph_depth rawValue='{}'", ex.rawValue)
        return problem(
            status = HttpStatus.BAD_REQUEST,
            type = "graph-invalid-depth",
            title = "Invalid Graph Depth",
            errorCode = LinkErrorCodes.INVALID_DEPTH,
            detail = ex.message,
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

    // ── 403 ──────────────────────────────────────────────────────────────────

    /**
     * [IssueAccessDeniedException] — 이슈 권한 미보유 — 403.
     *
     * 2026-07-27 신설. 이전에는 링크 API 에 권한 검사 자체가 없어 이 예외가 발생하지 않았고,
     * 게이트를 붙이자마자 매핑이 없어 **403 이어야 할 것이 500 으로 변질**됐다
     * (형제 핸들러는 전부 이 매핑을 갖고 있다 — Watcher · Comment · CycleTime).
     *
     * detail 에 actor·permission·scope 를 싣지 않는다 — 예외 message 에는 그것이 들어 있어
     * 그대로 흘리면 권한 구조가 응답으로 샌다([[fr-pm-04-guard-exception-message-http-leak]]).
     * 진단 정보는 로그에만 남긴다.
     */
    @ExceptionHandler(IssueAccessDeniedException::class)
    fun handleAccessDenied(ex: IssueAccessDeniedException): ProblemDetail {
        log.info("LINK_403 access_denied message='{}'", ex.message)
        return problem(
            status = HttpStatus.FORBIDDEN,
            type = "access-denied",
            title = "Access Denied",
            errorCode = "ISSUE_ACCESS_DENIED",
            detail = "이 작업을 수행할 권한이 없습니다.",
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

    // ── ResponseStatusException 전파 (catch-all 변질 차단) ────────────────────

    /**
     * [ResponseStatusException] — 상태 코드를 **그대로 전파**한다.
     *
     * 이 핸들러가 없으면 아래 catch-all 이 잡아 401/400 을 **전부 500 으로 변질**시킨다.
     * 특히 `CurrentActor.current()` 의 401 이 그렇게 삼켜지면, 프론트는 "세션 만료" 를
     * 알 수 없어 재로그인 유도 대신 "서버 오류" 를 띄운다.
     *
     * `HttpStatus.valueOf` 는 표준 코드가 아니면 예외를 던져 핸들러 자체를 터뜨리므로
     * ([[fr-db-03-public-dashboard-error-instance-token-leak-done]] 의 함정), 이 컨트롤러가
     * 실제로 내는 코드(401)만 분기하고 나머지는 일반 메시지로 수렴시킨다.
     */
    @ExceptionHandler(ResponseStatusException::class)
    fun handleResponseStatus(ex: ResponseStatusException): ProblemDetail {
        val status = HttpStatus.resolve(ex.statusCode.value()) ?: HttpStatus.INTERNAL_SERVER_ERROR
        log.info("LINK_{} response_status reason='{}'", status.value(), ex.reason)
        val (errorCode, detail) =
            when (status) {
                HttpStatus.UNAUTHORIZED -> "UNAUTHENTICATED" to "인증이 필요합니다. 세션이 만료되었을 수 있습니다."
                HttpStatus.FORBIDDEN -> "ISSUE_ACCESS_DENIED" to "이 작업을 수행할 권한이 없습니다."
                else -> LinkErrorCodes.INTERNAL_ERROR to "요청을 처리할 수 없습니다."
            }
        return problem(
            status = status,
            type = "response-status",
            title = status.reasonPhrase,
            errorCode = errorCode,
            detail = detail,
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
