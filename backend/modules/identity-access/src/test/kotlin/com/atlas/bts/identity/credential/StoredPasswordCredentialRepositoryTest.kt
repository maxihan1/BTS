// StoredPasswordCredentialRepository 통합 테스트 — Testcontainers PostgreSQL + Flyway V001~V003 적용

package com.atlas.bts.identity.credential

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
 * StoredPasswordCredentialRepository 통합 테스트.
 * @JdbcTest + Testcontainers PostgreSQL + Flyway V001~V003 자동 적용.
 * UPSERT 신규/갱신, find/null, delete, CASCADE 삭제 6건.
 */
@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(StoredPasswordCredentialRepository::class)
@Testcontainers
class StoredPasswordCredentialRepositoryTest {

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
    private lateinit var repo: StoredPasswordCredentialRepository

    @Autowired
    private lateinit var jdbc: NamedParameterJdbcTemplate

    private lateinit var userId: UUID

    @BeforeEach
    fun setUp() {
        jdbc.update("DELETE FROM local_credentials", emptyMap<String, Any>())
        jdbc.update("DELETE FROM users", emptyMap<String, Any>())

        // users 픽스처 — local_credentials FK 충족
        userId = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO users (id, username) VALUES (:id, :username)",
            mapOf("id" to userId, "username" to "test-user-${userId}"),
        )
    }

    @Test
    fun `save 신규 INSERT — created_at 설정되고 updated_at 과 동일하다`() {
        val credential = StoredPasswordCredential(
            userId = userId,
            passwordHash = "\$argon2id\$v=19\$m=65536,t=3,p=4\$salt1\$hash1",
            algoVersion = "argon2id-v1",
            createdAt = java.time.Instant.EPOCH, // Repository가 DB now() 로 덮어씀
            updatedAt = java.time.Instant.EPOCH,
        )

        val saved = repo.save(credential)

        assertThat(saved.userId).isEqualTo(userId)
        assertThat(saved.passwordHash).isEqualTo("\$argon2id\$v=19\$m=65536,t=3,p=4\$salt1\$hash1")
        assertThat(saved.algoVersion).isEqualTo("argon2id-v1")
        assertThat(saved.createdAt).isNotNull()
        // 신규 INSERT 시 created_at == updated_at (오차 허용: 같거나 updated_at >= created_at)
        assertThat(saved.updatedAt).isGreaterThanOrEqualTo(saved.createdAt)
    }

    @Test
    fun `save UPSERT — 같은 user_id 두 번 호출 시 password_hash 갱신, created_at 보존`() {
        val first = repo.save(
            StoredPasswordCredential(
                userId = userId,
                passwordHash = "\$argon2id\$v=19\$m=65536,t=3,p=4\$salt1\$hash1",
                algoVersion = "argon2id-v1",
                createdAt = java.time.Instant.EPOCH,
                updatedAt = java.time.Instant.EPOCH,
            ),
        )

        // 짧은 시간 차이도 DB now() 로 처리되므로 충분
        val second = repo.save(
            StoredPasswordCredential(
                userId = userId,
                passwordHash = "\$argon2id\$v=19\$m=65536,t=3,p=4\$salt2\$hash2",
                algoVersion = "argon2id-v1",
                createdAt = java.time.Instant.EPOCH,
                updatedAt = java.time.Instant.EPOCH,
            ),
        )

        assertThat(second.userId).isEqualTo(userId)
        assertThat(second.passwordHash).isEqualTo("\$argon2id\$v=19\$m=65536,t=3,p=4\$salt2\$hash2")
        // created_at 보존 — 첫 번째 저장과 동일해야 함
        assertThat(second.createdAt).isEqualTo(first.createdAt)
        // updated_at 갱신 — 두 번째가 같거나 이후
        assertThat(second.updatedAt).isGreaterThanOrEqualTo(first.updatedAt)
    }

    @Test
    fun `findByUserId 존재 — save 후 동일 user_id 조회 시 5 필드 일치`() {
        val saved = repo.save(
            StoredPasswordCredential(
                userId = userId,
                passwordHash = "\$argon2id\$v=19\$m=65536,t=3,p=4\$salt1\$hash1",
                algoVersion = "argon2id-v1",
                createdAt = java.time.Instant.EPOCH,
                updatedAt = java.time.Instant.EPOCH,
            ),
        )

        val found = repo.findByUserId(userId)

        assertThat(found).isNotNull()
        assertThat(found!!.userId).isEqualTo(saved.userId)
        assertThat(found.passwordHash).isEqualTo(saved.passwordHash)
        assertThat(found.algoVersion).isEqualTo(saved.algoVersion)
        assertThat(found.createdAt).isEqualTo(saved.createdAt)
        assertThat(found.updatedAt).isEqualTo(saved.updatedAt)
    }

    @Test
    fun `findByUserId 없음 — null 반환`() {
        val result = repo.findByUserId(UUID.randomUUID())

        assertThat(result).isNull()
    }

    @Test
    fun `deleteByUserId — 영향 행수 1 반환, 이후 findByUserId null`() {
        repo.save(
            StoredPasswordCredential(
                userId = userId,
                passwordHash = "\$argon2id\$v=19\$m=65536,t=3,p=4\$salt1\$hash1",
                algoVersion = "argon2id-v1",
                createdAt = java.time.Instant.EPOCH,
                updatedAt = java.time.Instant.EPOCH,
            ),
        )

        val deleted = repo.deleteByUserId(userId)

        assertThat(deleted).isEqualTo(1)
        assertThat(repo.findByUserId(userId)).isNull()
    }

    @Test
    fun `users CASCADE 삭제 — users 행 삭제 시 local_credentials 자동 삭제`() {
        repo.save(
            StoredPasswordCredential(
                userId = userId,
                passwordHash = "\$argon2id\$v=19\$m=65536,t=3,p=4\$salt1\$hash1",
                algoVersion = "argon2id-v1",
                createdAt = java.time.Instant.EPOCH,
                updatedAt = java.time.Instant.EPOCH,
            ),
        )

        // users 행 삭제 → ON DELETE CASCADE 로 local_credentials 도 삭제돼야 함
        jdbc.update("DELETE FROM users WHERE id = :id", mapOf("id" to userId))

        assertThat(repo.findByUserId(userId)).isNull()
    }
}
