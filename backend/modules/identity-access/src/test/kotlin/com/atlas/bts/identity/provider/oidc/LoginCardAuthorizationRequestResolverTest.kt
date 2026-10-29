// 로그인 카드 resolver 테스트 — login_hint · prompt=login 만 authorize 주소에 옮기고 returnTo 는 세션에 맡긴다 (S22)

package com.atlas.bts.identity.provider.oidc

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.security.oauth2.client.registration.ClientRegistration
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers
import org.springframework.security.oauth2.core.AuthorizationGrantType
import org.springframework.web.util.UriComponentsBuilder

class LoginCardAuthorizationRequestResolverTest {
    private val registration =
        ClientRegistration
            .withRegistrationId("auth")
            .clientId("bts")
            .clientSecret("secret")
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
            .scope("openid", "profile")
            .authorizationUri("https://auth.example.com/oauth2/authorize")
            .tokenUri("https://auth.example.com/oauth2/token")
            .build()

    // 운영 결선과 같다 — PKCE 를 붙이는 기본 resolver 를 감싼다
    private val resolver =
        LoginCardAuthorizationRequestResolver(
            DefaultOAuth2AuthorizationRequestResolver(
                InMemoryClientRegistrationRepository(registration),
                "/oauth2/authorization",
            ).apply { setAuthorizationRequestCustomizer(OAuth2AuthorizationRequestCustomizers.withPkce()) },
        )

    private fun entry(vararg params: Pair<String, String>) =
        MockHttpServletRequest("GET", "/oauth2/authorization/auth").apply {
            servletPath = "/oauth2/authorization/auth"
            params.forEach { (k, v) -> addParameter(k, v) }
        }

    private fun queryOf(request: MockHttpServletRequest): Map<String, String?> {
        val uri = requireNotNull(resolver.resolve(request)).authorizationRequestUri
        return UriComponentsBuilder.fromUriString(uri).build().queryParams.toSingleValueMap()
    }

    @Test
    fun `login_hint 를 authorize 주소에 옮긴다 — PKCE 는 그대로`() {
        val query = queryOf(entry("login_hint" to "maxi"))

        assertThat(query["login_hint"]).isEqualTo("maxi")
        assertThat(query["code_challenge"]).isNotBlank()
        assertThat(query["state"]).isNotBlank()
    }

    @Test
    fun `prompt=login 을 옮긴다`() {
        assertThat(queryOf(entry("prompt" to "login"))["prompt"]).isEqualTo("login")
    }

    @Test
    fun `prompt 의 다른 값은 버린다 — none 등으로 Auth 동작을 바꾸지 못한다`() {
        assertThat(queryOf(entry("prompt" to "none"))).doesNotContainKey("prompt")
    }

    @Test
    fun `모양이 다른 login_hint 는 버린다`() {
        assertThat(queryOf(entry("login_hint" to "a b&prompt=none"))).doesNotContainKey("login_hint")
        assertThat(queryOf(entry("login_hint" to "x".repeat(65)))).doesNotContainKey("login_hint")
    }

    @Test
    fun `허용 목록 밖의 쿼리는 authorize 로 가지 않는다`() {
        val query = queryOf(entry("max_age" to "0", "acr_values" to "x"))

        assertThat(query).doesNotContainKeys("max_age", "acr_values", "returnTo")
    }

    @Test
    fun `returnTo 는 authorize 가 아니라 세션에 맡긴다`() {
        val request = entry("returnTo" to "/projects/PROJ/board")

        assertThat(queryOf(request)).doesNotContainKey("returnTo")
        assertThat(request.getSession(false)?.getAttribute(RETURN_TO_SESSION_ATTR)).isEqualTo("/projects/PROJ/board")
    }

    @Test
    fun `returnTo 없이 다시 들어오면 지난 값을 지운다`() {
        val request = entry()
        requireNotNull(request.getSession(true)).setAttribute(RETURN_TO_SESSION_ATTR, "/old")

        resolver.resolve(request)

        assertThat(request.getSession(false)?.getAttribute(RETURN_TO_SESSION_ATTR)).isNull()
    }
}
