// StompAuthChannelInterceptor 단위 테스트 — CONNECT JWT 검증/Principal 설정 + PAT·무효토큰 거부 (FR-NT-02 Task 9)

package com.bts.notification.config

import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.messaging.Message
import org.springframework.messaging.MessageChannel
import org.springframework.messaging.simp.stomp.StompCommand
import org.springframework.messaging.simp.stomp.StompHeaderAccessor
import org.springframework.messaging.support.MessageBuilder
import org.springframework.security.oauth2.jwt.BadJwtException
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtException
import java.time.Instant
import java.util.UUID

/**
 * [StompAuthChannelInterceptor] 단위 테스트.
 *
 * STOMP CONNECT frame 을 [StompHeaderAccessor] 로 구성하고 [JwtDecoder] 를 MockK 로 stub 한다.
 *
 * ### 테스트 케이스
 * - CONNECT-1. 유효 JWT → Principal(name=userId subject) 설정 + 통과
 * - CONNECT-2. Authorization 헤더 없음 → 거부(예외)
 * - CONNECT-3. PAT(pat_ prefix) 토큰 → 거부(JWT 전용, decode 호출 안 함)
 * - CONNECT-4. 만료/무효 JWT(decode 예외) → 거부
 * - CONNECT-5. Bearer prefix 없는 헤더 → 거부
 * - SEND. 클라이언트 SEND → 거부(인앱 알림은 푸시 전용, 위조 주입 차단)
 * - SUBSCRIBE. 검증 없이 통과(이미 인증된 세션)
 */
class StompAuthChannelInterceptorTest {
    private val jwtDecoder = mockk<JwtDecoder>()
    private val interceptor = StompAuthChannelInterceptor(jwtDecoder)
    private val channel = mockk<MessageChannel>(relaxed = true)

    private val userId = UUID.fromString("11111111-1111-1111-1111-111111111111")
    private val accessToken = "header.payload.signature"

    /** subject(=userId) claim 을 가진 유효 JWT stub. */
    private fun validJwt(subject: String): Jwt =
        Jwt.withTokenValue(accessToken)
            .header("alg", "RS256")
            .subject(subject)
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(900))
            .build()

    /** 지정 native header 를 가진 CONNECT frame Message 를 만든다. */
    private fun connectMessage(authorization: String?): Message<ByteArray> {
        val accessor = StompHeaderAccessor.create(StompCommand.CONNECT)
        if (authorization != null) {
            accessor.setNativeHeader("Authorization", authorization)
        }
        accessor.setLeaveMutable(true)
        return MessageBuilder.createMessage(ByteArray(0), accessor.messageHeaders)
    }

    /** CONNECT 가 아닌 STOMP command frame. */
    private fun frameMessage(command: StompCommand): Message<ByteArray> {
        val accessor = StompHeaderAccessor.create(command)
        accessor.setLeaveMutable(true)
        return MessageBuilder.createMessage(ByteArray(0), accessor.messageHeaders)
    }

    @Test
    fun `CONNECT-1 유효 JWT 면 Principal name 에 userId subject 를 설정하고 통과시킨다`() {
        every { jwtDecoder.decode(accessToken) } returns validJwt(userId.toString())

        val result = interceptor.preSend(connectMessage("Bearer $accessToken"), channel)

        assertThat(result).isNotNull
        val accessor = StompHeaderAccessor.wrap(result!!)
        assertThat(accessor.user).isNotNull
        assertThat(accessor.user!!.name).isEqualTo(userId.toString())
    }

    @Test
    fun `CONNECT-2 Authorization 헤더가 없으면 거부한다`() {
        assertThatThrownBy {
            interceptor.preSend(connectMessage(null), channel)
        }.isInstanceOf(StompAuthenticationException::class.java)
    }

    @Test
    fun `CONNECT-3 PAT 토큰이면 decode 없이 거부한다`() {
        val pat = "pat_0123456789abcdef0123456789abcdef0123456789abcdef"

        assertThatThrownBy {
            interceptor.preSend(connectMessage("Bearer $pat"), channel)
        }.isInstanceOf(StompAuthenticationException::class.java)
    }

    @Test
    fun `CONNECT-4 만료 또는 무효 JWT 면 거부한다`() {
        every { jwtDecoder.decode(accessToken) } throws BadJwtException("expired")

        assertThatThrownBy {
            interceptor.preSend(connectMessage("Bearer $accessToken"), channel)
        }.isInstanceOf(StompAuthenticationException::class.java)
    }

    @Test
    fun `CONNECT-4b 일반 JwtException 도 거부로 수렴한다`() {
        every { jwtDecoder.decode(accessToken) } throws JwtException("decode failed")

        assertThatThrownBy {
            interceptor.preSend(connectMessage("Bearer $accessToken"), channel)
        }.isInstanceOf(StompAuthenticationException::class.java)
    }

    @Test
    fun `CONNECT-5 Bearer prefix 가 없으면 거부한다`() {
        assertThatThrownBy {
            interceptor.preSend(connectMessage(accessToken), channel)
        }.isInstanceOf(StompAuthenticationException::class.java)
    }

    @Test
    fun `CONNECT-6 subject 가 비어 있으면 거부한다`() {
        every { jwtDecoder.decode(accessToken) } returns
            Jwt.withTokenValue(accessToken)
                .header("alg", "RS256")
                .claim("noSub", "x")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(900))
                .build()

        assertThatThrownBy {
            interceptor.preSend(connectMessage("Bearer $accessToken"), channel)
        }.isInstanceOf(StompAuthenticationException::class.java)
    }

    @Test
    fun `SEND frame 은 거부한다 (인앱 알림은 푸시 전용, 위조 주입 차단)`() {
        assertThatThrownBy {
            interceptor.preSend(frameMessage(StompCommand.SEND), channel)
        }.isInstanceOf(StompAuthenticationException::class.java)
    }

    @Test
    fun `NON-CONNECT SUBSCRIBE frame 은 검증 없이 그대로 통과시킨다`() {
        val message = frameMessage(StompCommand.SUBSCRIBE)

        val result = interceptor.preSend(message, channel)

        assertThat(result).isSameAs(message)
    }
}
