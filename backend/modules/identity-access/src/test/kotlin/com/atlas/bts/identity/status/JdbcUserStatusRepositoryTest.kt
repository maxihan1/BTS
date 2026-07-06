// JdbcUserStatusRepository 통합 테스트 — Testcontainers PostgreSQL + Flyway 전체 적용 (FR-PR-02 task-2)

package com.atlas.bts.identity.status

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
import java.util.UUID

/**
 * JdbcUserStatusRepository 통합 테스트 (FR-PR-02 Task 2).
 *
 * @JdbcTest + Testcontainers PostgreSQL + Flyway 전체 마이그레이션(V001~V028) 자동 적용.
 * 검증 대상: upsert / findActiveByUserId / deleteByUserId.
 */
@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JdbcUserStatusRepository::class)
@Testcontainers
class JdbcUserStatusRepositoryTest {
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

        /** now() 대비 flakiness 없이 확실히 과거인 시각 (EC — 과거 expires_at 회귀 방지). */
        private val FAR_PAST: Instant = Instant.parse("2020-01-01T00:00:00Z")

        /** 확실히 미래인 시각. */
        private val FAR_FUTURE: Instant = Instant.parse("2099-01-01T00:00:00Z")
    }

    @Autowired
    @Suppress("VarCouldBeVal") // @Autowired lateinit var 는 val 불가(주입). detekt false-positive 억제
    private lateinit var repo: UserStatusRepository

    @Autowired
    @Suppress("VarCouldBeVal") // @Autowired lateinit var 는 val 불가(주입). detekt false-positive 억제
    private lateinit var jdbc: NamedParameterJdbcTemplate

    @BeforeEach
    fun setUp() {
        // users 삭제 시 user_statuses 는 ON DELETE CASCADE 로 함께 삭제됨 (V028)
        jdbc.update("DELETE FROM users", emptyMap<String, Any>())
    }

    /** FK 충족을 위해 users 행을 먼저 INSERT 하고 그 id 를 반환한다. */
    private fun insertTestUser(username: String): UUID {
        val id = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO users (id, username, email, display_name) VALUES (:id, :username, :email, :displayName)",
            mapOf(
                "id" to id,
                "username" to username,
                "email" to "$username@bts.local",
                "displayName" to username,
            ),
        )
        return id
    }

    // ── findActiveByUserId ───────────────────────────────────────────────────

    @Test
    fun `findActiveByUserId 없으면 null 반환`() {
        val userId = insertTestUser("status-none")

        val result = repo.findActiveByUserId(userId)

        assertThat(result).isNull()
    }

    // ── upsert ───────────────────────────────────────────────────────────────

    @Test
    fun `upsert emoji만 설정`() {
        val userId = insertTestUser("status-emoji-only")

        repo.upsert(userId, "🌴", null, null)

        val found = repo.findActiveByUserId(userId)
        assertThat(found).isNotNull()
        assertThat(found!!.userId).isEqualTo(userId)
        assertThat(found.emoji).isEqualTo("🌴")
        assertThat(found.text).isNull()
        assertThat(found.expiresAt).isNull()
    }

    @Test
    fun `upsert text만 설정`() {
        val userId = insertTestUser("status-text-only")

        repo.upsert(userId, null, "회의 중", null)

        val found = repo.findActiveByUserId(userId)
        assertThat(found).isNotNull()
        assertThat(found!!.emoji).isNull()
        assertThat(found.text).isEqualTo("회의 중")
        assertThat(found.expiresAt).isNull()
    }

    @Test
    fun `upsert emoji와 text 둘 다 + 만료시각 설정`() {
        val userId = insertTestUser("status-both")

        repo.upsert(userId, "🌴", "휴가 중", FAR_FUTURE)

        val found = repo.findActiveByUserId(userId)
        assertThat(found).isNotNull()
        assertThat(found!!.emoji).isEqualTo("🌴")
        assertThat(found.text).isEqualTo("휴가 중")
        assertThat(found.expiresAt).isEqualTo(FAR_FUTURE)
    }

    @Test
    fun `upsert 재호출 시 통짜 교체(replace) — 이전 값 잔존 없음`() {
        val userId = insertTestUser("status-replace")
        repo.upsert(userId, "🌴", "휴가 중", null)

        repo.upsert(userId, null, "회의 중", FAR_FUTURE)

        val found = repo.findActiveByUserId(userId)
        assertThat(found).isNotNull()
        assertThat(found!!.emoji).isNull()
        assertThat(found.text).isEqualTo("회의 중")
        assertThat(found.expiresAt).isEqualTo(FAR_FUTURE)
    }

    @Test
    fun `만료된 상태는 findActiveByUserId가 null 반환`() {
        val userId = insertTestUser("status-expired")
        repo.upsert(userId, "🌴", "지난 휴가", FAR_PAST)

        val found = repo.findActiveByUserId(userId)

        assertThat(found).isNull()
    }

    // ── deleteByUserId ───────────────────────────────────────────────────────

    @Test
    fun `deleteByUserId 이후 findActiveByUserId null`() {
        val userId = insertTestUser("status-delete")
        repo.upsert(userId, "🌴", "휴가 중", null)

        repo.deleteByUserId(userId)

        assertThat(repo.findActiveByUserId(userId)).isNull()
    }

    @Test
    fun `deleteByUserId는 행이 없어도 멱등하게 무시된다`() {
        val userId = insertTestUser("status-delete-idempotent")

        repo.deleteByUserId(userId)
        repo.deleteByUserId(userId)

        assertThat(repo.findActiveByUserId(userId)).isNull()
    }
}
