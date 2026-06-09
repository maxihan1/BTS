// provider 명시 선택 디스패처 CompositeAuthenticationManager 단위 테스트 (FR-AU-06 Task 2)

package com.atlas.bts.identity.auth

import com.atlas.bts.identity.provider.AuthnProviderConfigRepository
import com.atlas.bts.identity.provider.ldap.ProviderUnavailableException
import com.atlas.bts.identity.spi.AuthenticationProvider
import com.atlas.bts.identity.spi.AuthnResult
import com.atlas.bts.identity.spi.Credential
import com.atlas.bts.identity.spi.FailureReason
import com.atlas.bts.identity.spi.MfaChallenge
import com.atlas.bts.identity.spi.Principal
import com.atlas.bts.identity.spi.ProviderRegistry
import com.atlas.bts.identity.spi.ProviderType
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * [CompositeAuthenticationManager] 단위 테스트 (FR-AU-06 Task 2).
 *
 * - [ProviderRegistry] / [AuthnProviderConfigRepository] / 각 Provider 는 mockk 으로 대체한다.
 * - Testcontainers 불필요 — 순수 디스패치 로직(provider 명시 선택)만 검증한다.
 * - mockk 인자는 구체값을 사용한다 (any() 함정 회피).
 *
 * 핵심 계약: **명시 선택만, 자동 fallback/우선순위 순회 없음.** providerId 로 지정된
 * 단일 Provider 만 호출하고, 그 결과를 그대로 반환한다.
 */
class CompositeAuthenticationManagerTest {
    private val providerRegistry = mockk<ProviderRegistry>()
    private val authnProviderConfigRepository = mockk<AuthnProviderConfigRepository>()

    private val localProvider = mockk<AuthenticationProvider>()
    private val ldapProvider = mockk<AuthenticationProvider>()

    private val manager =
        CompositeAuthenticationManager(
            providerRegistry = providerRegistry,
            authnProviderConfigRepository = authnProviderConfigRepository,
        )

    private val successPrincipal =
        Principal(
            userId = UUID.fromString("11111111-1111-4111-8111-111111111111"),
            providerType = ProviderType.LOCAL,
            displayName = "Alice",
            externalSubject = null,
        )

    @Test
    fun `local provider 는 UsernamePassword 자격증명을 만들어 LocalProvider 로 인증한다`() {
        every { authnProviderConfigRepository.isEnabled(ProviderType.LOCAL) } returns true
        every { providerRegistry.findByType(ProviderType.LOCAL) } returns localProvider
        val captured = slot<Credential>()
        every { localProvider.authenticate(capture(captured)) } returns AuthnResult.Success(successPrincipal)

        val result = manager.authenticate("local", "alice", "pw".toCharArray())

        assertThat(result).isEqualTo(AuthnResult.Success(successPrincipal))
        val credential = captured.captured
        assertThat(credential).isInstanceOf(Credential.UsernamePassword::class.java)
        credential as Credential.UsernamePassword
        assertThat(credential.username).isEqualTo("alice")
        assertThat(credential.password).isEqualTo("pw".toCharArray())
        verify(exactly = 1) { localProvider.authenticate(any()) }
    }

    @Test
    fun `ldap provider 는 LdapBind 자격증명을 만들어 LdapProvider 로 인증한다`() {
        every { authnProviderConfigRepository.isEnabled(ProviderType.LDAP) } returns true
        every { providerRegistry.findByType(ProviderType.LDAP) } returns ldapProvider
        val captured = slot<Credential>()
        every { ldapProvider.authenticate(capture(captured)) } returns AuthnResult.Success(successPrincipal)

        val result = manager.authenticate("ldap", "bob", "pw".toCharArray())

        assertThat(result).isEqualTo(AuthnResult.Success(successPrincipal))
        val credential = captured.captured
        assertThat(credential).isInstanceOf(Credential.LdapBind::class.java)
        credential as Credential.LdapBind
        assertThat(credential.username).isEqualTo("bob")
        assertThat(credential.password).isEqualTo("pw".toCharArray())
        verify(exactly = 1) { ldapProvider.authenticate(any()) }
    }

    @Test
    fun `saml 은 username password 계열이 아니므로 Provider 호출 없이 Failure 를 반환한다`() {
        val result = manager.authenticate("saml", "carol", "pw".toCharArray())

        assertThat(result).isEqualTo(AuthnResult.Failure(FailureReason.INVALID_INPUT))
        verify(exactly = 0) { providerRegistry.findByType(any()) }
        verify(exactly = 0) { authnProviderConfigRepository.isEnabled(any()) }
    }

    @Test
    fun `알 수 없는 provider 문자열은 ProviderType 파싱 실패로 Failure 를 반환한다`() {
        val result = manager.authenticate("nonsense", "dave", "pw".toCharArray())

        assertThat(result).isEqualTo(AuthnResult.Failure(FailureReason.INVALID_INPUT))
        verify(exactly = 0) { providerRegistry.findByType(any()) }
    }

    @Test
    fun `LDAP 이 비활성화되어 있으면 Provider 호출 없이 Failure 를 반환한다`() {
        every { authnProviderConfigRepository.isEnabled(ProviderType.LDAP) } returns false

        val result = manager.authenticate("ldap", "bob", "pw".toCharArray())

        assertThat(result).isEqualTo(AuthnResult.Failure(FailureReason.PROVIDER_UNAVAILABLE))
        verify(exactly = 0) { providerRegistry.findByType(any()) }
    }

    @Test
    fun `해당 type 의 Provider Bean 이 없으면 Failure 를 반환한다`() {
        every { authnProviderConfigRepository.isEnabled(ProviderType.LOCAL) } returns true
        every { providerRegistry.findByType(ProviderType.LOCAL) } returns null

        val result = manager.authenticate("local", "alice", "pw".toCharArray())

        assertThat(result).isEqualTo(AuthnResult.Failure(FailureReason.PROVIDER_UNAVAILABLE))
    }

    @Test
    fun `Provider 가 RequiresMfa 를 반환하면 그대로 전파한다`() {
        every { authnProviderConfigRepository.isEnabled(ProviderType.LOCAL) } returns true
        every { providerRegistry.findByType(ProviderType.LOCAL) } returns localProvider
        every { localProvider.authenticate(any()) } returns AuthnResult.RequiresMfa(MfaChallenge.NOT_IMPLEMENTED_YET)

        val result = manager.authenticate("local", "alice", "pw".toCharArray())

        assertThat(result).isEqualTo(AuthnResult.RequiresMfa(MfaChallenge.NOT_IMPLEMENTED_YET))
    }

    @Test
    fun `Provider 가 ProviderUnavailableException 을 던지면 Composite 는 잡지 않고 전파한다`() {
        every { authnProviderConfigRepository.isEnabled(ProviderType.LOCAL) } returns true
        every { providerRegistry.findByType(ProviderType.LOCAL) } returns localProvider
        every { localProvider.authenticate(any()) } throws
            ProviderUnavailableException("local down", providerType = "LOCAL")

        assertThatThrownBy { manager.authenticate("local", "alice", "pw".toCharArray()) }
            .isInstanceOf(ProviderUnavailableException::class.java)
    }
}
