// OIDC 진입 요청에서 로그인 카드가 고른 login_hint · prompt=login 만 authorize 로 옮기고 returnTo 를 세션에 맡기는 resolver (S22)

package com.atlas.bts.identity.provider.oidc

import jakarta.servlet.http.HttpServletRequest
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest

/** 진입 때 받은 returnTo 를 콜백까지 들고 가는 세션 속성 — [OidcAuthenticationSuccessHandler] 가 꺼내 지운다 */
internal const val RETURN_TO_SESSION_ATTR = "bts.oidc.return-to"

/**
 * `/oauth2/authorization/{id}?login_hint=…|prompt=login&returnTo=…` 를 받는다 (S22 로그인 카드).
 *
 * - `login_hint` — 「이 계정으로 로그인」. Auth 가 아이디 칸을 채운다. [LOGIN_HINT] 모양만 옮긴다.
 * - `prompt=login` — 「다른 계정으로 로그인」. Auth 가 SSO 세션을 끊고 아이디 + 비밀번호를 묻는다. 값은 `login` 하나만.
 * - `returnTo` — 콜백(`/login/oauth2/code/{id}`)은 IdP 가 보내는 주소라 이 값이 없다. 그래서 세션에 맡긴다.
 *   검증은 꺼내는 쪽([OidcAuthenticationSuccessHandler] 의 RelayStateValidator)이 한다.
 *
 * 그 밖의 쿼리는 버린다 — 진입 주소로 authorize 요청을 마음대로 꾸미지 못하게.
 * `from()` 은 authorizationRequestUri 를 복사하지 않아 `build()` 가 덧붙인 파라미터로 주소를 다시 만든다.
 */
internal class LoginCardAuthorizationRequestResolver(
    private val delegate: OAuth2AuthorizationRequestResolver,
) : OAuth2AuthorizationRequestResolver {
    override fun resolve(request: HttpServletRequest): OAuth2AuthorizationRequest? =
        delegate.resolve(request)?.let { customize(request, it) }

    override fun resolve(
        request: HttpServletRequest,
        clientRegistrationId: String,
    ): OAuth2AuthorizationRequest? = delegate.resolve(request, clientRegistrationId)?.let { customize(request, it) }

    private fun customize(
        request: HttpServletRequest,
        authorizationRequest: OAuth2AuthorizationRequest,
    ): OAuth2AuthorizationRequest {
        val returnTo = request.getParameter(RETURN_PARAM)
        // 지난 진입이 남긴 값을 이번 로그인에 쓰지 않게, 없으면 지운다
        if (returnTo != null) {
            request.getSession(true).setAttribute(RETURN_TO_SESSION_ATTR, returnTo)
        } else {
            request.getSession(false)?.removeAttribute(RETURN_TO_SESSION_ATTR)
        }

        val extra =
            buildMap {
                request.getParameter(LOGIN_HINT_PARAM)?.takeIf { LOGIN_HINT.matches(it) }?.let { put(LOGIN_HINT_PARAM, it) }
                if (request.getParameter(PROMPT_PARAM) == PROMPT_LOGIN) put(PROMPT_PARAM, PROMPT_LOGIN)
            }
        if (extra.isEmpty()) return authorizationRequest
        return OAuth2AuthorizationRequest
            .from(authorizationRequest)
            .additionalParameters { it.putAll(extra) }
            .build()
    }

    private companion object {
        const val RETURN_PARAM = "returnTo"
        const val LOGIN_HINT_PARAM = "login_hint"
        const val PROMPT_PARAM = "prompt"
        const val PROMPT_LOGIN = "login"

        /** Auth username 모양 — 글자 · 숫자 · `. _ @ -`, 64자까지. 벗어나면 hint 없이 간다(아이디 칸이 빌 뿐이다) */
        val LOGIN_HINT = Regex("""^[\p{L}\p{N}._@-]{1,64}$""")
    }
}
