// UserRepository 통합 테스트 — Testcontainers PostgreSQL + Flyway V001 적용

package com.atlas.bts.identity.user

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
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
 * JdbcUserRepository 통합 테스트 (Task 32 — FR-AU-09).
 *
 * @JdbcTest + Testcontainers PostgreSQL + Flyway V001 자동 적용.
 * 검증 대상: findById / findByUsername / save (UPSERT) / updateLastLogin.
 */
@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JdbcUserRepository::class)
@Testcontainers
class UserRepositoryTest {

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
    private lateinit var repo: UserRepository

    @Autowired
    private lateinit var jdbc: NamedParameterJdbcTemplate

    @BeforeEach
    fun setUp() {
        // 의존 테이블 순서대로 삭제 (FK 제약 존중)
        jdbc.update("DELETE FROM user_external_accounts", emptyMap<String, Any>())
        jdbc.update("DELETE FROM local_credentials", emptyMap<String, Any>())
        jdbc.update("DELETE FROM users", emptyMap<String, Any>())
    }

    // ── findById ─────────────────────────────────────────────────────────────

    @Test
    fun `findById — 존재하는 id 조회 시 User 반환`() {
        val saved = repo.save("alice", "alice@bts.local", "Alice Kim")

        val found = repo.findById(saved.id)

        assertThat(found).isNotNull()
        assertThat(found!!.id).isEqualTo(saved.id)
        assertThat(found.username).isEqualTo("alice")
    }

    @Test
    fun `findById — 없는 id 조회 시 null 반환`() {
        val result = repo.findById(UUID.randomUUID())

        assertThat(result).isNull()
    }

    // ── findByUsername ────────────────────────────────────────────────────────

    @Test
    fun `findByUsername — 존재하는 username 조회 시 User 반환`() {
        repo.save("bob", "bob@bts.local", "Bob Lee")

        val found = repo.findByUsername("bob")

        assertThat(found).isNotNull()
        assertThat(found!!.username).isEqualTo("bob")
        assertThat(found.email).isEqualTo("bob@bts.local")
        assertThat(found.displayName).isEqualTo("Bob Lee")
    }

    @Test
    fun `findByUsername — 없는 username 조회 시 null 반환`() {
        val result = repo.findByUsername("nonexistent")

        assertThat(result).isNull()
    }

    // ── save (UPSERT) ─────────────────────────────────────────────────────────

    @Test
    fun `save — 신규 INSERT 시 id + createdAt + updatedAt 설정`() {
        val user = repo.save("charlie", "charlie@bts.local", "Charlie Park")

        assertThat(user.id).isNotNull()
        assertThat(user.username).isEqualTo("charlie")
        assertThat(user.email).isEqualTo("charlie@bts.local")
        assertThat(user.displayName).isEqualTo("Charlie Park")
        assertThat(user.createdAt).isNotNull()
        assertThat(user.updatedAt).isNotNull()
        assertThat(user.updatedAt).isAfterOrEqualTo(user.createdAt)
    }

    @Test
    fun `save — email null 허용`() {
        val user = repo.save("diana", null, "Diana Choi")

        assertThat(user.email).isNull()
        assertThat(user.username).isEqualTo("diana")
    }

    @Test
    fun `save UPSERT — 같은 username 두 번 호출 시 email + displayName 갱신, id 보존`() {
        val first = repo.save("eve", "eve@bts.local", "Eve Old")
        val second = repo.save("eve", "eve-new@bts.local", "Eve New")

        assertThat(second.id).isEqualTo(first.id)
        assertThat(second.email).isEqualTo("eve-new@bts.local")
        assertThat(second.displayName).isEqualTo("Eve New")
    }

    @Test
    fun `save — 다른 username 은 별개 행으로 삽입`() {
        repo.save("frank", null, "Frank A")
        repo.save("grace", null, "Grace B")

        val count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM users",
            emptyMap<String, Any>(),
            Int::class.java,
        )
        assertThat(count).isEqualTo(2)
    }

    // ── provisionFromExternal ─────────────────────────────────────────────────

    @Test
    fun `provisionFromExternal — 신규 사용자 UPSERT 후 User 반환`() {
        val user = repo.provisionFromExternal(
            username = "henry",
            email = "henry@ldap.bts.local",
            displayName = "Henry LDAP",
        )

        assertThat(user.username).isEqualTo("henry")
        assertThat(user.email).isEqualTo("henry@ldap.bts.local")
        assertThat(user.id).isNotNull()
    }

    @Test
    fun `provisionFromExternal — 동일 username 재호출 시 같은 id 반환 (멱등)`() {
        val first = repo.provisionFromExternal("ivy", "ivy@ldap.bts.local", "Ivy")
        val second = repo.provisionFromExternal("ivy", "ivy-updated@ldap.bts.local", "Ivy Updated")

        assertThat(second.id).isEqualTo(first.id)
        assertThat(second.displayName).isEqualTo("Ivy Updated")
    }

    // ── findByIds ─────────────────────────────────────────────────────────────

    @Test
    fun `findByIds — 주어진 id 목록에 해당하는 사용자만 반환`() {
        val alice = repo.save("findbyids-alice", "alice@bts.local", "Alice Kim")
        val bob = repo.save("findbyids-bob", "bob@bts.local", "Bob Lee")
        repo.save("findbyids-charlie", "charlie@bts.local", "Charlie Park")

        val result = repo.findByIds(listOf(alice.id, bob.id))

        assertThat(result).hasSize(2)
        assertThat(result.map { it.id }).containsExactlyInAnyOrder(alice.id, bob.id)
    }

    @Test
    fun `findByIds — 존재하지 않는 id 는 결과에서 조용히 제외`() {
        val alice = repo.save("findbyids-alice2", "alice2@bts.local", "Alice2")
        val missingId = UUID.randomUUID()

        val result = repo.findByIds(listOf(alice.id, missingId))

        assertThat(result).hasSize(1)
        assertThat(result.first().id).isEqualTo(alice.id)
    }

    @Test
    fun `findByIds — 빈 리스트 입력 시 빈 결과 반환`() {
        repo.save("findbyids-alice3", "alice3@bts.local", "Alice3")

        val result: List<User> = repo.findByIds(emptyList())

        assertThat(result).hasSize(0)
    }

    // ── updateLastLogin ───────────────────────────────────────────────────────

    @Test
    fun `updateLastLogin — 존재하지 않는 id 는 조용히 무시 (0 행 영향)`() {
        // 예외 없이 실행돼야 함
        repo.updateLastLogin(UUID.randomUUID())
    }

    @Test
    fun `updateLastLogin — 호출 후 updatedAt 이 갱신된다`() {
        val user = repo.save("jack", "jack@bts.local", "Jack")

        // updatedAt 기준점 확보 후 약간 대기
        Thread.sleep(10)
        repo.updateLastLogin(user.id)

        val found = repo.findById(user.id)!!
        assertThat(found.updatedAt).isAfterOrEqualTo(user.updatedAt)
    }
}
