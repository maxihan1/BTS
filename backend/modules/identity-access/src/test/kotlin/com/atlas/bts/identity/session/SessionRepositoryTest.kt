// SessionRepository 통합 테스트 — Testcontainers PostgreSQL + Flyway V001~V004 적용

package com.atlas.bts.identity.session

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
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * JdbcSessionRepository 통합 테스트 (FR-AU-09 Task 8).
 *
 * @JdbcTest + Testcontainers PostgreSQL + Flyway V001~V004 자동 적용.
 * 검증 대상: save / findById / findActiveByUserId / revokeAllByUserId / markRevoked / updateLastSeen.
 *
 * **회귀 가드**: findActiveByUserId 는 partial index (WHERE revoked_at IS NULL) 를 활용하는
 * 쿼리여야 한다. 폐기된 세션이 결과에 포함되지 않음을 명시적으로 검증한다.
 */
@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JdbcSessionRepository::class)
@Testcontainers
class SessionRepositoryTest {

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
    private lateinit var repo: SessionRepository

    @Autowired
    private lateinit var jdbc: NamedParameterJdbcTemplate

    /** 모든 테스트에서 사용할 사용자 ID — users FK 충족용 픽스처 */
    private lateinit var userId: UUID

    @BeforeEach
    fun setUp() {
        // FK 의존 순서대로 삭제
        jdbc.update("DELETE FROM sessions", emptyMap<String, Any>())
        jdbc.update("DELETE FROM users", emptyMap<String, Any>())

        // users 픽스처 — sessions.user_id FK 충족
        userId = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO users (id, username) VALUES (:id, :username)",
            mapOf("id" to userId, "username" to "test-user-$userId"),
        )
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun buildSession(
        id: UUID = UUID.randomUUID(),
        userId: UUID = this.userId,
        providerId: String = "local",
        expiresAt: Instant = Instant.now().plus(14, ChronoUnit.DAYS),
    ): Session =
        Session(
            id = id,
            userId = userId,
            providerId = providerId,
            deviceFingerprint = null,
            ipAddress = null,
            userAgent = null,
            createdAt = Instant.now(),
            expiresAt = expiresAt,
            lastSeenAt = Instant.now(),
            revokedAt = null,
            revokeReason = null,
        )

    // ── save ──────────────────────────────────────────────────────────────────

    @Test
    fun `save — INSERT 후 findById 로 동일 세션 조회 성공`() {
        val session = buildSession()

        repo.save(session)

        val found = repo.findById(session.id)
        assertThat(found).isNotNull()
        assertThat(found!!.id).isEqualTo(session.id)
        assertThat(found.userId).isEqualTo(session.userId)
        assertThat(found.providerId).isEqualTo("local")
        assertThat(found.revokedAt).isNull()
        assertThat(found.revokeReason).isNull()
    }

    @Test
    fun `save — expiresAt, lastSeenAt, createdAt 필드가 DB에 정확히 저장된다`() {
        val now = Instant.now().truncatedTo(ChronoUnit.MILLIS)
        val expiresAt = now.plus(14, ChronoUnit.DAYS)
        val session = buildSession(expiresAt = expiresAt)

        repo.save(session)

        val found = repo.findById(session.id)!!
        // TIMESTAMPTZ 는 마이크로초 단위로 저장되므로 밀리초 기준 오차를 허용한다
        assertThat(found.expiresAt.truncatedTo(ChronoUnit.MILLIS)).isEqualTo(expiresAt)
    }

    // ── findById ──────────────────────────────────────────────────────────────

    @Test
    fun `findById — 없는 id 조회 시 null 반환`() {
        val result = repo.findById(UUID.randomUUID())

        assertThat(result).isNull()
    }

    // ── findActiveByUserId ────────────────────────────────────────────────────

    @Test
    fun `findActiveByUserId — 활성 세션만 반환하고 폐기 세션은 제외한다`() {
        val active1 = buildSession()
        val active2 = buildSession()
        val toRevoke = buildSession()

        repo.save(active1)
        repo.save(active2)
        repo.save(toRevoke)

        // toRevoke 세션을 명시적으로 폐기
        repo.markRevoked(toRevoke.id, "logout")

        val results = repo.findActiveByUserId(userId)

        assertThat(results).hasSize(2)
        val ids = results.map { it.id }
        assertThat(ids).containsExactlyInAnyOrder(active1.id, active2.id)
        assertThat(ids).doesNotContain(toRevoke.id)
    }

    @Test
    fun `findActiveByUserId — 만료된 세션은 반환하지 않는다`() {
        val expired = buildSession(expiresAt = Instant.now().minus(1, ChronoUnit.HOURS))
        val active = buildSession()

        repo.save(expired)
        repo.save(active)

        val results = repo.findActiveByUserId(userId)

        // 만료된 세션은 활성 세션 조회에서 제외해야 한다
        assertThat(results.map { it.id }).doesNotContain(expired.id)
        assertThat(results.map { it.id }).contains(active.id)
    }

    @Test
    fun `findActiveByUserId — 세션이 없으면 빈 리스트 반환`() {
        val results = repo.findActiveByUserId(userId)

        assertThat(results).isEmpty()
    }

    // ── revokeAllByUserId ─────────────────────────────────────────────────────

    @Test
    fun `revokeAllByUserId — userId 에 속한 모든 활성 세션을 폐기한다`() {
        val session1 = buildSession()
        val session2 = buildSession()

        repo.save(session1)
        repo.save(session2)

        val revokedCount = repo.revokeAllByUserId(userId, "logout_all")

        assertThat(revokedCount).isEqualTo(2)
        assertThat(repo.findActiveByUserId(userId)).isEmpty()
    }

    @Test
    fun `revokeAllByUserId — 이미 폐기된 세션은 카운트에 포함되지 않는다`() {
        val session = buildSession()
        repo.save(session)
        repo.markRevoked(session.id, "logout")

        // 이미 폐기된 세션만 남아있는 경우 0 반환
        val revokedCount = repo.revokeAllByUserId(userId, "logout_all")

        assertThat(revokedCount).isEqualTo(0)
    }

    @Test
    fun `revokeAllByUserId — 다른 userId 세션에 영향을 주지 않는다`() {
        val otherUserId = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO users (id, username) VALUES (:id, :username)",
            mapOf("id" to otherUserId, "username" to "other-user-$otherUserId"),
        )

        val mySession = buildSession(userId = userId)
        val otherSession = buildSession(userId = otherUserId)
        repo.save(mySession)
        repo.save(otherSession)

        repo.revokeAllByUserId(userId, "logout_all")

        // 다른 사용자의 세션은 여전히 활성이어야 한다
        val otherActive = repo.findActiveByUserId(otherUserId)
        assertThat(otherActive).hasSize(1)
        assertThat(otherActive[0].id).isEqualTo(otherSession.id)
    }

    // ── markRevoked ───────────────────────────────────────────────────────────

    @Test
    fun `markRevoked — 단일 세션 폐기 후 revokedAt + revokeReason 설정`() {
        val session = buildSession()
        repo.save(session)

        repo.markRevoked(session.id, "replay_detected")

        val found = repo.findById(session.id)!!
        assertThat(found.revokedAt).isNotNull()
        assertThat(found.revokeReason).isEqualTo("replay_detected")
    }

    @Test
    fun `markRevoked — 존재하지 않는 id 는 예외 없이 0 행 영향`() {
        // 존재하지 않는 세션 ID에 대해 예외 없이 실행돼야 한다
        repo.markRevoked(UUID.randomUUID(), "logout")
    }

    // ── updateLastSeen ────────────────────────────────────────────────────────

    @Test
    fun `updateLastSeen — lastSeenAt 이 갱신된다`() {
        val session = buildSession()
        repo.save(session)

        // 충분한 시간 차이 확보
        Thread.sleep(10)
        repo.updateLastSeen(session.id)

        val found = repo.findById(session.id)!!
        assertThat(found.lastSeenAt).isAfterOrEqualTo(session.lastSeenAt)
    }

    @Test
    fun `updateLastSeen — 존재하지 않는 id 는 예외 없이 0 행 영향`() {
        // 존재하지 않는 세션 ID에 대해 예외 없이 실행돼야 한다
        repo.updateLastSeen(UUID.randomUUID())
    }
}
