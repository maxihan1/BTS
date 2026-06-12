// WebSocketConfig 단위 테스트 — STOMP 전송 백프레셔(DoS 방어) 한도 설정 검증 (FR-NT-02 P2)

package com.bts.notification.config

import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration

/**
 * [WebSocketConfig] 단위 테스트.
 *
 * STOMP 전송 백프레셔(DoS 방어) 한도가 설정되는지 검증한다 — 메시지 크기·송신 타임아웃·송신 버퍼.
 * 값이 의도치 않게 바뀌거나 제거되면 슬로우 클라이언트 힙 고갈에 노출되므로 회귀를 차단한다.
 */
class WebSocketConfigTest {
    private val jwtDecoder = mockk<JwtDecoder>()
    private val config = WebSocketConfig(jwtDecoder)

    @Suppress("MagicNumber") // 검증 대상이 곧 한도 값 자체 — 의미를 가리지 않게 직접 표기
    @Test
    fun `configureWebSocketTransport 는 메시지 크기·송신 타임아웃·송신 버퍼 한도를 설정한다`() {
        val registration = mockk<WebSocketTransportRegistration>(relaxed = true)

        config.configureWebSocketTransport(registration)

        verify { registration.setMessageSizeLimit(64 * 1024) }
        verify { registration.setSendTimeLimit(10 * 1000) }
        verify { registration.setSendBufferSizeLimit(512 * 1024) }
    }
}
