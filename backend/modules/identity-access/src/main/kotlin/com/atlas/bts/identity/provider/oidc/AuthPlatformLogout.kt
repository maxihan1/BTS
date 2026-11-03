// BTS 로그아웃 뒤 브라우저를 보낼 Auth_platform /connect/logout 주소를 만드는 컴포넌트 (S24 RP-initiated logout · FR-AU-04)

package com.atlas.bts.identity.provider.oidc

import com.atlas.bts.identity.session.Session
import com.bts.shared.crypto.SecretEncryptor
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.web.servlet.support.ServletUriComponentsBuilder
import org.springframework.web.util.UriComponentsBuilder

/**
 * Auth_platform 로그인 세션을 끝낼 때 Auth 세션도 끝내는 주소 (S24 · OIDC RP-Initiated Logout 1.0).
 *
 * `<issuer>/connect/logout?id_token_hint=<로그인 때 받은 id_token>&post_logout_redirect_uri=<이 앱>/login`.
 * Auth 는 지난 id_token 도 받고(서명 · iss · aud 만 본다) Auth 세션을 끝낸 뒤 back-channel 로 다른 앱에도 알린다.
 *
 * post_logout_redirect_uri 는 로그인 redirect_uri(`{baseUrl}`, [DbClientRegistrationRepository])와 같이 요청에서 얻는다 —
 * prod 의 `forward-headers-strategy: native` 가 Caddy 뒤의 https 주소를 돌려준다. Auth 가 등록 문자열과 **정확히**
 * 비교하므로 query 를 붙이지 않는다. 운영 등록값은 `https://bunker.maxihan.com/login` (옛 주소 bts. 도 전환 중 등록).
 */
@Component
class AuthPlatformLogout(
    @param:Qualifier("oidcSecretEncryptor") private val secretEncryptor: SecretEncryptor,
    @param:Value("\${bts.auth-platform.issuer-uri:}") private val issuerUri: String,
) {
    private val log = LoggerFactory.getLogger(AuthPlatformLogout::class.java)

    /**
     * [session] 이 id_token 을 가진 Auth_platform 로그인이면 Auth 로그아웃 주소, 아니면 null (지금처럼 BTS 만 끊는다).
     *
     * 풀 수 없는 암호문(키 교체 등)이면 경고만 남기고 null — 로그아웃 자체를 막지 않는다. 토큰은 로그에 남기지 않는다.
     */
    fun endSessionUrl(
        session: Session,
        request: HttpServletRequest,
    ): String? {
        val encrypted = session.authIdTokenEnc ?: return null
        if (issuerUri.isBlank()) return null
        val idToken =
            runCatching { secretEncryptor.decrypt(encrypted) }
                .onFailure { log.warn("Auth 로그아웃 id_token 복호화 실패 — BTS 세션만 끊는다: {}", it.javaClass.simpleName) }
                .getOrNull() ?: return null
        val postLogout = ServletUriComponentsBuilder.fromContextPath(request).path(LOGIN_PATH).build().toUriString()
        return UriComponentsBuilder.fromUriString(issuerUri.trimEnd('/') + END_SESSION_PATH)
            .queryParam("id_token_hint", idToken)
            .queryParam("post_logout_redirect_uri", postLogout)
            .encode()
            .build()
            .toUriString()
    }

    private companion object {
        const val END_SESSION_PATH = "/connect/logout"
        const val LOGIN_PATH = "/login"
    }
}
