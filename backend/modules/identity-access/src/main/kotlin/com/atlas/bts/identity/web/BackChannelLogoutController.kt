// Auth_platform 이 보내는 back-channel logout 을 받는 엔드포인트 — 로그인 없이 logout_token 서명으로 확인 (S24 · FR-AU-04)

package com.atlas.bts.identity.web

import com.atlas.bts.identity.provider.oidc.BackChannelLogoutService
import com.atlas.bts.identity.provider.oidc.LogoutTokenVerifier
import org.slf4j.LoggerFactory
import org.springframework.http.CacheControl
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.oauth2.jwt.JwtException
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * `POST /api/v1/auth/backchannel-logout` — OIDC Back-Channel Logout 1.0 수신 (S24 계약 §1).
 *
 * Auth 세션이 끝나면 Auth 서버가 form 본문 `logout_token=<JWT>` 로 부른다. 브라우저가 아니라 서버가 부르므로
 * BTS 의 JWT · 세션 · CSRF 토큰이 없다 — `SecurityConfig.INBOUND_WEBHOOK_PATHS` 로 permitAll · CSRF 예외 · bearer skip.
 * DEVELOPMENT.md §1.1-4 정식 예외이고 근거는 ADR `docs/decisions/2026-11-03-backchannel-logout-inbound-permitall.md`
 * (2026-11-03 Maxi 게이트1 승인). 필터가 비킨 자리는 [LogoutTokenVerifier] 의 RS256 서명 확인이 선다.
 *
 * 응답 — 맞으면 200, 틀리면 400 `{"error":"invalid_request"}`. 둘 다 `Cache-Control: no-store`.
 * 끊을 세션이 없어도 200 (이미 끊겼거나 BTS 에 로그인한 적 없음). token · sid · sub 는 로그에 남기지 않는다.
 */
@RestController
class BackChannelLogoutController(
    private val verifier: LogoutTokenVerifier,
    private val service: BackChannelLogoutService,
) {
    private val log = LoggerFactory.getLogger(BackChannelLogoutController::class.java)

    @PostMapping(PATH, consumes = [MediaType.APPLICATION_FORM_URLENCODED_VALUE])
    fun receive(
        @RequestParam("logout_token", required = false) logoutToken: String?,
    ): ResponseEntity<Any> {
        val claims =
            try {
                verifier.verify(logoutToken?.takeIf { it.isNotBlank() } ?: throw JwtException("logout_token 없음"))
            } catch (ex: JwtException) {
                log.warn("back-channel logout 거절: {}", ex.message)
                return ResponseEntity.badRequest()
                    .cacheControl(CacheControl.noStore())
                    .body(mapOf("error" to "invalid_request"))
            }
        val revoked = service.logout(claims)
        log.info("back-channel logout — 끊은 세션 {}개 (sid={})", revoked, claims.sid != null)
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).build()
    }

    internal companion object {
        const val PATH = "/api/v1/auth/backchannel-logout"
    }
}
