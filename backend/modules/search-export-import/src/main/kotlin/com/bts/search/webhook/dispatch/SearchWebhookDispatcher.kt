// 구독형 아웃바운드 Webhook을 SSRF 재검증 후 HMAC 서명해 HTTP로 발송하는 디스패처
package com.bts.search.webhook.dispatch

import com.bts.shared.http.OutboundUrlValidator
import com.bts.shared.http.UrlCheck
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException

/**
 * 아웃바운드 webhook 구독([com.bts.search.webhook.domain.OutboundWebhook])으로 이벤트를
 * HTTP POST 발송하는 디스패처 (FR-API-03 PR3 Task 7).
 *
 * ## notification `WebhookDispatcher`(FR-NT-05)와의 차이
 * FR-NT-05 `WebhookDispatcher`는 SSRF 검증 → RestClient 전송 → 결과 매핑의 기본 골격을 제공하며
 * 이 클래스도 같은 골격(shared [OutboundUrlValidator] + `Redirect.NEVER` [RestClient]) 위에서
 * 동작한다. 다만 이 클래스는 그 상위집합으로 다음을 추가한다.
 * - **HMAC-SHA256 서명** — 구독에 secret이 설정된 경우 [WebhookSigner]로 raw body를 서명해
 *   `X-BTS-Signature` 헤더에 부착한다(FR-NT-05는 서명 없음).
 * - **구독 payload/헤더 계약** — `X-BTS-Event`/`X-BTS-Delivery` 헤더, 화이트리스트된 이벤트
 *   payload(spec §6). FR-NT-05는 고정 엔벨로프(`{"event":...,"issueKey":...}`)만 보낸다.
 * - **응답 코드 보존** — 발송 이력([com.bts.search.webhook.domain.WebhookDelivery]) 기록에
 *   HTTP 상태 코드가 필요해 [WebhookDispatchResult.Sent]/[WebhookDispatchResult.Failed]에
 *   `code`를 담는다(FR-NT-05는 코드 없이 성공/실패만 구분).
 *
 * ## 처리 흐름
 * 1. 발송 직전 [OutboundUrlValidator.check]로 URL을 **재검증**한다 — 구독 등록 시점 검증과
 *    별개로 발송 시점에도 검증해 등록 후 DNS가 내부망으로 바뀌는 경우를 방어한다(spec §8).
 *    [UrlCheck.Blocked]/[UrlCheck.Malformed]면 발송하지 않고 [WebhookDispatchResult.Rejected]를 반환한다.
 * 2. [payloadBody]에 [WebhookSigner]로 서명해 헤더를 구성한다 — 호출자(워커, Task 8)가
 *    페이로드/멱등키를 조립하고, 이 클래스는 서명·발송만 책임진다(안정 멱등키는 워커 소관).
 * 3. [restClient]로 POST 전송한다([restClient]는 `Redirect.NEVER`로 SSRF 리다이렉트 우회를 차단).
 * 4. 2xx → [WebhookDispatchResult.Sent], non-2xx → [WebhookDispatchResult.Failed],
 *    예외(타임아웃/연결/DNS) → [WebhookDispatchResult.Failed](code=null)
 *
 * ## 보안 로그
 * secret 평문과 서명값은 절대 로그에 남기지 않는다. URL도 host만 로그한다(FR-NT-05 `extractHost` 패턴).
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
     * @param payloadBody 서명·전송할 raw body bytes. 호출자가 spec §6 화이트리스트 계약대로 구성한다.
     * @return [WebhookDispatchResult.Sent], [WebhookDispatchResult.Rejected], 또는 [WebhookDispatchResult.Failed]
     */
    @Suppress("TooGenericExceptionCaught", "ReturnCount") // 검증→전송→응답 3단계 분기 early return 필수 (FR-NT-05 동일 패턴)
    fun dispatch(
        url: String,
        secret: String?,
        eventType: String,
        deliveryId: String,
        payloadBody: ByteArray,
    ): WebhookDispatchResult {
        when (val check = validator.check(url)) {
            is UrlCheck.Blocked -> return rejectWithLog(url, eventType, check.reason, "search_webhook_url_blocked")
            is UrlCheck.Malformed -> return rejectWithLog(url, eventType, check.reason, "search_webhook_url_malformed")
            UrlCheck.Allowed -> Unit
        }

        return try {
            val statusCode =
                restClient.post()
                    .uri(url)
                    .contentType(MediaType.APPLICATION_JSON)
                    .headers { headers -> applyDeliveryHeaders(headers, secret, eventType, deliveryId, payloadBody) }
                    .body(payloadBody)
                    .exchange { _, response -> response.statusCode.value() }
            mapResponse(statusCode, url, eventType, deliveryId)
        } catch (e: RestClientException) {
            mapFailure(e, url, eventType, deliveryId, "search_webhook_dispatch_failed")
        } catch (e: Exception) {
            mapFailure(e, url, eventType, deliveryId, "search_webhook_dispatch_error")
        }
    }

    /** SSRF 검증 거부를 로그로 남기고 [WebhookDispatchResult.Rejected]를 만든다. */
    private fun rejectWithLog(
        url: String,
        eventType: String,
        reason: String,
        logEvent: String,
    ): WebhookDispatchResult.Rejected {
        log.warn(REJECT_LOG_FORMAT, logEvent, extractHost(url), eventType, reason)
        return WebhookDispatchResult.Rejected(reason)
    }

    /** HTTP 응답 상태 코드를 [WebhookDispatchResult.Sent]/[WebhookDispatchResult.Failed]로 매핑하고 로그를 남긴다. */
    @Suppress("MagicNumber")
    private fun mapResponse(
        statusCode: Int,
        url: String,
        eventType: String,
        deliveryId: String,
    ): WebhookDispatchResult {
        val host = extractHost(url)
        return if (statusCode in 200..299) {
            log.info(RESPONSE_LOG_FORMAT, "search_webhook_dispatched", host, eventType, deliveryId, statusCode)
            WebhookDispatchResult.Sent(statusCode)
        } else {
            log.warn(RESPONSE_LOG_FORMAT, "search_webhook_dispatch_non2xx", host, eventType, deliveryId, statusCode)
            WebhookDispatchResult.Failed("non-2xx status: $statusCode", statusCode)
        }
    }

    /** 발송 중 발생한 예외를 로그로 남기고 [WebhookDispatchResult.Failed](code=null)로 매핑한다. */
    private fun mapFailure(
        e: Exception,
        url: String,
        eventType: String,
        deliveryId: String,
        logEvent: String,
    ): WebhookDispatchResult.Failed {
        log.warn(FAILURE_LOG_FORMAT, logEvent, extractHost(url), eventType, deliveryId, e.message)
        return WebhookDispatchResult.Failed(e.message ?: "Unknown error")
    }

    /**
     * 요청 헤더에 `X-BTS-Event`/`X-BTS-Delivery`를 채우고, [secret]이 있으면 [WebhookSigner]로
     * [body]를 서명해 `X-BTS-Signature`를 추가한다(spec §6 헤더 계약).
     *
     * @param headers 채울 대상 [HttpHeaders]
     * @param secret 평문 signing secret. `null`이면 서명 헤더를 생략한다(spec EC2).
     * @param eventType `X-BTS-Event` 헤더 값
     * @param deliveryId `X-BTS-Delivery` 헤더 값
     * @param body 서명 대상 raw body bytes — 실제 전송 body와 바이트 단위로 동일해야 한다.
     */
    private fun applyDeliveryHeaders(
        headers: HttpHeaders,
        secret: String?,
        eventType: String,
        deliveryId: String,
        body: ByteArray,
    ) {
        headers.set(HEADER_EVENT, eventType)
        headers.set(HEADER_DELIVERY, deliveryId)
        if (secret != null) {
            headers.set(HEADER_SIGNATURE, WebhookSigner.sign(secret, body))
        }
    }

    /** URL에서 호스트만 추출한다. 실패 시 "(unknown)" 반환 (FR-NT-05 `extractHost` 패턴). */
    private fun extractHost(url: String): String =
        runCatching {
            java.net.URI(url).host ?: "(unknown)"
        }.getOrElse { "(unknown)" }

    companion object {
        private const val HEADER_EVENT = "X-BTS-Event"
        private const val HEADER_DELIVERY = "X-BTS-Delivery"
        private const val HEADER_SIGNATURE = "X-BTS-Signature"

        // 구조화 로그 포맷 (Pino-style key=value) — 첫 인자는 로그 이벤트명, url은 host만 포함(SSRF 정보 최소화)
        private const val REJECT_LOG_FORMAT = "{} url_host={} event={} reason={}"
        private const val RESPONSE_LOG_FORMAT = "{} url_host={} event={} delivery={} status={}"
        private const val FAILURE_LOG_FORMAT = "{} url_host={} event={} delivery={} error={}"
    }
}
