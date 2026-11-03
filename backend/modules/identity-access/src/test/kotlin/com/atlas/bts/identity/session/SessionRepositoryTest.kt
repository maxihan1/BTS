// SessionRepository 통합 테스트 — Testcontainers PostgreSQL + Flyway V001~V004 적용

package com.atlas.bts.identity.session

import com.atlas.bts.identity.support.SharedPostgres
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest
import org.springframework.context.annotation.Import
import org.springframework.core.io.ClassPathResource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DataSourceUtils
import org.springframework.jdbc.datasource.init.ScriptUtils
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
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
class SessionRepositoryTest {
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

    // ── S24 back-channel logout ───────────────────────────────────────────────

    @Test
    fun `save — Auth sid 와 암호화 id token 이 저장되고 다시 읽힌다`() {
        val session = buildSession(providerId = "oidc").copy(authSid = "sid-a", authIdTokenEnc = "cipher")

        repo.save(session)

        val found = repo.findById(session.id)!!
        assertThat(found.authSid).isEqualTo("sid-a")
        assertThat(found.authIdTokenEnc).isEqualTo("cipher")
    }

    @Test
    fun `revokeActiveByAuthSid — 그 sid 의 살아 있는 세션만 폐기하고 id 를 돌려준다`() {
        val a1 = buildSession(providerId = "oidc").copy(authSid = "sid-a")
        val a2 = buildSession(providerId = "oidc").copy(authSid = "sid-a")
        val other = buildSession(providerId = "oidc").copy(authSid = "sid-b")
        listOf(a1, a2, other).forEach(repo::save)
        repo.markRevoked(a2.id, "logout")

        val revoked = repo.revokeActiveByAuthSid("sid-a", "backchannel_logout")

        assertThat(revoked).containsExactly(a1.id)
        assertThat(repo.findById(a1.id)!!.revokeReason).isEqualTo("backchannel_logout")
        assertThat(repo.findById(a2.id)!!.revokeReason).isEqualTo("logout")
        assertThat(repo.findById(other.id)!!.revokedAt).isNull()
    }

    @Test
    fun `revokeActiveByUserCreatedBefore — 그 provider 의 기준 시각 전 세션만 폐기한다`() {
        val cut = Instant.now().truncatedTo(ChronoUnit.SECONDS)
        val before = buildSession(providerId = "oidc").copy(createdAt = cut.minusSeconds(60))
        val after = buildSession(providerId = "oidc").copy(createdAt = cut.plusSeconds(1))
        val local = buildSession(providerId = "local").copy(createdAt = cut.minusSeconds(60))
        listOf(before, after, local).forEach(repo::save)

        val revoked = repo.revokeActiveByUserCreatedBefore(userId, "oidc", cut, "backchannel_logout")

        // 다시 보낸 token 이 그 뒤 로그인을 끊지 않고 · 로컬 비밀번호 세션은 남는다 (S24 §1)
        assertThat(revoked).containsExactly(before.id)
        assertThat(repo.findById(after.id)!!.revokedAt).isNull()
        assertThat(repo.findById(local.id)!!.revokedAt).isNull()
    }

    @Test
    fun `V038 — S24 전 oidc 세션과 그 refresh 만 끊고 sid 있는 oidc · 로컬 세션은 남긴다`() {
        val pre = buildSession(providerId = "oidc")
        val withSid = buildSession(providerId = "oidc").copy(authSid = "sid-a")
        val local = buildSession(providerId = "local")
        listOf(pre, withSid, local).forEach(repo::save)
        val preToken = insertRefreshToken(pre.id)
        val withSidToken = insertRefreshToken(withSid.id)

        // 템플릿 DB 에는 이미 적용됐다 — 같은 SQL 을 이 데이터에 다시 돌린다 (테스트 트랜잭션 안)
        val dataSource = requireNotNull(jdbc.jdbcTemplate.dataSource)
        ScriptUtils.executeSqlScript(
            DataSourceUtils.getConnection(dataSource),
            ClassPathResource("db/migration/identity-access/V038__revoke_pre_s24_oidc_sessions.sql"),
        )

        assertThat(repo.findById(pre.id)?.revokeReason).isEqualTo("s24_relogin")
        assertThat(repo.findById(withSid.id)?.revokedAt).isNull()
        assertThat(repo.findById(local.id)?.revokedAt).isNull()
        assertThat(refreshUsedAt(preToken)).isNotNull()
        assertThat(refreshUsedAt(withSidToken)).isNull()
    }

    private fun insertRefreshToken(sessionId: UUID): UUID {
        val id = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO refresh_tokens (id, session_id, token_hash, expires_at) " +
                "VALUES (:id, :sid, :hash, NOW() + INTERVAL '1 day')",
            mapOf("id" to id, "sid" to sessionId, "hash" to id.toString().replace("-", "").padEnd(64, '0')),
        )
        return id
    }

    private fun refreshUsedAt(id: UUID): Any? =
        jdbc.queryForList("SELECT used_at FROM refresh_tokens WHERE id = :id", mapOf("id" to id)).single()["used_at"]
}
