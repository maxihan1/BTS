// STOMP over WebSocket 서버 설정 — 엔드포인트/브로커/인바운드 채널 인증 (FR-NT-02 Task 9)

package com.bts.notification.config

import org.springframework.context.annotation.Configuration
import org.springframework.messaging.simp.config.ChannelRegistration
import org.springframework.messaging.simp.config.MessageBrokerRegistry
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker
import org.springframework.web.socket.config.annotation.StompEndpointRegistry
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer

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
) : WebSocketMessageBrokerConfigurer {
    override fun registerStompEndpoints(registry: StompEndpointRegistry) {
        registry.addEndpoint(WS_ENDPOINT)
    }

    override fun configureMessageBroker(registry: MessageBrokerRegistry) {
        registry.enableSimpleBroker(QUEUE_PREFIX)
        registry.setUserDestinationPrefix(USER_PREFIX)
    }

    override fun configureClientInboundChannel(registration: ChannelRegistration) {
        registration.interceptors(StompAuthChannelInterceptor(jwtDecoder))
    }

    private companion object {
        const val WS_ENDPOINT = "/ws"
        const val QUEUE_PREFIX = "/queue"
        const val USER_PREFIX = "/user"
    }
}
