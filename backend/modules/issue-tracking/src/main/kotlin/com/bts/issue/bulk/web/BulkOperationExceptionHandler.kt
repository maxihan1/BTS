// BulkOperationExceptionHandler — bulk-operations BC 예외를 RFC 7807 ProblemDetail HTTP 응답으로 변환

package com.bts.issue.bulk.web

import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.net.URI
import java.time.Instant

/**
 * 일괄 작업(bulk-operations) 엔드포인트의 예외를 RFC 7807 ProblemDetail 형식으로 변환하는 핸들러.
 *
 * [basePackages] 를 `com.bts.issue.bulk.web` 으로 한정하여 다른 패키지의 예외를 잡지 않는다.
 *
 * 매핑 규칙.
 * - [IllegalArgumentException] → 400 + ISSUE_VALIDATION_FAILED (issueKeys 검증 실패 등)
 * - [BulkOperationForbiddenException] → 403 + ISSUE_BULK_FORBIDDEN
 * - [BulkOperationNotFoundException] → 404 + ISSUE_BULK_NOT_FOUND
 */
@RestControllerAdvice(basePackages = ["com.bts.issue.bulk.web"])
class BulkOperationExceptionHandler {
    private val log = LoggerFactory.getLogger(javaClass)

    // ── 400 VALIDATION_FAILED ─────────────────────────────────────────────────

    /**
     * [IllegalArgumentException] — 서비스 계층 검증 실패 — 400.
     *
     * issueKeys 비어있음, 1000 초과, operationType ↔ payload 불일치 등.
     *
     * @param ex 검증 실패 메시지를 포함하는 예외.
     */
    @ExceptionHandler(IllegalArgumentException::class)
    fun handleIllegalArgument(ex: IllegalArgumentException): ProblemDetail {
        log.info("ISSUE_BULK_400 validation_failed message='{}'", ex.message)
        return problem(
            status = HttpStatus.BAD_REQUEST,
            type = "bulk-validation-failed",
            title = "Bulk Validation Failed",
            errorCode = BulkErrorCodes.VALIDATION_FAILED,
            detail = ex.message,
        )
    }

    // ── 403 BULK_FORBIDDEN ────────────────────────────────────────────────────

    /**
     * [BulkOperationForbiddenException] — 작업 조회 권한 없음 — 403.
     *
     * @param ex actor 와 operationId 정보를 포함하는 예외.
     */
    @ExceptionHandler(BulkOperationForbiddenException::class)
    fun handleForbidden(ex: BulkOperationForbiddenException): ProblemDetail {
        log.info(
            "ISSUE_BULK_403 forbidden operationId='{}' actor='{}'",
            ex.operationId,
            ex.actorId,
        )
        return problem(
            status = HttpStatus.FORBIDDEN,
            type = "bulk-access-denied",
            title = "Bulk Access Denied",
            errorCode = BulkErrorCodes.BULK_FORBIDDEN,
            detail = "이 일괄 작업에 접근할 권한이 없습니다.",
        )
    }

    // ── 404 BULK_NOT_FOUND ────────────────────────────────────────────────────

    /**
     * [BulkOperationNotFoundException] — 일괄 작업이 존재하지 않음 — 404.
     *
     * @param ex 조회한 operationId 를 포함하는 예외.
     */
    @ExceptionHandler(BulkOperationNotFoundException::class)
    fun handleNotFound(ex: BulkOperationNotFoundException): ProblemDetail {
        log.info("ISSUE_BULK_404 not_found operationId='{}'", ex.operationId)
        return problem(
            status = HttpStatus.NOT_FOUND,
            type = "bulk-not-found",
            title = "Bulk Operation Not Found",
            errorCode = BulkErrorCodes.BULK_NOT_FOUND,
            detail = ex.message,
        )
    }

    // ── private helper ────────────────────────────────────────────────────────

    /**
     * Spring 6 의 [ProblemDetail] (RFC 7807) 인스턴스를 생성하는 헬퍼.
     *
     * 표준 필드 외에 커스텀 `errorCode` 와 `timestamp` 를 추가한다.
     *
     * @param status HTTP 응답 상태 코드.
     * @param type `https://bts.example.com/problems/` 뒤에 붙는 type suffix.
     * @param title 사람이 읽을 수 있는 문제 유형 요약.
     * @param errorCode BTS 에러 코드 상수 ([BulkErrorCodes]).
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

/**
 * 일괄 작업 BC 에러 코드 상수.
 *
 * `ISSUE_` prefix 정책에 따라 bulk 작업 전용 에러 코드를 정의한다.
 */
object BulkErrorCodes {
    const val VALIDATION_FAILED = "ISSUE_BULK_VALIDATION_FAILED"
    const val BULK_FORBIDDEN = "ISSUE_BULK_FORBIDDEN"
    const val BULK_NOT_FOUND = "ISSUE_BULK_NOT_FOUND"
}
