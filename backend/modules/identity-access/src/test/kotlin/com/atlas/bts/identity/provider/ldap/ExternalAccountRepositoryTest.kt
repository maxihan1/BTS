// ExternalAccountRepository 통합 테스트 — Testcontainers PostgreSQL + Flyway V001+V002 적용

package com.atlas.bts.identity.provider.ldap

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * ExternalAccountRepository 통합 테스트.
 * @JdbcTest + Testcontainers PostgreSQL + Flyway V001+V002 자동 적용.
 * provisionUser 단일 트랜잭션 rollback 포함 (DATA.md §6).
 */
@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(ExternalAccountRepository::class)
@Testcontainers
class ExternalAccountRepositoryTest {

    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("bts_test")
            .withUsername("bts")
            .withPassword("bts_test")

        @DynamicPropertySource
        @JvmStatic
        fun postgresProps(r: DynamicPropertyRegistry) {
            r.add("spring.datasource.url") { postgres.jdbcUrl }
            r.add("spring.datasource.username") { postgres.username }
            r.add("spring.datasource.password") { postgres.password }
            r.add("spring.flyway.enabled") { "true" }
        }
    }

    @Autowired
    private lateinit var repo: ExternalAccountRepository

    @Autowired
    private lateinit var jdbc: NamedParameterJdbcTemplate

    private lateinit var providerId: UUID

    @BeforeEach
    fun setUp() {
        // 테스트 데이터 초기화
        jdbc.update("DELETE FROM user_external_accounts", emptyMap<String, Any>())
        jdbc.update("DELETE FROM authn_providers", emptyMap<String, Any>())
        jdbc.update("DELETE FROM users", emptyMap<String, Any>())

        // authn_providers 픽스처
        providerId = UUID.randomUUID()
        jdbc.update(
            """
            INSERT INTO authn_providers (id, type, name, config, enabled)
            VALUES (:id, 'LDAP', 'test-ldap', '{"serverUrl":"ldap://test:389","baseDn":"dc=bts,dc=local",
                "bindDn":"cn=admin","bindPasswordEnv":"TEST_PASS","userSearchBase":"ou=people",
                "userSearchFilter":"(uid={0})","groupSearchBase":"ou=groups","groupSearchFilter":"(member={0})",
                "lockoutPolicy":{"maxAttempts":3,"lockoutMinutes":1,"scope":"PER_USER_PER_PROVIDER"}}'::jsonb, true)
            """.trimIndent(),
            mapOf("id" to providerId),
        )
    }

    @Test
    fun `findByProviderIdAndExternalSubject — 매핑 없으면 null 반환`() {
        val result = repo.findByProviderIdAndExternalSubject(providerId, "uid=alice,ou=people,dc=bts,dc=local")
        assertThat(result).isNull()
    }

    @Test
    fun `provisionUser — users + user_external_accounts 단일 트랜잭션 INSERT`() {
        val account = repo.provisionUser(
            providerId = providerId,
            externalSubject = "uid=alice,ou=people,dc=bts,dc=local",
            username = "alice@bts.local",
            displayName = "Alice Test",
            email = "alice@bts.local",
            groups = listOf("cn=engineers,ou=groups,dc=bts,dc=local"),
        )

        assertThat(account.externalSubject).isEqualTo("uid=alice,ou=people,dc=bts,dc=local")
        assertThat(account.userId).isNotNull()
        assertThat(account.failedAttempts).isZero()
        assertThat(account.lockedUntil).isNull()

        // users 테이블에 삽입됐는지 확인
        val userCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM users WHERE username = :username",
            mapOf("username" to "alice@bts.local"),
            Int::class.java,
        )
        assertThat(userCount).isEqualTo(1)
    }

    @Test
    fun `provisionUser 후 findByProviderIdAndExternalSubject 조회 성공`() {
        repo.provisionUser(
            providerId = providerId,
            externalSubject = "uid=alice,ou=people,dc=bts,dc=local",
            username = "alice@bts.local",
            displayName = "Alice Test",
            email = "alice@bts.local",
            groups = emptyList(),
        )

        val found = repo.findByProviderIdAndExternalSubject(providerId, "uid=alice,ou=people,dc=bts,dc=local")
        assertThat(found).isNotNull()
        assertThat(found!!.externalSubject).isEqualTo("uid=alice,ou=people,dc=bts,dc=local")
    }

    @Test
    fun `provisionUser — username 중복 시 rollback (users + user_external_accounts 모두 없어야 함)`() {
        // 첫 번째 provisionUser 성공
        repo.provisionUser(
            providerId = providerId,
            externalSubject = "uid=alice,ou=people,dc=bts,dc=local",
            username = "alice@bts.local",
            displayName = "Alice Test",
            email = "alice@bts.local",
            groups = emptyList(),
        )

        // 두 번째 — 다른 externalSubject 지만 동일 username → users.username UNIQUE 위반 → rollback
        var thrown: Exception? = null
        try {
            repo.provisionUser(
                providerId = providerId,
                externalSubject = "uid=alice2,ou=people,dc=bts,dc=local",
                username = "alice@bts.local",  // 동일 username
                displayName = "Alice2",
                email = "alice2@bts.local",
                groups = emptyList(),
            )
        } catch (e: Exception) {
            thrown = e
        }

        assertThat(thrown).isNotNull()
        // alice2 의 user_external_accounts 행이 없어야 함 (rollback 됐으므로)
        val count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM user_external_accounts WHERE external_subject = :subject",
            mapOf("subject" to "uid=alice2,ou=people,dc=bts,dc=local"),
            Int::class.java,
        )
        assertThat(count).isZero()
    }

    @Test
    fun `incrementFailedAttempts — 카운터 +1`() {
        val account = repo.provisionUser(
            providerId = providerId,
            externalSubject = "uid=alice,ou=people,dc=bts,dc=local",
            username = "alice@bts.local",
            displayName = "Alice",
            email = "alice@bts.local",
            groups = emptyList(),
        )

        repo.incrementFailedAttempts(account.id)

        val found = repo.findByProviderIdAndExternalSubject(providerId, "uid=alice,ou=people,dc=bts,dc=local")
        assertThat(found!!.failedAttempts).isEqualTo(1)
    }

    @Test
    fun `resetFailedAttempts — 카운터 0 으로 reset`() {
        val account = repo.provisionUser(
            providerId = providerId,
            externalSubject = "uid=alice,ou=people,dc=bts,dc=local",
            username = "alice@bts.local",
            displayName = "Alice",
            email = "alice@bts.local",
            groups = emptyList(),
        )

        repo.incrementFailedAttempts(account.id)
        repo.incrementFailedAttempts(account.id)
        repo.resetFailedAttempts(account.id)

        val found = repo.findByProviderIdAndExternalSubject(providerId, "uid=alice,ou=people,dc=bts,dc=local")
        assertThat(found!!.failedAttempts).isZero()
    }

    @Test
    fun `markLockedUntil — locked_until 갱신`() {
        val account = repo.provisionUser(
            providerId = providerId,
            externalSubject = "uid=alice,ou=people,dc=bts,dc=local",
            username = "alice@bts.local",
            displayName = "Alice",
            email = "alice@bts.local",
            groups = emptyList(),
        )

        val lockUntil = Instant.now().plus(15, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.MILLIS)
        repo.markLockedUntil(account.id, lockUntil)

        val found = repo.findByProviderIdAndExternalSubject(providerId, "uid=alice,ou=people,dc=bts,dc=local")
        assertThat(found!!.lockedUntil).isNotNull()
        assertThat(found.lockedUntil).isAfter(Instant.now())
    }

    @Test
    fun `updateLastLoginAt — last_login_at 갱신 + failedAttempts 0 reset`() {
        val account = repo.provisionUser(
            providerId = providerId,
            externalSubject = "uid=alice,ou=people,dc=bts,dc=local",
            username = "alice@bts.local",
            displayName = "Alice",
            email = "alice@bts.local",
            groups = emptyList(),
        )

        repo.incrementFailedAttempts(account.id)
        val loginTime = Instant.now().truncatedTo(ChronoUnit.MILLIS)
        repo.updateLastLoginAt(account.id, loginTime)

        val found = repo.findByProviderIdAndExternalSubject(providerId, "uid=alice,ou=people,dc=bts,dc=local")
        assertThat(found!!.lastLoginAt).isNotNull()
        assertThat(found.failedAttempts).isZero()
    }
}
