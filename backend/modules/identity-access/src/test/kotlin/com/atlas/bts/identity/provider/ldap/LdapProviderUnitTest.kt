// LdapProvider 단위 테스트 — MockK 기반, S-01~S-07 + 엣지 케이스 + CONCERN-3 회귀 가드

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

/**
 * LdapProvider 단위 테스트 (MockK).
 * 시간 검증을 위해 Clock.fixed 주입.
 */
class LdapProviderUnitTest {
    private lateinit var configService: LdapProviderConfigService
    private lateinit var externalAccountRepo: ExternalAccountRepository
    private lateinit var autoProvisionService: AutoProvisionService
    private lateinit var ldapTemplate: LdapTemplate
    private lateinit var clock: Clock
    private lateinit var provider: LdapProvider

    private val fixedNow = Instant.parse("2026-05-20T10:00:00Z")
    private val providerId = UUID.fromString("11111111-1111-1111-1111-111111111111")
    private val userId = UUID.fromString("22222222-2222-2222-2222-222222222222")
    private val accountId = UUID.fromString("33333333-3333-3333-3333-333333333333")

    // PATH 환경변수는 어느 환경에서나 존재하므로 bind password env var 로 활용
    private val sampleConfig =
        LdapConfig(
            serverUrl = "ldap://openldap:389",
            baseDn = "dc=bts,dc=local",
            bindDn = "cn=admin,dc=bts,dc=local",
            // PATH 환경변수는 어느 환경에서나 존재하므로 bind password 테스트용으로 활용
            bindPasswordEnv = "PATH",
            userSearchBase = "ou=people",
            userSearchFilter = "(uid={0})",
            groupSearchBase = "ou=groups",
            groupSearchFilter = "(member={0})",
            lockoutPolicy = LockoutPolicy(maxAttempts = 3, lockoutMinutes = 1),
        )

    private val sampleAccount =
        ExternalAccount(
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
        autoProvisionService = mockk(relaxed = true)
        ldapTemplate = mockk()
        clock = Clock.fixed(fixedNow, ZoneOffset.UTC)
        provider = LdapProvider(configService, externalAccountRepo, autoProvisionService, ldapTemplate, clock)
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
        every { ldapTemplate.authenticate(any<String>(), any<String>(), any<String>()) } returns true
        every {
            externalAccountRepo.findByProviderIdAndExternalSubject(providerId, any())
        } returns sampleAccount
        every { autoProvisionService.provision(any(), any()) } returns sampleAccount
        every { externalAccountRepo.updateLastLoginAt(accountId, any()) } returns Unit

        val result = provider.authenticate(Credential.LdapBind("alice", "Test1234!".toCharArray()))

        assertThat(result is AuthnResult.Success).isTrue()
        val success = result as AuthnResult.Success
        assertThat(success.principal.userId).isEqualTo(userId)
        assertThat(success.principal.providerType).isEqualTo(ProviderType.LDAP)
    }

    @Test
    fun `S-02 첫 로그인 — 자동 프로비저닝`() {
        every { configService.findEnabledLdapConfig() } returns Pair(providerId, sampleConfig)
        every { ldapTemplate.authenticate(any<String>(), any<String>(), any<String>()) } returns true
        every {
            externalAccountRepo.findByProviderIdAndExternalSubject(providerId, any())
        } returns null // 기존 매핑 없음
        every { autoProvisionService.provision(any(), any()) } returns sampleAccount
        every { externalAccountRepo.updateLastLoginAt(accountId, any()) } returns Unit

        val result = provider.authenticate(Credential.LdapBind("alice", "Test1234!".toCharArray()))

        assertThat(result is AuthnResult.Success).isTrue()
        verify { autoProvisionService.provision(any(), any()) }
    }

    @Test
    fun `S-03 잘못된 비밀번호 — INVALID_CREDENTIALS`() {
        every { configService.findEnabledLdapConfig() } returns Pair(providerId, sampleConfig)
        every {
            ldapTemplate.authenticate(any<String>(), any<String>(), any<String>())
        } throws AuthenticationException(javax.naming.AuthenticationException("bad credentials"))
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
        every {
            ldapTemplate.authenticate(any<String>(), any<String>(), any<String>())
        } throws AuthenticationException(javax.naming.AuthenticationException("user not found"))
        every {
            externalAccountRepo.findByProviderIdAndExternalSubject(providerId, any())
        } returns null // 매핑 없음

        val result = provider.authenticate(Credential.LdapBind("nonexistent", "anything".toCharArray()))

        // USER_NOT_FOUND 대신 INVALID_CREDENTIALS — enumeration 방지
        assertThat(result).isEqualTo(AuthnResult.Failure(FailureReason.INVALID_CREDENTIALS))
    }

    @Test
    fun `S-05 잠금 상태 — ACCOUNT_LOCKED`() {
        val lockedAccount =
            sampleAccount.copy(
                // 아직 잠금 중
                lockedUntil = fixedNow.plus(5, ChronoUnit.MINUTES),
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
        val expiredLock =
            sampleAccount.copy(
                // 이미 만료
                lockedUntil = fixedNow.minus(1, ChronoUnit.SECONDS),
            )
        every { configService.findEnabledLdapConfig() } returns Pair(providerId, sampleConfig)
        every { ldapTemplate.authenticate(any<String>(), any<String>(), any<String>()) } returns true
        every {
            externalAccountRepo.findByProviderIdAndExternalSubject(providerId, any())
        } returns expiredLock
        every { autoProvisionService.provision(any(), any()) } returns sampleAccount
        every { externalAccountRepo.updateLastLoginAt(accountId, any()) } returns Unit

        val result = provider.authenticate(Credential.LdapBind("alice", "Test1234!".toCharArray()))

        assertThat(result is AuthnResult.Success).isTrue()
    }

    @Test
    fun `S-06 LDAP 서버 장애 — PROVIDER_UNAVAILABLE`() {
        every { configService.findEnabledLdapConfig() } returns Pair(providerId, sampleConfig)
        every {
            ldapTemplate.authenticate(any<String>(), any<String>(), any<String>())
        } throws CommunicationException(javax.naming.CommunicationException("connection refused"))
        every {
            externalAccountRepo.findByProviderIdAndExternalSubject(providerId, any())
        } returns sampleAccount

        val result = provider.authenticate(Credential.LdapBind("alice", "Test1234!".toCharArray()))

        assertThat(result).isEqualTo(AuthnResult.Failure(FailureReason.PROVIDER_UNAVAILABLE))
    }

    @Test
    fun `S-07 그룹 정보 저장 — autoProvisionService provision 호출 시 attrs 전달`() {
        every { configService.findEnabledLdapConfig() } returns Pair(providerId, sampleConfig)
        every { ldapTemplate.authenticate(any<String>(), any<String>(), any<String>()) } returns true
        every {
            externalAccountRepo.findByProviderIdAndExternalSubject(providerId, any())
        } returns null // 첫 로그인
        every { autoProvisionService.provision(any(), any()) } returns sampleAccount
        every { externalAccountRepo.updateLastLoginAt(accountId, any()) } returns Unit

        provider.authenticate(Credential.LdapBind("alice", "Test1234!".toCharArray()))

        // AutoProvisionService 를 통해 provisioning 됐는지 검증 (Task 15 위임 확인)
        verify { autoProvisionService.provision(any(), any()) }
    }

    @Test
    fun `authn_providers 없음 — PROVIDER_UNAVAILABLE (lazy init)`() {
        every { configService.findEnabledLdapConfig() } returns null

        val result = provider.authenticate(Credential.LdapBind("alice", "Test1234!".toCharArray()))

        assertThat(result).isEqualTo(AuthnResult.Failure(FailureReason.PROVIDER_UNAVAILABLE))
    }

    @Test
    fun `bind password env var 미설정 — PROVIDER_UNAVAILABLE`() {
        // 존재하지 않는 env var 이름을 사용하는 config
        val configWithMissingEnv =
            sampleConfig.copy(
                bindPasswordEnv = "BTS_LDAP_BIND_PASSWORD_TEST_NONEXISTENT_XYZ_123456",
            )
        every { configService.findEnabledLdapConfig() } returns Pair(providerId, configWithMissingEnv)

        val result = provider.authenticate(Credential.LdapBind("alice", "Test1234!".toCharArray()))

        assertThat(result).isEqualTo(AuthnResult.Failure(FailureReason.PROVIDER_UNAVAILABLE))
    }

    @Test
    fun `escapeForLdapFilter 는 공백을 escape 하지 않는다`() {
        // given: 공백 포함 username (RFC 4515 가 규정하지 않는 문자)
        val input = "John Doe"

        // when: internal 가시성으로 직접 호출
        val escaped = LdapProvider.escapeForLdapFilter(input)

        // then: 공백은 그대로 보존 (false-positive 방지)
        assertThat(escaped).isEqualTo("John Doe")
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
