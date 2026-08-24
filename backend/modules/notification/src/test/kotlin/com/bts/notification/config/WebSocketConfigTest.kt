// WebSocketConfig 단위 테스트 — STOMP 전송 백프레셔(DoS 방어) 한도 설정 검증 (FR-NT-02 P2)

package com.bts.notification.config

import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.web.socket.config.annotation.StompEndpointRegistry
import org.springframework.web.socket.config.annotation.StompWebSocketEndpointRegistration
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration

/**
 * [WebSocketConfig] 단위 테스트.
 *
 * STOMP 전송 백프레셔(DoS 방어) 한도가 설정되는지 검증한다 — 메시지 크기·송신 타임아웃·송신 버퍼.
 * 값이 의도치 않게 바뀌거나 제거되면 슬로우 클라이언트 힙 고갈에 노출되므로 회귀를 차단한다.
 */
class WebSocketConfigTest {
    private val jwtDecoder = mockk<JwtDecoder>()
    private val allowedOrigins = listOf("http://localhost:5173", "https://bts.example.com")
    private val config = WebSocketConfig(jwtDecoder, allowedOrigins)

    @Suppress("MagicNumber") // 검증 대상이 곧 한도 값 자체 — 의미를 가리지 않게 직접 표기
    @Test
    fun `configureWebSocketTransport 는 메시지 크기·송신 타임아웃·송신 버퍼 한도를 설정한다`() {
        val registration = mockk<WebSocketTransportRegistration>(relaxed = true)

        config.configureWebSocketTransport(registration)

        verify { registration.setMessageSizeLimit(64 * 1024) }
        verify { registration.setSendTimeLimit(10 * 1000) }
        verify { registration.setSendBufferSizeLimit(512 * 1024) }
        // 인증 전 소켓 수명 — permitAll 로 업그레이드가 성립하면서 CONNECT 전에 자원이 잡힌다.
        verify { registration.setTimeToFirstMessage(30 * 1000) }
    }

    /**
     * 핸드셰이크 허용 출처를 **소스에 명시**하는지 검증한다.
     *
     * 이 호출이 없으면 Spring 기본값(동일 출처)에 의존한다 — 오늘은 안전하지만 그것은
     * 우리가 적은 것이 아니라 프레임워크 기본값이라, 버전 업그레이드가 기본값을 바꾸면
     * 조용히 달라진다. 중앙 SecurityConfig 가 이 경로를 permitAll 로 열어 HTTP 계층
     * 방어선이 하나 줄었으므로, 남은 방어선을 암묵값으로 두지 않는다.
     *
     * 목록은 HTTP CORS 와 **같은 프로퍼티**(`bts.security.cors.allowed-origins`)를 읽는다.
     * 두 목록을 따로 두면 한쪽만 바뀌었을 때 어느 테스트도 그 어긋남을 보지 못한다.
     */
    @Test
    fun `registerStompEndpoints 는 허용 출처를 명시적으로 설정한다`() {
        val registration = mockk<StompWebSocketEndpointRegistration>(relaxed = true)
        val registry = mockk<StompEndpointRegistry>(relaxed = true)
        every { registry.addEndpoint(*anyVararg()) } returns registration

        config.registerStompEndpoints(registry)

        verify { registration.setAllowedOrigins(*allowedOrigins.toTypedArray()) }
    }

    /**
     * 허용 출처에 `"*"` 가 들어오면 부팅을 실패시킨다.
     *
     * CorsConfig 는 `allowCredentials = true` 라 Spring 이 `"*"` 를 런타임에 시끄럽게 거절하는데,
     * WebSocket 은 같은 값을 **조용히 전 출처 허용**으로 받는다. 같은 프로퍼티를 공유하면서
     * 실패 모드가 비대칭이라, 주석이 아니라 코드가 막아야 한다.
     */
    @Test
    fun `허용 출처에 와일드카드가 있으면 등록 단계에서 실패한다`() {
        val config = WebSocketConfig(jwtDecoder, listOf("http://localhost:5173", "*"))
        val registration = mockk<StompWebSocketEndpointRegistration>(relaxed = true)
        val registry = mockk<StompEndpointRegistry>(relaxed = true)
        every { registry.addEndpoint(*anyVararg()) } returns registration

        val thrown =
            assertThrows<IllegalArgumentException> { config.registerStompEndpoints(registry) }

        assertThat(thrown).hasMessageContaining("전 출처")
    }
}
