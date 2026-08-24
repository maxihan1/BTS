// /ws STOMP 핸드셰이크가 prod 조립 필터체인을 실제로 통과하는지 실 HTTP 로 검증 (FR-NT-02)

package com.bts.app

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus

/**
 * prod 조립 컨텍스트에서 `/ws` STOMP 핸드셰이크가 중앙 [com.atlas.bts.identity.config.SecurityConfig]
 * 필터체인을 **실제로 통과**하는지 검증한다 (FR-NT-02).
 *
 * ## 왜 이 테스트만이 관문인가 — 두 설정이 서로를 확인하지 않았다
 * notification BC 의 `WebSocketConfig` 는 `/ws` 가 **HTTP 계층에서는 열려 있다**고 전제하고 인증을
 * STOMP `CONNECT` frame 의 `Authorization: Bearer` 로 미룬다(`StompAuthChannelInterceptor` 단독 책임).
 * 그런데 중앙 `SecurityConfig` 에는 그 permitAll 이 없어 `anyRequest().authenticated()` 가 먼저 잡았고,
 * 브라우저는 업그레이드에서 401 을 받아 아래 오류만 남겼다 —
 * `WebSocket connection to 'wss://…/ws' failed: HTTP Authentication failed; no valid credentials available`.
 * 두 모듈 어느 테스트도 상대 설정을 읽지 않아 유닛은 전부 초록인데 **프로덕션 실시간 알림만 전 화면에서
 * 죽어 있었다**(저장소 지배 결함 양식 — 두 목록이 서로를 확인하지 않는다).
 *
 * ## ★ 「401 이 아님」에 기대지 않는다 — 양성 판별자는 400 이다
 * `/ws` 에 업그레이드 헤더 없이 평범한 GET 을 보내면, **필터를 통과해 WebSocket 핸들러까지 닿았을 때만**
 * Spring 의 핸드셰이크 처리기가 400 Bad Request 로 거절한다("Can \"Upgrade\" only to \"WebSocket\"").
 * 즉 400 은 「필터체인 통과」의 **양성 증명**이다.
 *
 * 반대로 401 에 기대면 공허해진다 — permitAll 이 죽어 필터가 자른 401 과, permitAll 통과 후 핸들러
 * 부재로 `/error` 에서 나는 401 은 상태코드도 `WWW-Authenticate: Bearer` 헤더도 **같다**
 * ([GitWebhookInboundPermitAllTest] KDoc 의 실측 기록). 그래서 이 테스트는 400 만 본다.
 *
 * ## 인증이 사라진 것이 아니다
 * 열린 것은 핸드셰이크뿐이다. CONNECT frame 의 JWT 검증은 `StompAuthChannelInterceptor` 가 그대로 지며
 * (헤더 부재·형식 오류·decode 실패·subject 부재·PAT 거부, 클라이언트발 SEND 도 차단), 그 검증은
 * `:modules:notification` 의 `StompAuthChannelInterceptorTest` 가 별도로 지킨다. 두 테스트는 함께 읽는다.
 */
class WebSocketHandshakePermitAllTest : ProdAssemblyHttpTestBase() {
    @Test
    fun `ws 핸드셰이크는 필터체인을 통과해 WebSocket 핸들러에 닿는다 (400, 401 아님)`() {
        val response = rest.getForEntity("/ws", String::class.java)

        assertThat(response.statusCode)
            .withFailMessage(
                "GET /ws 가 %s 입니다. 400(업그레이드 헤더 없음)이라야 필터체인을 통과해 WebSocket " +
                    "핸들러까지 닿았다는 뜻입니다. 401 이면 SecurityConfig 의 WS_HANDSHAKE_PATH permitAll 이 " +
                    "빠져 브라우저 업그레이드가 핸드셰이크에서 죽습니다(실시간 알림 전면 중단).",
                response.statusCode,
            )
            .isEqualTo(HttpStatus.BAD_REQUEST)
    }
}
