// q_transition_events의 WebhookRequested를 외부 URL로 HTTP 전송하는 디스패처

package com.bts.notification.webhook

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException

/**
 * Webhook URL 로 HTTP 요청을 전송하는 디스패처.
 *
 * ## 처리 흐름
 * 1. [WebhookUrlValidator.check] 로 URL 검증 → [UrlCheck.Blocked] / [UrlCheck.Malformed] 이면
 *    [WebhookDispatchResult.Rejected] 반환 (전송 X)
 * 2. 엔벨로프 body 구성: `{"event":"WebhookRequested","issueKey":"<key>"}`
 * 3. [RestClient] 로 HTTP 전송 (method=POST 기본, PUT 지원, 그 외는 POST fallback)
 * 4. 2xx → [WebhookDispatchResult.Sent], 3xx/4xx/5xx → [WebhookDispatchResult.Failed]
 * 5. 예외(타임아웃/연결/DNS) → [WebhookDispatchResult.Failed]
 *
 * ## 리다이렉트 차단 (FR8)
 * [RestClient] 는 [com.bts.notification.config.WebhookHttpClientConfig] 에서
 * [java.net.http.HttpClient.Redirect.NEVER] 로 구성된 JDK HttpClient 를 사용한다.
 * 3xx 응답은 따라가지 않고 non-2xx 로 처리 → [WebhookDispatchResult.Failed].
 *
 * @param validator SSRF 검증기
 * @param restClient 리다이렉트 차단 설정된 Spring [RestClient]
 * @param objectMapper JSON 직렬화
 */
@Component
class WebhookDispatcher(
    private val validator: WebhookUrlValidator,
    private val restClient: RestClient,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * [url] 로 Webhook HTTP 요청을 전송한다.
     *
     * @param url 전송 대상 URL
     * @param method HTTP 메서드 (POST/PUT 지원, 그 외 POST fallback)
     * @param issueKey 알림 페이로드에 포함할 이슈 키
     * @return [WebhookDispatchResult.Sent], [WebhookDispatchResult.Rejected], 또는 [WebhookDispatchResult.Failed]
     */
    @Suppress("TooGenericExceptionCaught", "ReturnCount") // 검증→전송→응답 3단계 분기 early return 필수
    fun dispatch(
        url: String,
        method: String,
        issueKey: String,
    ): WebhookDispatchResult {
        when (val check = validator.check(url)) {
            is UrlCheck.Blocked -> {
                log.warn("webhook_url_blocked url_host={} reason={}", extractHost(url), check.reason)
                return WebhookDispatchResult.Rejected(check.reason)
            }
            is UrlCheck.Malformed -> {
                log.warn("webhook_url_malformed reason={}", check.reason)
                return WebhookDispatchResult.Rejected(check.reason)
            }
            UrlCheck.Allowed -> Unit
        }

        val body = buildEnvelope(issueKey)
        val httpMethod = resolveMethod(method)

        return try {
            val statusCode =
                restClient.method(httpMethod)
                    .uri(url)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .exchange { _, response ->
                        response.statusCode.value()
                    }

            @Suppress("MagicNumber")
            if (statusCode in 200..299) {
                log.info(
                    "webhook_dispatched url_host={} issueKey={} status={}",
                    extractHost(url),
                    issueKey,
                    statusCode,
                )
                WebhookDispatchResult.Sent
            } else {
                log.warn(
                    "webhook_dispatch_non2xx url_host={} issueKey={} status={}",
                    extractHost(url),
                    issueKey,
                    statusCode,
                )
                WebhookDispatchResult.Failed("non-2xx status: $statusCode")
            }
        } catch (e: RestClientException) {
            log.warn("webhook_dispatch_failed url_host={} issueKey={} error={}", extractHost(url), issueKey, e.message)
            WebhookDispatchResult.Failed(e.message ?: "RestClientException")
        } catch (e: Exception) {
            log.warn("webhook_dispatch_error url_host={} issueKey={} error={}", extractHost(url), issueKey, e.message)
            WebhookDispatchResult.Failed(e.message ?: "Unknown error")
        }
    }

    /**
     * 전송 엔벨로프 JSON 문자열을 구성한다.
     *
     * 형식: `{"event":"WebhookRequested","issueKey":"<key>"}`
     *
     * @param issueKey 이슈 키
     * @return JSON 문자열
     */
    private fun buildEnvelope(issueKey: String): String {
        val node = objectMapper.createObjectNode()
        node.put("event", "WebhookRequested")
        node.put("issueKey", issueKey)
        return objectMapper.writeValueAsString(node)
    }

    /**
     * 메서드 문자열을 [HttpMethod] 로 변환한다.
     *
     * POST/PUT 만 지원하고 그 외는 POST 로 fallback 한다.
     *
     * @param method HTTP 메서드 문자열
     * @return [HttpMethod.POST] 또는 [HttpMethod.PUT]
     */
    private fun resolveMethod(method: String): HttpMethod =
        when (method.uppercase()) {
            "PUT" -> HttpMethod.PUT
            else -> HttpMethod.POST
        }

    /** URL 에서 호스트만 추출한다. 실패 시 "(unknown)" 반환. */
    private fun extractHost(url: String): String =
        runCatching {
            java.net.URI(url).host ?: "(unknown)"
        }.getOrElse { "(unknown)" }
}
