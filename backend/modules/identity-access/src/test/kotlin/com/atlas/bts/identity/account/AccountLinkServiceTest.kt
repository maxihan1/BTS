// AccountLinkService 단위 테스트 — 연결/해제/충돌/멱등/마지막수단 분기 (FR-AU-08)

package com.atlas.bts.identity.account

import com.atlas.bts.identity.credential.StoredPasswordCredential
import com.atlas.bts.identity.credential.StoredPasswordCredentialRepository
import com.atlas.bts.identity.provider.AuthnProviderConfigRepository
import com.atlas.bts.identity.provider.AuthnProviderInfo
import com.atlas.bts.identity.provider.ldap.ExternalAccount
import com.atlas.bts.identity.provider.ldap.ExternalAccountRepository
import com.atlas.bts.identity.provider.ldap.LdapProvider
import com.atlas.bts.identity.provider.ldap.LdapProvisionAttrs
import com.atlas.bts.identity.spi.ProviderType
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * AccountLinkService 단위 테스트 (FR-AU-08 Task 4).
 *
 * 모든 협력자(ExternalAccountRepository / LdapProvider / StoredPasswordCredentialRepository /
 * AuthnProviderConfigRepository)는 mockk 으로 대체한다.
 *
 * 검증 대상.
 * - listLinks: 본인 링크 + provider 정보(name/type/enabled) + hasLocalPassword 결합.
 * - link: bind 선행(실패 시 401 의미 예외) → 충돌 조회로 신규/멱등/타계정 충돌(409) 분기.
 * - unlink: advisory lock 선행 → enabled provider 링크 + 로컬비번으로 남은 수단 재조회 →
 *   0이면 마지막수단(409), delete 0행이면 not-found(404).
 */
class AccountLinkServiceTest {
    private val externalAccountRepository = mockk<ExternalAccountRepository>(relaxed = true)
    private val ldapProvider = mockk<LdapProvider>(relaxed = true)
    private val storedPasswordCredentialRepository = mockk<StoredPasswordCredentialRepository>(relaxed = true)
    private val authnProviderConfigRepository = mockk<AuthnProviderConfigRepository>(relaxed = true)

    private val sut =
        AccountLinkService(
            externalAccountRepository = externalAccountRepository,
            ldapProvider = ldapProvider,
            storedPasswordCredentialRepository = storedPasswordCredentialRepository,
            authnProviderConfigRepository = authnProviderConfigRepository,
        )

    private val userId = UUID.randomUUID()
    private val otherUserId = UUID.randomUUID()
    private val ldapProviderId = UUID.randomUUID()
    private val dn = "uid=alice,ou=people,dc=corp"

    private fun externalAccount(
        id: UUID = UUID.randomUUID(),
        providerId: UUID = ldapProviderId,
        subject: String = dn,
        owner: UUID = userId,
        groups: List<String> = emptyList(),
    ): ExternalAccount =
        ExternalAccount(
            id = id,
            providerId = providerId,
            externalSubject = subject,
            userId = owner,
            groups = groups,
            failedAttempts = 0,
            lockedUntil = null,
            lastLoginAt = null,
            createdAt = Instant.parse("2026-01-01T00:00:00Z"),
            updatedAt = Instant.parse("2026-01-01T00:00:00Z"),
        )

    private fun providerInfo(
        id: UUID = ldapProviderId,
        type: ProviderType = ProviderType.LDAP,
        enabled: Boolean = true,
    ): AuthnProviderInfo = AuthnProviderInfo(id = id, name = "Corp ${type.name}", type = type, enabled = enabled)

    private fun storedCredential(): StoredPasswordCredential = mockk(relaxed = true)

    private fun pw() = "Secret@1234".toCharArray()

    /** 본인 소유의 두 번째(보존용) 링크 — provider 별도로 enabled 판정한다. */
    private fun keepAccount(subject: String): ExternalAccount =
        externalAccount(id = UUID.randomUUID(), providerId = UUID.randomUUID(), subject = subject, owner = userId)

    /** 여러 링크의 provider 정보를 enabled 지정과 함께 묶어 findByIds 응답 맵을 만든다. */
    private fun providerMap(vararg pairs: Pair<ExternalAccount, Boolean>): Map<UUID, AuthnProviderInfo> =
        pairs.associate { (account, enabled) ->
            account.providerId to providerInfo(id = account.providerId, enabled = enabled)
        }

    // ── listLinks ──────────────────────────────────────────────────────────

    @Test
    fun `listLinks — 본인 링크에 provider 정보와 hasLocalPassword 를 결합한다`() {
        val link = externalAccount()
        every { externalAccountRepository.findByUserId(userId) } returns listOf(link)
        every { authnProviderConfigRepository.findByIds(any()) } returns mapOf(ldapProviderId to providerInfo())
        every { storedPasswordCredentialRepository.findByUserId(userId) } returns storedCredential()

        val result = sut.listLinks(userId)

        assertThat(result.hasLocalPassword).isTrue()
        assertThat(result.links).hasSize(1)
        val view = result.links.first()
        assertThat(view.id).isEqualTo(link.id)
        assertThat(view.providerId).isEqualTo(ldapProviderId)
        assertThat(view.providerType).isEqualTo(ProviderType.LDAP)
        assertThat(view.providerEnabled).isTrue()
        assertThat(view.providerName).isEqualTo("Corp LDAP")
    }

    @Test
    fun `listLinks — 로컬 비밀번호가 없으면 hasLocalPassword 는 false`() {
        every { externalAccountRepository.findByUserId(userId) } returns emptyList()
        every { authnProviderConfigRepository.findByIds(any()) } returns emptyMap()
        every { storedPasswordCredentialRepository.findByUserId(userId) } returns null

        val result = sut.listLinks(userId)

        assertThat(result.hasLocalPassword).isFalse()
        assertThat(result.links).isEmpty()
    }

    // ── link ───────────────────────────────────────────────────────────────

    @Test
    fun `link — bind 실패면 AccountLinkAuthException (provision 호출 안 함)`() {
        every { ldapProvider.bindForLinking(ldapProviderId, "alice", any()) } returns null

        assertThatThrownBy { sut.link(userId, ldapProviderId, "alice", pw()) }
            .isInstanceOf(AccountLinkAuthException::class.java)

        verify(exactly = 0) { externalAccountRepository.provisionUser(any(), any(), any(), any()) }
    }

    @Test
    fun `link — bind 성공이고 기존 매핑 없으면 신규 INSERT(현재 userId attach)`() {
        val attrs = LdapProvisionAttrs("alice", null, "Alice", dn, emptyList())
        every { ldapProvider.bindForLinking(ldapProviderId, "alice", any()) } returns attrs
        every { externalAccountRepository.findByProviderIdAndExternalSubject(ldapProviderId, dn) } returns null
        every { externalAccountRepository.provisionUser(ldapProviderId, dn, userId, emptyList()) } returns
            externalAccount(subject = dn, owner = userId)

        val view = sut.link(userId, ldapProviderId, "alice", pw())

        assertThat(view.providerId).isEqualTo(ldapProviderId)
        verify(exactly = 1) { externalAccountRepository.provisionUser(ldapProviderId, dn, userId, emptyList()) }
    }

    @Test
    fun `link — 이미 본인에게 연결된 매핑이면 멱등 no-op(기존 반환, provision 호출 안 함)`() {
        val attrs = LdapProvisionAttrs("alice", null, "Alice", dn, emptyList())
        val existing = externalAccount(subject = dn, owner = userId)
        every { ldapProvider.bindForLinking(ldapProviderId, "alice", any()) } returns attrs
        every { externalAccountRepository.findByProviderIdAndExternalSubject(ldapProviderId, dn) } returns existing

        val view = sut.link(userId, ldapProviderId, "alice", pw())

        assertThat(view.id).isEqualTo(existing.id)
        verify(exactly = 0) { externalAccountRepository.provisionUser(any(), any(), any(), any()) }
    }

    @Test
    fun `link — 타 사용자에게 연결된 매핑이면 AccountLinkConflictException(provision 호출 안 함)`() {
        val attrs = LdapProvisionAttrs("alice", null, "Alice", dn, emptyList())
        val foreign = externalAccount(subject = dn, owner = otherUserId)
        every { ldapProvider.bindForLinking(ldapProviderId, "alice", any()) } returns attrs
        every { externalAccountRepository.findByProviderIdAndExternalSubject(ldapProviderId, dn) } returns foreign

        assertThatThrownBy { sut.link(userId, ldapProviderId, "alice", pw()) }
            .isInstanceOf(AccountLinkConflictException::class.java)

        verify(exactly = 0) { externalAccountRepository.provisionUser(any(), any(), any(), any()) }
    }

    // ── unlink ───────────────────────────────────────────────────────────────

    @Test
    fun `unlink — lock 을 먼저 잡고 남은 수단 재조회 후 삭제한다`() {
        val target = externalAccount(id = UUID.randomUUID(), owner = userId)
        val keep = keepAccount("uid=bob")
        every { externalAccountRepository.findByUserId(userId) } returns listOf(target, keep)
        every { authnProviderConfigRepository.findByIds(any()) } returns providerMap(target to true, keep to true)
        every { storedPasswordCredentialRepository.findByUserId(userId) } returns null
        every { externalAccountRepository.deleteByIdAndUserId(target.id, userId) } returns 1

        sut.unlink(userId, target.id)

        verify(exactly = 1) { externalAccountRepository.acquireUserLock(userId) }
        verify(exactly = 1) { externalAccountRepository.deleteByIdAndUserId(target.id, userId) }
    }

    @Test
    fun `unlink — 마지막 남은 수단이면 AccountLinkLastMethodException(삭제 안 함)`() {
        val only = externalAccount(id = UUID.randomUUID(), owner = userId)
        every { externalAccountRepository.findByUserId(userId) } returns listOf(only)
        every { authnProviderConfigRepository.findByIds(any()) } returns providerMap(only to true)
        every { storedPasswordCredentialRepository.findByUserId(userId) } returns null

        assertThatThrownBy { sut.unlink(userId, only.id) }
            .isInstanceOf(AccountLinkLastMethodException::class.java)

        verify(exactly = 0) { externalAccountRepository.deleteByIdAndUserId(any(), any()) }
    }

    @Test
    fun `unlink — 비활성 provider 링크는 남은 수단에서 제외(영구 락 방지)`() {
        // target(enabled) 제거 후 남는 건 비활성 provider 링크뿐 → 로컬비번도 없으면 남은 수단 0.
        val target = externalAccount(id = UUID.randomUUID(), owner = userId)
        val disabledLink = keepAccount("uid=old")
        every { externalAccountRepository.findByUserId(userId) } returns listOf(target, disabledLink)
        every { authnProviderConfigRepository.findByIds(any()) } returns
            providerMap(target to true, disabledLink to false)
        every { storedPasswordCredentialRepository.findByUserId(userId) } returns null

        assertThatThrownBy { sut.unlink(userId, target.id) }
            .isInstanceOf(AccountLinkLastMethodException::class.java)
    }

    @Test
    fun `unlink — 로컬 비밀번호가 남으면 마지막 링크도 삭제 가능`() {
        val only = externalAccount(id = UUID.randomUUID(), owner = userId)
        every { externalAccountRepository.findByUserId(userId) } returns listOf(only)
        every { authnProviderConfigRepository.findByIds(any()) } returns providerMap(only to true)
        every { storedPasswordCredentialRepository.findByUserId(userId) } returns storedCredential()
        every { externalAccountRepository.deleteByIdAndUserId(only.id, userId) } returns 1

        sut.unlink(userId, only.id)

        verify(exactly = 1) { externalAccountRepository.deleteByIdAndUserId(only.id, userId) }
    }

    @Test
    fun `unlink — delete 0행이면 AccountLinkNotFoundException(타인 소유 또는 미존재)`() {
        val target = externalAccount(id = UUID.randomUUID(), owner = userId)
        val keep = keepAccount("uid=bob")
        every { externalAccountRepository.findByUserId(userId) } returns listOf(target, keep)
        every { authnProviderConfigRepository.findByIds(any()) } returns providerMap(target to true, keep to true)
        every { storedPasswordCredentialRepository.findByUserId(userId) } returns null
        every { externalAccountRepository.deleteByIdAndUserId(target.id, userId) } returns 0

        assertThatThrownBy { sut.unlink(userId, target.id) }
            .isInstanceOf(AccountLinkNotFoundException::class.java)
    }
}
