// WebhookActionClient 단위 테스트 — SSRF 차단, 2xx/5xx/타임아웃, 리다이렉트 미추종 검증 (FR-AT-02 Task 8)

package com.bts.automation.adapter

import com.bts.shared.http.OutboundHttpClientConfig
import com.bts.shared.http.OutboundUrlValidator
import com.bts.shared.http.UrlCheck
import com.sun.net.httpserver.HttpServer
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * [WebhookActionClient] 단위 테스트 (FR-AT-02 Task 8).
 *
 * search-export-import `SearchWebhookDispatcherTest`(FR-API-03)와 동일한 방식으로 JDK 내장
 * [HttpServer]를 로컬 stub 서버로 사용한다 — 신규 의존성 0. SSRF 검증([OutboundUrlValidator])만
 * MockK로 대체하고, 실제 [org.springframework.web.client.RestClient]로 HTTP 요청을 검증한다.
 *
 * ### 검증 항목
 * - D-a. SSRF 차단(Blocked) URL → success=false, 전송 안 함(hit=0)
 * - D-b. malformed URL → success=false, 전송 안 함(hit=0)
 * - D-c. 2xx 응답 → success=true, statusCode 보존, error=null
 * - D-d. 5xx 응답 → success=false, statusCode 보존
 * - D-e. 타임아웃(read timeout 초과) → success=false, statusCode=null, error 존재
 * - D-f. 3xx 리다이렉트 → 미추종, success=false, 리다이렉트 타깃 서버 hit=0
 * - D-g. 커스텀 헤더가 실제 요청에 반영된다
 * - D-h. body가 실제 요청 본문으로 전송된다
 * - D-i. 알 수 없는 method → POST로 fallback
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WebhookActionClientTest {
    private val hitCount = AtomicInteger(0)
    private val lastMethod = AtomicReference<String?>()
    private val lastBody = AtomicReference<String?>()
    private val lastCustomHeader = AtomicReference<String?>()
    private var stubStatusCode = 200
    private var stubDelayMs = 0L

    private val stubServer: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

    // 리다이렉트 타깃 서버(D-f 검증용 — 이 서버에 요청이 오면 안 됨)
    private val redirectTargetHits = AtomicInteger(0)
    private val targetServer: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val redirectServer: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

    init {
        stubServer.executor = null
        stubServer.createContext("/hook") { exchange ->
            if (stubDelayMs > 0) Thread.sleep(stubDelayMs)
            hitCount.incrementAndGet()
            lastMethod.set(exchange.requestMethod)
            lastCustomHeader.set(exchange.requestHeaders.getFirst("X-Custom-Header"))
            lastBody.set(exchange.requestBody.bufferedReader().readText())
            val responseBytes = "OK".toByteArray()
            exchange.sendResponseHeaders(stubStatusCode, responseBytes.size.toLong())
            exchange.responseBody.use { it.write(responseBytes) }
        }
        stubServer.start()

        targetServer.executor = null
        targetServer.createContext("/target") { exchange ->
            redirectTargetHits.incrementAndGet()
            val responseBytes = "target".toByteArray()
            exchange.sendResponseHeaders(200, responseBytes.size.toLong())
            exchange.responseBody.use { it.write(responseBytes) }
        }
        targetServer.start()

        redirectServer.executor = null
        redirectServer.createContext("/redirect") { exchange ->
            exchange.responseHeaders.add("Location", "http://127.0.0.1:${targetServer.address.port}/target")
            exchange.sendResponseHeaders(302, -1)
            exchange.responseBody.close()
        }
        redirectServer.start()
    }

    @AfterAll
    fun stopServers() {
        stubServer.stop(0)
        targetServer.stop(0)
        redirectServer.stop(0)
    }

    private val stubPort get() = stubServer.address.port
    private val stubUrl get() = "http://127.0.0.1:$stubPort/hook"
    private val redirectUrl get() = "http://127.0.0.1:${redirectServer.address.port}/redirect"

    private val validator = mockk<OutboundUrlValidator>()

    // 기본 타임아웃 클라이언트(happy path) — D-e 는 짧은 타임아웃 클라이언트를 별도로 구성한다.
    private val restClient = OutboundHttpClientConfig().outboundHttpRestClient()
    private val client = WebhookActionClient(validator, restClient)

    // D-e 전용 — read timeout 200ms로 짧게 구성해 테스트 시간을 절약한다.
    private val shortTimeoutRestClient =
        OutboundHttpClientConfig().outboundHttpRestClient(connectTimeoutMs = 200L, readTimeoutMs = 200L)
    private val shortTimeoutClient = WebhookActionClient(validator, shortTimeoutRestClient)

    @BeforeEach
    fun resetState() {
        hitCount.set(0)
        lastMethod.set(null)
        lastBody.set(null)
        lastCustomHeader.set(null)
        redirectTargetHits.set(0)
        stubStatusCode = 200
        stubDelayMs = 0

        every { validator.check(stubUrl) } returns UrlCheck.Allowed
        every { validator.check(redirectUrl) } returns UrlCheck.Allowed
        every { validator.check("http://192.168.99.99/hook") } returns UrlCheck.Blocked("내부망 차단")
        every { validator.check("") } returns UrlCheck.Malformed("URL이 비어 있습니다")
    }

    @Test
    fun `D-a SSRF 차단 URL이면 success=false를 반환하고 전송하지 않는다`() {
        val result = client.call("http://192.168.99.99/hook", "POST", emptyMap(), null)

        assertThat(result.success).isFalse()
        assertThat(result.statusCode).isNull()
        assertThat(hitCount.get()).isEqualTo(0)
    }

    @Test
    fun `D-b malformed URL이면 success=false를 반환하고 전송하지 않는다`() {
        val result = client.call("", "POST", emptyMap(), null)

        assertThat(result.success).isFalse()
        assertThat(hitCount.get()).isEqualTo(0)
    }

    @Test
    fun `D-c 2xx 응답이면 success=true, statusCode 보존, error=null`() {
        stubStatusCode = 200

        val result = client.call(stubUrl, "POST", emptyMap(), null)

        assertThat(result.success).isTrue()
        assertThat(result.statusCode).isEqualTo(200)
        assertThat(result.error).isNull()
    }

    @Test
    fun `D-d 5xx 응답이면 success=false, statusCode 보존`() {
        stubStatusCode = 500

        val result = client.call(stubUrl, "POST", emptyMap(), null)

        assertThat(result.success).isFalse()
        assertThat(result.statusCode).isEqualTo(500)
    }

    @Test
    fun `D-e 읽기 타임아웃 초과이면 success=false, statusCode=null, error 존재`() {
        every { validator.check(stubUrl) } returns UrlCheck.Allowed
        stubDelayMs = 1000L

        val result = shortTimeoutClient.call(stubUrl, "POST", emptyMap(), null)

        assertThat(result.success).isFalse()
        assertThat(result.statusCode).isNull()
        assertThat(result.error).isNotNull()
    }

    @Test
    fun `D-f 3xx 리다이렉트는 미추종하며 success=false, 타깃 서버 hit=0`() {
        val result = client.call(redirectUrl, "POST", emptyMap(), null)

        assertThat(result.success).isFalse()
        assertThat(redirectTargetHits.get()).isEqualTo(0)
    }

    @Test
    fun `D-g 커스텀 헤더가 실제 요청에 반영된다`() {
        client.call(stubUrl, "POST", mapOf("X-Custom-Header" to "abc123"), null)

        assertThat(lastCustomHeader.get()).isEqualTo("abc123")
    }

    @Test
    fun `D-h body가 실제 요청 본문으로 전송된다`() {
        client.call(stubUrl, "POST", emptyMap(), """{"key":"value"}""")

        assertThat(lastBody.get()).isEqualTo("""{"key":"value"}""")
    }

    @Test
    fun `D-i 알 수 없는 method이면 POST로 fallback한다`() {
        client.call(stubUrl, "UNKNOWN", emptyMap(), null)

        assertThat(lastMethod.get()).isEqualTo("POST")
    }
}
