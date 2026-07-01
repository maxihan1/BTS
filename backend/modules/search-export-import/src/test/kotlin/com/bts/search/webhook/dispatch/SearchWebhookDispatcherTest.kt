// SearchWebhookDispatcher 단위 테스트 — SSRF 재검증, HMAC 서명 헤더, 2xx/non-2xx 결과 매핑 검증
package com.bts.search.webhook.dispatch

import com.bts.shared.http.OutboundHttpClientConfig
import com.bts.shared.http.OutboundUrlValidator
import com.bts.shared.http.UrlCheck
import com.sun.net.httpserver.HttpServer
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * [SearchWebhookDispatcher] 단위 테스트.
 *
 * notification `WebhookDispatcherTest`(FR-NT-05)와 동일한 방식으로 JDK 내장 [HttpServer]를
 * 로컬 stub 서버로 사용한다 — RestClient 플루언트 빌더를 MockK로 흉내내는 대신, 실제 로컬
 * HTTP 서버 + 실제 [org.springframework.web.client.RestClient]로 요청을 검증하는 편이
 * 더 신뢰도 높고 유지보수가 쉽다(신규 의존성 0). SSRF 검증([OutboundUrlValidator])만 mock한다.
 *
 * ### 검증 항목
 * - D-a. SSRF 차단(Blocked) URL → Rejected, 전송 안 함(hit=0)
 * - D-b. secret 있으면 X-BTS-Signature 헤더 부착(HMAC 서명값 검증)
 * - D-c. secret 없으면 X-BTS-Signature 헤더 부재
 * - D-d. 2xx 응답 → Sent(code)
 * - D-e. non-2xx 응답 → Failed(reason, code) / 연결 불가 → Failed(reason, code=null)
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SearchWebhookDispatcherTest {
    private val hitCount = AtomicInteger(0)
    private val lastEventHeader = AtomicReference<String?>()
    private val lastDeliveryHeader = AtomicReference<String?>()
    private val lastSignatureHeader = AtomicReference<String?>()
    private val lastContentType = AtomicReference<String?>()
    private val lastBody = AtomicReference<String?>()
    private var stubStatusCode = 200

    private val stubServer: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

    init {
        stubServer.executor = null
        stubServer.createContext("/hook") { exchange ->
            hitCount.incrementAndGet()
            lastEventHeader.set(exchange.requestHeaders.getFirst("X-BTS-Event"))
            lastDeliveryHeader.set(exchange.requestHeaders.getFirst("X-BTS-Delivery"))
            lastSignatureHeader.set(exchange.requestHeaders.getFirst("X-BTS-Signature"))
            lastContentType.set(exchange.requestHeaders.getFirst("Content-Type"))
            val body = exchange.requestBody.bufferedReader().readText()
            lastBody.set(body)
            val responseBytes = "OK".toByteArray()
            exchange.sendResponseHeaders(stubStatusCode, responseBytes.size.toLong())
            exchange.responseBody.use { it.write(responseBytes) }
        }
        stubServer.start()
    }

    @AfterAll
    fun stopServer() {
        stubServer.stop(0)
    }

    private val stubPort get() = stubServer.address.port
    private val stubUrl get() = "http://127.0.0.1:$stubPort/hook"

    private val validator = mockk<OutboundUrlValidator>()
    private val restClient = OutboundHttpClientConfig().outboundHttpRestClient()
    private val dispatcher = SearchWebhookDispatcher(validator, restClient)

    private val payload = """{"event":"issue.created","deliveryId":"d-1","occurredAt":"2026-07-01T00:00:00Z","data":{}}"""
        .toByteArray(Charsets.UTF_8)

    @BeforeEach
    fun resetState() {
        hitCount.set(0)
        lastEventHeader.set(null)
        lastDeliveryHeader.set(null)
        lastSignatureHeader.set(null)
        lastContentType.set(null)
        lastBody.set(null)
        stubStatusCode = 200

        every { validator.check(stubUrl) } returns UrlCheck.Allowed
        every { validator.check("http://192.168.99.99/hook") } returns UrlCheck.Blocked("내부망 차단")
        every { validator.check("http://127.0.0.1:1/hook") } returns UrlCheck.Allowed
    }

    @Test
    fun `D-a SSRF 차단 URL이면 Rejected를 반환하고 전송하지 않는다`() {
        val result =
            dispatcher.dispatch(
                url = "http://192.168.99.99/hook",
                secret = null,
                eventType = "issue.created",
                deliveryId = "d-1",
                payloadBody = payload,
            )

        assertTrue(result is WebhookDispatchResult.Rejected)
        assertEquals(0, hitCount.get())
    }

    @Test
    fun `D-b secret이 있으면 X-BTS-Signature 헤더를 부착한다`() {
        val secret = "It's a Secret to Everybody"

        dispatcher.dispatch(
            url = stubUrl,
            secret = secret,
            eventType = "issue.created",
            deliveryId = "d-1",
            payloadBody = payload,
        )

        val expectedSignature = WebhookSigner.sign(secret, payload)
        assertEquals(expectedSignature, lastSignatureHeader.get())
        assertEquals("issue.created", lastEventHeader.get())
        assertEquals("d-1", lastDeliveryHeader.get())
    }

    @Test
    fun `D-c secret이 없으면 X-BTS-Signature 헤더가 부재한다`() {
        dispatcher.dispatch(
            url = stubUrl,
            secret = null,
            eventType = "issue.created",
            deliveryId = "d-1",
            payloadBody = payload,
        )

        assertNull(lastSignatureHeader.get())
    }

    @Test
    fun `D-d 2xx 응답이면 Sent(code)를 반환한다`() {
        stubStatusCode = 200

        val result =
            dispatcher.dispatch(
                url = stubUrl,
                secret = null,
                eventType = "issue.created",
                deliveryId = "d-1",
                payloadBody = payload,
            )

        assertTrue(result is WebhookDispatchResult.Sent)
        assertEquals(200, (result as WebhookDispatchResult.Sent).code)
    }

    @Test
    fun `D-e non-2xx 응답이면 Failed(reason, code)를 반환한다`() {
        stubStatusCode = 500

        val result =
            dispatcher.dispatch(
                url = stubUrl,
                secret = null,
                eventType = "issue.created",
                deliveryId = "d-1",
                payloadBody = payload,
            )

        assertTrue(result is WebhookDispatchResult.Failed)
        assertEquals(500, (result as WebhookDispatchResult.Failed).code)
    }

    @Test
    fun `D-e 연결 불가(예외) 이면 Failed(reason, code=null)를 반환한다`() {
        // 포트 1(reserved, 미리스닝) — 연결 자체가 실패해 RestClientException 발생
        val result =
            dispatcher.dispatch(
                url = "http://127.0.0.1:1/hook",
                secret = null,
                eventType = "issue.created",
                deliveryId = "d-1",
                payloadBody = payload,
            )

        assertTrue(result is WebhookDispatchResult.Failed)
        assertNull((result as WebhookDispatchResult.Failed).code)
    }
}
