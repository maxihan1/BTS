// LdapProvider.bindForLinking 통합 테스트 — bind만 하고 provision 미호출 (FR-AU-08 계정 연결)

package com.atlas.bts.identity.provider.ldap

import com.atlas.bts.identity.config.TestIntegrationSecurityConfig
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.ldap.CommunicationException
import org.springframework.ldap.core.LdapTemplate
import org.springframework.test.context.ActiveProfiles
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.UUID

/**
 * LdapProvider.bindForLinking 통합 테스트 (FR-AU-08 계정 연결).
 *
 * 일반 로그인(authenticate)과 달리, 계정 연결은 **현재 로그인된 user 에 DN 을 붙이는** 경로이므로
 * bind + 속성 추출만 하고 AutoProvisionService.provision(신규 user/account 생성)은 호출하지 않는다.
 *
 * 검증 시나리오.
 * - L-01 정상 자격증명 → LdapProvisionAttrs 반환 (externalSubject=DN).
 * - L-02 bind 후 user_external_accounts / users 신규 행 0 (provision 미호출 증명).
 * - L-03 잘못된 비밀번호 → null + 신규 행 0.
 * - L-04 비활성/미존재 providerId → null.
 *
 * 사전 조건. infra/ldap/seed.ldif 에 alice/bob 사용자 정의.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@ActiveProfiles("test-integration")
@Import(TestIntegrationSecurityConfig::class)
class LdapProviderBindForLinkingTest : LdapTestcontainersBase() {
    @Autowired
    private lateinit var ldapProvider: LdapProvider

    @Autowired
    private lateinit var jdbc: NamedParameterJdbcTemplate

    private lateinit var providerId: UUID

    @BeforeEach
    fun setUp() {
        jdbc.update("DELETE FROM user_external_accounts", emptyMap<String, Any>())
        jdbc.update("DELETE FROM authn_providers", emptyMap<String, Any>())
        jdbc.update("DELETE FROM users", emptyMap<String, Any>())

        providerId = UUID.randomUUID()
        val ldapUrl = "ldap://${openldap.host}:${openldap.firstMappedPort}"

        jdbc.update(
            """
            INSERT INTO authn_providers (id, type, name, config, enabled)
            VALUES (:id, 'LDAP', 'integration-ldap', :config::jsonb, true)
            """.trimIndent(),
            mapOf(
                "id" to providerId,
                "config" to
                    """
                    {
                        "serverUrl": "$ldapUrl",
                        "baseDn": "dc=example,dc=org",
                        "bindDn": "cn=admin,dc=example,dc=org",
                        "bindPasswordEnv": "BTS_LDAP_BIND_PASSWORD_INTEGRATION",
                        "userSearchBase": "ou=people",
                        "userSearchFilter": "(uid={0})",
                        "groupSearchBase": "ou=groups",
                        "groupSearchFilter": "(member={0})",
                        "lockoutPolicy": {"maxAttempts": 3, "lockoutMinutes": 1, "scope": "PER_USER_PER_PROVIDER"}
                    }
                    """.trimIndent(),
            ),
        )
    }

    private fun externalAccountCount(): Int =
        jdbc.queryForObject(
            "SELECT count(*) FROM user_external_accounts",
            emptyMap<String, Any>(),
            Int::class.java,
        ) ?: error("count(*) 결과 없음")

    private fun userCount(): Int =
        jdbc.queryForObject(
            "SELECT count(*) FROM users",
            emptyMap<String, Any>(),
            Int::class.java,
        ) ?: error("count(*) 결과 없음")

    @Test
    fun `L-01 정상 자격증명 — bindForLinking 이 externalSubject(DN) 를 담은 attrs 반환`() {
        val attrs = ldapProvider.bindForLinking(providerId, "alice", "Test1234!".toCharArray())

        assertThat(attrs).isNotNull()
        // externalSubject 는 LDAP DN 형식이어야 한다 (uid=alice,...).
        assertThat(attrs!!.externalSubject).contains("uid=alice")
    }

    @Test
    fun `L-02 bind 성공 후 provision 미호출 — user_external_accounts 와 users 신규 행 0`() {
        val before = externalAccountCount()
        val beforeUsers = userCount()

        val attrs = ldapProvider.bindForLinking(providerId, "alice", "Test1234!".toCharArray())

        assertThat(attrs).isNotNull()
        // provision 이 호출되지 않았으므로 신규 매핑/사용자 행이 생기지 않아야 한다.
        assertThat(externalAccountCount()).isEqualTo(before)
        assertThat(userCount()).isEqualTo(beforeUsers)
    }

    @Test
    fun `L-03 잘못된 비밀번호 — null + 신규 행 0`() {
        val result = ldapProvider.bindForLinking(providerId, "alice", "wrongpassword".toCharArray())

        assertThat(result).isNull()
        assertThat(externalAccountCount()).isEqualTo(0)
        assertThat(userCount()).isEqualTo(0)
    }

    @Test
    fun `L-04 비활성 providerId — null`() {
        jdbc.update(
            "UPDATE authn_providers SET enabled = false WHERE id = :id",
            mapOf("id" to providerId),
        )

        val result = ldapProvider.bindForLinking(providerId, "alice", "Test1234!".toCharArray())

        assertThat(result).isNull()
    }

    @Test
    fun `L-05 미존재 providerId — null`() {
        val unknown = UUID.randomUUID()

        val result = ldapProvider.bindForLinking(unknown, "alice", "Test1234!".toCharArray())

        assertThat(result).isNull()
    }

    /**
     * L-06 LDAP 서버 통신 불가(Unavailable) → ProviderUnavailableException 시그널 (C1 — EC3 503 실배선).
     *
     * 자격증명 오류([BindOutcome.InvalidCredentials])는 그대로 null 을 유지하지만, 서버 장애
     * ([BindOutcome.Unavailable])는 null 이 아니라 [ProviderUnavailableException] 으로 신호해야
     * [AccountLinkService.link] 를 통과해 컨트롤러가 503 으로 응답할 수 있다(스펙 EC3).
     *
     * 실 LDAP 컨테이너는 "다운" 시뮬레이션이 어려우므로 MockK [LdapTemplate] 으로 [CommunicationException]
     * 을 유발해 Unavailable 경로만 격리 검증한다([LdapProviderUnitTest] S-06 패턴과 동일).
     */
    @Test
    fun `L-06 LDAP 서버 통신 불가 — ProviderUnavailableException 으로 신호`() {
        val mockTemplate = mockk<LdapTemplate>()
        every { mockTemplate.authenticate(any<String>(), any<String>(), any<String>()) } throws
            CommunicationException(javax.naming.CommunicationException("connection refused"))

        val mockConfigService = mockk<LdapProviderConfigService>()
        every { mockConfigService.findEnabledLdapConfig() } returns Pair(providerId, unavailableConfig())

        val isolatedProvider =
            LdapProvider(
                configService = mockConfigService,
                externalAccountRepo = mockk(relaxed = true),
                autoProvisionService = mockk(relaxed = true),
                ldapTemplate = mockTemplate,
                auditLog = mockk(relaxed = true),
            )

        assertThatThrownBy {
            isolatedProvider.bindForLinking(providerId, "alice", "Test1234!".toCharArray())
        }.isInstanceOf(ProviderUnavailableException::class.java)
    }

    /**
     * L-06 보강 — 자격증명 오류는 [ProviderUnavailableException] 이 아니라 그대로 null 을 유지한다.
     * Unavailable 만 예외로 분기하고 InvalidCredentials 는 401(=null) 경로를 보존함을 격리 검증한다.
     */
    @Test
    fun `L-06b 잘못된 비밀번호는 예외 없이 여전히 null`() {
        val mockTemplate = mockk<LdapTemplate>()
        every { mockTemplate.authenticate(any<String>(), any<String>(), any<String>()) } returns false

        val mockConfigService = mockk<LdapProviderConfigService>()
        every { mockConfigService.findEnabledLdapConfig() } returns Pair(providerId, unavailableConfig())

        val isolatedProvider =
            LdapProvider(
                configService = mockConfigService,
                externalAccountRepo = mockk(relaxed = true),
                autoProvisionService = mockk(relaxed = true),
                ldapTemplate = mockTemplate,
                auditLog = mockk(relaxed = true),
            )

        val result = isolatedProvider.bindForLinking(providerId, "alice", "wrongpassword".toCharArray())

        assertThat(result).isNull()
    }

    /** MockK 단위 케이스용 LdapConfig — bind password env 는 PATH(어느 환경에나 존재)로 채운다. */
    private fun unavailableConfig(): LdapConfig =
        LdapConfig(
            serverUrl = "ldap://unreachable:389",
            baseDn = "dc=example,dc=org",
            bindDn = "cn=admin,dc=example,dc=org",
            bindPasswordEnv = "PATH",
            userSearchBase = "ou=people",
            userSearchFilter = "(uid={0})",
            groupSearchBase = "ou=groups",
            groupSearchFilter = "(member={0})",
            lockoutPolicy = LockoutPolicy(maxAttempts = 3, lockoutMinutes = 1),
        )
}
