// SlackResponseUrlClient 단위 테스트 — response_url POST 페이로드·2xx/4xx/5xx/네트워크·URL 오류 best-effort 검증 (FR-SL-04 Task 4)

package com.bts.slack.message

import com.bts.shared.http.OutboundHttpClientConfig
import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * [SlackResponseUrlClient] 단위 테스트 (FR-SL-04 Task 4).
 *
 * automation `WebhookActionClientTest`(FR-AT-02 Task 8)와 동일한 방식으로 JDK 내장
 * [HttpServer]를 로컬 stub 서버로 사용한다 — 신규 의존성 0. 실제
 * [org.springframework.web.client.RestClient]([OutboundHttpClientConfig])로 HTTP 요청을 검증한다.
 *
 * ### 검증 항목
 * - 2xx 응답 → `true` 반환, `{"response_type":"ephemeral","blocks":[...],"replace_original":false}` 페이로드 정확 전송
 * - 4xx/5xx 응답 → `false` 반환(예외 미전파)
 * - 연결 거부(네트워크 오류) → `false` 반환(예외 미전파)
 * - malformed URL → `false` 반환(예외 미전파)
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SlackResponseUrlClientTest {
    private val objectMapper = ObjectMapper()

    private val hitCount = AtomicInteger(0)
    private val lastMethod = AtomicReference<String?>()
    private val lastContentType = AtomicReference<String?>()
    private val lastBody = AtomicReference<String?>()
    private var stubStatusCode = 200

    private val stubServer: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

    init {
        stubServer.executor = null
        stubServer.createContext("/commands/T1/1234/abcd") { exchange ->
            hitCount.incrementAndGet()
            lastMethod.set(exchange.requestMethod)
            lastContentType.set(exchange.requestHeaders.getFirst("Content-Type"))
            lastBody.set(exchange.requestBody.bufferedReader().readText())
            val responseBytes = "ok".toByteArray()
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
    private val responseUrl get() = "http://127.0.0.1:$stubPort/commands/T1/1234/abcd"

    private val restClient = OutboundHttpClientConfig().outboundHttpRestClient()
    private val client = SlackResponseUrlClient(objectMapper, restClient)

    private val blocks =
        objectMapper.createArrayNode().apply {
            add(objectMapper.createObjectNode().apply { put("type", "section") })
        }

    @BeforeEach
    fun resetState() {
        hitCount.set(0)
        lastMethod.set(null)
        lastContentType.set(null)
        lastBody.set(null)
        stubStatusCode = 200
    }

    @Test
    fun `2xx 응답이면 true를 반환하고 ephemeral 페이로드를 정확히 POST한다`() {
        val result = client.post(responseUrl, blocks)

        assertThat(result).isTrue()
        assertThat(hitCount.get()).isEqualTo(1)
        assertThat(lastMethod.get()).isEqualTo("POST")
        assertThat(lastContentType.get()).startsWith("application/json")

        val sentPayload = objectMapper.readTree(lastBody.get())
        assertThat(sentPayload.get("response_type").asText()).isEqualTo("ephemeral")
        assertThat(sentPayload.get("replace_original").asBoolean()).isFalse()
        assertThat(sentPayload.get("blocks")).isEqualTo(blocks)
    }

    @Test
    fun `4xx 응답이면 false를 반환한다(예외 미전파)`() {
        stubStatusCode = 400

        val result = client.post(responseUrl, blocks)

        assertThat(result).isFalse()
    }

    @Test
    fun `5xx 응답이면 false를 반환한다(예외 미전파)`() {
        stubStatusCode = 500

        val result = client.post(responseUrl, blocks)

        assertThat(result).isFalse()
    }

    @Test
    fun `연결 거부(네트워크 오류)면 예외를 전파하지 않고 false를 반환한다`() {
        val closedPort = ServerSocket(0).use { it.localPort }
        val unreachableUrl = "http://127.0.0.1:$closedPort/commands/x"

        val result = client.post(unreachableUrl, blocks)

        assertThat(result).isFalse()
        assertThat(hitCount.get()).isEqualTo(0)
    }

    @Test
    fun `malformed URL이면 예외를 전파하지 않고 false를 반환한다`() {
        val result = client.post("not a valid url", blocks)

        assertThat(result).isFalse()
        assertThat(hitCount.get()).isEqualTo(0)
    }

    @Test
    fun `허용 호스트 접미사(slack_com)가 아니면 POST하지 않고 false를 반환한다`() {
        // 기본 허용 접미사(slack.com)로 생성한 클라이언트는 loopback(127.0.0.1) 호스트로 전송하지 않는다(SSRF 심층방어, S1).
        val pinnedClient = SlackResponseUrlClient(objectMapper, restClient)

        val result = pinnedClient.post(responseUrl, blocks)

        assertThat(result).isFalse()
        assertThat(hitCount.get()).isEqualTo(0)
    }
}
