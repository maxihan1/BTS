// prod 조립이 프록시가 넘긴 X-Forwarded-Proto 를 믿어 요청을 https 로 보는지 실 HTTP 로 검증 (OPS-F5-09)

package com.bts.app

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod

/**
 * 운영 경로는 host Caddy(TLS 종단) → nginx(http) → backend 다. backend 가 받는 연결은 http 라,
 * `server.forward-headers-strategy` 가 없으면 Spring 이 `X-Forwarded-Proto: https` 를 무시하고
 * `{baseUrl}` 을 `http://` 로 만든다 — OIDC `redirect_uri` · SAML ACS 가 http 로 나가 IdP 가 거절했다(2026-10-27).
 *
 * ## 왜 HSTS 헤더로 보나
 * OIDC 시작 경로는 Auth_platform 등록(issuer discovery)이 있어야 열려 이 컨텍스트에서 못 탄다. 대신 Spring Security
 * 기본 HSTS 는 `request.isSecure` 일 때만 붙으므로, 같은 판정(`{baseUrl}` 의 scheme 을 정하는 것)을 실 Tomcat 에서
 * 양쪽으로 관측할 수 있다. 테스트 클라이언트는 루프백에서 붙으므로 Tomcat RemoteIpValve 가 믿는 프록시 자리다.
 */
class ForwardedProtoProdHttpTest : ProdAssemblyHttpTestBase() {
    @Test
    fun `프록시가 X-Forwarded-Proto https 를 넘기면 요청을 https 로 본다`() {
        val headers = HttpHeaders().apply { set("X-Forwarded-Proto", "https") }

        val response = rest.exchange("/actuator/health", HttpMethod.GET, HttpEntity<Void>(headers), String::class.java)

        assertThat(response.headers.getFirst("Strict-Transport-Security")).isNotNull()
    }

    @Test
    fun `X-Forwarded-Proto 가 없으면 http 그대로다`() {
        val response = rest.getForEntity("/actuator/health", String::class.java)

        assertThat(response.headers.getFirst("Strict-Transport-Security")).isNull()
    }
}
