// LdapProvider 단위 테스트 — MockK 기반, S-01~S-07 + 엣지 케이스 시나리오

package com.atlas.bts.identity.provider.ldap

import com.atlas.bts.identity.spi.AuthnResult
import com.atlas.bts.identity.spi.Credential
import com.atlas.bts.identity.spi.FailureReason
import com.atlas.bts.identity.spi.ProviderType
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.ldap.AuthenticationException
import org.springframework.ldap.CommunicationException
import org.springframework.ldap.core.LdapTemplate
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID
import javax.naming.NamingException

/**
 * LdapProvider 단위 테스트 (MockK).
 * 시간 검증을 위해 Clock.fixed 주입.
 */
class LdapProviderUnitTest {

    private lateinit var configService: LdapProviderConfigService
    private lateinit var externalAccountRepo: ExternalAccountRepository
    private lateinit var ldapTemplate: LdapTemplate
    private lateinit var clock: Clock
    private lateinit var provider: LdapProvider

    private val fixedNow = Instant.parse("2026-05-20T10:00:00Z")
    private val providerId = UUID.fromString("11111111-1111-1111-1111-111111111111")
    private val userId = UUID.fromString("22222222-2222-2222-2222-222222222222")
    private val accountId = UUID.fromString("33333333-3333-3333-3333-333333333333")

    private val sampleConfig = LdapConfig(
        serverUrl = "ldap://openldap:389",
        baseDn = "dc=bts,dc=local",
        bindDn = "cn=admin,dc=bts,dc=local",
        bindPasswordEnv = "BTS_LDAP_BIND_PASSWORD_TEST_NONEXISTENT",
        userSearchBase = "ou=people",
        userSearchFilter = "(uid={0})",
        groupSearchBase = "ou=groups",
        groupSearchFilter = "(member={0})",
        lockoutPolicy = LockoutPolicy(maxAttempts = 3, lockoutMinutes = 1),
    )

    private val sampleAccount = ExternalAccount(
        id = accountId,
        providerId = providerId,
        externalSubject = "uid=alice,ou=people,dc=bts,dc=local",
        userId = userId,
        groups = listOf("cn=engineers,ou=groups,dc=bts,dc=local"),
        failedAttempts = 0,
        lockedUntil = null,
        lastLoginAt = null,
        createdAt = fixedNow,
        updatedAt = fixedNow,
    )

    @BeforeEach
    fun setUp() {
        configService = mockk()
        externalAccountRepo = mockk(relaxed = true)
        ldapTemplate = mockk()
        clock = Clock.fixed(fixedNow, ZoneOffset.UTC)
        provider = LdapProvider(configService, externalAccountRepo, ldapTemplate, clock)
    }

    @Test
    fun `type은 LDAP`() {
        assertThat(provider.type).isEqualTo(ProviderType.LDAP)
    }

    @Test
    fun `supports — LdapBind 반환 true`() {
        assertThat(provider.supports(Credential.LdapBind("alice", "pw".toCharArray()))).isTrue()
    }

    @Test
    fun `supports — UsernamePassword 반환 false`() {
        assertThat(provider.supports(Credential.UsernamePassword("alice", "pw".toCharArray()))).isFalse()
    }

    @Test
    fun `S-01 기존 사용자 로그인 성공`() {
        every { configService.findEnabledLdapConfig() } returns Pair(providerId, sampleConfig)
        every { ldapTemplate.authenticate(any(), any(), any()) } returns true
        every {
            externalAccountRepo.findByProviderIdAndExternalSubject(providerId, any())
        } returns sampleAccount
        every { externalAccountRepo.updateLastLoginAt(accountId, any()) } returns Unit

        val result = provider.authenticate(Credential.LdapBind("alice", "Test1234!".toCharArray()))

        assertThat(result).isInstanceOf(AuthnResult.Success::class.java)
        val success = result as AuthnResult.Success
        assertThat(success.principal.userId).isEqualTo(userId)
        assertThat(success.principal.providerType).isEqualTo(ProviderType.LDAP)
    }

    @Test
    fun `S-02 첫 로그인 — 자동 프로비저닝`() {
        every { configService.findEnabledLdapConfig() } returns Pair(providerId, sampleConfig)
        every { ldapTemplate.authenticate(any(), any(), any()) } returns true
        every {
            externalAccountRepo.findByProviderIdAndExternalSubject(providerId, any())
        } returns null  // 기존 매핑 없음
        every {
            externalAccountRepo.provisionUser(any(), any(), any(), any(), any(), any())
        } returns sampleAccount
        every { externalAccountRepo.updateLastLoginAt(accountId, any()) } returns Unit

        val result = provider.authenticate(Credential.LdapBind("alice", "Test1234!".toCharArray()))

        assertThat(result).isInstanceOf(AuthnResult.Success::class.java)
        verify { externalAccountRepo.provisionUser(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `S-03 잘못된 비밀번호 — INVALID_CREDENTIALS`() {
        every { configService.findEnabledLdapConfig() } returns Pair(providerId, sampleConfig)
        every { ldapTemplate.authenticate(any(), any(), any()) } throws AuthenticationException(NamingException("bad credentials"))
        every {
            externalAccountRepo.findByProviderIdAndExternalSubject(providerId, any())
        } returns sampleAccount
        every { externalAccountRepo.incrementFailedAttempts(accountId) } returns Unit

        val result = provider.authenticate(Credential.LdapBind("alice", "wrong".toCharArray()))

        assertThat(result).isEqualTo(AuthnResult.Failure(FailureReason.INVALID_CREDENTIALS))
        verify { externalAccountRepo.incrementFailedAttempts(accountId) }
    }

    @Test
    fun `S-04 사용자 미존재 — INVALID_CREDENTIALS (enumeration 방지)`() {
        every { configService.findEnabledLdapConfig() } returns Pair(providerId, sampleConfig)
        every { ldapTemplate.authenticate(any(), any(), any()) } throws AuthenticationException(NamingException("user not found"))
        every {
            externalAccountRepo.findByProviderIdAndExternalSubject(providerId, any())
        } returns null  // 매핑 없음

        val result = provider.authenticate(Credential.LdapBind("nonexistent", "anything".toCharArray()))

        // USER_NOT_FOUND 대신 INVALID_CREDENTIALS — enumeration 방지
        assertThat(result).isEqualTo(AuthnResult.Failure(FailureReason.INVALID_CREDENTIALS))
    }

    @Test
    fun `S-05 잠금 상태 — ACCOUNT_LOCKED`() {
        val lockedAccount = sampleAccount.copy(
            lockedUntil = fixedNow.plus(5, ChronoUnit.MINUTES),  // 아직 잠금 중
        )
        every { configService.findEnabledLdapConfig() } returns Pair(providerId, sampleConfig)
        every {
            externalAccountRepo.findByProviderIdAndExternalSubject(providerId, any())
        } returns lockedAccount

        val result = provider.authenticate(Credential.LdapBind("alice", "Test1234!".toCharArray()))

        assertThat(result).isEqualTo(AuthnResult.Failure(FailureReason.ACCOUNT_LOCKED))
    }

    @Test
    fun `S-05 잠금 만료 후 — 인증 시도 허용`() {
        val expiredLock = sampleAccount.copy(
            lockedUntil = fixedNow.minus(1, ChronoUnit.SECONDS),  // 이미 만료
        )
        every { configService.findEnabledLdapConfig() } returns Pair(providerId, sampleConfig)
        every { ldapTemplate.authenticate(any(), any(), any()) } returns true
        every {
            externalAccountRepo.findByProviderIdAndExternalSubject(providerId, any())
        } returns expiredLock
        every { externalAccountRepo.updateLastLoginAt(accountId, any()) } returns Unit

        val result = provider.authenticate(Credential.LdapBind("alice", "Test1234!".toCharArray()))

        assertThat(result).isInstanceOf(AuthnResult.Success::class.java)
    }

    @Test
    fun `S-06 LDAP 서버 장애 — PROVIDER_UNAVAILABLE`() {
        every { configService.findEnabledLdapConfig() } returns Pair(providerId, sampleConfig)
        every { ldapTemplate.authenticate(any(), any(), any()) } throws CommunicationException(NamingException("connection refused"))
        every {
            externalAccountRepo.findByProviderIdAndExternalSubject(providerId, any())
        } returns sampleAccount

        val result = provider.authenticate(Credential.LdapBind("alice", "Test1234!".toCharArray()))

        assertThat(result).isEqualTo(AuthnResult.Failure(FailureReason.PROVIDER_UNAVAILABLE))
    }

    @Test
    fun `S-07 그룹 정보 저장 — provisionUser 호출 시 groups 전달`() {
        every { configService.findEnabledLdapConfig() } returns Pair(providerId, sampleConfig)
        every { ldapTemplate.authenticate(any(), any(), any()) } returns true
        every {
            externalAccountRepo.findByProviderIdAndExternalSubject(providerId, any())
        } returns null  // 첫 로그인
        every {
            externalAccountRepo.provisionUser(any(), any(), any(), any(), any(), any())
        } returns sampleAccount
        every { externalAccountRepo.updateLastLoginAt(accountId, any()) } returns Unit

        provider.authenticate(Credential.LdapBind("alice", "Test1234!".toCharArray()))

        // groups 파라미터가 전달됐는지 — 정확한 값 대신 호출 여부만 (LdapTemplate mock 한계)
        verify { externalAccountRepo.provisionUser(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `authn_providers 없음 — PROVIDER_UNAVAILABLE (lazy init)`() {
        every { configService.findEnabledLdapConfig() } returns null

        val result = provider.authenticate(Credential.LdapBind("alice", "Test1234!".toCharArray()))

        assertThat(result).isEqualTo(AuthnResult.Failure(FailureReason.PROVIDER_UNAVAILABLE))
    }

    @Test
    fun `bind password env var 미설정 — PROVIDER_UNAVAILABLE`() {
        // sampleConfig.bindPasswordEnv = "BTS_LDAP_BIND_PASSWORD_TEST_NONEXISTENT" — 테스트 환경에 없음
        every { configService.findEnabledLdapConfig() } returns Pair(providerId, sampleConfig)

        val result = provider.authenticate(Credential.LdapBind("alice", "Test1234!".toCharArray()))

        assertThat(result).isEqualTo(AuthnResult.Failure(FailureReason.PROVIDER_UNAVAILABLE))
    }

    @Test
    fun `password wipe — 인증 완료 후 CharArray 가 비워짐`() {
        every { configService.findEnabledLdapConfig() } returns Pair(providerId, sampleConfig)
        // bind password 미설정으로 조기 반환되지만 wipe는 항상 발생해야 함

        val password = "Test1234!".toCharArray()
        provider.authenticate(Credential.LdapBind("alice", password))

        // wipe 후 배열은 공백 문자로 채워짐
        assertThat(password).containsOnly(' ')
    }
}
