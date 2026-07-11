// CallWebhook 액션의 아웃바운드 HTTP 클라이언트 — SSRF 재검증 후 HTTP 호출, 예외를 결과로 흡수 (FR-AT-02 Task 8)

package com.bts.automation.adapter

import com.bts.shared.http.OutboundUrlValidator
import com.bts.shared.http.UrlCheck
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException

/**
 * CallWebhook 액션이 외부 URL로 HTTP 요청을 보내는 아웃바운드 클라이언트 (FR-AT-02 Task 8).
 *
 * search-export-import `SearchWebhookDispatcher`(FR-API-03) / notification `WebhookDispatcher`
 * (FR-NT-05)와 같은 골격 — shared-kernel [OutboundUrlValidator] + `Redirect.NEVER` [RestClient] —
 * 위에서 동작한다. 다만 이 클래스는 예외를 던지지 않고 항상 [WebhookCallResult] 로 흡수한다
 * (executor 가 액션 실행 상태 집계에 사용하며 재시도하지 않는 best-effort).
 *
 * ## 처리 흐름
 * 1. [OutboundUrlValidator.check] 로 URL 을 검증한다 — [UrlCheck.Blocked]/[UrlCheck.Malformed] 면
 *    **호출하지 않고** 실패 결과를 반환한다(SSRF 방어).
 * 2. [UrlCheck.Allowed] 면 [method] 를 [HttpMethod] 로 해석해([resolveMethod], 알 수 없는 값은
 *    POST로 fallback) [restClient] 로 요청을 보낸다. [restClient] 는
 *    [com.bts.shared.http.OutboundHttpClientConfig] 에서 `Redirect.NEVER` 로 구성되어 3xx
 *    리다이렉트를 따라가지 않는다(non-2xx 로 처리).
 * 3. 2xx 이면 성공, 그 외(non-2xx) 또는 예외(타임아웃/연결/DNS)면 실패로 매핑한다. 재시도는
 *    하지 않는다(executor 가 향후 재시도 정책을 별도로 관리).
 *
 * ## 응답 본문 미소비 (선례 미러)
 * 응답 본문은 절대 읽지 않는다 — `exchange { _, response -> response.statusCode.value() }` 로
 * 상태 코드만 추출한다(선례 동형). 대용량 응답 본문을 메모리에 버퍼링하지 않아 크기 상한을 별도로
 * 두지 않아도 무제한 메모리 소비 위험이 없다.
 *
 * ## 보안 로그
 * URL 은 host 만 로그로 남긴다(전체 URL·쿼리스트링 미기록). 커스텀 헤더 값·요청/응답 본문은
 * 절대 로그에 남기지 않는다 — 비밀 토큰이 헤더에 담길 수 있다(DEVELOPMENT §1.1.2).
 *
 * @param validator SSRF 검증기(shared-kernel).
 * @param restClient 리다이렉트 차단(`Redirect.NEVER`) 설정된 shared [RestClient].
 */
@Component
class WebhookActionClient(
    private val validator: OutboundUrlValidator,
    private val restClient: RestClient,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * [url] 로 HTTP 요청을 보낸다.
     *
     * @param url 호출 대상 URL.
     * @param method HTTP 메서드 문자열. 알 수 없는 값이면 POST 로 fallback 한다.
     * @param headers 요청에 부착할 커스텀 헤더.
     * @param body 요청 본문. `null` 이면 본문 없이 전송한다.
     * @return [WebhookCallResult] — 성공/실패 모두 예외 없이 결과로 반환한다.
     */
    @Suppress("TooGenericExceptionCaught", "ReturnCount") // 검증→전송→응답 3단계 분기 early return 필수 (선례 동형)
    fun call(
        url: String,
        method: String,
        headers: Map<String, String>,
        body: String?,
    ): WebhookCallResult {
        when (val check = validator.check(url)) {
            is UrlCheck.Blocked -> return rejectWithLog(url, check.reason, "automation_webhook_action_blocked")
            is UrlCheck.Malformed -> return rejectWithLog(url, check.reason, "automation_webhook_action_malformed")
            UrlCheck.Allowed -> Unit
        }

        return try {
            val statusCode = executeRequest(url, resolveMethod(method), headers, body)
            mapResponse(statusCode, url)
        } catch (e: RestClientException) {
            mapFailure(e, url, "automation_webhook_action_failed")
        } catch (e: Exception) {
            mapFailure(e, url, "automation_webhook_action_error")
        }
    }

    /** [restClient] 로 실제 HTTP 요청을 보내고 응답 상태 코드만 추출한다(본문 미소비). */
    private fun executeRequest(
        url: String,
        httpMethod: HttpMethod,
        headers: Map<String, String>,
        body: String?,
    ): Int {
        val requestSpec =
            restClient.method(httpMethod)
                .uri(url)
                .headers { httpHeaders -> applyHeaders(httpHeaders, headers) }
        return if (body != null) {
            requestSpec.body(body).exchange { _, response -> response.statusCode.value() }
        } else {
            requestSpec.exchange { _, response -> response.statusCode.value() }
        }
    }

    /** 커스텀 헤더를 요청 [HttpHeaders] 에 채운다. */
    private fun applyHeaders(
        httpHeaders: HttpHeaders,
        headers: Map<String, String>,
    ) {
        headers.forEach { (key, value) -> httpHeaders.set(key, value) }
    }

    /**
     * 메서드 문자열을 [HttpMethod] 로 변환한다. 알 수 없는 값은 POST 로 fallback 한다.
     *
     * Spring 6 [HttpMethod] 는 더 이상 enum 이 아니라 `valueOf` 가 미등록 이름도 커스텀 인스턴스로
     * 생성해버려 예외로 fallback 을 감지할 수 없다 — [KNOWN_METHODS] 화이트리스트로 직접 검사한다.
     */
    private fun resolveMethod(method: String): HttpMethod {
        val normalized = method.uppercase()
        return if (normalized in KNOWN_METHODS) HttpMethod.valueOf(normalized) else HttpMethod.POST
    }

    /** SSRF 검증 거부를 로그로 남기고 실패 [WebhookCallResult] 를 만든다. */
    private fun rejectWithLog(
        url: String,
        reason: String,
        logEvent: String,
    ): WebhookCallResult {
        log.warn("{} url_host={} reason={}", logEvent, extractHost(url), reason)
        return WebhookCallResult(success = false, statusCode = null, error = SSRF_REJECT_ERROR)
    }

    /** HTTP 응답 상태 코드를 [WebhookCallResult] 로 매핑하고 로그를 남긴다. */
    @Suppress("MagicNumber")
    private fun mapResponse(
        statusCode: Int,
        url: String,
    ): WebhookCallResult {
        val host = extractHost(url)
        return if (statusCode in 200..299) {
            log.info("automation_webhook_action_dispatched url_host={} status={}", host, statusCode)
            WebhookCallResult(success = true, statusCode = statusCode, error = null)
        } else {
            log.warn("automation_webhook_action_non2xx url_host={} status={}", host, statusCode)
            WebhookCallResult(success = false, statusCode = statusCode, error = "non-2xx status: $statusCode")
        }
    }

    /** 호출 중 발생한 예외를 로그로 남기고 실패 [WebhookCallResult] 로 매핑한다(원본 메시지 미노출). */
    private fun mapFailure(
        e: Exception,
        url: String,
        logEvent: String,
    ): WebhookCallResult {
        log.warn("{} url_host={} error={}", logEvent, extractHost(url), e.message)
        return WebhookCallResult(success = false, statusCode = null, error = REQUEST_ERROR)
    }

    /** URL 에서 호스트만 추출한다. 실패 시 "(unknown)" 반환(선례 `extractHost` 패턴). */
    private fun extractHost(url: String): String =
        runCatching {
            java.net.URI(url).host ?: "(unknown)"
        }.getOrElse { "(unknown)" }

    companion object {
        /** SSRF 차단/형식 오류 URL 에 대한 일반 실패 사유(내부 host·차단 근거 미포함). */
        private const val SSRF_REJECT_ERROR = "허용되지 않는 URL입니다 (SSRF 검증 실패)"

        /** 호출 중 예외(타임아웃/연결/DNS) 시 결과에 담는 일반 사유(원본 예외 메시지 미노출). */
        private const val REQUEST_ERROR = "요청 처리 중 오류가 발생했습니다"

        /** [resolveMethod] 가 인식하는 HTTP 메서드 이름 화이트리스트. 그 외는 POST 로 fallback. */
        private val KNOWN_METHODS = setOf("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS", "TRACE")
    }
}

/**
 * [WebhookActionClient.call] 호출 결과.
 *
 * @param success 2xx 응답이면 `true`. SSRF 차단/non-2xx/예외이면 `false`.
 * @param statusCode 응답을 받았다면 HTTP 상태 코드. SSRF 차단이나 예외로 응답 자체를 받지 못했다면 `null`.
 * @param error 실패 사유(일반화된 메시지 — 내부 host·원본 예외 메시지 미포함). 성공이면 `null`.
 */
data class WebhookCallResult(
    val success: Boolean,
    val statusCode: Int?,
    val error: String?,
)
