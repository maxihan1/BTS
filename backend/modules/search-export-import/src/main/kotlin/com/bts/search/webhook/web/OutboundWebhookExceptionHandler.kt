// 아웃바운드 webhook 구독 컨트롤러 예외를 RFC 7807 ProblemDetail 로 변환 — 식별자·비밀값 누출 차단 (FR-API-03 PR2)

package com.bts.search.webhook.web

import com.bts.search.web.SearchErrorCodes
import com.bts.search.webhook.application.WebhookConflictException
import com.bts.search.webhook.application.WebhookForbiddenException
import com.bts.search.webhook.application.WebhookNotFoundException
import com.bts.search.webhook.application.WebhookValidationException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.server.ResponseStatusException
import java.net.URI
import java.time.Instant

/**
 * 아웃바운드 webhook 구독 컨트롤러 예외를 RFC 7807 [ProblemDetail] 로 변환한다.
 *
 * [assignableTypes] 를 [OutboundWebhookController] 하나로 한정해 형제 컨트롤러나 타 BC 컨트롤러를
 * 가로채지 않는다(교훈 domain-exception-http-handler-basepackage-scope). SavedFilter 핸들러가 두
 * 컨트롤러 순환 참조 때문에 basePackages 를 쓴 것과 달리, webhook 컨트롤러는 단일이라 클래스 참조가 안전하다.
 *
 * ## 식별자·비밀값 누출 차단 (교훈 fr-pm-04-guard-exception-message-http-leak)
 * [WebhookNotFoundException]/[WebhookConflictException] 의 message 에는 디버그용 식별자(UUID)가
 * 포함되므로 detail 로 직접 노출하지 않고 **일반 메시지로 치환**한다. [WebhookForbiddenException] 도
 * 대상 존재 여부를 노출하지 않는 일반 메시지를 사용한다. secret 평문/암호문은 어떤 경로로도 응답에 담지 않는다.
 *
 * ## catch-all 안티패턴 회피 (교훈 catch-all-exceptionhandler-swallows-responsestatusexception)
 * [ResponseStatusException](actor 추출 401 등)은 전용 핸들러가 상태를 전파하고, 분류되지 않은 예외만
 * 최후 [handleInternal] 이 500 으로 매핑한다. 암호화 키 미설정 시 서비스가 전파하는
 * [IllegalStateException] 도 이 최후 핸들러가 **비밀값 미포함 일반 메시지**로 500 처리한다.
 */
@Suppress("TooManyFunctions")
@RestControllerAdvice(assignableTypes = [OutboundWebhookController::class])
class OutboundWebhookExceptionHandler {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * [WebhookValidationException] — SSRF 차단/형식 오류 URL 또는 도메인 불변식 위반 — 400.
     *
     * 서비스가 이미 비밀값·내부 host 를 배제한 안전한 메시지를 구성하므로 그대로 노출한다
     * (형제 [com.bts.search.savedfilter.web.SavedFilterExceptionHandler] 와 동일 정책).
     */
    @ExceptionHandler(WebhookValidationException::class)
    fun handleValidation(ex: WebhookValidationException): ProblemDetail {
        log.info("WEBHOOK_400 validation message='{}'", ex.message)
        return problem(
            HttpStatus.BAD_REQUEST,
            "outbound-webhook-validation-failed",
            "Validation Failed",
            SearchErrorCodes.SEARCH_VALIDATION_FAILED,
            ex.message ?: "요청 값이 올바르지 않습니다.",
        )
    }

    /**
     * [WebhookForbiddenException] — SYSTEM_ADMIN 이 아님 — 403.
     *
     * 리소스 조회 이전 admin 게이트에서 발생하므로 존재 여부를 노출하지 않는 일반 메시지를 쓴다.
     */
    @ExceptionHandler(WebhookForbiddenException::class)
    fun handleForbidden(
        @Suppress("UnusedParameter") ex: WebhookForbiddenException,
    ): ProblemDetail {
        log.info("WEBHOOK_403 forbidden")
        return problem(
            HttpStatus.FORBIDDEN,
            "outbound-webhook-forbidden",
            "Forbidden",
            SearchErrorCodes.SEARCH_ACCESS_DENIED,
            "이 작업을 수행할 권한이 없습니다.",
        )
    }

    /**
     * [WebhookNotFoundException] — 존재하지 않거나 소프트 삭제됨 — 404.
     *
     * message 에 포함된 식별자(UUID)를 노출하지 않도록 detail 을 일반 메시지로 치환한다.
     */
    @ExceptionHandler(WebhookNotFoundException::class)
    fun handleNotFound(
        @Suppress("UnusedParameter") ex: WebhookNotFoundException,
    ): ProblemDetail {
        log.info("WEBHOOK_404 not_found")
        return problem(
            HttpStatus.NOT_FOUND,
            "outbound-webhook-not-found",
            "Not Found",
            SearchErrorCodes.SEARCH_NOT_FOUND,
            "아웃바운드 webhook 구독을 찾을 수 없습니다.",
        )
    }

    /**
     * [WebhookConflictException] — OCC 충돌(stale version) — 409.
     *
     * message 에 포함된 식별자(UUID)를 노출하지 않도록 detail 을 일반 메시지로 치환한다.
     */
    @ExceptionHandler(WebhookConflictException::class)
    fun handleConflict(
        @Suppress("UnusedParameter") ex: WebhookConflictException,
    ): ProblemDetail {
        log.info("WEBHOOK_409 occ_conflict")
        return problem(
            HttpStatus.CONFLICT,
            "outbound-webhook-conflict",
            "Conflict",
            WEBHOOK_CONFLICT,
            "구독이 다른 요청으로 수정되었습니다. 최신 버전을 재조회 후 재시도하세요.",
        )
    }

    /**
     * [HttpMessageNotReadableException] — 요청 본문 역직렬화 실패 — 400.
     */
    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun handleNotReadable(ex: HttpMessageNotReadableException): ProblemDetail {
        log.info("WEBHOOK_400 not_readable cause='{}'", ex.cause?.message ?: ex.message)
        return problem(
            HttpStatus.BAD_REQUEST,
            "outbound-webhook-validation-failed",
            "Validation Failed",
            SearchErrorCodes.SEARCH_VALIDATION_FAILED,
            "요청 본문을 읽을 수 없습니다. JSON 형식 또는 필드 값을 확인해 주세요.",
        )
    }

    /**
     * [MethodArgumentTypeMismatchException] — 경로 변수 타입 불일치(잘못된 UUID 등) — 400.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    fun handleTypeMismatch(ex: MethodArgumentTypeMismatchException): ProblemDetail {
        log.info("WEBHOOK_400 type_mismatch param='{}'", ex.name)
        return problem(
            HttpStatus.BAD_REQUEST,
            "outbound-webhook-validation-failed",
            "Validation Failed",
            SearchErrorCodes.SEARCH_VALIDATION_FAILED,
            "요청 경로 또는 파라미터 형식이 올바르지 않습니다.",
        )
    }

    /**
     * [ResponseStatusException] — actor 추출(401)·필수 필드/페이지네이션(400) 등 명시 상태 전파.
     */
    @ExceptionHandler(ResponseStatusException::class)
    fun handleResponseStatus(ex: ResponseStatusException): ProblemDetail {
        val status = HttpStatus.valueOf(ex.statusCode.value())
        log.info("WEBHOOK_{} response_status", status.value())
        val (errorCode, detail) =
            when (status) {
                HttpStatus.UNAUTHORIZED ->
                    SearchErrorCodes.SEARCH_UNAUTHENTICATED to "인증이 필요합니다. 세션이 만료되었을 수 있습니다."
                HttpStatus.FORBIDDEN ->
                    SearchErrorCodes.SEARCH_ACCESS_DENIED to "이 작업을 수행할 권한이 없습니다."
                HttpStatus.BAD_REQUEST ->
                    SearchErrorCodes.SEARCH_VALIDATION_FAILED to "요청 값이 올바르지 않습니다."
                else ->
                    SearchErrorCodes.SEARCH_INTERNAL_ERROR to "요청을 처리할 수 없습니다."
            }
        return problem(status, "outbound-webhook-response-status", status.reasonPhrase, errorCode, detail)
    }

    /**
     * 분류되지 않은 모든 예외 — 500.
     *
     * 암호화 키 미설정 시 서비스가 전파하는 [IllegalStateException] 도 여기서 처리하며, detail 에는
     * 비밀값·내부 정보를 포함하지 않는 일반 메시지만 반환한다(스택트레이스는 서버 로그 전용).
     */
    @ExceptionHandler(Exception::class)
    fun handleInternal(ex: Exception): ProblemDetail {
        log.error("WEBHOOK_500 internal_error", ex)
        return problem(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "outbound-webhook-internal-error",
            "Internal Server Error",
            SearchErrorCodes.SEARCH_INTERNAL_ERROR,
            "서버 내부 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.",
        )
    }

    // ── private helpers ───────────────────────────────────────────────────────

    /**
     * RFC 7807 [ProblemDetail] 인스턴스를 생성한다(형제 SavedFilter/Search 핸들러와 동일 형식).
     *
     * @param status HTTP 응답 상태.
     * @param type type suffix.
     * @param title 문제 유형 요약.
     * @param errorCode BTS 에러 코드.
     * @param detail 상세 설명(식별자·비밀값 미포함).
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

    private companion object {
        /** OCC 충돌 에러 코드. */
        const val WEBHOOK_CONFLICT = "SEARCH_WEBHOOK_CONFLICT"
    }
}
