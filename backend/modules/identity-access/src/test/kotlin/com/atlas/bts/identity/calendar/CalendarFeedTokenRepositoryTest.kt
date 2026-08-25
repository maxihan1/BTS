// CalendarFeedTokenRepository 통합 테스트 — Testcontainers PostgreSQL + Flyway 전체 적용 (FR-CA-02 task-2)

package com.atlas.bts.identity.calendar

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
 * CalendarFeedTokenRepository 통합 테스트 (FR-CA-02 Task 2).
 *
 * @JdbcTest + Testcontainers PostgreSQL + Flyway 전체 마이그레이션(V001~V034) 자동 적용
 * (JdbcOutOfOfficeRepositoryTest 선례).
 *
 * 검증 대상.
 *  - upsert(userId, hash) 후 findUserIdByHash(hash) = 소유자 userId.
 *  - 재upsert 가 토큰을 rotate — 이전 hash 조회 실패, 새 hash 만 유효(사용자당 1개).
 *  - deleteByUserId 이후 findUserIdByHash / findByUserId 모두 null(하드 삭제).
 *  - findByUserId 가 발급 시각(created_at) 을 반환.
 */
@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(CalendarFeedTokenRepository::class)
class CalendarFeedTokenRepositoryTest {
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
    @Suppress("VarCouldBeVal") // @Autowired lateinit var 는 val 불가(주입). detekt false-positive 억제
    private lateinit var repo: CalendarFeedTokenRepository

    @Autowired
    @Suppress("VarCouldBeVal") // @Autowired lateinit var 는 val 불가(주입). detekt false-positive 억제
    private lateinit var jdbc: NamedParameterJdbcTemplate

    @BeforeEach
    fun setUp() {
        // users 삭제 시 user_calendar_tokens 는 ON DELETE CASCADE 로 함께 삭제됨 (V034)
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

    // ── upsert + findUserIdByHash ────────────────────────────────────────────

    @Test
    fun `upsert 후 findUserIdByHash는 소유자 userId를 반환한다`() {
        val userId = insertTestUser("cal-feed-owner")
        val hash = CalendarFeedToken.generate().hash

        repo.upsert(userId, hash)

        assertThat(repo.findUserIdByHash(hash)).isEqualTo(userId)
    }

    @Test
    fun `findUserIdByHash는 미존재 해시에 null을 반환한다`() {
        assertThat(repo.findUserIdByHash("0".repeat(64))).isNull()
    }

    @Test
    fun `재upsert는 토큰을 rotate한다 — 이전 해시 조회 실패, 새 해시만 유효`() {
        val userId = insertTestUser("cal-feed-rotate")
        val oldHash = CalendarFeedToken.generate().hash
        val newHash = CalendarFeedToken.generate().hash
        repo.upsert(userId, oldHash)

        repo.upsert(userId, newHash)

        assertThat(repo.findUserIdByHash(oldHash)).isNull()
        assertThat(repo.findUserIdByHash(newHash)).isEqualTo(userId)
    }

    // ── findByUserId (created_at) ────────────────────────────────────────────

    @Test
    fun `findByUserId는 발급 후 created_at을 반환한다`() {
        val userId = insertTestUser("cal-feed-createdat")

        repo.upsert(userId, CalendarFeedToken.generate().hash)

        assertThat(repo.findByUserId(userId)).isNotNull()
    }

    @Test
    fun `findByUserId는 미발급 사용자에 null을 반환한다`() {
        val userId = insertTestUser("cal-feed-none")

        assertThat(repo.findByUserId(userId)).isNull()
    }

    // ── deleteByUserId ───────────────────────────────────────────────────────

    @Test
    fun `deleteByUserId 이후 findUserIdByHash와 findByUserId는 null`() {
        val userId = insertTestUser("cal-feed-delete")
        val hash = CalendarFeedToken.generate().hash
        repo.upsert(userId, hash)

        repo.deleteByUserId(userId)

        assertThat(repo.findUserIdByHash(hash)).isNull()
        assertThat(repo.findByUserId(userId)).isNull()
    }

    @Test
    fun `deleteByUserId는 행이 없어도 멱등하게 무시된다`() {
        val userId = insertTestUser("cal-feed-delete-idempotent")

        repo.deleteByUserId(userId)
        repo.deleteByUserId(userId)

        assertThat(repo.findByUserId(userId)).isNull()
    }
}
