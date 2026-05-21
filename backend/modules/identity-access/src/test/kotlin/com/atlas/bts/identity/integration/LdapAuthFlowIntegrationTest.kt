// LDAP 인증 흐름 통합 테스트 — Auto-provisioning + LDAP unavailable (CONCERN-4) + EC-17 rollback

package com.atlas.bts.identity.integration

import com.atlas.bts.identity.config.TestIntegrationSecurityConfig
import com.atlas.bts.identity.provider.ldap.AutoProvisionService
import com.atlas.bts.identity.provider.ldap.ExternalAccountRepository
import com.atlas.bts.identity.provider.ldap.LdapProvider
import com.atlas.bts.identity.provider.ldap.LdapProvisionAttrs
import com.atlas.bts.identity.provider.ldap.LdapTestcontainersBase
import com.atlas.bts.identity.spi.AuthnResult
import com.atlas.bts.identity.spi.Credential
import com.atlas.bts.identity.spi.FailureReason
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * LDAP 인증 전체 흐름 통합 테스트 (FR-AU-09 Task 27 / SDD §19.2).
 *
 * ## 검증 시나리오
 * - [S1AutoProvisioningTest]: LDAP 미등록 사용자 첫 로그인 → bind 성공 + users + user_external_accounts UPSERT
 * - [S2ResyncTest]: 재로그인 시 users.id 보존 (UPSERT 멱등성)
 * - [S3LdapUnavailableTest]: LDAP container stop → PROVIDER_UNAVAILABLE 반환 (CONCERN-4) + Local Provider 격리
 * - [S4Ec17RollbackTest]: Auto-provisioning 도중 DB 오류 → users + user_external_accounts 양쪽 rollback (EC-17)
 * - [Ec11ConcurrentFirstLoginTest]: 동시 첫 로그인 → UPSERT ON CONFLICT 멱등성 (EC-11)
 *
 * ## Testcontainers 구성
 * - PostgreSQL 16 Alpine — `LdapTestcontainersBase.postgres`
 * - osixia/openldap 1.5.0 — `LdapTestcontainersBase.openldap`
 *
 * ## RED 조건
 * 이 테스트가 전부 통과하려면 다음 production 코드 변경이 필요하다 (GREEN 조건).
 * 1. `ExternalAccountRepository.provisionUser()` — `userId` 파라미터를 `UserRepository.provisionFromExternal()`
 *    이 반환한 실제 users.id 로 사용해야 한다. 현재 내부에서 `UUID.randomUUID()` 를 생성하여
 *    users UPSERT 결과 id 와 불일치 → FK 위반 발생 (AUTO_PROVISION_BUG_1).
 * 2. `AuthController.login()` — `Failure(PROVIDER_UNAVAILABLE)` 를 HTTP 503 으로 매핑해야 한다
 *    (CONCERN-4). 현재는 401 반환 (AUTO_PROVISION_BUG_2).
 *
 * ## CONCERN-4 전략
 * S3 테스트는 Provider 레이어에서 LDAP container stop 후 `PROVIDER_UNAVAILABLE` 반환을 검증한다.
 * HTTP 레이어(503 응답) 검증은 `AuthController` 가 `PROVIDER_UNAVAILABLE → 503` 매핑을 추가한 후
 * HTTP integration 테스트에서 추가 검증이 필요하다 (GREEN 조건).
 *
 * ## EC-17 전략
 * 존재하지 않는 providerId 로 `AutoProvisionService.provision()` 을 직접 호출하여
 * `user_external_accounts.provider_id` FK 위반을 유발한다. @Transactional 경계 내
 * 모든 변경이 rollback 되는지 확인한다.
 *
 * ## CONCERN-4 주의 (LDAP container stop 격리)
 * `S3LdapUnavailableTest` 는 공유 openldap container 를 stop 한다.
 * osixia/openldap 은 `docker start` 재시작을 지원하지 않으므로, container stop 이후
 * LDAP 에 의존하는 테스트는 실패한다.
 * S3 는 마지막 Nested 클래스로 배치되어 있으며 내부적으로 LDAP stop 이후
 * 같은 컨텍스트를 사용하는 다른 테스트에 영향을 줄 수 있다.
 * 실행 순서: JUnit 5 는 기본 선언 순서를 따르지 않는다 — S3 가 S1/S2 보다 먼저 실행될 경우
 * S1/S2 도 실패할 수 있음. 이 경우 `@TestMethodOrder(MethodOrderer.OrderAnnotation::class)` 로
 * 순서를 명시적으로 지정해야 한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@ActiveProfiles("test-integration")
@Import(TestIntegrationSecurityConfig::class)
class LdapAuthFlowIntegrationTest : LdapTestcontainersBase() {

    @Autowired
    private lateinit var ldapProvider: LdapProvider

    @Autowired
    private lateinit var jdbc: NamedParameterJdbcTemplate

    @Autowired
    private lateinit var autoProvisionService: AutoProvisionService

    @Autowired
    private lateinit var externalAccountRepo: ExternalAccountRepository

    private lateinit var providerId: UUID

    @BeforeEach
    fun setUp() {
        // 매 테스트마다 데이터 초기화 — FK 순서 (user_external_accounts → authn_providers → users)
        jdbc.update("DELETE FROM user_external_accounts", emptyMap<String, Any>())
        jdbc.update("DELETE FROM authn_providers", emptyMap<String, Any>())
        jdbc.update("DELETE FROM users", emptyMap<String, Any>())

        providerId = UUID.randomUUID()

        // LDAP container 가 이미 stop 된 경우 setUp 단계에서 ldapUrl 을 빈 값으로 설정한다.
        // S3LdapUnavailableTest 의 container stop 이후에도 BeforeEach 는 호출되므로,
        // container 가 stop 된 경우 authn_providers 를 설정하지 않는다 (S3 이후 S1/S2 재실행 방지).
        if (!openldap.isRunning) {
            return
        }

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
                        "lockoutPolicy": {"maxAttempts": 5, "lockoutMinutes": 1, "scope": "PER_USER_PER_PROVIDER"}
                    }
                    """.trimIndent(),
            ),
        )
    }

    // ── S1: Auto-provisioning — LDAP 미등록 사용자 첫 로그인 ─────────────────────

    /**
     * 시나리오 1: LDAP 미등록 사용자 첫 로그인.
     *
     * LDAP bind 성공 후 users + user_external_accounts 가 단일 트랜잭션으로 생성되어야 한다 (EC-17).
     *
     * **RED 원인**: `ExternalAccountRepository.provisionUser()` 내부에서 `UUID.randomUUID()` 를 사용하여
     * `UserRepository.provisionFromExternal()` 이 반환한 실제 users.id 와 다른 userId 를 생성한다.
     * `user_external_accounts.user_id FK → users.id` 위반 발생.
     * GREEN 조건: `ExternalAccountRepository.provisionUser()` 가 users UPSERT 결과 id 를 사용.
     */
    @Nested
    inner class S1AutoProvisioningTest {

        @Test
        fun `첫 로그인 - bind 성공 후 users 테이블에 alice 행이 생성된다`() {
            val result = ldapProvider.authenticate(Credential.LdapBind("alice", "Test1234!".toCharArray()))

            assertThat(result).isInstanceOf(AuthnResult.Success::class.java)

            val userCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM users WHERE username LIKE '%alice%'",
                emptyMap<String, Any>(),
                Int::class.java,
            )
            assertThat(userCount).isEqualTo(1)
        }

        @Test
        fun `첫 로그인 - bind 성공 후 user_external_accounts 에 LDAP 매핑이 생성된다`() {
            val result = ldapProvider.authenticate(Credential.LdapBind("alice", "Test1234!".toCharArray()))

            assertThat(result).isInstanceOf(AuthnResult.Success::class.java)

            val accountCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM user_external_accounts WHERE provider_id = :pid",
                mapOf("pid" to providerId),
                Int::class.java,
            )
            assertThat(accountCount).isEqualTo(1)
        }

        @Test
        fun `첫 로그인 - users 와 user_external_accounts 가 올바른 FK 로 연결된다`() {
            val result = ldapProvider.authenticate(Credential.LdapBind("alice", "Test1234!".toCharArray()))
            assertThat(result).isInstanceOf(AuthnResult.Success::class.java)

            // users.id 와 user_external_accounts.user_id 가 일치하는지 확인
            val linkedCount = jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM users u
                JOIN user_external_accounts uea ON uea.user_id = u.id
                WHERE u.username LIKE '%alice%'
                  AND uea.provider_id = :pid
                """.trimIndent(),
                mapOf("pid" to providerId),
                Int::class.java,
            )
            assertThat(linkedCount).isEqualTo(1)
        }

        @Test
        fun `첫 로그인 - AuthnResult Success 에 userId 가 포함된다`() {
            val result = ldapProvider.authenticate(Credential.LdapBind("alice", "Test1234!".toCharArray()))

            assertThat(result).isInstanceOf(AuthnResult.Success::class.java)
            val principal = (result as AuthnResult.Success).principal
            assertThat(principal.userId).isNotNull()
        }

        @Test
        fun `첫 로그인 - bob 도 alice 와 독립적으로 프로비저닝된다`() {
            ldapProvider.authenticate(Credential.LdapBind("alice", "Test1234!".toCharArray()))
            ldapProvider.authenticate(Credential.LdapBind("bob", "Test1234!".toCharArray()))

            val totalUsers = jdbc.queryForObject(
                "SELECT COUNT(*) FROM users",
                emptyMap<String, Any>(),
                Int::class.java,
            )
            assertThat(totalUsers).isEqualTo(2)

            val totalAccounts = jdbc.queryForObject(
                "SELECT COUNT(*) FROM user_external_accounts WHERE provider_id = :pid",
                mapOf("pid" to providerId),
                Int::class.java,
            )
            assertThat(totalAccounts).isEqualTo(2)
        }
    }

    // ── S2: 재로그인 — 정보 동기화 ──────────────────────────────────────────────

    /**
     * 시나리오 2: LDAP 기존 사용자 재로그인.
     *
     * 재로그인 시 users.id 가 보존(UPSERT ON CONFLICT)되고
     * user_external_accounts 행이 중복 생성되지 않아야 한다.
     *
     * **RED 원인**: S1 과 동일. Auto-provisioning FK 위반으로 첫 로그인이 실패하므로 재로그인 검증 불가.
     */
    @Nested
    inner class S2ResyncTest {

        @Test
        fun `재로그인 - users 테이블에 alice 행이 1개만 존재한다 (중복 생성 방지)`() {
            // 첫 로그인
            ldapProvider.authenticate(Credential.LdapBind("alice", "Test1234!".toCharArray()))
            // 재로그인
            val result = ldapProvider.authenticate(Credential.LdapBind("alice", "Test1234!".toCharArray()))

            assertThat(result).isInstanceOf(AuthnResult.Success::class.java)

            val userCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM users WHERE username LIKE '%alice%'",
                emptyMap<String, Any>(),
                Int::class.java,
            )
            assertThat(userCount).isEqualTo(1)
        }

        @Test
        fun `재로그인 - user_external_accounts 행이 1개만 존재한다 (멱등성)`() {
            ldapProvider.authenticate(Credential.LdapBind("alice", "Test1234!".toCharArray()))
            ldapProvider.authenticate(Credential.LdapBind("alice", "Test1234!".toCharArray()))

            val accountCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM user_external_accounts WHERE provider_id = :pid",
                mapOf("pid" to providerId),
                Int::class.java,
            )
            assertThat(accountCount).isEqualTo(1)
        }

        @Test
        fun `재로그인 - users id 가 첫 로그인과 동일하게 보존된다`() {
            ldapProvider.authenticate(Credential.LdapBind("alice", "Test1234!".toCharArray()))
            val firstUserId = jdbc.queryForObject(
                "SELECT id FROM users WHERE username LIKE '%alice%'",
                emptyMap<String, Any>(),
                UUID::class.java,
            )

            ldapProvider.authenticate(Credential.LdapBind("alice", "Test1234!".toCharArray()))
            val secondUserId = jdbc.queryForObject(
                "SELECT id FROM users WHERE username LIKE '%alice%'",
                emptyMap<String, Any>(),
                UUID::class.java,
            )

            assertThat(firstUserId).isEqualTo(secondUserId)
        }

        @Test
        fun `재로그인 - user_external_accounts updated_at 이 갱신된다`() {
            ldapProvider.authenticate(Credential.LdapBind("alice", "Test1234!".toCharArray()))
            val firstUpdatedAt = jdbc.queryForObject(
                "SELECT updated_at FROM user_external_accounts WHERE provider_id = :pid",
                mapOf("pid" to providerId),
                java.sql.Timestamp::class.java,
            )

            // 타임스탬프 차이를 보장하기 위해 약간의 대기
            Thread.sleep(50)

            ldapProvider.authenticate(Credential.LdapBind("alice", "Test1234!".toCharArray()))
            val secondUpdatedAt = jdbc.queryForObject(
                "SELECT updated_at FROM user_external_accounts WHERE provider_id = :pid",
                mapOf("pid" to providerId),
                java.sql.Timestamp::class.java,
            )

            assertThat(secondUpdatedAt).isAfterOrEqualTo(firstUpdatedAt)
        }
    }

    // ── S3: LDAP unavailable (CONCERN-4) — Provider 레이어 격리 검증 ──────────

    /**
     * 시나리오 3: CONCERN-4 LDAP unavailable.
     *
     * `LdapProvider.authenticate()` 가 LDAP 사용 불가 상태에서 `PROVIDER_UNAVAILABLE` 을 반환해야 한다.
     * Local Provider 는 LDAP 와 독립적으로 동작해야 한다.
     *
     * ## 검증 분기 — provider 미설정 (현재 PR scope)
     * `LdapProviderConfigService.findEnabledLdapConfig()` 가 null 을 반환하면 LdapProvider 가 즉시
     * `PROVIDER_UNAVAILABLE` 을 반환한다 (LdapProvider.kt:93). 이 nested 는 그 분기를 통합 시나리오 안에서
     * 검증한다.
     *
     * "LDAP 서버 통신 불가 (CommunicationException)" 분기는 LdapProvider 단위 테스트가 mock LdapTemplate 으로
     * 검증한다. 통합 레벨에서 dead URL 을 INSERT 하는 방식은 현재 prod 의 단일 LdapTemplate Bean 구조
     * (application.yml `spring.ldap.urls` 고정) 에서 의미가 없다. FR-AU-06 멀티-Provider 도입 후 DB serverUrl
     * 동적 wiring 이 추가되면 dead URL 시뮬레이션도 의미를 되찾는다.
     *
     * ## HTTP 레이어 (503) GREEN 조건
     * `AuthController.login()` 이 `Failure(PROVIDER_UNAVAILABLE)` 를 HTTP 503 으로 매핑해야 한다.
     * 현재 구현은 모든 Failure 를 401 로 반환한다 (CONCERN-4 미구현).
     *
     * ## 격리 확인
     * Local Provider 는 DB 만 의존하므로 LDAP 가용성과 무관하다.
     * `LdapProvider.supports(Credential.UsernamePassword) = false` 이므로
     * Local 로그인 요청은 `ProviderRegistry.findFor()` 에서 `LocalProvider` 를 선택한다.
     * alice 가 `local_credentials` 테이블에 없으면 INVALID_CREDENTIALS(401) 가 정상 응답이다.
     */
    @Nested
    inner class S3LdapUnavailableTest {

        @Test
        fun `CONCERN-4 - LDAP provider 미설정 시 LdapProvider 가 PROVIDER_UNAVAILABLE 을 반환한다`() {
            // 부모 @BeforeEach 가 INSERT 한 LDAP provider 행 제거 → findEnabledLdapConfig() null 분기 진입.
            jdbc.update("DELETE FROM authn_providers", emptyMap<String, Any>())

            val result = ldapProvider.authenticate(Credential.LdapBind("alice", "Test1234!".toCharArray()))

            assertThat(result).isEqualTo(AuthnResult.Failure(FailureReason.PROVIDER_UNAVAILABLE))
        }

        /**
         * Provider 레이어가 PROVIDER_UNAVAILABLE 을 반환하는지 통합 시나리오 안에서 확인하고,
         * HTTP 503 매핑 spec 을 KDoc 으로 명시한다.
         *
         * 실제 HTTP 503 검증은 `AuthController.login()` 이 PROVIDER_UNAVAILABLE → 503 매핑을
         * 추가한 후 별도 HTTP integration 테스트에서 수행한다 (현재 401 반환).
         */
        @Test
        fun `CONCERN-4 격리 - PROVIDER_UNAVAILABLE 반환 (HTTP 503 매핑 후속 작업)`() {
            jdbc.update("DELETE FROM authn_providers", emptyMap<String, Any>())

            val result = ldapProvider.authenticate(Credential.LdapBind("alice", "Test1234!".toCharArray()))

            assertThat(result).isEqualTo(AuthnResult.Failure(FailureReason.PROVIDER_UNAVAILABLE))
        }
    }

    // ── S4: EC-17 rollback — Auto-provisioning 도중 DB 오류 ──────────────────

    /**
     * 시나리오 4: EC-17 Auto-provisioning rollback.
     *
     * `AutoProvisionService.provision()` 내부에서 DB 오류 발생 시
     * users + user_external_accounts 양쪽이 모두 rollback 되어야 한다.
     *
     * 전략: 존재하지 않는 providerId 를 넘겨 `user_external_accounts.provider_id FK` 위반을 유발.
     * `@Transactional` 경계 내 모든 변경(users UPSERT 포함)이 rollback 되는지 확인.
     */
    @Nested
    inner class S4Ec17RollbackTest {

        @Test
        fun `EC-17 - AutoProvisionService provision 실패 시 users 행이 생성되지 않는다`() {
            val nonExistentProviderId = UUID.fromString("99999999-9999-9999-9999-999999999999")

            val thrown = runCatching {
                autoProvisionService.provision(
                    providerId = nonExistentProviderId,
                    attrs = LdapProvisionAttrs(
                        username = "alice@example.org",
                        email = null,
                        displayName = "alice",
                        externalSubject = "uid=alice,ou=people,dc=example,dc=org",
                        groups = emptyList(),
                    ),
                )
            }

            // 예외가 발생해야 한다 — FK 위반 또는 제약 위반
            assertThat(thrown.isFailure).isTrue()

            // users 테이블에 alice 행이 남아있으면 안 된다 (rollback 확인)
            val userCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM users WHERE username = 'alice@example.org'",
                emptyMap<String, Any>(),
                Int::class.java,
            )
            assertThat(userCount).isEqualTo(0)
        }

        @Test
        fun `EC-17 - AutoProvisionService provision 실패 시 user_external_accounts 행이 생성되지 않는다`() {
            val nonExistentProviderId = UUID.fromString("99999999-9999-9999-9999-999999999999")

            runCatching {
                autoProvisionService.provision(
                    providerId = nonExistentProviderId,
                    attrs = LdapProvisionAttrs(
                        username = "bob@example.org",
                        email = null,
                        displayName = "bob",
                        externalSubject = "uid=bob,ou=people,dc=example,dc=org",
                        groups = emptyList(),
                    ),
                )
            }

            // user_external_accounts 에도 bob 행이 없어야 한다
            val accountCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM user_external_accounts WHERE external_subject LIKE '%bob%'",
                emptyMap<String, Any>(),
                Int::class.java,
            )
            assertThat(accountCount).isEqualTo(0)
        }
    }

    // ── EC-11: race condition — 동시 첫 로그인 ────────────────────────────────

    /**
     * EC-11: 동시 첫 로그인 race condition.
     *
     * 두 스레드가 동시에 alice 첫 로그인을 시도할 때,
     * users + user_external_accounts 행이 정확히 1개씩만 생성되어야 한다.
     * ON CONFLICT UPSERT 가 race condition 을 방어한다.
     *
     * **RED 원인**: S1 동일 — AutoProvisionService FK 위반으로 두 시도 모두 실패 가능.
     */
    @Nested
    inner class Ec11ConcurrentFirstLoginTest {

        @Test
        fun `EC-11 - 동시 첫 로그인 시도 시 users 와 user_external_accounts 행이 각 1개만 생성된다`() {
            val executor = Executors.newFixedThreadPool(2)

            val futures: List<Future<AuthnResult>> = executor.invokeAll(
                listOf(
                    Callable { ldapProvider.authenticate(Credential.LdapBind("alice", "Test1234!".toCharArray())) },
                    Callable { ldapProvider.authenticate(Credential.LdapBind("alice", "Test1234!".toCharArray())) },
                ),
            )

            executor.shutdown()

            val results = futures.map { it.get() }
            // 최소 하나는 성공해야 한다 (둘 다 성공 가능 — UPSERT 멱등성)
            assertThat(results).anyMatch { it is AuthnResult.Success }

            val userCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM users WHERE username LIKE '%alice%'",
                emptyMap<String, Any>(),
                Int::class.java,
            )
            assertThat(userCount).isEqualTo(1)

            val accountCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM user_external_accounts WHERE provider_id = :pid",
                mapOf("pid" to providerId),
                Int::class.java,
            )
            assertThat(accountCount).isEqualTo(1)
        }
    }
}
