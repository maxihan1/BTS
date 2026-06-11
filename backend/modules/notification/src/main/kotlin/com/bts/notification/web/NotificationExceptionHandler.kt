// notification BC 도메인 예외를 RFC 7807 ProblemDetail 응답으로 변환하는 핸들러

package com.bts.notification.web

import com.bts.notification.application.NotificationPolicyDuplicateException
import com.bts.notification.application.NotificationPolicyForbiddenException
import com.bts.notification.application.NotificationPolicyNotFoundException
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
 * notification BC 도메인 예외를 RFC 7807 ProblemDetail 형식으로 변환하는 핸들러.
 *
 * [basePackages] 를 `com.bts.notification.web` 로 한정하여 다른 BC 의 예외를 잡지 않는다
 * (memory: domain-exception-http-handler-basepackage-scope 교훈).
 *
 * catch-all [Exception] 핸들러를 두되, [ResponseStatusException] 은 구체 핸들러가 없으면
 * Spring MVC 가 직접 처리하므로 catch-all 이 삼키지 않도록 [ResponseStatusException] 을 먼저 rethrow 한다
 * (memory: catch-all-exceptionhandler-swallows-responsestatusexception 교훈).
 *
 * 에러 코드 접두사는 `NOTIF_` 로 고정한다 (BTS 에러 코드 규칙 §1.16).
 *
 * ### 매핑 규칙
 * - [MethodArgumentNotValidException] → 400 + NOTIF_VALIDATION_FAILED
 * - [IllegalArgumentException] (enum 파싱 실패) → 400 + NOTIF_INVALID_ENUM
 * - [NotificationPolicyForbiddenException] → 403 + NOTIF_FORBIDDEN
 * - [NotificationPolicyNotFoundException] → 404 + NOTIF_POLICY_NOT_FOUND
 * - [NotificationPolicyDuplicateException] → 409 + NOTIF_POLICY_DUPLICATE
 * - [Exception] (fallback) → 500 + NOTIF_INTERNAL_ERROR
 */
@RestControllerAdvice(basePackages = ["com.bts.notification.web"])
class NotificationExceptionHandler {
    private val log = LoggerFactory.getLogger(javaClass)

    // ── 400 VALIDATION_FAILED ─────────────────────────────────────────────────

    /**
     * Bean Validation (`@Valid`) 실패 — 400.
     */
    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidationFailed(ex: MethodArgumentNotValidException): ProblemDetail {
        val fieldErrors =
            ex.bindingResult.fieldErrors.joinToString("; ") { "${it.field}: ${it.defaultMessage}" }
        log.info("NOTIF_400 validation_failed fields='{}'", fieldErrors)
        return problem(
            status = HttpStatus.BAD_REQUEST,
            type = "notification-validation-failed",
            title = "Validation Failed",
            errorCode = "NOTIF_VALIDATION_FAILED",
            detail = fieldErrors.ifBlank { "요청 값 검증에 실패했습니다." },
        )
    }

    // ── 400 INVALID_ENUM ──────────────────────────────────────────────────────

    /**
     * enum 파싱 실패 (잘못된 eventType / recipientRole / channel 문자열) — 400.
     *
     * 컨트롤러에서 `?: throw IllegalArgumentException(...)` 으로 던진 경우 이 핸들러가 잡는다.
     */
    @ExceptionHandler(IllegalArgumentException::class)
    fun handleInvalidEnum(ex: IllegalArgumentException): ProblemDetail {
        log.info("NOTIF_400 invalid_enum message='{}'", ex.message)
        return problem(
            status = HttpStatus.BAD_REQUEST,
            type = "notification-invalid-enum",
            title = "Invalid Enum Value",
            errorCode = "NOTIF_INVALID_ENUM",
            detail = "요청 값이 올바르지 않습니다.",
        )
    }

    // ── 403 FORBIDDEN ─────────────────────────────────────────────────────────

    /**
     * [NotificationPolicyForbiddenException] — 권한 없음 — 403.
     */
    @ExceptionHandler(NotificationPolicyForbiddenException::class)
    fun handleForbidden(
        @Suppress("UnusedParameter") ex: NotificationPolicyForbiddenException,
    ): ProblemDetail {
        log.warn("NOTIF_403 forbidden")
        return problem(
            status = HttpStatus.FORBIDDEN,
            type = "notification-forbidden",
            title = "Forbidden",
            errorCode = "NOTIF_FORBIDDEN",
            detail = "알림 정책 관리 권한이 없습니다.",
        )
    }

    // ── 404 NOT_FOUND ─────────────────────────────────────────────────────────

    /**
     * [NotificationPolicyNotFoundException] — 알림 정책 미존재 — 404.
     */
    @ExceptionHandler(NotificationPolicyNotFoundException::class)
    fun handleNotFound(ex: NotificationPolicyNotFoundException): ProblemDetail {
        log.info("NOTIF_404 not_found id='{}'", ex.id)
        return problem(
            status = HttpStatus.NOT_FOUND,
            type = "notification-policy-not-found",
            title = "Notification Policy Not Found",
            errorCode = "NOTIF_POLICY_NOT_FOUND",
            detail = "알림 정책을 찾을 수 없습니다.",
        )
    }

    // ── 409 DUPLICATE ─────────────────────────────────────────────────────────

    /**
     * [NotificationPolicyDuplicateException] — 동일 조합 정책 중복 — 409.
     */
    @ExceptionHandler(NotificationPolicyDuplicateException::class)
    fun handleDuplicate(ex: NotificationPolicyDuplicateException): ProblemDetail {
        log.info("NOTIF_409 duplicate message='{}'", ex.message)
        return problem(
            status = HttpStatus.CONFLICT,
            type = "notification-policy-duplicate",
            title = "Notification Policy Duplicate",
            errorCode = "NOTIF_POLICY_DUPLICATE",
            detail = "동일한 조합의 알림 정책이 이미 존재합니다.",
        )
    }

    // ── 500 INTERNAL_ERROR (fallback) ─────────────────────────────────────────

    /**
     * 분류되지 않은 모든 예외 — 500.
     *
     * [ResponseStatusException] 은 Spring MVC 가 직접 처리하므로 rethrow 하여 catch-all 이 삼키지 않게 한다
     * (memory: catch-all-exceptionhandler-swallows-responsestatusexception 교훈).
     */
    @ExceptionHandler(Exception::class)
    fun handleInternalError(ex: Exception): ProblemDetail {
        if (ex is ResponseStatusException) throw ex
        log.error("NOTIF_500 internal_error", ex)
        return problem(
            status = HttpStatus.INTERNAL_SERVER_ERROR,
            type = "notification-internal-error",
            title = "Internal Server Error",
            errorCode = "NOTIF_INTERNAL_ERROR",
            detail = "서버 내부 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.",
        )
    }

    // ── private helper ────────────────────────────────────────────────────────

    /**
     * RFC 7807 [ProblemDetail] 인스턴스를 생성하는 헬퍼.
     *
     * 표준 필드 외에 커스텀 `errorCode` 와 `timestamp` 를 추가한다.
     *
     * @param status HTTP 응답 상태 코드
     * @param type `https://bts.example.com/problems/` 뒤에 붙는 type suffix
     * @param title 사람이 읽을 수 있는 문제 유형 요약
     * @param errorCode BTS 에러 코드 상수 (NOTIF_ 접두사)
     * @param detail 이 특정 발생에 대한 상세 설명
     * @return 완성된 [ProblemDetail] 인스턴스
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
