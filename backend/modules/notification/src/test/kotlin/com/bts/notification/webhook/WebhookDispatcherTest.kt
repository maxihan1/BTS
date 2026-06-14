// WebhookDispatcher 단위 테스트 — JDK 내장 HttpServer 로컬 stub, SSRF 차단, 리다이렉트 비추적 검증

package com.bts.notification.webhook

import com.bts.notification.config.WebhookHttpClientConfig
import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpServer
import io.kotest.core.spec.style.DescribeSpec
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * [WebhookDispatcher] 단위 테스트.
 *
 * JDK 내장 [HttpServer]를 로컬 stub 서버로 사용 — 신규 의존성 0.
 * stub 서버가 127.0.0.1에서 동작하므로 URL 검증은 MockK stub으로 대체한다.
 * SSRF 차단 자체는 [WebhookUrlValidatorTest]에서 별도 검증한다.
 *
 * ### 검증 항목
 * - D-1. 2xx 응답 → Sent, 요청 method=POST, Content-Type=application/json, 엔벨로프 body 검증
 * - D-2. 5xx 응답 → Failed
 * - D-3. 3xx(302) → 리다이렉트 미추적, Failed, 타깃 서버 hit=0 (FR8 리다이렉트 차단)
 * - D-4. SSRF 차단 URL → Rejected, stub hit=0 (전송 안 함)
 * - D-5. blank URL → Rejected
 * - D-6. 비-http 스킴 → Rejected
 * - D-7. method=PUT → 실제 PUT 전송 검증
 * - D-8. 알 수 없는 method → POST fallback
 */
class WebhookDispatcherTest : DescribeSpec({

    // 로컬 stub 서버 — 실제 HTTP 요청을 수신
    val hitCount = AtomicInteger(0)
    val lastMethod = AtomicReference<String>()
    val lastBody = AtomicReference<String>()
    val lastContentType = AtomicReference<String>()

    val stubServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    var stubStatusCode = 200

    stubServer.createContext("/hook") { exchange ->
        hitCount.incrementAndGet()
        lastMethod.set(exchange.requestMethod)
        lastContentType.set(exchange.requestHeaders.getFirst("Content-Type"))
        val body = exchange.requestBody.bufferedReader().readText()
        lastBody.set(body)
        val responseBytes = "OK".toByteArray()
        exchange.sendResponseHeaders(stubStatusCode, responseBytes.size.toLong())
        exchange.responseBody.use { it.write(responseBytes) }
    }

    // 리다이렉트 타깃 서버 (D-3 검증용 — 이 서버에 요청이 오면 안 됨)
    val redirectTargetHits = AtomicInteger(0)
    val targetServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    targetServer.createContext("/target") { exchange ->
        redirectTargetHits.incrementAndGet()
        val responseBytes = "target".toByteArray()
        exchange.sendResponseHeaders(200, responseBytes.size.toLong())
        exchange.responseBody.use { it.write(responseBytes) }
    }

    // 302 리다이렉트 서버
    val redirectServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

    stubServer.executor = null
    targetServer.executor = null
    redirectServer.executor = null
    stubServer.start()
    targetServer.start()
    redirectServer.start()

    val stubPort = stubServer.address.port
    val redirectPort = redirectServer.address.port
    val targetPort = targetServer.address.port

    redirectServer.createContext("/redirect") { exchange ->
        exchange.responseHeaders.add("Location", "http://127.0.0.1:$targetPort/target")
        exchange.sendResponseHeaders(302, -1)
        exchange.responseBody.close()
    }

    val objectMapper = ObjectMapper()

    // validator를 mock — stub 서버(127.0.0.1)는 Allowed로, SSRF 테스트 URL은 Blocked로 설정
    val validator = mockk<WebhookUrlValidator>()
    val restClient = WebhookHttpClientConfig().webhookRestClient()
    val dispatcher = WebhookDispatcher(validator, restClient, objectMapper)

    // stub 서버 URL → Allowed (SSRF 검증은 WebhookUrlValidatorTest에서 별도 검증)
    every {
        validator.check(match { it.contains(":$stubPort/") || it.contains(":$redirectPort/") })
    } returns UrlCheck.Allowed

    // SSRF 차단 URL → Blocked
    every { validator.check("http://192.168.99.99/hook") } returns UrlCheck.Blocked("내부망 차단")
    // blank URL → Malformed
    every { validator.check("") } returns UrlCheck.Malformed("URL이 비어 있습니다")
    // 비-http 스킴 → Blocked
    every { validator.check("ftp://host/path") } returns UrlCheck.Blocked("비-http 스킴")

    beforeEach {
        hitCount.set(0)
        lastMethod.set(null)
        lastBody.set(null)
        lastContentType.set(null)
        redirectTargetHits.set(0)
        stubStatusCode = 200
    }

    afterSpec {
        stubServer.stop(0)
        targetServer.stop(0)
        redirectServer.stop(0)
    }

    describe("D-1 2xx 응답 — Sent + 요청 형식 검증") {
        it("2xx 응답이면 Sent를 반환한다") {
            val result = dispatcher.dispatch("http://127.0.0.1:$stubPort/hook", "POST", "PROJ-1")
            assertThat(result).isInstanceOf(WebhookDispatchResult.Sent::class.java)
        }

        it("stub 서버에 정확히 1번 요청한다") {
            dispatcher.dispatch("http://127.0.0.1:$stubPort/hook", "POST", "PROJ-1")
            assertThat(hitCount.get()).isEqualTo(1)
        }

        it("요청 method는 POST이다") {
            dispatcher.dispatch("http://127.0.0.1:$stubPort/hook", "POST", "PROJ-1")
            assertThat(lastMethod.get()).isEqualTo("POST")
        }

        it("Content-Type 헤더가 application/json을 포함한다") {
            dispatcher.dispatch("http://127.0.0.1:$stubPort/hook", "POST", "PROJ-1")
            assertThat(lastContentType.get()).contains("application/json")
        }

        it("body가 event=WebhookRequested, issueKey=PROJ-1를 포함한다") {
            dispatcher.dispatch("http://127.0.0.1:$stubPort/hook", "POST", "PROJ-1")
            val body = objectMapper.readTree(lastBody.get())
            assertThat(body.path("event").asText()).isEqualTo("WebhookRequested")
            assertThat(body.path("issueKey").asText()).isEqualTo("PROJ-1")
        }
    }

    describe("D-2 5xx 응답 — Failed") {
        it("5xx 응답이면 Failed를 반환한다") {
            stubStatusCode = 500
            val result = dispatcher.dispatch("http://127.0.0.1:$stubPort/hook", "POST", "PROJ-1")
            assertThat(result).isInstanceOf(WebhookDispatchResult.Failed::class.java)
        }
    }

    describe("D-3 3xx 리다이렉트 — 미추적, Failed (FR8)") {
        it("302 응답이면 Failed를 반환한다 (리다이렉트 비추적)") {
            val result = dispatcher.dispatch("http://127.0.0.1:$redirectPort/redirect", "POST", "PROJ-1")
            assertThat(result).isInstanceOf(WebhookDispatchResult.Failed::class.java)
        }

        it("리다이렉트 타깃 서버에는 요청이 오지 않는다 (hit=0)") {
            dispatcher.dispatch("http://127.0.0.1:$redirectPort/redirect", "POST", "PROJ-1")
            assertThat(redirectTargetHits.get()).isEqualTo(0)
        }
    }

    describe("D-4 SSRF 차단 URL — Rejected, 전송 안 함") {
        it("SSRF 차단 URL이면 Rejected를 반환한다") {
            val result = dispatcher.dispatch("http://192.168.99.99/hook", "POST", "PROJ-1")
            assertThat(result).isInstanceOf(WebhookDispatchResult.Rejected::class.java)
        }

        it("SSRF 차단 URL이면 stub 서버에 요청하지 않는다 (hit=0)") {
            dispatcher.dispatch("http://192.168.99.99/hook", "POST", "PROJ-1")
            assertThat(hitCount.get()).isEqualTo(0)
        }
    }

    describe("D-5 blank URL — Rejected") {
        it("blank URL이면 Rejected를 반환한다") {
            val result = dispatcher.dispatch("", "POST", "PROJ-1")
            assertThat(result).isInstanceOf(WebhookDispatchResult.Rejected::class.java)
        }
    }

    describe("D-6 비-http 스킴 — Rejected") {
        it("ftp 스킴이면 Rejected를 반환한다") {
            val result = dispatcher.dispatch("ftp://host/path", "POST", "PROJ-1")
            assertThat(result).isInstanceOf(WebhookDispatchResult.Rejected::class.java)
        }
    }

    describe("D-7 method=PUT — 실제 PUT 전송") {
        it("method=PUT이면 PUT으로 전송한다") {
            dispatcher.dispatch("http://127.0.0.1:$stubPort/hook", "PUT", "PROJ-1")
            assertThat(lastMethod.get()).isEqualTo("PUT")
        }
    }

    describe("D-8 알 수 없는 method — POST fallback") {
        it("알 수 없는 method이면 POST로 전송한다") {
            dispatcher.dispatch("http://127.0.0.1:$stubPort/hook", "UNKNOWN", "PROJ-1")
            assertThat(lastMethod.get()).isEqualTo("POST")
        }
    }
})
