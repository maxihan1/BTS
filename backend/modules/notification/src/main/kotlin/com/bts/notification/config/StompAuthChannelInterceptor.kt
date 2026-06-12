// STOMP CONNECT 시 JWT 를 검증해 Principal 을 설정하는 ChannelInterceptor (FR-NT-02 Task 9)

package com.bts.notification.config

import org.springframework.messaging.Message
import org.springframework.messaging.MessageChannel
import org.springframework.messaging.simp.stomp.StompCommand
import org.springframework.messaging.simp.stomp.StompHeaderAccessor
import org.springframework.messaging.support.ChannelInterceptor
import org.springframework.security.oauth2.jwt.JwtDecoder

/**
 * RED 스텁 — 의도적으로 모든 CONNECT 를 거부(fail-closed)한다.
 *
 * GREEN 단계에서 정식 JWT 검증/Principal 설정 로직으로 대체한다. 보안 절대 규칙상
 * "항상 통과"하는 우회 스텁을 두지 않고 "항상 거부" 방향으로 둔다.
 */
class StompAuthChannelInterceptor(
    @Suppress("unused") private val jwtDecoder: JwtDecoder,
) : ChannelInterceptor {
    override fun preSend(
        message: Message<*>,
        channel: MessageChannel,
    ): Message<*> {
        val accessor = StompHeaderAccessor.wrap(message)
        if (StompCommand.CONNECT != accessor.command) {
            return message
        }
        throw StompAuthenticationException("RED stub — not implemented")
    }
}

/** STOMP CONNECT 인증 실패를 나타내는 예외. */
class StompAuthenticationException(message: String) : RuntimeException(message)
