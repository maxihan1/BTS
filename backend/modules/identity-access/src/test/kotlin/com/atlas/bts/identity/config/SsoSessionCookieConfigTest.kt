// SSO 체인 세션 구성 검증 — session-fixation 속성 이관 + JSESSIONID SameSite/secure 설정값 (FR-AU-08b Task 8)

package com.atlas.bts.identity.config

import com.atlas.bts.identity.account.SsoLinkingIntent
import com.atlas.bts.identity.account.SsoLinkingIntentStore
import com.atlas.bts.identity.spi.ProviderType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.env.EnumerablePropertySource
import org.springframework.core.io.ClassPathResource
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.authentication.TestingAuthenticationToken
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy
import java.time.Instant
import java.util.UUID

/**
 * SSO 체인 세션 구성 검증 (FR-AU-08b Task 8 / B2·EC10·EC17).
 *
 * ## (a) session-fixation 속성 이관 (EC10)
 * SSO 체인은 인증 성공 시 session-fixation 보호로 세션 ID 를 회전한다. 전략을
 * [ChangeSessionIdAuthenticationStrategy] (changeSessionId) 로 두면 세션 ID 만 바뀌고
 * **세션 속성(SSO LinkingIntent)은 보존**된다. `newSession` 전략이면 intent 가 유실돼 연결
 * 모드 콜백이 일반 로그인으로 새므로(fail-open 위험), 속성 이관 보존을 실증한다.
 *
 * ## (b)(c) JSESSIONID SameSite/secure 설정값 (EC17)
 * SAML ACS 는 IdP 가 외부에서 **cross-site POST** 로 콜백한다. JSESSIONID 가 `SameSite=Strict`
 * 면 그 POST 에 동반되지 않아 start 단계 세션이 끊긴다. 따라서 base `application.yml` 은
 * `server.servlet.session.cookie.same-site: none` 으로 둔다. `secure: true` 는 http 로 부팅하는
 * base/dev/test(RANDOM_PORT 통합테스트 포함)에서 JSESSIONID 미방출을 일으키므로 **prod 에만**
 * 둔다(C4). 두 yaml 파일의 설정값을 직접 파싱해 검증한다([IssuerUriEnvOverrideTest] 패턴).
 */
class SsoSessionCookieConfigTest {
    // ── (a) session-fixation 속성 이관 (EC10) ─────────────────────────────────

    @Test
    fun `changeSessionId 전략은 세션 ID 를 회전하되 SSO intent 속성을 보존한다`() {
        val request = MockHttpServletRequest()
        // start 단계가 세션에 심은 SSO LinkingIntent 를 모사한다.
        val session = request.getSession(true)!!
        val intent =
            SsoLinkingIntent(
                mode = SsoLinkingIntent.Mode.LINK,
                userId = UUID.randomUUID(),
                sid = null,
                registrationId = "okta",
                providerType = ProviderType.SAML,
                expiresAt = Instant.parse("2026-06-09T10:05:00Z"),
            )
        session.setAttribute(INTENT_KEY, intent)
        val originalSessionId = session.id

        // Spring Security 가 인증 성공 시 적용하는 session-fixation 전략(changeSessionId).
        val strategy = ChangeSessionIdAuthenticationStrategy()
        strategy.onAuthentication(
            TestingAuthenticationToken("alice", "n/a"),
            request,
            MockHttpServletResponse(),
        )

        val rotated = request.getSession(false)!!
        // 세션 ID 는 회전됐지만(고정 방어) intent 속성은 그대로 살아있어야 한다.
        assertThat(rotated.id).isNotEqualTo(originalSessionId)
        assertThat(rotated.getAttribute(INTENT_KEY)).isEqualTo(intent)
    }

    // ── (b) base application.yml — SameSite=None, secure 없음 (C4) ─────────────

    @Test
    fun `base application yml 의 JSESSIONID SameSite 는 none 이다 (SAML ACS cross-site POST 왕복)`() {
        val sameSite = property("application.yml", "server.servlet.session.cookie.same-site")

        assertThat(sameSite)
            .`as`("base application.yml 에 same-site 설정이 없다 — none 추가 필요(EC17)")
            .isNotNull()
        assertThat(sameSite.toString().lowercase()).isEqualTo("none")
    }

    @Test
    fun `base application yml 에는 cookie secure 설정이 없다 (http 부팅 — JSESSIONID 미방출 방지, C4)`() {
        val secure = property("application.yml", "server.servlet.session.cookie.secure")

        assertThat(secure)
            .`as`("base/test 는 http 부팅이라 secure:true 면 JSESSIONID 미방출 — base 에 두면 안 됨(C4)")
            .isNull()
    }

    // ── (c) application-prod.yml — secure=true (prod 한정) ─────────────────────

    @Test
    fun `prod application yml 의 cookie secure 는 true 다 (prod 한정 — https 강제)`() {
        val secure = property("application-prod.yml", "server.servlet.session.cookie.secure")

        assertThat(secure)
            .`as`("application-prod.yml 에 cookie.secure:true 가 없다 — SameSite=None 의 secure 짝 필요")
            .isNotNull()
        assertThat(secure.toString().lowercase()).isEqualTo("true")
    }

    // ── 헬퍼 ──────────────────────────────────────────────────────────────────

    /** 주어진 yaml 파일에서 [key] 의 raw 값을 읽는다(placeholder resolve 전). 없으면 null. */
    private fun property(
        yamlFile: String,
        key: String,
    ): Any? {
        val loader = YamlPropertySourceLoader()
        val resource = ClassPathResource(yamlFile)
        return loader.load(yamlFile, resource)
            .filterIsInstance<EnumerablePropertySource<*>>()
            .mapNotNull { it.getProperty(key) }
            .firstOrNull()
    }

    private companion object {
        /** SsoLinkingIntentStore 가 실제로 쓰는 세션 속성 키를 그대로 재사용한다(상수 drift 방지, N1). */
        const val INTENT_KEY = SsoLinkingIntentStore.ATTRIBUTE_KEY
    }
}
