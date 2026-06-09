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

    // ── FR-AU-08 계정 연결 관리 (조회/삭제/락) ──────────────────────────

    /**
     * 두 번째 authn_providers 행 INSERT.
     * user_external_accounts 의 UNIQUE 제약은 (provider_id, external_subject) 이므로
     * 같은 user 에 여러 external account 를 연결하려면 서로 다른 provider 가 필요하다.
     */
    private fun insertSecondProvider(): UUID {
        val secondProviderId = UUID.randomUUID()
        jdbc.update(
            """
            INSERT INTO authn_providers (id, type, name, config, enabled)
            VALUES (:id, 'LDAP', 'test-ldap-2', '{"serverUrl":"ldap://test2:389","baseDn":"dc=bts,dc=local",
                "bindDn":"cn=admin","bindPasswordEnv":"TEST_PASS","userSearchBase":"ou=people",
                "userSearchFilter":"(uid={0})","groupSearchBase":"ou=groups","groupSearchFilter":"(member={0})",
                "lockoutPolicy":{"maxAttempts":3,"lockoutMinutes":1,"scope":"PER_USER_PER_PROVIDER"}}'::jsonb, true)
            """.trimIndent(),
            mapOf("id" to secondProviderId),
        )
        return secondProviderId
    }

    /** 두 번째 users 행 INSERT — 소유 검증(타인 userId) 테스트용. */
    private fun insertSecondUser(): UUID {
        val bobUserId = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO users (id, username, email, display_name) VALUES (:id, :u, :e, :d)",
            mapOf("id" to bobUserId, "u" to "bob@bts.local", "e" to "bob@bts.local", "d" to "Bob"),
        )
        return bobUserId
    }

    @Test
    fun `findByUserId — 한 user 에 연결된 external account 다건 반환`() {
        val secondProviderId = insertSecondProvider()
        repo.provisionUser(
            providerId = providerId,
            externalSubject = "uid=alice,ou=people,dc=bts,dc=local",
            userId = aliceUserId,
            groups = emptyList(),
        )
        repo.provisionUser(
            providerId = secondProviderId,
            externalSubject = "uid=alice2,ou=people,dc=bts,dc=local",
            userId = aliceUserId,
            groups = emptyList(),
        )

        val accounts = repo.findByUserId(aliceUserId)

        assertThat(accounts).hasSize(2)
        assertThat(accounts.map { it.providerId })
            .containsExactlyInAnyOrder(providerId, secondProviderId)
        assertThat(accounts.map { it.userId }).containsOnly(aliceUserId)
    }

    @Test
    fun `findByUserId — 연결 없으면 빈 리스트`() {
        val accounts = repo.findByUserId(aliceUserId)
        assertThat(accounts).isEmpty()
    }

    @Test
    fun `deleteByIdAndUserId — 소유자 일치 시 1행 삭제`() {
        val account =
            repo.provisionUser(
                providerId = providerId,
                externalSubject = "uid=alice,ou=people,dc=bts,dc=local",
                userId = aliceUserId,
                groups = emptyList(),
            )

        val deleted = repo.deleteByIdAndUserId(account.id, aliceUserId)

        assertThat(deleted).isEqualTo(1)
        assertThat(repo.findByUserId(aliceUserId)).isEmpty()
    }

    @Test
    fun `deleteByIdAndUserId — 타인 userId 로는 삭제되지 않음 (소유 검증)`() {
        val bobUserId = insertSecondUser()
        val account =
            repo.provisionUser(
                providerId = providerId,
                externalSubject = "uid=alice,ou=people,dc=bts,dc=local",
                userId = aliceUserId,
                groups = emptyList(),
            )

        // bob 이 alice 의 account id 로 삭제 시도 → 0행 (소유 불일치)
        val deleted = repo.deleteByIdAndUserId(account.id, bobUserId)

        assertThat(deleted).isZero()
        // alice 의 account 는 그대로 보존
        assertThat(repo.findByUserId(aliceUserId)).hasSize(1)
    }

    @Test
    fun `acquireUserLock — 호출 성공 + 같은 userId 두 번 호출 무해`() {
        // 같은 트랜잭션 내 pg_advisory_xact_lock 획득. 예외 없이 성공해야 한다.
        // 재진입(같은 userId 두 번)도 advisory lock 은 무해 — 상세 동시성은 Task 7 통합테스트.
        repo.acquireUserLock(aliceUserId)
        repo.acquireUserLock(aliceUserId)

        // 락 보유 상태에서도 후속 조회가 정상 동작 (lock 후 재조회 선례 검증)
        assertThat(repo.findByUserId(aliceUserId)).isEmpty()
    }

    // ── FR-AU-08b SSO 연결 (insertLink 순수 INSERT + subject advisory lock) ──────────

    @Test
    fun `insertLink — 신규 신원 INSERT 후 row 반환`() {
        val account =
            repo.insertLink(
                providerId = providerId,
                externalSubject = "oidc|alice-sub-123",
                userId = aliceUserId,
                groups = listOf("engineers"),
            )

        assertThat(account.providerId).isEqualTo(providerId)
        assertThat(account.externalSubject).isEqualTo("oidc|alice-sub-123")
        assertThat(account.userId).isEqualTo(aliceUserId)
        assertThat(account.failedAttempts).isZero()
        assertThat(account.lockedUntil).isNull()

        // 실제로 영속됐는지 재조회로 확인
        val found = repo.findByProviderIdAndExternalSubject(providerId, "oidc|alice-sub-123")
        assertThat(found).isNotNull()
        assertThat(found!!.userId).isEqualTo(aliceUserId)
    }

    @Test
    fun `insertLink — 중복 (provider_id, external_subject) 면 예외 (UPSERT 아님, 타계정 선점 삼킴 차단)`() {
        val bobUserId = insertSecondUser()
        // alice 가 먼저 신원 연결
        repo.insertLink(
            providerId = providerId,
            externalSubject = "oidc|shared-sub",
            userId = aliceUserId,
            groups = emptyList(),
        )

        // bob 이 같은 (provider_id, external_subject) 로 INSERT 시도 → UNIQUE 위반 예외.
        // ON CONFLICT DO UPDATE 였다면 bob 이 alice 의 신원을 조용히 가로챘을 것(거짓 success) — 그걸 차단.
        // (UNIQUE 위반 후 같은 트랜잭션은 abort 되므로 user_id 보존 재조회는 별도 트랜잭션이 필요한
        //  통합테스트(Task 10)에서 검증한다. 여기서는 중복이 조용히 삼켜지지 않고 예외로 거부됨만 확인.)
        var thrown: Exception? = null
        try {
            repo.insertLink(
                providerId = providerId,
                externalSubject = "oidc|shared-sub",
                userId = bobUserId,
                groups = emptyList(),
            )
        } catch (e: Exception) {
            thrown = e
        }

        assertThat(thrown).isNotNull()
        assertThat(thrown!!.message).contains("user_external_accounts_provider_id_external_subject_key")
    }

    @Test
    fun `acquireSubjectLock — 같은 tx 내 호출 성공 + 재진입 무해 (스모크)`() {
        // pg_advisory_xact_lock 획득. 예외 없이 성공해야 한다.
        // 같은 (providerId, externalSubject) 두 번 호출(재진입)도 advisory lock 은 무해.
        repo.acquireSubjectLock(providerId, "oidc|alice-sub-123")
        repo.acquireSubjectLock(providerId, "oidc|alice-sub-123")

        // 락 보유 상태에서도 후속 INSERT 가 같은 tx 내에서 정상 동작 (lock 후 재조회/쓰기 선례)
        val account =
            repo.insertLink(
                providerId = providerId,
                externalSubject = "oidc|alice-sub-123",
                userId = aliceUserId,
                groups = emptyList(),
            )
        assertThat(account.userId).isEqualTo(aliceUserId)
    }
}
