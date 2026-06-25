// Inbox 도메인 예외를 RFC 7807 ProblemDetail 응답으로 변환하는 핸들러

package com.bts.notification.inbox.web

import com.bts.notification.inbox.application.InboxItemNotFoundException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.server.ResponseStatusException
import java.net.URI
import java.time.Instant

/**
 * Inbox BC 도메인 예외를 RFC 7807 ProblemDetail 형식으로 변환하는 핸들러.
 *
 * [basePackages] 를 `com.bts.notification.inbox.web` 으로 한정하여 다른 패키지(notification.web 등)의
 * 예외를 잡지 않는다 (memory: domain-exception-http-handler-basepackage-scope 교훈).
 *
 * 기존 `NotificationExceptionHandler` 는 `com.bts.notification.web` 한정이라 이 패키지를 커버하지 않는다.
 * 별도 핸들러로 분리하지 않으면 [InboxItemNotFoundException] 이 500 으로 변질된다
 * (memory: catch-all-exceptionhandler-swallows-responsestatusexception 교훈, plan CONCERN-2).
 *
 * catch-all [Exception] 핸들러는 [ResponseStatusException] 을 먼저 rethrow 하여
 * Spring MVC 가 직접 처리하도록 한다.
 *
 * 에러 코드 접두사는 `NOTIF_INBOX_` 로 고정한다 (BTS 에러 코드 규칙 §1.16).
 *
 * ### 매핑 규칙
 * - [InboxItemNotFoundException]               → 404 + NOTIF_INBOX_NOT_FOUND
 * - [MethodArgumentTypeMismatchException]       → 400 + NOTIF_INBOX_INVALID (UUID/Instant 형식 오류)
 * - [HttpMessageNotReadableException]           → 400 + NOTIF_INBOX_INVALID (malformed JSON)
 * - [MethodArgumentNotValidException]           → 400 + NOTIF_INBOX_INVALID (Bean Validation)
 * - [Exception] (fallback)                      → 500 + NOTIF_INBOX_INTERNAL_ERROR
 */
@RestControllerAdvice(basePackages = ["com.bts.notification.inbox.web"])
class InboxExceptionHandler {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Inbox 항목 미존재 또는 타인 소유 — 404.
     *
     * [InboxItemNotFoundException] 은 보안상 리소스 존재 여부를 노출하지 않기 위해
     * 항상 동일한 일반 메시지로 응답한다.
     */
    @ExceptionHandler(InboxItemNotFoundException::class)
    fun handleNotFound(
        @Suppress("UnusedParameter") ex: InboxItemNotFoundException,
    ): ProblemDetail {
        log.info("NOTIF_INBOX_404 not_found")
        return problem(
            status = HttpStatus.NOT_FOUND,
            type = "inbox-not-found",
            title = "Inbox Item Not Found",
            errorCode = "NOTIF_INBOX_NOT_FOUND",
            detail = "알림 항목을 찾을 수 없습니다.",
        )
    }

    /**
     * 쿼리 파라미터 타입 불일치 (비-UUID senderId, 비-ISO from/to 등) — 400.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    fun handleTypeMismatch(ex: MethodArgumentTypeMismatchException): ProblemDetail {
        log.info("NOTIF_INBOX_400 type_mismatch param='{}' value='{}'", ex.name, ex.value)
        return problem(
            status = HttpStatus.BAD_REQUEST,
            type = "inbox-invalid",
            title = "Inbox Request Invalid",
            errorCode = "NOTIF_INBOX_INVALID",
            detail = "요청 파라미터 값이 올바르지 않습니다.",
        )
    }

    /**
     * malformed JSON body — 400.
     */
    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun handleMessageNotReadable(
        @Suppress("UnusedParameter") ex: HttpMessageNotReadableException,
    ): ProblemDetail {
        log.info("NOTIF_INBOX_400 message_not_readable")
        return problem(
            status = HttpStatus.BAD_REQUEST,
            type = "inbox-invalid",
            title = "Inbox Request Invalid",
            errorCode = "NOTIF_INBOX_INVALID",
            detail = "요청 바디를 파싱할 수 없습니다.",
        )
    }

    /**
     * Bean Validation(`@Valid`) 실패 — 400.
     */
    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidationFailed(
        @Suppress("UnusedParameter") ex: MethodArgumentNotValidException,
    ): ProblemDetail {
        log.info("NOTIF_INBOX_400 validation_failed")
        return problem(
            status = HttpStatus.BAD_REQUEST,
            type = "inbox-invalid",
            title = "Inbox Request Invalid",
            errorCode = "NOTIF_INBOX_INVALID",
            detail = "요청 값 검증에 실패했습니다.",
        )
    }

    /**
     * 분류되지 않은 모든 예외 — 500.
     *
     * [ResponseStatusException] 은 rethrow 하여 Spring MVC 가 직접 처리하게 한다
     * (memory: catch-all-exceptionhandler-swallows-responsestatusexception 교훈).
     */
    @ExceptionHandler(Exception::class)
    fun handleInternalError(ex: Exception): ProblemDetail {
        if (ex is ResponseStatusException) throw ex
        log.error("NOTIF_INBOX_500 internal_error", ex)
        return problem(
            status = HttpStatus.INTERNAL_SERVER_ERROR,
            type = "inbox-internal-error",
            title = "Inbox Internal Server Error",
            errorCode = "NOTIF_INBOX_INTERNAL_ERROR",
            detail = "서버 내부 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.",
        )
    }

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
