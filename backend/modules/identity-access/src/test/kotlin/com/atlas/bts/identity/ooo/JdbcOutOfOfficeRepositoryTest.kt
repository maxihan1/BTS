// JdbcOutOfOfficeRepository 통합 테스트 — Testcontainers PostgreSQL + Flyway 전체 적용 (FR-PR-03 task-2)

package com.atlas.bts.identity.ooo

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
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * JdbcOutOfOfficeRepository 통합 테스트 (FR-PR-03 Task 2).
 *
 * @JdbcTest + Testcontainers PostgreSQL + Flyway 전체 마이그레이션(V001~V029) 자동 적용.
 * 검증 대상: upsert / findByUserId(raw, delegateName LEFT JOIN) / findActiveByUserId(Clock 기준 필터) /
 * deleteByUserId.
 */
@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JdbcOutOfOfficeRepository::class)
class JdbcOutOfOfficeRepositoryTest {
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

        /** 고정 기준 시각 — 활성 필터 판정에 사용하는 Clock. */
        private val NOW: Instant = Instant.parse("2026-07-07T10:00:00Z")
        private val FIXED_CLOCK: Clock = Clock.fixed(NOW, ZoneOffset.UTC)
    }

    @Autowired
    @Suppress("VarCouldBeVal") // @Autowired lateinit var 는 val 불가(주입). detekt false-positive 억제
    private lateinit var repo: OutOfOfficeRepository

    @Autowired
    @Suppress("VarCouldBeVal") // @Autowired lateinit var 는 val 불가(주입). detekt false-positive 억제
    private lateinit var jdbc: NamedParameterJdbcTemplate

    @BeforeEach
    fun setUp() {
        // users 삭제 시 user_ooo 는 ON DELETE CASCADE 로 함께 삭제됨 (V029)
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

    // ── findByUserId (raw) ───────────────────────────────────────────────────

    @Test
    fun `findByUserId 없으면 null 반환`() {
        val userId = insertTestUser("ooo-none")

        assertThat(repo.findByUserId(userId)).isNull()
    }

    @Test
    fun `findByUserId는 종료된 OOO도 raw로 반환한다 (무필터)`() {
        val userId = insertTestUser("ooo-ended-raw")
        val past = NOW.minusSeconds(3600)
        val moreThanPast = NOW.minusSeconds(1)
        repo.upsert(userId, past, moreThanPast, null, null)

        val found = repo.findByUserId(userId)

        assertThat(found).isNotNull()
        assertThat(found!!.startsAt).isEqualTo(past)
        assertThat(found.endsAt).isEqualTo(moreThanPast)
    }

    // ── upsert ───────────────────────────────────────────────────────────────

    @Test
    fun `upsert 기간만 설정 — 대리자-메시지 없이도 성립`() {
        val userId = insertTestUser("ooo-period-only")
        val startsAt = NOW.plusSeconds(3600)
        val endsAt = NOW.plusSeconds(7200)

        repo.upsert(userId, startsAt, endsAt, null, null)

        val found = repo.findByUserId(userId)
        assertThat(found).isNotNull()
        assertThat(found!!.userId).isEqualTo(userId)
        assertThat(found.startsAt).isEqualTo(startsAt)
        assertThat(found.endsAt).isEqualTo(endsAt)
        assertThat(found.delegateUserId).isNull()
        assertThat(found.delegateName).isNull()
        assertThat(found.message).isNull()
    }

    @Test
    fun `upsert 대리자와 메시지까지 설정 — delegateName은 users LEFT JOIN 파생`() {
        val userId = insertTestUser("ooo-with-delegate")
        val delegateId = insertTestUser("ooo-delegate-bob")
        val startsAt = NOW.minusSeconds(3600)
        val endsAt = NOW.plusSeconds(3600)

        repo.upsert(userId, startsAt, endsAt, delegateId, "휴가 중입니다.")

        val found = repo.findByUserId(userId)
        assertThat(found).isNotNull()
        assertThat(found!!.delegateUserId).isEqualTo(delegateId)
        assertThat(found.delegateName).isEqualTo("ooo-delegate-bob")
        assertThat(found.message).isEqualTo("휴가 중입니다.")
    }

    @Test
    fun `upsert 재호출 시 통짜 교체(replace) — 이전 값 잔존 없음`() {
        val userId = insertTestUser("ooo-replace")
        val delegateId = insertTestUser("ooo-replace-delegate")
        repo.upsert(userId, NOW.minusSeconds(100), NOW.plusSeconds(100), delegateId, "첫 메시지")

        val newStartsAt = NOW.plusSeconds(1000)
        val newEndsAt = NOW.plusSeconds(2000)
        repo.upsert(userId, newStartsAt, newEndsAt, null, null)

        val found = repo.findByUserId(userId)
        assertThat(found).isNotNull()
        assertThat(found!!.startsAt).isEqualTo(newStartsAt)
        assertThat(found.endsAt).isEqualTo(newEndsAt)
        assertThat(found.delegateUserId).isNull()
        assertThat(found.delegateName).isNull()
        assertThat(found.message).isNull()
    }

    // ── findActiveByUserId (Clock 기준 필터) ───────────────────────────────────

    @Test
    fun `findActiveByUserId는 startsAt-endsAt 사이(현재)에만 반환한다`() {
        val userId = insertTestUser("ooo-active")
        repo.upsert(userId, NOW.minusSeconds(3600), NOW.plusSeconds(3600), null, null)

        val found = repo.findActiveByUserId(userId, FIXED_CLOCK)

        assertThat(found).isNotNull()
        assertThat(found!!.userId).isEqualTo(userId)
    }

    @Test
    fun `findActiveByUserId는 미래 예약 OOO를 반환하지 않는다`() {
        val userId = insertTestUser("ooo-future")
        repo.upsert(userId, NOW.plusSeconds(3600), NOW.plusSeconds(7200), null, null)

        assertThat(repo.findActiveByUserId(userId, FIXED_CLOCK)).isNull()
    }

    @Test
    fun `findActiveByUserId는 종료된 OOO를 반환하지 않는다`() {
        val userId = insertTestUser("ooo-past")
        repo.upsert(userId, NOW.minusSeconds(7200), NOW.minusSeconds(3600), null, null)

        assertThat(repo.findActiveByUserId(userId, FIXED_CLOCK)).isNull()
    }

    @Test
    fun `findActiveByUserId는 없으면 null 반환`() {
        val userId = insertTestUser("ooo-active-none")

        assertThat(repo.findActiveByUserId(userId, FIXED_CLOCK)).isNull()
    }

    // ── deleteByUserId ───────────────────────────────────────────────────────

    @Test
    fun `deleteByUserId 이후 findByUserId null`() {
        val userId = insertTestUser("ooo-delete")
        repo.upsert(userId, NOW.minusSeconds(100), NOW.plusSeconds(100), null, null)

        repo.deleteByUserId(userId)

        assertThat(repo.findByUserId(userId)).isNull()
    }

    @Test
    fun `deleteByUserId는 행이 없어도 멱등하게 무시된다`() {
        val userId = insertTestUser("ooo-delete-idempotent")

        repo.deleteByUserId(userId)
        repo.deleteByUserId(userId)

        assertThat(repo.findByUserId(userId)).isNull()
    }
}
