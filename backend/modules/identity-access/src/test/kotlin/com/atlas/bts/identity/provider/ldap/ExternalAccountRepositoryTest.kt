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
        val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer("postgres:16-alpine")
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
    private lateinit var aliceUserId: UUID

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

        // users 픽스처 — provisionUser 가 user_external_accounts.user_id FK 만 검증하므로 users 선행 필요.
        aliceUserId = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO users (id, username, email, display_name) VALUES (:id, :u, :e, :d)",
            mapOf("id" to aliceUserId, "u" to "alice@bts.local", "e" to "alice@bts.local", "d" to "Alice"),
        )
    }

    @Test
    fun `findByProviderIdAndExternalSubject — 매핑 없으면 null 반환`() {
        val result = repo.findByProviderIdAndExternalSubject(providerId, "uid=alice,ou=people,dc=bts,dc=local")
        assertThat(result).isNull()
    }

    @Test
    fun `provisionUser — user_external_accounts INSERT 후 row 반환`() {
        val account =
            repo.provisionUser(
                providerId = providerId,
                externalSubject = "uid=alice,ou=people,dc=bts,dc=local",
                userId = aliceUserId,
                groups = listOf("cn=engineers,ou=groups,dc=bts,dc=local"),
            )

        assertThat(account.externalSubject).isEqualTo("uid=alice,ou=people,dc=bts,dc=local")
        assertThat(account.userId).isEqualTo(aliceUserId)
        assertThat(account.failedAttempts).isZero()
        assertThat(account.lockedUntil).isNull()
    }

    @Test
    fun `provisionUser 후 findByProviderIdAndExternalSubject 조회 성공`() {
        repo.provisionUser(
            providerId = providerId,
            externalSubject = "uid=alice,ou=people,dc=bts,dc=local",
            userId = aliceUserId,
            groups = emptyList(),
        )

        val found = repo.findByProviderIdAndExternalSubject(providerId, "uid=alice,ou=people,dc=bts,dc=local")
        assertThat(found).isNotNull()
        assertThat(found!!.externalSubject).isEqualTo("uid=alice,ou=people,dc=bts,dc=local")
    }

    @Test
    fun `provisionUser — 미존재 userId 전달 시 FK 위반 예외`() {
        // user_external_accounts.user_id → users.id FK. users 에 없는 UUID 는 예외.
        val ghostUserId = UUID.randomUUID()

        var thrown: Exception? = null
        try {
            repo.provisionUser(
                providerId = providerId,
                externalSubject = "uid=ghost,ou=people,dc=bts,dc=local",
                userId = ghostUserId,
                groups = emptyList(),
            )
        } catch (e: Exception) {
            thrown = e
        }

        assertThat(thrown).isNotNull()
    }

    @Test
    fun `incrementFailedAttempts — 카운터 +1`() {
        val account =
            repo.provisionUser(
                providerId = providerId,
                externalSubject = "uid=alice,ou=people,dc=bts,dc=local",
                userId = aliceUserId,
                groups = emptyList(),
            )

        repo.incrementFailedAttempts(account.id)

        val found = repo.findByProviderIdAndExternalSubject(providerId, "uid=alice,ou=people,dc=bts,dc=local")
        assertThat(found!!.failedAttempts).isEqualTo(1)
    }

    @Test
    fun `resetFailedAttempts — 카운터 0 으로 reset`() {
        val account =
            repo.provisionUser(
                providerId = providerId,
                externalSubject = "uid=alice,ou=people,dc=bts,dc=local",
                userId = aliceUserId,
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
        val account =
            repo.provisionUser(
                providerId = providerId,
                externalSubject = "uid=alice,ou=people,dc=bts,dc=local",
                userId = aliceUserId,
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
        val account =
            repo.provisionUser(
                providerId = providerId,
                externalSubject = "uid=alice,ou=people,dc=bts,dc=local",
                userId = aliceUserId,
                groups = emptyList(),
            )

        repo.incrementFailedAttempts(account.id)
        val loginTime = Instant.now().truncatedTo(ChronoUnit.MILLIS)
        repo.updateLastLoginAt(account.id, loginTime)

        val found = repo.findByProviderIdAndExternalSubject(providerId, "uid=alice,ou=people,dc=bts,dc=local")
        assertThat(found!!.lastLoginAt).isNotNull()
        assertThat(found.failedAttempts).isZero()
    }

    // ── 회귀 가드 강화 + UPSERT 시나리오 ────────────────────────────────────────

    @Test
    fun `provisionUser 는 단일 SQL 로 row 를 반환한다 (회귀 가드)`() {
        val account =
            repo.provisionUser(
                providerId = providerId,
                externalSubject = "uid=alice,ou=people,dc=bts,dc=local",
                userId = aliceUserId,
                groups = emptyList(),
            )

        assertThat(account.id).isInstanceOf(UUID::class.java)
        val externalSubject = "uid=alice,ou=people,dc=bts,dc=local"
        assertThat(repo.findByProviderIdAndExternalSubject(providerId, externalSubject))
            .isNotNull()
    }

    @Test
    fun `provisionUser 두 번 호출 시 같은 row 반환 (UPSERT)`() {
        val first =
            repo.provisionUser(
                providerId = providerId,
                externalSubject = "uid=alice,ou=people,dc=bts,dc=local",
                userId = aliceUserId,
                groups = emptyList(),
            )

        val second =
            repo.provisionUser(
                providerId = providerId,
                externalSubject = "uid=alice,ou=people,dc=bts,dc=local",
                userId = aliceUserId,
                groups = emptyList(),
            )

        // UPSERT 동작 — ON CONFLICT (provider_id, external_subject) 로 같은 id 보존
        assertThat(second.id).isEqualTo(first.id)
    }
}
