// SsoLinkingCallbackProcessor 단위 테스트 — 모드 분기·fail-closed·고정 복귀경로 (FR-AU-08b Task 6)

package com.atlas.bts.identity.account

import com.atlas.bts.identity.spi.ProviderType
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import jakarta.servlet.http.HttpServletResponse
import jakarta.servlet.http.HttpSession
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * SsoLinkingCallbackProcessor 단위 테스트 (FR-AU-08b Task 6).
 *
 * 협력자(SsoLinkingIntentStore / AccountLinkService / ReauthService / HttpSession /
 * HttpServletResponse)는 mockk 으로 대체한다.
 *
 * 검증 대상.
 * - intent 없음 → false(일반 로그인 위임, 리다이렉트 안 함).
 * - LINK: registrationId/providerType 불일치 → `?link=error`. Created → `?link=success` /
 *   AlreadyLinked → `?link=already_linked` / Conflict → `?link=conflict`. 항상 true.
 * - REAUTH: 불일치 → `?reauth=failed`. 성공 → `?reauth=success` / 예외 → `?reauth=failed`. 항상 true.
 * - 고정 복귀경로 + status 쿼리(open-redirect 0).
 */
class SsoLinkingCallbackProcessorTest {
    private val intentStore = mockk<SsoLinkingIntentStore>()
    private val accountLinkService = mockk<AccountLinkService>()
    private val reauthService = mockk<ReauthService>(relaxed = true)
    private val session = mockk<HttpSession>()
    private val response = mockk<HttpServletResponse>(relaxed = true)

    private val sut = SsoLinkingCallbackProcessor(intentStore, accountLinkService, reauthService)

    private val userId = UUID.randomUUID()
    private val sid = UUID.randomUUID()
    private val providerId = UUID.randomUUID()
    private val registrationId = "corp-oidc"
    private val externalSubject = "oidc-sub-9999"
    private val groups = listOf("eng")

    private fun linkIntent(
        reg: String = registrationId,
        type: ProviderType = ProviderType.OIDC,
    ): SsoLinkingIntent =
        SsoLinkingIntent(
            mode = SsoLinkingIntent.Mode.LINK,
            userId = userId,
            sid = null,
            registrationId = reg,
            providerType = type,
            expiresAt = Instant.parse("2099-01-01T00:00:00Z"),
        )

    private fun reauthIntent(
        reg: String = registrationId,
        type: ProviderType = ProviderType.OIDC,
    ): SsoLinkingIntent =
        SsoLinkingIntent(
            mode = SsoLinkingIntent.Mode.REAUTH,
            userId = userId,
            sid = sid,
            registrationId = reg,
            providerType = type,
            expiresAt = Instant.parse("2099-01-01T00:00:00Z"),
        )

    private fun view(): AccountLinkView =
        AccountLinkView(
            id = UUID.randomUUID(),
            providerId = providerId,
            providerName = "Corp OIDC",
            providerType = ProviderType.OIDC,
            providerEnabled = true,
            externalSubject = externalSubject,
            linkedAt = Instant.parse("2026-01-01T00:00:00Z"),
            lastLoginAt = null,
        )

    private fun process(): Boolean =
        sut.process(
            session = session,
            providerType = ProviderType.OIDC,
            registrationId = registrationId,
            providerId = providerId,
            externalSubject = externalSubject,
            groups = groups,
            response = response,
        )

    @Test
    fun `intent 없음 — false 반환(일반 로그인 위임), 리다이렉트 안 함`() {
        every { intentStore.consume(session) } returns null

        val handled = process()

        assertThat(handled).isFalse()
        verify(exactly = 0) { response.sendRedirect(any()) }
        verify(exactly = 0) { accountLinkService.linkExternalSubject(any(), any(), any(), any()) }
        verify(exactly = 0) { reauthService.reauthenticateSso(any(), any(), any(), any()) }
    }

    @Test
    fun `LINK Created — link=success 리다이렉트, true 반환`() {
        every { intentStore.consume(session) } returns linkIntent()
        every { accountLinkService.linkExternalSubject(userId, providerId, externalSubject, groups) } returns
            LinkOutcome.Created(view())

        val handled = process()

        assertThat(handled).isTrue()
        verify { response.sendRedirect("/settings/account-links?link=success") }
    }

    @Test
    fun `LINK AlreadyLinked — link=already_linked 리다이렉트`() {
        every { intentStore.consume(session) } returns linkIntent()
        every { accountLinkService.linkExternalSubject(userId, providerId, externalSubject, groups) } returns
            LinkOutcome.AlreadyLinked(view())

        process()

        verify { response.sendRedirect("/settings/account-links?link=already_linked") }
    }

    @Test
    fun `LINK Conflict — link=conflict 리다이렉트(attach 0)`() {
        every { intentStore.consume(session) } returns linkIntent()
        every { accountLinkService.linkExternalSubject(userId, providerId, externalSubject, groups) } throws
            AccountLinkConflictException()

        val handled = process()

        assertThat(handled).isTrue()
        verify { response.sendRedirect("/settings/account-links?link=conflict") }
    }

    @Test
    fun `LINK registrationId 불일치 — link=error(linkExternalSubject 미호출)`() {
        every { intentStore.consume(session) } returns linkIntent(reg = "other-reg")

        val handled = process()

        assertThat(handled).isTrue()
        verify { response.sendRedirect("/settings/account-links?link=error") }
        verify(exactly = 0) { accountLinkService.linkExternalSubject(any(), any(), any(), any()) }
    }

    @Test
    fun `LINK providerType 불일치 — link=error(linkExternalSubject 미호출)`() {
        // intent 는 SAML 인데 콜백은 OIDC 체인에서 옴 → 불일치 거부.
        every { intentStore.consume(session) } returns linkIntent(type = ProviderType.SAML)

        val handled = process()

        assertThat(handled).isTrue()
        verify { response.sendRedirect("/settings/account-links?link=error") }
        verify(exactly = 0) { accountLinkService.linkExternalSubject(any(), any(), any(), any()) }
    }

    @Test
    fun `REAUTH 성공 — reauth=success 리다이렉트, grant 호출`() {
        every { intentStore.consume(session) } returns reauthIntent()

        val handled = process()

        assertThat(handled).isTrue()
        verify { reauthService.reauthenticateSso(userId, sid, providerId, externalSubject) }
        verify { response.sendRedirect("/settings/account-links?reauth=success") }
    }

    @Test
    fun `REAUTH 실패(예외) — reauth=failed 리다이렉트`() {
        every { intentStore.consume(session) } returns reauthIntent()
        every { reauthService.reauthenticateSso(userId, sid, providerId, externalSubject) } throws
            ReauthChallengeFailedException()

        val handled = process()

        assertThat(handled).isTrue()
        verify { response.sendRedirect("/settings/account-links?reauth=failed") }
    }

    @Test
    fun `REAUTH registrationId 불일치 — reauth=failed(reauthenticateSso 미호출)`() {
        every { intentStore.consume(session) } returns reauthIntent(reg = "other-reg")

        val handled = process()

        assertThat(handled).isTrue()
        verify { response.sendRedirect("/settings/account-links?reauth=failed") }
        verify(exactly = 0) { reauthService.reauthenticateSso(any(), any(), any(), any()) }
    }
}
