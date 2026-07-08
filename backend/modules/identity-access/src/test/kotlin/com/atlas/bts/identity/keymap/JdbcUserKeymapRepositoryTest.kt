// JdbcUserKeymapRepository 통합 테스트 — Testcontainers PostgreSQL + Flyway 전체 적용 (FR-PF-03 Task 3)

package com.atlas.bts.identity.keymap

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
import java.util.UUID

/**
 * [JdbcUserKeymapRepository] 통합 테스트 (FR-PF-03 Task 3).
 *
 * @JdbcTest + Testcontainers PostgreSQL + Flyway 전체 마이그레이션(V001~V033 포함) 자동 적용.
 * `user_keymap.user_id` 는 `users.id` FK 이므로, 각 테스트 전에 users 행을 먼저 INSERT해
 * FK 제약을 충족시킨다(JdbcUserPreferencesRepositoryTest 선례와 동일 패턴).
 */
@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JdbcUserKeymapRepository::class)
@Testcontainers
class JdbcUserKeymapRepositoryTest {
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
    @Suppress("VarCouldBeVal") // @Autowired lateinit var 는 val 불가(주입). detekt false-positive 억제
    private lateinit var repo: JdbcUserKeymapRepository

    @Autowired
    @Suppress("VarCouldBeVal") // @Autowired lateinit var 는 val 불가(주입). detekt false-positive 억제
    private lateinit var jdbc: NamedParameterJdbcTemplate

    private lateinit var userId: UUID

    @BeforeEach
    fun setUp() {
        jdbc.update("DELETE FROM user_keymap", emptyMap<String, Any>())
        jdbc.update("DELETE FROM users", emptyMap<String, Any>())

        // users 픽스처 — user_keymap FK 충족
        userId = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO users (id, username) VALUES (:id, :username)",
            mapOf("id" to userId, "username" to "test-user-$userId"),
        )
    }

    // ── findByUserId ─────────────────────────────────────────────────────────

    @Test
    fun `findByUserId 저장 없는 user는 빈 리스트를 반환한다`() {
        assertThat(repo.findByUserId(userId)).isEmpty()
    }

    @Test
    fun `findByUserId는 일부 override 저장 후 그 override만 반환한다`() {
        repo.replaceOverrides(userId, listOf(KeymapBinding("help", "h")))

        val found = repo.findByUserId(userId)

        assertThat(found).containsExactly(KeymapBinding("help", "h"))
    }

    @Test
    fun `findByUserId는 다른 user의 override를 반환하지 않는다`() {
        val otherUserId = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO users (id, username) VALUES (:id, :username)",
            mapOf("id" to otherUserId, "username" to "test-user-$otherUserId"),
        )
        repo.replaceOverrides(otherUserId, listOf(KeymapBinding("search", "/")))

        assertThat(repo.findByUserId(userId)).isEmpty()
    }

    // ── replaceOverrides ─────────────────────────────────────────────────────

    @Test
    fun `replaceOverrides는 기존 override를 전부 제거 후 새 override로 원자적 교체한다`() {
        repo.replaceOverrides(
            userId,
            listOf(KeymapBinding("help", "h"), KeymapBinding("search", "s")),
        )

        repo.replaceOverrides(userId, listOf(KeymapBinding("create-issue", "n")))

        val found = repo.findByUserId(userId)
        assertThat(found).containsExactly(KeymapBinding("create-issue", "n"))
    }

    @Test
    fun `replaceOverrides에 빈 목록을 넘기면 기본값 복원 — 해당 user 행 전부 삭제된다`() {
        repo.replaceOverrides(
            userId,
            listOf(KeymapBinding("help", "h"), KeymapBinding("search", "s")),
        )

        repo.replaceOverrides(userId, emptyList())

        assertThat(repo.findByUserId(userId)).isEmpty()
    }

    // ── users FK CASCADE ─────────────────────────────────────────────────────

    @Test
    fun `users CASCADE 삭제 — users 행 삭제 시 user_keymap override가 자동 삭제된다`() {
        repo.replaceOverrides(userId, listOf(KeymapBinding("help", "h")))

        jdbc.update("DELETE FROM users WHERE id = :id", mapOf("id" to userId))

        assertThat(repo.findByUserId(userId)).isEmpty()
    }
}
