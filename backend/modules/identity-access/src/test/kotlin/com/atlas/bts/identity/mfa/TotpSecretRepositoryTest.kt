// TotpSecretRepository 통합 테스트 — Testcontainers PostgreSQL 16 + Flyway(V022 totp_secrets) 실 repo 검증

package com.atlas.bts.identity.mfa

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
 * [TotpSecretRepository] 통합 테스트 (FR-MF-01 Task 4).
 *
 * `@JdbcTest` + Testcontainers PostgreSQL 16 + Flyway(V022 `totp_secrets`)를 적용해
 * 실 repo + 실 DB 로 영속 연산을 end-to-end 검증한다(mock 미사용).
 *
 * - **upsertPending** — 신규 PENDING 저장 + 기존(PENDING/ACTIVE)을 새 PENDING 으로 덮어쓰기(re-setup).
 * - **findByUser** — 단건 조회, 없으면 null.
 * - **activate** — PENDING → ACTIVE + confirmed_at 설정.
 * - **advanceVerifiedStep** — 단조 증가(replay/TOCTOU 방어) 조건부 UPDATE. 같거나 낮은 step 은 false.
 * - **deleteByUser** — disable(행 삭제).
 *
 * users FK 충족을 위해 [setUp] 에서 users 행을 선 INSERT 한다.
 */
@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TotpSecretRepository::class)
@Testcontainers
class TotpSecretRepositoryTest {
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
    private lateinit var repo: TotpSecretRepository

    @Autowired
    private lateinit var jdbc: NamedParameterJdbcTemplate

    private lateinit var userId: UUID

    @BeforeEach
    fun setUp() {
        jdbc.update("DELETE FROM totp_secrets", emptyMap<String, Any>())
        jdbc.update("DELETE FROM users", emptyMap<String, Any>())

        // users 픽스처 — totp_secrets.user_id FK 충족
        userId = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO users (id, username) VALUES (:id, :username)",
            mapOf("id" to userId, "username" to "test-user-$userId"),
        )
    }

    // ── upsertPending / findByUser ────────────────────────────────────────────

    @Test
    fun `upsertPending는 신규 PENDING secret을 저장하고 findByUser로 재조회된다`() {
        repo.upsertPending(userId, "cipher-1")

        val found = repo.findByUser(userId)
        assertThat(found).isNotNull()
        assertThat(found!!.userId).isEqualTo(userId)
        assertThat(found.secretCipher).isEqualTo("cipher-1")
        assertThat(found.status).isEqualTo(TotpStatus.PENDING)
        assertThat(found.lastVerifiedStep).isNull()
        assertThat(found.confirmedAt).isNull()
        assertThat(found.createdAt).isNotNull()
        assertThat(found.updatedAt).isNotNull()
    }

    @Test
    fun `존재하지 않는 user 조회 시 null을 반환한다`() {
        assertThat(repo.findByUser(UUID.randomUUID())).isNull()
    }

    @Test
    fun `upsertPending는 기존 ACTIVE secret을 새 PENDING으로 되돌린다 (re-setup)`() {
        repo.upsertPending(userId, "cipher-old")
        repo.activate(userId)
        repo.advanceVerifiedStep(userId, 100L)
        assertThat(repo.findByUser(userId)!!.status).isEqualTo(TotpStatus.ACTIVE)

        repo.upsertPending(userId, "cipher-new")

        val reSetup = repo.findByUser(userId)
        assertThat(reSetup).isNotNull()
        assertThat(reSetup!!.secretCipher).isEqualTo("cipher-new")
        assertThat(reSetup.status).isEqualTo(TotpStatus.PENDING)
        assertThat(reSetup.lastVerifiedStep).isNull()
        assertThat(reSetup.confirmedAt).isNull()
    }

    // ── activate ────────────────────────────────────────────────────────────────

    @Test
    fun `activate는 PENDING을 ACTIVE로 전이하고 confirmed_at을 채운다`() {
        repo.upsertPending(userId, "cipher-1")

        repo.activate(userId)

        val activated = repo.findByUser(userId)
        assertThat(activated).isNotNull()
        assertThat(activated!!.status).isEqualTo(TotpStatus.ACTIVE)
        assertThat(activated.confirmedAt).isNotNull()
    }

    // ── advanceVerifiedStep (replay / TOCTOU 방어) ────────────────────────────────

    @Test
    fun `advanceVerifiedStep는 더 큰 step이면 true를 반환하고 값을 갱신한다`() {
        repo.upsertPending(userId, "cipher-1")

        assertThat(repo.advanceVerifiedStep(userId, 100L)).isTrue()
        assertThat(repo.findByUser(userId)!!.lastVerifiedStep).isEqualTo(100L)

        assertThat(repo.advanceVerifiedStep(userId, 101L)).isTrue()
        assertThat(repo.findByUser(userId)!!.lastVerifiedStep).isEqualTo(101L)
    }

    @Test
    fun `advanceVerifiedStep는 같거나 낮은 step이면 false를 반환하고 값을 유지한다 (replay 차단)`() {
        repo.upsertPending(userId, "cipher-1")
        repo.advanceVerifiedStep(userId, 100L)

        // 같은 step (replay) → 거부
        assertThat(repo.advanceVerifiedStep(userId, 100L)).isFalse()
        assertThat(repo.findByUser(userId)!!.lastVerifiedStep).isEqualTo(100L)

        // 낮은 step (replay) → 거부
        assertThat(repo.advanceVerifiedStep(userId, 99L)).isFalse()
        assertThat(repo.findByUser(userId)!!.lastVerifiedStep).isEqualTo(100L)
    }

    @Test
    fun `advanceVerifiedStep는 last_verified_step이 NULL이면 첫 step을 허용한다`() {
        repo.upsertPending(userId, "cipher-1")
        assertThat(repo.findByUser(userId)!!.lastVerifiedStep).isNull()

        assertThat(repo.advanceVerifiedStep(userId, 1L)).isTrue()
        assertThat(repo.findByUser(userId)!!.lastVerifiedStep).isEqualTo(1L)
    }

    // ── deleteByUser ──────────────────────────────────────────────────────────────

    @Test
    fun `deleteByUser는 존재하면 true, 없으면 false를 반환한다`() {
        repo.upsertPending(userId, "cipher-1")

        assertThat(repo.deleteByUser(userId)).isTrue()
        assertThat(repo.findByUser(userId)).isNull()
        assertThat(repo.deleteByUser(userId)).isFalse()
    }
}
