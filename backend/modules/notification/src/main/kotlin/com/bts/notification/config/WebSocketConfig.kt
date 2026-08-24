// STOMP over WebSocket 서버 설정 — 엔드포인트/브로커/인바운드 채널 인증 (FR-NT-02 Task 9)

package com.bts.notification.config

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Configuration
import org.springframework.messaging.simp.config.ChannelRegistration
import org.springframework.messaging.simp.config.MessageBrokerRegistry
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker
import org.springframework.web.socket.config.annotation.StompEndpointRegistry
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration

/**
 * 인앱 알림 실시간 푸시를 위한 STOMP over WebSocket 서버 설정 (FR-NT-02).
 *
 * ## 구성
 * - **엔드포인트** `/ws` — 순수 WebSocket (브라우저 native WebSocket 사용, SockJS fallback 불요).
 * - **simple broker** `/queue` — 사용자별 destination 으로 알림을 push 한다.
 * - **user destination prefix** `/user` — `convertAndSendToUser(userId, "/queue/...")` 가
 *   `/user/{userId}/queue/...` 로 라우팅된다. userId 는 [StompAuthChannelInterceptor] 가
 *   설정한 Principal.name(JWT subject) 이다.
 * - **client inbound channel** — [StompAuthChannelInterceptor] 를 등록해 CONNECT 시 JWT 를
 *   검증하고 Principal 을 박제한다.
 *
 * ## 인증
 * BTS 는 STATELESS + JWT Bearer 라 세션 쿠키 인증이 불가하다. WebSocket 인증은 CONNECT frame 의
 * native header `Authorization: Bearer <accessToken>` 로 기존 JWT 를 재사용한다.
 * 검증 책임은 [StompAuthChannelInterceptor] 가 단독으로 진다.
 *
 * @param jwtDecoder STOMP CONNECT JWT 검증에 사용할 Spring 표준 디코더 (identity-access JwtConfig 제공 빈).
 */
@Configuration
@EnableWebSocketMessageBroker
class WebSocketConfig(
    private val jwtDecoder: JwtDecoder,
    @Value("\${bts.security.cors.allowed-origins}") private val allowedOrigins: List<String>,
) : WebSocketMessageBrokerConfigurer {
    /**
     * STOMP 핸드셰이크 엔드포인트를 등록한다.
     *
     * ## 허용 출처를 명시하는 이유
     * `setAllowedOrigins` 를 부르지 않으면 Spring 기본값(동일 출처)이 적용된다. 오늘은
     * 안전하지만 그것은 우리가 적은 값이 아니라 **프레임워크 기본값**이라, 버전
     * 업그레이드가 기본값을 바꾸면 조용히 달라진다. 중앙
     * [com.atlas.bts.identity.config.SecurityConfig] 가 이 경로를 permitAll 로 열어 HTTP
     * 계층 방어선이 하나 줄었으므로(FR-NT-02), 남은 방어선을 암묵값으로 두지 않는다.
     *
     * ## 목록을 HTTP CORS 와 공유하는 이유
     * `bts.security.cors.allowed-origins` **같은 프로퍼티**를 읽는다. 브라우저가 이 소켓을
     * 여는 출처와 REST 를 호출하는 출처는 같은 SPA 하나이므로 두 목록이 갈릴 이유가 없고,
     * 갈라 두면 한쪽만 바뀌었을 때 **어느 테스트도 그 어긋남을 보지 못한다**(저장소 지배
     * 결함 양식 — 두 목록이 서로를 검사하지 않는다). 소비처는
     * [com.atlas.bts.identity.config.CorsConfig] 와 여기 둘뿐이다.
     *
     * 🛑 여기에 `"*"` 를 넣지 마라 — 인증이 CONNECT frame 의 Bearer 라 앰비언트 자격
     *    도용은 지금도 불가능하지만, 그렇다고 아무 출처에나 소켓을 여는 것이 정당해지지는
     *    않는다(CSWSH 방어선을 스스로 없애는 셈이다).
     *
     * `SpreadOperator` 억제 근거 — Spring 의 `setAllowedOrigins` 가 Java `String...` 이라
     * Kotlin 에서는 spread 가 유일한 호출 방법이다. 부팅 시 1회이고 원소는 오리진 한 줌이라
     * 복사 비용이 문제되는 자리가 아니다(BtsApplication·DashboardRepository 등 5곳이 같은
     * 이유로 같은 억제를 쓴다).
     */
    @Suppress("SpreadOperator")
    override fun registerStompEndpoints(registry: StompEndpointRegistry) {
        // 🛑 `"*"` fail-fast. CorsConfig 는 `allowCredentials = true` 라 `"*"` 를 넣으면 Spring 이
        //    런타임에 IllegalArgumentException 으로 **시끄럽게** 거절한다. 그런데 여기서는 같은
        //    값이 **조용히 전 출처 허용**이 된다 — 같은 프로퍼티를 공유하는데 실패 모드가
        //    비대칭이라, 주석 한 줄로는 못 막는다(리뷰 C3).
        require(WILDCARD_ORIGIN !in allowedOrigins) {
            "bts.security.cors.allowed-origins 에 \"$WILDCARD_ORIGIN\" 를 두지 마라 — " +
                "WebSocket 핸드셰이크는 CorsConfig 와 달리 조용히 전 출처를 허용한다. " +
                "CONNECT frame Bearer 덕에 자격 도용은 막히지만 CSWSH 방어선을 스스로 없애는 셈이다."
        }
        registry.addEndpoint(WS_ENDPOINT).setAllowedOrigins(*allowedOrigins.toTypedArray())
    }

    override fun configureMessageBroker(registry: MessageBrokerRegistry) {
        registry.enableSimpleBroker(QUEUE_PREFIX)
        registry.setUserDestinationPrefix(USER_PREFIX)
    }

    override fun configureClientInboundChannel(registration: ChannelRegistration) {
        registration.interceptors(StompAuthChannelInterceptor(jwtDecoder))
    }

    /**
     * WebSocket 전송 백프레셔 한도를 설정한다 (DoS 방어).
     *
     * 느린/악의적 클라이언트가 서버 메모리를 고갈시키지 못하도록 수신 메시지 크기·단건 송신 타임아웃·
     * 세션별 송신 버퍼를 제한한다. 한도 초과 세션은 Spring 이 닫는다. 인앱 알림 프레임은 제목+이슈키
     * 수준이라 작아 64KB 면 충분하다. 한도 미설정 시 Spring 기본값은 관대해 슬로우 클라이언트가 힙을
     * 고갈시킬 수 있다(eng-review P2 백프레셔 지적).
     *
     * @param registration WebSocket 전송 레지스트레이션
     */
    override fun configureWebSocketTransport(registration: WebSocketTransportRegistration) {
        registration.setMessageSizeLimit(MESSAGE_SIZE_LIMIT_BYTES)
        registration.setSendTimeLimit(SEND_TIME_LIMIT_MS)
        registration.setSendBufferSizeLimit(SEND_BUFFER_SIZE_LIMIT_BYTES)
        // 🛑 인증 **전** 소켓의 수명. permitAll 이전에는 CONNECT 를 안 보내는 익명 클라이언트가
        //    필터에서 401 로 끊겨 세션 자원을 한 톨도 잡지 못했다. 이제는 업그레이드가 성립해
        //    CONNECT 전에 커넥션·세션 엔트리가 할당된다 — 위 세 한도와 **같은 이유로** 이 값도
        //    프레임워크 기본값(60초)에 맡기지 않고 명시한다(리뷰 C2).
        registration.setTimeToFirstMessage(TIME_TO_FIRST_MESSAGE_MS)
    }

    private companion object {
        const val WS_ENDPOINT = "/ws"
        const val QUEUE_PREFIX = "/queue"
        const val USER_PREFIX = "/user"

        /** 수신 STOMP 메시지 최대 크기 (64KB) — 인앱 알림 프레임은 작아 충분. 초과 시 세션 종료. */
        const val MESSAGE_SIZE_LIMIT_BYTES = 64 * 1024

        /** 핸드셰이크 허용 출처에 넣으면 안 되는 와일드카드 — [registerStompEndpoints] 가 거부한다. */
        const val WILDCARD_ORIGIN = "*"

        /**
         * 업그레이드 후 첫 STOMP 메시지(CONNECT)까지 허용하는 시간 (30초).
         *
         * 클라이언트는 소켓이 열리자마자 CONNECT 를 보내므로 30초는 아주 느린 모바일 회선에도
         * 여유가 있다. Spring 기본값 60초의 절반으로 잡아 **인증 전 유휴 소켓**이 자원을 붙들고
         * 있는 창을 좁힌다 — 값 자체는 판단이고, 요점은 그 판단이 우리 코드에 적혀 있다는 것이다.
         */
        const val TIME_TO_FIRST_MESSAGE_MS = 30 * 1000

        /** 단건 메시지 송신 제한 시간 (10초) — 느린 클라이언트의 송신 스레드 점유 방지. */
        const val SEND_TIME_LIMIT_MS = 10 * 1000

        /** 세션별 송신 버퍼 상한 (512KB) — 백프레셔. 초과 시 세션 종료해 메모리 고갈 차단. */
        const val SEND_BUFFER_SIZE_LIMIT_BYTES = 512 * 1024
    }
}
