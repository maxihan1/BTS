// 구독형 아웃바운드 Webhook을 SSRF 재검증 후 HMAC 서명해 HTTP로 발송하는 디스패처
package com.bts.search.webhook.dispatch

import com.bts.shared.http.OutboundUrlValidator
import com.bts.shared.http.UrlCheck
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException

/**
 * 아웃바운드 webhook 구독으로 이벤트를 HTTP POST 발송하는 디스패처 (FR-API-03 PR3 Task 7).
 *
 * @param validator SSRF 재검증기 (shared-kernel)
 * @param restClient 리다이렉트 차단(`Redirect.NEVER`) 설정된 shared [RestClient]
 */
@Component
class SearchWebhookDispatcher(
    private val validator: OutboundUrlValidator,
    private val restClient: RestClient,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * [url]로 webhook 이벤트를 HTTP POST 발송한다.
     *
     * @param url 발송 대상 URL (구독에 등록된 값)
     * @param secret 서명용 평문 secret. `null`이면 `X-BTS-Signature` 헤더를 생략한다.
     * @param eventType `X-BTS-Event` 헤더 값 (예: `issue.created`)
     * @param deliveryId `X-BTS-Delivery` 헤더 값 — 호출자가 구성한 안정 멱등키
     * @param payloadBody 서명·전송할 raw body bytes
     * @return [WebhookDispatchResult.Sent], [WebhookDispatchResult.Rejected], 또는 [WebhookDispatchResult.Failed]
     */
    @Suppress("TooGenericExceptionCaught", "ReturnCount")
    fun dispatch(
        url: String,
        secret: String?,
        eventType: String,
        deliveryId: String,
        payloadBody: ByteArray,
    ): WebhookDispatchResult {
        when (val check = validator.check(url)) {
            is UrlCheck.Blocked -> {
                log.warn("search_webhook_url_blocked url_host={} event={} reason={}", extractHost(url), eventType, check.reason)
                return WebhookDispatchResult.Rejected(check.reason)
            }
            is UrlCheck.Malformed -> {
                log.warn("search_webhook_url_malformed event={} reason={}", eventType, check.reason)
                return WebhookDispatchResult.Rejected(check.reason)
            }
            UrlCheck.Allowed -> Unit
        }

        return try {
            val statusCode =
                restClient.post()
                    .uri(url)
                    .contentType(MediaType.APPLICATION_JSON)
                    .headers { headers ->
                        headers.set("X-BTS-Event", eventType)
                        headers.set("X-BTS-Delivery", deliveryId)
                        if (secret != null) {
                            headers.set("X-BTS-Signature", WebhookSigner.sign(secret, payloadBody))
                        }
                    }
                    .body(payloadBody)
                    .exchange { _, response -> response.statusCode.value() }

            @Suppress("MagicNumber")
            if (statusCode in 200..299) {
                log.info(
                    "search_webhook_dispatched url_host={} event={} delivery={} status={}",
                    extractHost(url),
                    eventType,
                    deliveryId,
                    statusCode,
                )
                WebhookDispatchResult.Sent(statusCode)
            } else {
                log.warn(
                    "search_webhook_dispatch_non2xx url_host={} event={} delivery={} status={}",
                    extractHost(url),
                    eventType,
                    deliveryId,
                    statusCode,
                )
                WebhookDispatchResult.Failed("non-2xx status: $statusCode", statusCode)
            }
        } catch (e: RestClientException) {
            log.warn(
                "search_webhook_dispatch_failed url_host={} event={} delivery={} error={}",
                extractHost(url),
                eventType,
                deliveryId,
                e.message,
            )
            WebhookDispatchResult.Failed(e.message ?: "RestClientException")
        } catch (e: Exception) {
            log.warn(
                "search_webhook_dispatch_error url_host={} event={} delivery={} error={}",
                extractHost(url),
                eventType,
                deliveryId,
                e.message,
            )
            WebhookDispatchResult.Failed(e.message ?: "Unknown error")
        }
    }

    /** URL에서 호스트만 추출한다. 실패 시 "(unknown)" 반환. */
    private fun extractHost(url: String): String =
        runCatching {
            java.net.URI(url).host ?: "(unknown)"
        }.getOrElse { "(unknown)" }
}
