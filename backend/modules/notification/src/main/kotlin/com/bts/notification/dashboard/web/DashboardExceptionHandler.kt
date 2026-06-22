// 대시보드 도메인 예외를 RFC 7807 ProblemDetail 응답으로 변환하는 핸들러

package com.bts.notification.dashboard.web

import com.bts.notification.dashboard.application.DashboardConflictException
import com.bts.notification.dashboard.application.DashboardForbiddenException
import com.bts.notification.dashboard.application.DashboardNotFoundException
import com.bts.notification.dashboard.domain.DashboardDomainException
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
 * 대시보드 BC 도메인 예외를 RFC 7807 ProblemDetail 형식으로 변환하는 핸들러.
 *
 * basePackages 를 com.bts.notification.dashboard.web 으로 한정해 다른 BC 의 예외를 잡지 않는다
 * (memory: domain-exception-http-handler-basepackage-scope 교훈).
 *
 * catch-all Exception 핸들러는 ResponseStatusException 을 먼저 rethrow 한다
 * (memory: catch-all-exceptionhandler-swallows-responsestatusexception 교훈).
 *
 * 에러 코드 접두사는 NOTIF_DASHBOARD_ 로 고정한다 (C5 — BTS 에러 코드 규칙 §1.16).
 *
 * 매핑 규칙.
 * - DashboardDomainException -> 400 + NOTIF_DASHBOARD_INVALID
 * - MethodArgumentNotValidException -> 400 + NOTIF_DASHBOARD_INVALID
 * - HttpMessageNotReadableException -> 400 + NOTIF_DASHBOARD_INVALID
 * - MethodArgumentTypeMismatchException -> 400 + NOTIF_DASHBOARD_INVALID
 * - DashboardNotFoundException -> 404 + NOTIF_DASHBOARD_NOT_FOUND
 * - DashboardForbiddenException -> 403 + NOTIF_DASHBOARD_FORBIDDEN
 * - DashboardConflictException -> 409 + NOTIF_DASHBOARD_CONFLICT
 * - Exception (fallback) -> 500 + NOTIF_DASHBOARD_INTERNAL_ERROR
 */
@RestControllerAdvice(basePackages = ["com.bts.notification.dashboard.web"])
class DashboardExceptionHandler {
    private val log = LoggerFactory.getLogger(javaClass)

    /** 도메인 불변식 위반 — 400. */
    @ExceptionHandler(DashboardDomainException::class)
    fun handleDomainException(ex: DashboardDomainException): ProblemDetail {
        log.info("NOTIF_DASHBOARD_400 domain_invalid message='{}'", ex.message)
        return problem(
            status = HttpStatus.BAD_REQUEST,
            type = "dashboard-invalid",
            title = "Dashboard Validation Failed",
            errorCode = "NOTIF_DASHBOARD_INVALID",
            detail = "요청 값이 대시보드 규칙에 맞지 않습니다.",
        )
    }

    /** 대시보드 미존재 또는 접근 불가 — 404. */
    @ExceptionHandler(DashboardNotFoundException::class)
    fun handleNotFound(ex: DashboardNotFoundException): ProblemDetail {
        log.info("NOTIF_DASHBOARD_404 not_found id='{}'", ex.id)
        return problem(
            status = HttpStatus.NOT_FOUND,
            type = "dashboard-not-found",
            title = "Dashboard Not Found",
            errorCode = "NOTIF_DASHBOARD_NOT_FOUND",
            detail = "대시보드를 찾을 수 없습니다.",
        )
    }

    /** 소유자가 아닌 사용자의 수정·삭제 시도 — 403. */
    @ExceptionHandler(DashboardForbiddenException::class)
    fun handleForbidden(
        @Suppress("UnusedParameter") ex: DashboardForbiddenException,
    ): ProblemDetail {
        log.warn("NOTIF_DASHBOARD_403 forbidden")
        return problem(
            status = HttpStatus.FORBIDDEN,
            type = "dashboard-forbidden",
            title = "Dashboard Access Denied",
            errorCode = "NOTIF_DASHBOARD_FORBIDDEN",
            detail = "해당 대시보드를 수정·삭제할 권한이 없습니다.",
        )
    }

    /** OCC version 불일치 충돌 — 409. */
    @ExceptionHandler(DashboardConflictException::class)
    fun handleConflict(ex: DashboardConflictException): ProblemDetail {
        log.info("NOTIF_DASHBOARD_409 conflict id='{}'", ex.id)
        return problem(
            status = HttpStatus.CONFLICT,
            type = "dashboard-conflict",
            title = "Dashboard Conflict",
            errorCode = "NOTIF_DASHBOARD_CONFLICT",
            detail = "대시보드가 다른 사용자에 의해 수정되었습니다. 최신 버전으로 다시 시도하세요.",
        )
    }

    /** Bean Validation(@Valid) 실패 — 400. */
    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidationFailed(
        @Suppress("UnusedParameter") ex: MethodArgumentNotValidException,
    ): ProblemDetail {
        log.info("NOTIF_DASHBOARD_400 validation_failed")
        return problem(
            status = HttpStatus.BAD_REQUEST,
            type = "dashboard-invalid",
            title = "Dashboard Validation Failed",
            errorCode = "NOTIF_DASHBOARD_INVALID",
            detail = "요청 값이 대시보드 규칙에 맞지 않습니다.",
        )
    }

    /** malformed JSON body — 400. */
    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun handleMessageNotReadable(
        @Suppress("UnusedParameter") ex: HttpMessageNotReadableException,
    ): ProblemDetail {
        log.info("NOTIF_DASHBOARD_400 message_not_readable")
        return problem(
            status = HttpStatus.BAD_REQUEST,
            type = "dashboard-invalid",
            title = "Dashboard Validation Failed",
            errorCode = "NOTIF_DASHBOARD_INVALID",
            detail = "요청 바디를 파싱할 수 없습니다.",
        )
    }

    /** path 파라미터 타입 불일치(UUID 파싱 실패 등) — 400. */
    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    fun handleTypeMismatch(
        @Suppress("UnusedParameter") ex: MethodArgumentTypeMismatchException,
    ): ProblemDetail {
        log.info("NOTIF_DASHBOARD_400 type_mismatch")
        return problem(
            status = HttpStatus.BAD_REQUEST,
            type = "dashboard-invalid",
            title = "Dashboard Validation Failed",
            errorCode = "NOTIF_DASHBOARD_INVALID",
            detail = "요청 파라미터 형식이 올바르지 않습니다.",
        )
    }

    /** 분류되지 않은 모든 예외 — 500. ResponseStatusException 은 rethrow. */
    @ExceptionHandler(Exception::class)
    fun handleInternalError(ex: Exception): ProblemDetail {
        if (ex is ResponseStatusException) throw ex
        log.error("NOTIF_DASHBOARD_500 internal_error", ex)
        return problem(
            status = HttpStatus.INTERNAL_SERVER_ERROR,
            type = "dashboard-internal-error",
            title = "Dashboard Internal Server Error",
            errorCode = "NOTIF_DASHBOARD_INTERNAL_ERROR",
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
