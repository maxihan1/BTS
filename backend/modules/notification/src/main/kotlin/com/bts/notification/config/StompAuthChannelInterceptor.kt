// STOMP CONNECT 시 JWT 를 검증해 Principal 을 설정하는 ChannelInterceptor (FR-NT-02 Task 9)

package com.bts.notification.config

import org.slf4j.LoggerFactory
import org.springframework.messaging.Message
import org.springframework.messaging.MessageChannel
import org.springframework.messaging.simp.stomp.StompCommand
import org.springframework.messaging.simp.stomp.StompHeaderAccessor
import org.springframework.messaging.support.ChannelInterceptor
import org.springframework.messaging.support.MessageHeaderAccessor
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtException

/**
 * STOMP CONNECT frame 의 JWT 를 검증하고 사용자 [java.security.Principal] 을 설정한다.
 *
 * ## 인증 모델 — JWT 재사용
 * BTS 는 `SessionCreationPolicy.STATELESS` + JWT Bearer 라 세션 쿠키로 WebSocket 을 인증할 수 없다.
 * 클라이언트는 STOMP CONNECT frame 의 native header `Authorization: Bearer <accessToken>` 로
 * 기존 Access Token 을 그대로 전달한다. 이 인터셉터가 CONNECT 시점에 한 번만 검증한 뒤
 * Principal 을 세션에 박제하므로, 이후 SUBSCRIBE/UNSUBSCRIBE/DISCONNECT frame 은 재검증 없이
 * 통과한다. 단, 인앱 알림은 서버→클라이언트 푸시 전용이라 클라이언트 SEND 는 거부한다(아래 규칙).
 *
 * ## 트레이드오프 — JWT 만료 vs 장수명 세션
 * CONNECT 1회 검증 모델상 JWT 가 만료돼도 기존 WebSocket 세션은 살아 푸시를 계속 받는다(장수명
 * 연결의 알려진 트레이드오프). 이번 PR 은 서버→클라이언트 푸시 전용이며 클라이언트 SEND 를
 * 인터셉터가 거부하므로 폭발 반경이 작다. 향후 클라이언트→서버 SEND(예: 읽음 처리)를 도입하면
 * 해당 destination 의 메시지 레벨 인가 + heartbeat 기반 토큰 재검증/세션 만료가 필요하다.
 *
 * ## 검증 규칙 (fail-closed)
 * - CONNECT 외 SUBSCRIBE/UNSUBSCRIBE/DISCONNECT 등은 통과 (이미 인증된 세션).
 * - SEND frame → 거부. @MessageMapping 핸들러가 없고 simple broker 라 클라이언트 SEND 가 브로커
 *   destination 으로 직행해 타 사용자에게 위조 알림을 주입할 수 있어 메시지 레벨에서 차단한다.
 * - `Authorization` 헤더 없음 / `Bearer ` prefix 없음 → 거부.
 * - `pat_` prefix(Personal Access Token) → 거부. WebSocket 은 JWT 전용이다.
 * - [JwtDecoder.decode] 실패(만료·서명 불일치 등) → 거부.
 * - subject(userId) 가 비어 있으면 → 거부.
 * - 위 모든 거부는 [StompAuthenticationException] 으로 수렴한다. 불명은 거부한다.
 *
 * ## BC 격리
 * identity-access 내부 타입(`com.atlas.bts.identity.*`)을 직접 import 하지 않는다.
 * Spring 표준 [JwtDecoder] 빈만 주입받는다(런타임 동일 ApplicationContext). userId 는 JWT 의
 * `sub`(subject) claim 에서 추출한다 — JwtIssuer 가 `subject(userId)` 로 발급한다.
 *
 * @param jwtDecoder Access Token 의 RS256 서명을 검증하는 Spring 표준 디코더.
 */
class StompAuthChannelInterceptor(
    private val jwtDecoder: JwtDecoder,
) : ChannelInterceptor {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun preSend(
        message: Message<*>,
        channel: MessageChannel,
    ): Message<*> {
        // 인바운드 메시지의 기존 mutable accessor 를 제자리 수정한다.
        // wrap()+재조립 패턴은 user 헤더를 세션으로 전파하지 못해 SimpUserRegistry 가 비어
        // user destination 해석이 실패한다(원인: DefaultUserDestinationResolver 가 빈 레지스트리 조회).
        val accessor =
            MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor::class.java)
                ?: return message
        when (accessor.command) {
            StompCommand.CONNECT -> accessor.user = authenticate(extractBearerToken(accessor))
            StompCommand.SEND -> {
                // 인앱 알림은 서버→클라이언트 푸시 전용이다. @MessageMapping 핸들러가 없고 simple
                // broker 라 클라이언트 SEND 는 브로커 destination 으로 직행해 타 사용자에게 위조 알림을
                // 주입할 수 있다(메시지 레벨 인가 부재). 따라서 클라이언트 SEND 를 거부한다.
                log.debug("STOMP SEND 거부 — 인앱 알림은 푸시 전용(클라이언트 SEND 불가)")
                throw StompAuthenticationException("client SEND not allowed (push-only notification channel)")
            }
            else -> Unit // SUBSCRIBE/UNSUBSCRIBE/DISCONNECT/heartbeat 등은 인증된 세션이므로 통과
        }
        return message
    }

    /**
     * CONNECT frame 의 native `Authorization` 헤더에서 Bearer 토큰을 추출한다.
     *
     * @throws StompAuthenticationException 헤더가 없거나 `Bearer ` prefix 가 아닐 때.
     */
    private fun extractBearerToken(accessor: StompHeaderAccessor): String {
        val header = accessor.getFirstNativeHeader(AUTHORIZATION_HEADER)
        if (header.isNullOrBlank() || !header.startsWith(BEARER_PREFIX)) {
            log.debug("STOMP CONNECT 거부 — Authorization Bearer 헤더 없음")
            throw StompAuthenticationException("missing or malformed Authorization header")
        }
        return header.removePrefix(BEARER_PREFIX).trim()
    }

    /**
     * 토큰을 검증하고 userId(subject) 를 name 으로 갖는 인증 주체를 만든다.
     *
     * PAT 는 거부하고, JWT 만 [JwtDecoder] 로 검증한다.
     *
     * @throws StompAuthenticationException PAT, decode 실패, subject 부재 등 모든 거부 사유.
     */
    private fun authenticate(token: String): UsernamePasswordAuthenticationToken {
        rejectPersonalAccessToken(token)
        val subject = decodeSubject(token)
        return UsernamePasswordAuthenticationToken(subject, null, emptyList())
    }

    /**
     * PAT(`pat_` prefix) 토큰을 거부한다. WebSocket 은 JWT 전용이다.
     *
     * @throws StompAuthenticationException 토큰이 PAT 일 때.
     */
    private fun rejectPersonalAccessToken(token: String) {
        if (token.startsWith(PAT_PREFIX)) {
            log.debug("STOMP CONNECT 거부 — PAT 는 WebSocket 인증에 사용할 수 없음")
            throw StompAuthenticationException("personal access token not allowed for WebSocket")
        }
    }

    /**
     * JWT 를 검증하고 비어 있지 않은 subject(userId) 를 반환한다.
     *
     * @throws StompAuthenticationException decode 실패 또는 subject 부재 시.
     */
    private fun decodeSubject(token: String): String {
        val subject =
            try {
                jwtDecoder.decode(token).subject
            } catch (e: JwtException) {
                log.debug("STOMP CONNECT 거부 — JWT 검증 실패: {}", e.javaClass.simpleName)
                throw StompAuthenticationException("invalid token", e)
            }
        if (subject.isNullOrBlank()) {
            log.debug("STOMP CONNECT 거부 — JWT subject 부재")
            throw StompAuthenticationException("token has no subject")
        }
        return subject
    }

    private companion object {
        const val AUTHORIZATION_HEADER = "Authorization"
        const val BEARER_PREFIX = "Bearer "

        /**
         * Personal Access Token prefix.
         *
         * identity-access 의 `PersonalAccessToken.TOKEN_PREFIX`(internal) 와 동일한 값이다.
         * BC 격리상 직접 import 할 수 없어 문자열로 복제한다.
         */
        const val PAT_PREFIX = "pat_"
    }
}

/**
 * STOMP CONNECT 인증 실패를 나타내는 예외.
 *
 * 모든 거부 사유(헤더 부재, PAT, JWT 검증 실패, subject 부재)가 이 타입으로 수렴한다.
 */
class StompAuthenticationException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
