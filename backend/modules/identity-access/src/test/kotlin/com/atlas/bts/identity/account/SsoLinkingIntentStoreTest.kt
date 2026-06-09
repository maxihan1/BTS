// SsoLinkingIntentStore 단위 테스트 — MockHttpSession 1회용 소비 + 만료 검사 (FR-AU-08b)

package com.atlas.bts.identity.account

import com.atlas.bts.identity.spi.ProviderType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpSession
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * [SsoLinkingIntentStore] 단위 테스트.
 *
 * 의도 보존 메커니즘은 [MockHttpSession](spring-test) 으로 검증한다 — Testcontainers 불요.
 * 핵심 계약:
 * - put 후 같은 세션에서 consume 이 동일 intent 를 반환한다.
 * - consume 은 1회용이다(두 번째 consume 은 null).
 * - 만료(expiresAt 과거)된 intent 는 consume 에서 null 로 무효화된다.
 * - LINK/REAUTH 모드 구분이 보존된다.
 */
class SsoLinkingIntentStoreTest {
    private val fixedNow = Instant.parse("2026-06-09T10:00:00Z")
    private val clock = Clock.fixed(fixedNow, ZoneOffset.UTC)
    private val store = SsoLinkingIntentStore(clock)

    private val userId = UUID.fromString("a1b2c3d4-0000-4000-8000-000000000001")
    private val sid = UUID.fromString("b1b2c3d4-0000-4000-8000-000000000002")

    private fun linkIntent(expiresAt: Instant = fixedNow.plus(Duration.ofMinutes(5))): SsoLinkingIntent =
        SsoLinkingIntent(
            mode = SsoLinkingIntent.Mode.LINK,
            userId = userId,
            sid = null,
            registrationId = "corp-oidc",
            providerType = ProviderType.OIDC,
            expiresAt = expiresAt,
        )

    private fun reauthIntent(expiresAt: Instant = fixedNow.plus(Duration.ofMinutes(5))): SsoLinkingIntent =
        SsoLinkingIntent(
            mode = SsoLinkingIntent.Mode.REAUTH,
            userId = userId,
            sid = sid,
            registrationId = "corp-saml",
            providerType = ProviderType.SAML,
            expiresAt = expiresAt,
        )

    @Test
    fun `put 후 consume 이 동일 intent 를 반환한다`() {
        val session = MockHttpSession()
        val intent = linkIntent()

        store.put(session, intent)
        val consumed = store.consume(session)

        assertThat(consumed).isEqualTo(intent)
    }

    @Test
    fun `consume 은 1회용 — 두 번째 consume 은 null`() {
        val session = MockHttpSession()
        store.put(session, linkIntent())

        val first = store.consume(session)
        val second = store.consume(session)

        assertThat(first).isNotNull()
        assertThat(second).isNull()
    }

    @Test
    fun `만료된 intent 는 consume 에서 null — 1회용 소비도 발생`() {
        val session = MockHttpSession()
        // expiresAt 을 현재 시각보다 과거로 두면 만료
        store.put(session, linkIntent(expiresAt = fixedNow.minus(Duration.ofSeconds(1))))

        val consumed = store.consume(session)

        assertThat(consumed).isNull()
        // 만료 intent 도 소비(제거)되어 재시도 시에도 null
        assertThat(store.consume(session)).isNull()
    }

    @Test
    fun `만료 경계 — expiresAt 가 정확히 현재 시각이면 만료 처리(닫힘 비교)`() {
        val session = MockHttpSession()
        store.put(session, linkIntent(expiresAt = fixedNow))

        assertThat(store.consume(session)).isNull()
    }

    @Test
    fun `intent 없는 세션에서 consume 은 null`() {
        val session = MockHttpSession()
        assertThat(store.consume(session)).isNull()
    }

    @Test
    fun `LINK 모드 보존 — consume 결과의 mode 가 LINK`() {
        val session = MockHttpSession()
        store.put(session, linkIntent())

        val consumed = store.consume(session)

        assertThat(consumed).isNotNull()
        assertThat(consumed!!.mode).isEqualTo(SsoLinkingIntent.Mode.LINK)
        assertThat(consumed.sid).isNull()
    }

    @Test
    fun `REAUTH 모드 보존 — consume 결과의 mode 가 REAUTH 이고 sid 보존`() {
        val session = MockHttpSession()
        store.put(session, reauthIntent())

        val consumed = store.consume(session)

        assertThat(consumed).isNotNull()
        assertThat(consumed!!.mode).isEqualTo(SsoLinkingIntent.Mode.REAUTH)
        assertThat(consumed.sid).isEqualTo(sid)
        assertThat(consumed.providerType).isEqualTo(ProviderType.SAML)
    }
}
