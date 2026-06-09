// ReauthService 단위 테스트 — 민감동작 전 재인증 챌린지(LOCAL/LDAP) + step-up 부여 (FR-AU-08)

package com.atlas.bts.identity.account

import com.atlas.bts.identity.credential.LocalCredentialService
import com.atlas.bts.identity.provider.ldap.ExternalAccount
import com.atlas.bts.identity.provider.ldap.ExternalAccountRepository
import com.atlas.bts.identity.provider.ldap.LdapProvider
import com.atlas.bts.identity.provider.ldap.LdapProvisionAttrs
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * ReauthService 단위 테스트 (FR-AU-08 Task 5).
 *
 * 모든 협력자(StepUpService / LocalCredentialService / LdapProvider / ExternalAccountRepository)는
 * mockk 으로 대체한다.
 *
 * 검증 대상.
 * - LOCAL 성공: verifyForUser true → stepUpService.grant(sid) 호출.
 * - LOCAL 실패: verifyForUser false → grant 미호출 + 도메인 예외(컨트롤러가 401 매핑).
 * - LDAP 성공: bind 성공 + DN 이 현재 user 에 연결됨(EC9) → grant 호출.
 * - LDAP 실패: bind null, 또는 bind 성공이나 DN 미연결/타 user 연결 → grant 미호출 + 예외.
 * - 평문 wipe: LDAP 경로에서 password 가 호출 후 wipe 됨(verifyForUser 는 자체 wipe).
 */
class ReauthServiceTest {
    private val stepUpService = mockk<StepUpService>(relaxed = true)
    private val localCredentialService = mockk<LocalCredentialService>(relaxed = true)
    private val ldapProvider = mockk<LdapProvider>(relaxed = true)
    private val externalAccountRepository = mockk<ExternalAccountRepository>(relaxed = true)

    private val sut =
        ReauthService(
            stepUpService = stepUpService,
            localCredentialService = localCredentialService,
            ldapProvider = ldapProvider,
            externalAccountRepository = externalAccountRepository,
        )

    private val userId = UUID.randomUUID()
    private val otherUserId = UUID.randomUUID()
    private val sid = UUID.randomUUID()
    private val ldapProviderId = UUID.randomUUID()
    private val dn = "uid=alice,ou=people,dc=corp"

    private fun pw() = "Secret@1234".toCharArray()

    private fun externalAccount(owner: UUID): ExternalAccount =
        ExternalAccount(
            id = UUID.randomUUID(),
            providerId = ldapProviderId,
            externalSubject = dn,
            userId = owner,
            groups = emptyList(),
            failedAttempts = 0,
            lockedUntil = null,
            lastLoginAt = null,
            createdAt = Instant.parse("2026-01-01T00:00:00Z"),
            updatedAt = Instant.parse("2026-01-01T00:00:00Z"),
        )

    // ── LOCAL ──────────────────────────────────────────────────────────────

    @Test
    fun `LOCAL 성공 — verifyForUser true 면 grant(sid) 호출`() {
        every { localCredentialService.verifyForUser(eq(userId), any()) } returns true

        sut.reauthenticateLocal(userId, sid, pw())

        verify(exactly = 1) { stepUpService.grant(sid) }
    }

    @Test
    fun `LOCAL 실패 — verifyForUser false 면 grant 미호출 + ReauthChallengeFailedException`() {
        every { localCredentialService.verifyForUser(eq(userId), any()) } returns false

        assertThatThrownBy { sut.reauthenticateLocal(userId, sid, pw()) }
            .isInstanceOf(ReauthChallengeFailedException::class.java)

        verify(exactly = 0) { stepUpService.grant(any()) }
    }

    // ── LDAP ───────────────────────────────────────────────────────────────

    @Test
    fun `LDAP 성공 — bind 성공이고 DN 이 현재 user 에 연결됨(EC9) 이면 grant 호출`() {
        val attrs = LdapProvisionAttrs("alice", null, "Alice", dn, emptyList())
        every { ldapProvider.bindForLinking(eq(ldapProviderId), eq("alice"), any()) } returns attrs
        every { externalAccountRepository.findByProviderIdAndExternalSubject(ldapProviderId, dn) } returns
            externalAccount(owner = userId)

        sut.reauthenticateLdap(userId, sid, ldapProviderId, "alice", pw())

        verify(exactly = 1) { stepUpService.grant(sid) }
    }

    @Test
    fun `LDAP 실패 — bind null 이면 grant 미호출 + ReauthChallengeFailedException`() {
        every { ldapProvider.bindForLinking(eq(ldapProviderId), eq("alice"), any()) } returns null

        assertThatThrownBy { sut.reauthenticateLdap(userId, sid, ldapProviderId, "alice", pw()) }
            .isInstanceOf(ReauthChallengeFailedException::class.java)

        verify(exactly = 0) { stepUpService.grant(any()) }
    }

    @Test
    fun `LDAP 실패 — bind 성공이나 DN 이 미연결(EC9) 이면 grant 미호출 + 예외`() {
        val attrs = LdapProvisionAttrs("alice", null, "Alice", dn, emptyList())
        every { ldapProvider.bindForLinking(eq(ldapProviderId), eq("alice"), any()) } returns attrs
        every { externalAccountRepository.findByProviderIdAndExternalSubject(ldapProviderId, dn) } returns null

        assertThatThrownBy { sut.reauthenticateLdap(userId, sid, ldapProviderId, "alice", pw()) }
            .isInstanceOf(ReauthChallengeFailedException::class.java)

        verify(exactly = 0) { stepUpService.grant(any()) }
    }

    @Test
    fun `LDAP 실패 — bind 성공이나 DN 이 타 user 에 연결(EC9) 이면 grant 미호출 + 예외`() {
        val attrs = LdapProvisionAttrs("alice", null, "Alice", dn, emptyList())
        every { ldapProvider.bindForLinking(eq(ldapProviderId), eq("alice"), any()) } returns attrs
        every { externalAccountRepository.findByProviderIdAndExternalSubject(ldapProviderId, dn) } returns
            externalAccount(owner = otherUserId)

        assertThatThrownBy { sut.reauthenticateLdap(userId, sid, ldapProviderId, "alice", pw()) }
            .isInstanceOf(ReauthChallengeFailedException::class.java)

        verify(exactly = 0) { stepUpService.grant(any()) }
    }

    // ── 평문 wipe ─────────────────────────────────────────────────────────────

    @Test
    fun `LDAP 경로 — bindForLinking 호출 후 password 가 wipe 된다`() {
        val captured = slot<CharArray>()
        val attrs = LdapProvisionAttrs("alice", null, "Alice", dn, emptyList())
        every { ldapProvider.bindForLinking(eq(ldapProviderId), eq("alice"), capture(captured)) } returns attrs
        every { externalAccountRepository.findByProviderIdAndExternalSubject(ldapProviderId, dn) } returns
            externalAccount(owner = userId)

        val password = pw()
        sut.reauthenticateLdap(userId, sid, ldapProviderId, "alice", password)

        // ReauthService 의 finally wipe 후 호출자 배열이 비워졌는지 확인 (공백으로 채워짐).
        assertThat(password).containsOnly(' ')
        assertThat(captured.captured).isSameAs(password)
    }
}
