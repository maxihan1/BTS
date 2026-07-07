// JdbcUserPreferencesRepository 통합 테스트 — Testcontainers PostgreSQL + Flyway 전체 적용 (FR-PF-01 Task 2)

package com.atlas.bts.identity.preferences

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
 * [JdbcUserPreferencesRepository] 통합 테스트 (FR-PF-01 Task 2).
 *
 * @JdbcTest + Testcontainers PostgreSQL + Flyway 전체 마이그레이션(V001~V031 포함) 자동 적용.
 * `user_preferences.user_id` 는 `users.id` FK 이므로, 각 테스트 전에 users 행을 먼저 INSERT해
 * FK 제약을 충족시킨다(StoredPasswordCredentialRepositoryTest 선례와 동일 패턴).
 */
@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JdbcUserPreferencesRepository::class)
@Testcontainers
class JdbcUserPreferencesRepositoryTest {
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
    private lateinit var repo: JdbcUserPreferencesRepository

    @Autowired
    @Suppress("VarCouldBeVal") // @Autowired lateinit var 는 val 불가(주입). detekt false-positive 억제
    private lateinit var jdbc: NamedParameterJdbcTemplate

    private lateinit var userId: UUID

    @BeforeEach
    fun setUp() {
        jdbc.update("DELETE FROM user_preferences", emptyMap<String, Any>())
        jdbc.update("DELETE FROM users", emptyMap<String, Any>())

        // users 픽스처 — user_preferences FK 충족
        userId = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO users (id, username) VALUES (:id, :username)",
            mapOf("id" to userId, "username" to "test-user-$userId"),
        )
    }

    @Test
    fun `findByUserId 없음 — null 반환`() {
        assertThat(repo.findByUserId(userId)).isNull()
    }

    @Test
    fun `upsert 신규 INSERT 후 findByUserId로 조회 가능`() {
        repo.upsert(UserPreferences(userId, "dark", "en", "us"))

        val found = repo.findByUserId(userId)

        assertThat(found).isNotNull()
        assertThat(found!!.userId).isEqualTo(userId)
        assertThat(found.theme).isEqualTo("dark")
        assertThat(found.locale).isEqualTo("en")
        assertThat(found.dateFormat).isEqualTo("us")
    }

    @Test
    fun `upsert 멱등 — 같은 user_id 두 번 호출 시 마지막 값으로 갱신(ON CONFLICT DO UPDATE)`() {
        repo.upsert(UserPreferences(userId, "light", "ko", "iso"))
        repo.upsert(UserPreferences(userId, "dark", "en", "kr"))

        val found = repo.findByUserId(userId)

        assertThat(found).isNotNull()
        assertThat(found!!.theme).isEqualTo("dark")
        assertThat(found.locale).isEqualTo("en")
        assertThat(found.dateFormat).isEqualTo("kr")
    }

    @Test
    fun `users CASCADE 삭제 — users 행 삭제 시 user_preferences 자동 삭제`() {
        repo.upsert(UserPreferences(userId, "dark", "en", "us"))

        jdbc.update("DELETE FROM users WHERE id = :id", mapOf("id" to userId))

        assertThat(repo.findByUserId(userId)).isNull()
    }
}
