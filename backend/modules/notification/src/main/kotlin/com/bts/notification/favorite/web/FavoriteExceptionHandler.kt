// 즐겨찾기 도메인 예외를 RFC 7807 ProblemDetail 응답으로 변환하는 핸들러

package com.bts.notification.favorite.web

import com.bts.notification.favorite.domain.FavoriteDomainException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.server.ResponseStatusException
import java.net.URI
import java.time.Instant

/**
 * 즐겨찾기 BC 도메인 예외를 RFC 7807 ProblemDetail 형식으로 변환하는 핸들러.
 *
 * basePackages 를 com.bts.notification.favorite.web 으로 한정해 다른 BC 의 예외를 잡지 않는다
 * (memory: domain-exception-http-handler-basepackage-scope 교훈).
 *
 * catch-all Exception 핸들러는 ResponseStatusException 을 먼저 rethrow 한다
 * (memory: catch-all-exceptionhandler-swallows-responsestatusexception 교훈).
 *
 * 에러 코드 접두사는 NOTIF_FAV_ 로 고정한다 (BTS 에러 코드 규칙 §1.16).
 *
 * 매핑 규칙.
 * - FavoriteDomainException -> 400 + NOTIF_FAV_INVALID
 * - MethodArgumentNotValidException -> 400 + NOTIF_FAV_INVALID
 * - HttpMessageNotReadableException -> 400 + NOTIF_FAV_INVALID
 * - Exception (fallback) -> 500 + NOTIF_FAV_INTERNAL_ERROR (ResponseStatusException 은 rethrow)
 */
@RestControllerAdvice(basePackages = ["com.bts.notification.favorite.web"])
class FavoriteExceptionHandler {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 즐겨찾기 도메인 불변식 위반 — 400.
     *
     * targetType 무효값, targetId 빈 문자열/초과 길이 등 도메인 검증 실패 시 발생한다.
     */
    @ExceptionHandler(FavoriteDomainException::class)
    fun handleDomainException(ex: FavoriteDomainException): ProblemDetail {
        log.info("NOTIF_FAV_400 domain_invalid message='{}'", ex.message)
        return problem(
            status = HttpStatus.BAD_REQUEST,
            type = "favorite-invalid",
            title = "Favorite Validation Failed",
            errorCode = "NOTIF_FAV_INVALID",
            detail = "요청 값이 즐겨찾기 규칙에 맞지 않습니다.",
        )
    }

    /**
     * Bean Validation(@Valid) 실패 — 400.
     *
     * notification 모듈에 Bean Validation provider 가 없어 현재 무동작이나,
     * 추후 provider 추가 시를 대비해 핸들러를 등록한다.
     */
    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidationFailed(
        @Suppress("UnusedParameter") ex: MethodArgumentNotValidException,
    ): ProblemDetail {
        log.info("NOTIF_FAV_400 validation_failed")
        return problem(
            status = HttpStatus.BAD_REQUEST,
            type = "favorite-invalid",
            title = "Favorite Validation Failed",
            errorCode = "NOTIF_FAV_INVALID",
            detail = "요청 값이 즐겨찾기 규칙에 맞지 않습니다.",
        )
    }

    /** malformed JSON body — 400. */
    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun handleMessageNotReadable(
        @Suppress("UnusedParameter") ex: HttpMessageNotReadableException,
    ): ProblemDetail {
        log.info("NOTIF_FAV_400 message_not_readable")
        return problem(
            status = HttpStatus.BAD_REQUEST,
            type = "favorite-invalid",
            title = "Favorite Validation Failed",
            errorCode = "NOTIF_FAV_INVALID",
            detail = "요청 바디를 파싱할 수 없습니다.",
        )
    }

    /** 분류되지 않은 모든 예외 — 500. ResponseStatusException 은 rethrow. */
    @ExceptionHandler(Exception::class)
    fun handleInternalError(ex: Exception): ProblemDetail {
        if (ex is ResponseStatusException) throw ex
        log.error("NOTIF_FAV_500 internal_error", ex)
        return problem(
            status = HttpStatus.INTERNAL_SERVER_ERROR,
            type = "favorite-internal-error",
            title = "Favorite Internal Server Error",
            errorCode = "NOTIF_FAV_INTERNAL_ERROR",
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
