// 첨부 파일 컨트롤러 전용 예외 핸들러 — IssueAttachmentController 스코프 한정

package com.bts.issue.attachment.web

import com.bts.issue.domain.IssueAccessDeniedException
import com.bts.issue.domain.IssueNotFoundException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.multipart.MaxUploadSizeExceededException
import org.springframework.web.multipart.MultipartException
import org.springframework.web.server.ResponseStatusException
import java.net.URI
import java.time.Instant

/**
 * 첨부 파일 컨트롤러 예외 → HTTP 응답 변환 핸들러.
 *
 * [IssueAttachmentController] 에만 스코프를 한정한다.
 * [com.bts.issue.adapter.inbound.rest.IssueExceptionHandler] 의 basePackages 가
 * `com.bts.issue.adapter.inbound.rest` 로 고정되어 있어 `com.bts.issue.attachment.web` 패키지를
 * 커버하지 않는다. 따라서 이 핸들러에서 [IssueNotFoundException](404) 과
 * [IssueAccessDeniedException](403) 을 직접 처리한다.
 *
 * 매핑 규칙.
 * - [MaxUploadSizeExceededException] → 413 Payload Too Large
 * - [MultipartException] → 400 Bad Request (file part 누락 포함)
 * - [IssueNotFoundException] → 404 Not Found
 * - [IssueAccessDeniedException] → 403 Forbidden
 */
@RestControllerAdvice(assignableTypes = [IssueAttachmentController::class])
class AttachmentExceptionHandler {
    private val log = LoggerFactory.getLogger(javaClass)

    // ── 413 PAYLOAD_TOO_LARGE ─────────────────────────────────────────────────

    /**
     * 최대 업로드 크기 초과 — 413.
     *
     * Spring multipart 설정의 max-file-size 또는 max-request-size 초과 시 발생한다.
     *
     * @param ex 최대 크기 초과 예외.
     */
    @ExceptionHandler(MaxUploadSizeExceededException::class)
    fun handleMaxUploadSizeExceeded(ex: MaxUploadSizeExceededException): ProblemDetail {
        log.info("ISSUE_413 file_too_large maxSize={}", ex.maxUploadSize)
        return problem(
            status = HttpStatus.PAYLOAD_TOO_LARGE,
            type = "file-too-large",
            title = "File Too Large",
            errorCode = "ISSUE_FILE_TOO_LARGE",
            detail = "업로드 파일 크기가 최대 허용 크기를 초과했습니다.",
        )
    }

    // ── 400 BAD_REQUEST (multipart) ───────────────────────────────────────────

    /**
     * Multipart 요청 오류 — 400.
     *
     * file part 누락 또는 multipart 파싱 실패 시 발생한다.
     * [MaxUploadSizeExceededException] 은 별도 핸들러가 처리하므로 여기서 제외한다.
     *
     * @param ex Multipart 처리 예외.
     */
    @ExceptionHandler(MultipartException::class)
    fun handleMultipartException(ex: MultipartException): ProblemDetail {
        log.info("ISSUE_400 multipart_error message='{}'", ex.message)
        return problem(
            status = HttpStatus.BAD_REQUEST,
            type = "multipart-error",
            title = "Bad Request",
            errorCode = "ISSUE_MULTIPART_ERROR",
            detail = "파일 업로드 요청이 유효하지 않습니다.",
        )
    }

    // ── 404 ISSUE_NOT_FOUND ───────────────────────────────────────────────────

    /**
     * 이슈 미존재 또는 소프트 삭제 — 404.
     *
     * [com.bts.issue.adapter.inbound.rest.IssueExceptionHandler] 의 basePackages 범위 밖이므로
     * 이 핸들러에서 직접 처리한다.
     *
     * @param ex 조회한 이슈 키 정보를 포함하는 예외.
     */
    @ExceptionHandler(IssueNotFoundException::class)
    fun handleIssueNotFound(ex: IssueNotFoundException): ProblemDetail {
        log.info("ISSUE_404 issue_not_found message='{}'", ex.message)
        return problem(
            status = HttpStatus.NOT_FOUND,
            type = "issue-not-found",
            title = "Issue Not Found",
            errorCode = "ISSUE_NOT_FOUND",
            detail = ex.message,
        )
    }

    // ── 403 ACCESS_DENIED ─────────────────────────────────────────────────────

    /**
     * 권한 없음 — 403.
     *
     * [com.bts.issue.adapter.inbound.rest.IssueExceptionHandler] 의 basePackages 범위 밖이므로
     * 이 핸들러에서 직접 처리한다.
     *
     * @param ex 행위자/권한/범위 정보를 포함하는 예외.
     */
    @ExceptionHandler(IssueAccessDeniedException::class)
    fun handleAccessDenied(ex: IssueAccessDeniedException): ProblemDetail {
        log.info("ISSUE_403 access_denied message='{}'", ex.message)
        return problem(
            status = HttpStatus.FORBIDDEN,
            type = "access-denied",
            title = "Access Denied",
            errorCode = "ISSUE_ACCESS_DENIED",
            detail = "이 작업을 수행할 권한이 없습니다.",
        )
    }

    // ── 500 INTERNAL_ERROR (fallback) ─────────────────────────────────────────

    /**
     * 분류되지 않은 모든 예외 — 500.
     *
     * MinIO I/O 실패([com.bts.issue.attachment.MinioStorageException]) 등이 여기로 떨어진다.
     * 형제 핸들러(IssueTemplateExceptionHandler)와 동일하게 BC 표준 ProblemDetail 포맷으로 변환해
     * Spring Boot 기본 `/error` 포맷으로 새는 것을 막는다.
     *
     * [ResponseStatusException](예: [com.bts.issue.web.CurrentActor] 미인증 401)은 Spring 이
     * 처리하도록 re-throw 한다(401/403 이 500 으로 변질되는 것을 방지).
     *
     * @param ex 처리되지 않은 예외.
     */
    @ExceptionHandler(Exception::class)
    fun handleInternalError(ex: Exception): ProblemDetail {
        if (ex is ResponseStatusException) {
            throw ex
        }
        log.error("ISSUE_ATTACHMENT_500 internal_error", ex)
        return problem(
            status = HttpStatus.INTERNAL_SERVER_ERROR,
            type = "attachment-internal-error",
            title = "Internal Server Error",
            errorCode = "ISSUE_INTERNAL_ERROR",
            detail = "서버 내부 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.",
        )
    }

    // ── private helper ────────────────────────────────────────────────────────

    /**
     * [ProblemDetail] (RFC 7807) 인스턴스를 생성하는 헬퍼.
     *
     * @param status HTTP 응답 상태 코드.
     * @param type type suffix.
     * @param title 문제 유형 요약.
     * @param errorCode BTS 에러 코드.
     * @param detail 상세 설명. null 이면 생략.
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
