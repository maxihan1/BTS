// Keycloak Testcontainers 통합 테스트 — JWT 발급/검증 + CSRF 면역 실제 동작 확인

package com.atlas.bts.identity.integration

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType

class KeycloakIntegrationTest : KeycloakIntegrationBase() {
    // T1. spec F4 — 무인증 GET → 401
    @Test
    fun `GET whoami returns 401 without token`() {
        val resp = restTemplate.getForEntity("/api/v1/users/me/whoami", String::class.java)
        assertThat(resp.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
    }

    // T2. spec F4 — Keycloak에서 JWT 발급 + GET 200 (실제 JWT 검증 확인)
    @Test
    fun `GET whoami returns 200 with valid JWT from Keycloak`() {
        val token = obtainTokenFromKeycloak("alice", "Test1234!")
        val headers = HttpHeaders().apply { setBearerAuth(token) }
        val resp =
            restTemplate.exchange(
                "/api/v1/users/me/whoami",
                HttpMethod.GET,
                HttpEntity<Void>(headers),
                Map::class.java,
            )
        assertThat(resp.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(resp.body?.get("username")).isEqualTo("alice")
    }

    // T3. T5 CONCERN 보강 — 미인증 POST → CSRF 필터(or 인증 필터)가 먼저 차단
    // Bearer Token 방식은 stateless라 CSRF 면역이지만,
    // 미인증 요청은 인증 필터(401) 또는 CSRF 필터(403) 중 하나가 차단.
    // 실 브라우저에서는 세션 쿠키 없는 POST이므로 403이 기대값.
    // TestRestTemplate은 실제 HTTP 스택이므로 Mock과 달리 실제 필터 체인 순서가 적용.
    @Test
    fun `POST preferences returns 401 or 403 without auth`() {
        val headers = HttpHeaders().apply { contentType = MediaType.APPLICATION_JSON }
        val resp =
            restTemplate.postForEntity(
                "/api/v1/users/me/preferences",
                HttpEntity("{}", headers),
                String::class.java,
            )
        // 인증 필터가 CSRF 필터보다 먼저 평가되면 401, 아니면 403.
        // 어느 쪽이든 "접근 거부"가 핵심 — 둘 다 허용.
        assertThat(resp.statusCode).isIn(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN)
    }

    // T4. JWT + POST → 200 (CSRF 면역 확인 — Bearer Token stateless 설계)
    @Test
    fun `POST preferences returns 200 with valid JWT`() {
        val token = obtainTokenFromKeycloak("alice", "Test1234!")
        val headers =
            HttpHeaders().apply {
                setBearerAuth(token)
                contentType = MediaType.APPLICATION_JSON
            }
        val resp =
            restTemplate.exchange(
                "/api/v1/users/me/preferences",
                HttpMethod.POST,
                HttpEntity("{}", headers),
                Map::class.java,
            )
        assertThat(resp.statusCode).isEqualTo(HttpStatus.OK)
    }
}
