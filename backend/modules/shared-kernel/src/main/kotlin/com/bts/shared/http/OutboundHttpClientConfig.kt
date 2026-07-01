// 아웃바운드 HTTP 전송용 RestClient를 구성하는 Spring Configuration — 리다이렉트 비추적, 타임아웃 설정

package com.bts.shared.http

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.web.client.RestClient
import java.net.http.HttpClient
import java.time.Duration

/**
 * 아웃바운드 HTTP 전송용 [RestClient] 빈 설정.
 *
 * ## 리다이렉트 차단 (FR8 / G1)
 * JDK [HttpClient]를 [HttpClient.Redirect.NEVER]로 설정해 3xx 리다이렉트를 자동으로 따라가지 않는다.
 * SSRF 공격자가 3xx → 내부망으로 우회하는 경로를 차단한다.
 * 3xx 응답은 [WebhookDispatcher]가 non-2xx 로 처리해 [WebhookDispatchResult.Failed]를 반환한다.
 *
 * ## 타임아웃
 * - connect: [connectTimeoutMs] (기본 3000ms)
 * - read: [readTimeoutMs] (기본 5000ms)
 * VT_SECONDS=60, BATCH_SIZE=5 기준 5s×5=25s < 60s 이므로 VT 내에서 처리 완료.
 *
 * ## 신규 의존성 0
 * [HttpClient] 는 JDK 11 내장, [RestClient] 는 spring-web 내장.
 */
@Configuration(proxyBeanMethods = false)
class OutboundHttpClientConfig {
    /**
     * 아웃바운드 HTTP 전송 전용 [RestClient] 빈.
     *
     * 리다이렉트 [HttpClient.Redirect.NEVER]로 SSRF 우회를 차단한다.
     *
     * @param connectTimeoutMs HTTP 연결 타임아웃 (기본 3000ms)
     * @param readTimeoutMs HTTP 읽기 타임아웃 (기본 5000ms)
     * @return 설정된 [RestClient]
     */
    @Bean
    fun outboundHttpRestClient(
        @Value("\${bts.outbound-http.connect-timeout-ms:3000}") connectTimeoutMs: Long,
        @Value("\${bts.outbound-http.read-timeout-ms:5000}") readTimeoutMs: Long,
    ): RestClient = buildRestClient(connectTimeoutMs, readTimeoutMs)

    /**
     * 테스트 및 직접 생성 시 사용하는 팩토리 — 기본 타임아웃(connect=[DEFAULT_CONNECT_TIMEOUT_MS]ms, read=[DEFAULT_READ_TIMEOUT_MS]ms).
     *
     * @return 리다이렉트 차단 설정된 [RestClient]
     */
    fun outboundHttpRestClient(): RestClient = buildRestClient(DEFAULT_CONNECT_TIMEOUT_MS, DEFAULT_READ_TIMEOUT_MS)

    private fun buildRestClient(
        connectTimeoutMs: Long,
        readTimeoutMs: Long,
    ): RestClient {
        val httpClient =
            HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                .build()
        val requestFactory = JdkClientHttpRequestFactory(httpClient)
        requestFactory.setReadTimeout(Duration.ofMillis(readTimeoutMs))
        return RestClient.builder()
            .requestFactory(requestFactory)
            .build()
    }

    companion object {
        /** 기본 HTTP 연결 타임아웃 (ms). */
        const val DEFAULT_CONNECT_TIMEOUT_MS = 3000L

        /** 기본 HTTP 읽기 타임아웃 (ms). */
        const val DEFAULT_READ_TIMEOUT_MS = 5000L
    }
}
