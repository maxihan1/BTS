// OutboundHttpClientConfig 단위 테스트 — 무인자 팩토리 RestClient 생성 및 리다이렉트 NEVER 정책 검증

package com.bts.shared.http

import com.sun.net.httpserver.HttpServer
import io.kotest.core.spec.style.DescribeSpec
import org.assertj.core.api.Assertions.assertThat
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger

/**
 * [OutboundHttpClientConfig] 단위 테스트.
 *
 * JDK 내장 [HttpServer]를 로컬 stub 으로 사용 — 신규 의존성 0 (notification WebhookDispatcherTest 선례).
 *
 * ### 검증 항목
 * - C-1. 무인자 팩토리 [OutboundHttpClientConfig.outboundHttpRestClient] 가 non-null RestClient 를 반환한다.
 * - C-2. 리다이렉트 정책이 NEVER 로 구성되어 3xx 응답을 자동으로 따라가지 않는다 (타깃 서버 hit=0).
 */
class OutboundHttpClientConfigTest : DescribeSpec({

    describe("C-1 무인자 팩토리") {
        it("non-null RestClient를 반환한다") {
            val restClient = OutboundHttpClientConfig().outboundHttpRestClient()
            assertThat(restClient).isNotNull()
        }
    }

    describe("C-2 리다이렉트 정책 NEVER") {
        it("3xx 응답을 자동으로 따라가지 않고 원 응답(302)을 그대로 반환한다") {
            val targetHits = AtomicInteger(0)
            val targetServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            targetServer.executor = null
            targetServer.createContext("/target") { exchange ->
                targetHits.incrementAndGet()
                val body = "target".toByteArray()
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            targetServer.start()
            val targetPort = targetServer.address.port

            val redirectServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            redirectServer.executor = null
            redirectServer.createContext("/redirect") { exchange ->
                exchange.responseHeaders.add("Location", "http://127.0.0.1:$targetPort/target")
                exchange.sendResponseHeaders(302, -1)
                exchange.responseBody.close()
            }
            redirectServer.start()
            val redirectPort = redirectServer.address.port

            try {
                val restClient = OutboundHttpClientConfig().outboundHttpRestClient()
                val statusCode =
                    restClient.get()
                        .uri("http://127.0.0.1:$redirectPort/redirect")
                        .exchange { _, response -> response.statusCode.value() }

                assertThat(statusCode).isEqualTo(302)
                assertThat(targetHits.get()).isEqualTo(0)
            } finally {
                redirectServer.stop(0)
                targetServer.stop(0)
            }
        }
    }
})
