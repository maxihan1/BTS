// UserProfileRepository 통합 테스트 — Testcontainers PostgreSQL + Flyway 전체 적용 (FR-PR-01)

package com.atlas.bts.identity.profile

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
 * JdbcUserProfileRepository 통합 테스트 (FR-PR-01 Task 2).
 *
 * @JdbcTest + Testcontainers PostgreSQL + Flyway 전체 마이그레이션(V001~V027) 자동 적용.
 * 검증 대상: findByUserId / upsertProfile / setAvatarObjectKey / clearAvatar.
 */
@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JdbcUserProfileRepository::class)
@Testcontainers
class UserProfileRepositoryTest {
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
    private lateinit var repo: UserProfileRepository

    @Autowired
    private lateinit var jdbc: NamedParameterJdbcTemplate

    @BeforeEach
    fun setUp() {
        // users 삭제 시 user_profiles 는 ON DELETE CASCADE 로 함께 삭제됨 (V027)
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

    // ── findByUserId ─────────────────────────────────────────────────────────

    @Test
    fun `findByUserId 없으면 null 반환`() {
        val userId = insertTestUser("profile-none")

        val result = repo.findByUserId(userId)

        assertThat(result).isNull()
    }

    // ── upsertProfile ────────────────────────────────────────────────────────

    @Test
    fun `upsertProfile 신규 INSERT (lazy 생성)`() {
        val userId = insertTestUser("profile-insert")

        repo.upsertProfile(userId, "Asia/Seoul", "Engineering")

        val found = repo.findByUserId(userId)
        assertThat(found).isNotNull()
        assertThat(found!!.userId).isEqualTo(userId)
        assertThat(found.timezone).isEqualTo("Asia/Seoul")
        assertThat(found.department).isEqualTo("Engineering")
        assertThat(found.avatarObjectKey).isNull()
    }

    @Test
    fun `upsertProfile 기존 UPDATE`() {
        val userId = insertTestUser("profile-update")
        repo.upsertProfile(userId, "Asia/Seoul", "Engineering")

        repo.upsertProfile(userId, "America/New_York", "Sales")

        val found = repo.findByUserId(userId)
        assertThat(found).isNotNull()
        assertThat(found!!.timezone).isEqualTo("America/New_York")
        assertThat(found.department).isEqualTo("Sales")
    }

    @Test
    fun `upsertProfile은 avatar_object_key를 건드리지 않는다`() {
        val userId = insertTestUser("profile-preserve-avatar")
        repo.setAvatarObjectKey(userId, "avatars/preserve.png")

        repo.upsertProfile(userId, "Asia/Seoul", "Engineering")

        val found = repo.findByUserId(userId)
        assertThat(found).isNotNull()
        assertThat(found!!.avatarObjectKey).isEqualTo("avatars/preserve.png")
        assertThat(found.timezone).isEqualTo("Asia/Seoul")
        assertThat(found.department).isEqualTo("Engineering")
    }

    @Test
    fun `department 3-state — null 명시 저장 가능`() {
        val userId = insertTestUser("profile-null-department")
        repo.upsertProfile(userId, "Asia/Seoul", "Engineering")

        repo.upsertProfile(userId, "Asia/Seoul", null)

        val found = repo.findByUserId(userId)
        assertThat(found).isNotNull()
        assertThat(found!!.department).isNull()
    }

    // ── setAvatarObjectKey ───────────────────────────────────────────────────

    @Test
    fun `setAvatarObjectKey 신규 — lazy 생성 + timezone 기본값 UTC`() {
        val userId = insertTestUser("profile-avatar-new")

        repo.setAvatarObjectKey(userId, "avatars/new.png")

        val found = repo.findByUserId(userId)
        assertThat(found).isNotNull()
        assertThat(found!!.avatarObjectKey).isEqualTo("avatars/new.png")
        assertThat(found.timezone).isEqualTo("UTC")
        assertThat(found.department).isNull()
    }

    @Test
    fun `setAvatarObjectKey 기존 — timezone과 department 보존`() {
        val userId = insertTestUser("profile-avatar-existing")
        repo.upsertProfile(userId, "Asia/Seoul", "Engineering")

        repo.setAvatarObjectKey(userId, "avatars/updated.png")

        val found = repo.findByUserId(userId)
        assertThat(found).isNotNull()
        assertThat(found!!.avatarObjectKey).isEqualTo("avatars/updated.png")
        assertThat(found.timezone).isEqualTo("Asia/Seoul")
        assertThat(found.department).isEqualTo("Engineering")
    }

    // ── clearAvatar ──────────────────────────────────────────────────────────

    @Test
    fun `clearAvatar → avatar_object_key null`() {
        val userId = insertTestUser("profile-clear-avatar")
        repo.setAvatarObjectKey(userId, "avatars/toclear.png")

        repo.clearAvatar(userId)

        val found = repo.findByUserId(userId)
        assertThat(found).isNotNull()
        assertThat(found!!.avatarObjectKey).isNull()
    }
}
