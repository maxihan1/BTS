// JdbcMfaBackupCodeRepository 통합 테스트 — Testcontainers PostgreSQL 16 + Flyway(V023 백업코드) 실 repo 검증

package com.atlas.bts.identity.mfa

import com.atlas.bts.identity.support.SharedPostgres
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
import java.util.UUID

/**
 * [JdbcMfaBackupCodeRepository] 통합 테스트 (FR-MF-02 Task 4).
 *
 * `@JdbcTest` + Testcontainers PostgreSQL 16 + Flyway(V023 `user_mfa_backup_codes`)를 적용해
 * 실 repo + 실 DB 로 영속 연산을 end-to-end 검증한다(mock 미사용).
 *
 * - **replaceAll** — 기존 user_id 전량 DELETE + 새 codeHashes INSERT(단일 트랜잭션). 재발급 시 이전 코드 전부 무효.
 * - **consumeIfUnused** — 단일 atomic UPDATE. 미사용 코드 1회만 소진(영향 1행→true), 2회째/없는 해시→false.
 * - **countUnused / countTotal** — 상태 표시용 미사용·전체 카운트.
 * - **deleteAllByUser** — 사용자 백업 코드 전량 삭제(disable).
 *
 * users FK 충족을 위해 [setUp] 에서 users 행을 선 INSERT 한다(join-table-fk-cascade 교훈).
 */
@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JdbcMfaBackupCodeRepository::class)
class JdbcMfaBackupCodeRepositoryIntegrationTest {
    companion object {
        /**
         * 공용 컨테이너의 템플릿 DB 를 복제한 전용 데이터베이스.
         *
         * 격리는 그대로이고 컨테이너 기동과 마이그레이션 재적용만 사라진다.
         * 근거와 주의점은 [com.atlas.bts.identity.support.SharedPostgres] 헤더.
         */
        @JvmStatic
        val postgres = SharedPostgres.freshDatabase()

        @DynamicPropertySource
        @JvmStatic
        fun postgresProps(r: DynamicPropertyRegistry) {
            r.add("spring.datasource.url") { postgres.jdbcUrl }
            r.add("spring.datasource.username") { postgres.username }
            r.add("spring.datasource.password") { postgres.password }
            // 템플릿 DB 에서 이미 적용됐다 — 여기서 다시 돌리면 이 최적화가 무의미해진다
            r.add("spring.flyway.enabled") { "false" }
            // 공용 컨테이너라 커넥션 한도도 공유한다. context 캐시가 쌓이면 기본 풀(10)로는
            // max_connections 를 넘긴다 — SharedPostgres 헤더 참조.
            r.add("spring.datasource.hikari.maximum-pool-size") { SharedPostgres.MAX_POOL_SIZE }
        }
    }

    @Autowired
    @Suppress("VarCouldBeVal")
    private lateinit var repo: MfaBackupCodeRepository

    @Autowired
    @Suppress("VarCouldBeVal")
    private lateinit var jdbc: NamedParameterJdbcTemplate

    private lateinit var userId: UUID

    @BeforeEach
    fun setUp() {
        jdbc.update("DELETE FROM user_mfa_backup_codes", emptyMap<String, Any>())
        jdbc.update("DELETE FROM users", emptyMap<String, Any>())

        // users 픽스처 — user_mfa_backup_codes.user_id FK 충족
        userId = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO users (id, username) VALUES (:id, :username)",
            mapOf("id" to userId, "username" to "test-user-$userId"),
        )
    }

    // ── replaceAll ────────────────────────────────────────────────────────────────

    @Test
    fun `replaceAll은 새 codeHashes를 모두 저장하고 countTotal_countUnused로 재조회된다`() {
        repo.replaceAll(userId, listOf("hash-a", "hash-b", "hash-c"))

        assertThat(repo.countTotal(userId)).isEqualTo(3)
        assertThat(repo.countUnused(userId)).isEqualTo(3)
    }

    @Test
    fun `replaceAll 재호출은 기존 미사용 코드를 전부 무효화하고 새 묶음으로 교체한다`() {
        repo.replaceAll(userId, listOf("old-1", "old-2"))

        repo.replaceAll(userId, listOf("new-1", "new-2", "new-3"))

        assertThat(repo.countTotal(userId)).isEqualTo(3)
        assertThat(repo.countUnused(userId)).isEqualTo(3)
        // 이전 코드는 더 이상 소진 불가(존재하지 않음)
        assertThat(repo.consumeIfUnused(userId, "old-1")).isFalse()
        // 새 코드는 소진 가능
        assertThat(repo.consumeIfUnused(userId, "new-1")).isTrue()
    }

    @Test
    fun `replaceAll 재호출은 이미 사용된 코드도 전부 삭제한다`() {
        repo.replaceAll(userId, listOf("used-1", "keep-1"))
        repo.consumeIfUnused(userId, "used-1")
        assertThat(repo.countTotal(userId)).isEqualTo(2)

        repo.replaceAll(userId, listOf("fresh-1"))

        // 사용/미사용 무관 이전 묶음 전량 삭제 → 새 1건만 남는다
        assertThat(repo.countTotal(userId)).isEqualTo(1)
        assertThat(repo.countUnused(userId)).isEqualTo(1)
        assertThat(repo.consumeIfUnused(userId, "used-1")).isFalse()
        assertThat(repo.consumeIfUnused(userId, "fresh-1")).isTrue()
    }

    @Test
    fun `replaceAll에 빈 리스트를 주면 기존 코드만 삭제하고 새로 추가하지 않는다`() {
        repo.replaceAll(userId, listOf("a", "b"))

        repo.replaceAll(userId, emptyList())

        assertThat(repo.countTotal(userId)).isEqualTo(0)
        assertThat(repo.countUnused(userId)).isEqualTo(0)
    }

    // ── consumeIfUnused (단일 atomic UPDATE — 멱등) ─────────────────────────────────

    @Test
    fun `consumeIfUnused는 미사용 코드를 1회 소진하면 true를 반환한다`() {
        repo.replaceAll(userId, listOf("code-x", "code-y"))

        assertThat(repo.consumeIfUnused(userId, "code-x")).isTrue()
        // 소진 후 미사용 카운트만 감소, 전체는 유지(used_at 만 채워짐)
        assertThat(repo.countUnused(userId)).isEqualTo(1)
        assertThat(repo.countTotal(userId)).isEqualTo(2)
    }

    @Test
    fun `consumeIfUnused는 같은 코드를 두 번째로 소진하면 false를 반환한다 (멱등)`() {
        repo.replaceAll(userId, listOf("code-x"))

        assertThat(repo.consumeIfUnused(userId, "code-x")).isTrue()
        // 2회째 — 이미 used_at 이 채워져 영향 0행 → false
        assertThat(repo.consumeIfUnused(userId, "code-x")).isFalse()
        assertThat(repo.countUnused(userId)).isEqualTo(0)
        assertThat(repo.countTotal(userId)).isEqualTo(1)
    }

    @Test
    fun `consumeIfUnused는 존재하지 않는 해시면 false를 반환한다`() {
        repo.replaceAll(userId, listOf("real-code"))

        assertThat(repo.consumeIfUnused(userId, "ghost-code")).isFalse()
        assertThat(repo.countUnused(userId)).isEqualTo(1)
    }

    @Test
    fun `consumeIfUnused는 다른 사용자의 코드는 소진하지 못한다`() {
        val other = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO users (id, username) VALUES (:id, :username)",
            mapOf("id" to other, "username" to "other-user-$other"),
        )
        repo.replaceAll(userId, listOf("shared-hash"))
        repo.replaceAll(other, listOf("shared-hash"))

        // userId 가 자기 코드를 소진해도 other 의 같은 해시 코드는 그대로 미사용
        assertThat(repo.consumeIfUnused(userId, "shared-hash")).isTrue()
        assertThat(repo.countUnused(other)).isEqualTo(1)
        assertThat(repo.consumeIfUnused(other, "shared-hash")).isTrue()
    }

    // ── countUnused / countTotal ────────────────────────────────────────────────────

    @Test
    fun `countUnused와 countTotal은 코드가 없으면 0을 반환한다`() {
        assertThat(repo.countUnused(userId)).isEqualTo(0)
        assertThat(repo.countTotal(userId)).isEqualTo(0)
    }

    // ── deleteAllByUser ───────────────────────────────────────────────────────────

    @Test
    fun `deleteAllByUser는 사용자 백업 코드를 전량 삭제한다`() {
        repo.replaceAll(userId, listOf("d-1", "d-2", "d-3"))
        repo.consumeIfUnused(userId, "d-1")

        repo.deleteAllByUser(userId)

        assertThat(repo.countTotal(userId)).isEqualTo(0)
        assertThat(repo.countUnused(userId)).isEqualTo(0)
    }

    @Test
    fun `deleteAllByUser는 다른 사용자 코드는 건드리지 않는다`() {
        val other = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO users (id, username) VALUES (:id, :username)",
            mapOf("id" to other, "username" to "other-user-$other"),
        )
        repo.replaceAll(userId, listOf("mine-1"))
        repo.replaceAll(other, listOf("other-1", "other-2"))

        repo.deleteAllByUser(userId)

        assertThat(repo.countTotal(userId)).isEqualTo(0)
        assertThat(repo.countTotal(other)).isEqualTo(2)
    }
}
