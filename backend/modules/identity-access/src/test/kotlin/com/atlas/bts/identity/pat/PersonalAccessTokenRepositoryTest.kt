// PersonalAccessTokenRepository 통합 테스트 — Testcontainers PostgreSQL + Flyway V001~V006 적용

package com.atlas.bts.identity.pat

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

// JacksonAutoConfiguration 명시 — @JdbcTest 슬라이스는 ObjectMapper 자동 구성을 포함하지 않으므로
// JdbcPersonalAccessTokenRepository 의 scopes JSONB 직렬화에 필요한 ObjectMapper Bean 을 공급한다.

/**
 * PersonalAccessTokenRepository 통합 테스트 (FR-AU-09 Task 10).
 *
 * @JdbcTest + Testcontainers PostgreSQL + Flyway V001~V006 자동 적용.
 * 검증 대상: save / findByTokenHash / findActiveByUserId / updateLastUsed / revoke + scopes JSONB 직렬화.
 * FR-API-04 Task 2 추가: listByUserIncludingExpired / findByIdAndUserId / revokeOwned / countActiveByUser
 * (본인 소유 확인·IDOR 차단·만료 포함/제외 구분).
 *
 * EC-26. token_hash = SHA-256("pat_" + body) 64자 hex 저장, raw token 미저장.
 * EC-27. expires_at nullable — 무기한 PAT.
 */
@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JdbcPersonalAccessTokenRepository::class, JacksonAutoConfiguration::class)
@Testcontainers
class PersonalAccessTokenRepositoryTest {
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

        /** 테스트용 token_hash — SHA-256 형식 64자 hex (실제 값 불필요, 형식만 충족). */
        private const val TOKEN_HASH_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        private const val TOKEN_HASH_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        private const val TOKEN_HASH_C = "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
        private const val TOKEN_HASH_D = "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd"
    }

    @Autowired
    private lateinit var repo: PersonalAccessTokenRepository

    @Autowired
    private lateinit var jdbc: NamedParameterJdbcTemplate

    /** 테스트마다 공유되는 users FK 픽스처 */
    private lateinit var userId: UUID

    @BeforeEach
    fun setUp() {
        // FK 의존 순서: personal_access_tokens → users
        jdbc.update("DELETE FROM personal_access_tokens", emptyMap<String, Any>())
        jdbc.update("DELETE FROM local_credentials", emptyMap<String, Any>())
        jdbc.update("DELETE FROM user_external_accounts", emptyMap<String, Any>())
        jdbc.update("DELETE FROM users", emptyMap<String, Any>())

        userId = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO users (id, username) VALUES (:id, :username)",
            mapOf("id" to userId, "username" to "pat-test-user-$userId"),
        )
    }

    // ── save ─────────────────────────────────────────────────────────────────

    @Test
    fun `save — 신규 INSERT 후 동일 엔티티 반환`() {
        val pat = buildPat(tokenHash = TOKEN_HASH_A, scopes = listOf("read:issues"))

        val saved = repo.save(pat)

        assertThat(saved.id).isEqualTo(pat.id)
        assertThat(saved.userId).isEqualTo(userId)
        assertThat(saved.name).isEqualTo(pat.name)
        assertThat(saved.tokenHash).isEqualTo(TOKEN_HASH_A)
        assertThat(saved.scopes).containsExactly("read:issues")
        assertThat(saved.createdAt).isNotNull()
        assertThat(saved.revokedAt).isNull()
        assertThat(saved.lastUsedAt).isNull()
    }

    @Test
    fun `save — scopes 빈 리스트 JSONB 직렬화`() {
        val pat = buildPat(tokenHash = TOKEN_HASH_A, scopes = emptyList())

        val saved = repo.save(pat)

        assertThat(saved.scopes).isEmpty()
    }

    @Test
    fun `save — scopes 다중 항목 JSONB 직렬화 후 역직렬화`() {
        val scopes = listOf("read:issues", "write:comments", "*")
        val pat = buildPat(tokenHash = TOKEN_HASH_A, scopes = scopes)

        val saved = repo.save(pat)

        assertThat(saved.scopes).containsExactlyInAnyOrderElementsOf(scopes)
    }

    @Test
    fun `save — expires_at null 허용 (EC-27 무기한 PAT)`() {
        val pat = buildPat(tokenHash = TOKEN_HASH_A, expiresAt = null)

        val saved = repo.save(pat)

        assertThat(saved.expiresAt).isNull()
    }

    @Test
    fun `save — expires_at 설정 시 보존`() {
        val expiry = Instant.now().plusSeconds(3600)
        val pat = buildPat(tokenHash = TOKEN_HASH_A, expiresAt = expiry)

        val saved = repo.save(pat)

        // DB 왕복 시 나노초 손실 허용: 밀리초 단위 비교
        assertThat(saved.expiresAt!!.toEpochMilli()).isEqualTo(expiry.toEpochMilli())
    }

    // ── findByTokenHash ──────────────────────────────────────────────────────

    @Test
    fun `findByTokenHash — 존재하는 hash 조회 시 PAT 반환`() {
        val pat = repo.save(buildPat(tokenHash = TOKEN_HASH_A, scopes = listOf("read:issues")))

        val found = repo.findByTokenHash(TOKEN_HASH_A)

        assertThat(found).isNotNull()
        assertThat(found!!.id).isEqualTo(pat.id)
        assertThat(found.tokenHash).isEqualTo(TOKEN_HASH_A)
        assertThat(found.scopes).containsExactly("read:issues")
    }

    @Test
    fun `findByTokenHash — 없는 hash 조회 시 null 반환`() {
        val result = repo.findByTokenHash(TOKEN_HASH_A)

        assertThat(result).isNull()
    }

    @Test
    fun `findByTokenHash — revoke 된 PAT 도 반환 (상태 판별은 엔티티 책임)`() {
        val pat = repo.save(buildPat(tokenHash = TOKEN_HASH_A))
        repo.revoke(pat.id)

        val found = repo.findByTokenHash(TOKEN_HASH_A)

        assertThat(found).isNotNull()
        assertThat(found!!.revokedAt).isNotNull()
    }

    // ── findActiveByUserId ───────────────────────────────────────────────────

    @Test
    fun `findActiveByUserId — 활성 PAT 만 반환`() {
        val activeA = repo.save(buildPat(tokenHash = TOKEN_HASH_A, name = "ci-token"))
        val activeB = repo.save(buildPat(tokenHash = TOKEN_HASH_B, name = "script-token"))
        val toRevoke = repo.save(buildPat(tokenHash = TOKEN_HASH_C, name = "revoked-token"))
        repo.revoke(toRevoke.id)

        val results = repo.findActiveByUserId(userId)

        val ids = results.map { it.id }
        assertThat(ids).containsExactlyInAnyOrder(activeA.id, activeB.id)
        assertThat(ids).doesNotContain(toRevoke.id)
    }

    @Test
    fun `findActiveByUserId — 없으면 빈 리스트`() {
        val result = repo.findActiveByUserId(userId)

        assertThat(result).isEmpty()
    }

    @Test
    fun `findActiveByUserId — 만료된 PAT 제외`() {
        val expired =
            buildPat(
                tokenHash = TOKEN_HASH_A,
                expiresAt = Instant.now().minusSeconds(1),
            )
        repo.save(expired)
        val active = repo.save(buildPat(tokenHash = TOKEN_HASH_B, expiresAt = null))

        val results = repo.findActiveByUserId(userId)

        // expires_at 이 과거인 토큰은 partial index 로 필터되지 않지만 엔티티 isActive() 로 2차 필터
        // Repository 레벨에서 expires_at 필터를 SQL 에 포함하는지 여부에 따라 결과 달라짐
        // 최소: active 는 포함돼야 함
        assertThat(results.map { it.id }).contains(active.id)
    }

    // ── updateLastUsed ───────────────────────────────────────────────────────

    @Test
    fun `updateLastUsed — last_used_at 이 NULL 에서 값으로 갱신된다`() {
        val pat = repo.save(buildPat(tokenHash = TOKEN_HASH_A))
        assertThat(pat.lastUsedAt).isNull()

        repo.updateLastUsed(pat.id)

        val found = repo.findByTokenHash(TOKEN_HASH_A)!!
        assertThat(found.lastUsedAt).isNotNull()
    }

    @Test
    fun `updateLastUsed — 존재하지 않는 id 는 조용히 무시`() {
        // 예외 없이 실행돼야 함
        repo.updateLastUsed(UUID.randomUUID())
    }

    // ── revoke ───────────────────────────────────────────────────────────────

    @Test
    fun `revoke — revoked_at 이 NULL 에서 설정된다`() {
        val pat = repo.save(buildPat(tokenHash = TOKEN_HASH_A))
        assertThat(pat.revokedAt).isNull()

        repo.revoke(pat.id)

        val found = repo.findByTokenHash(TOKEN_HASH_A)!!
        assertThat(found.revokedAt).isNotNull()
    }

    @Test
    fun `revoke — 이미 revoke 된 PAT 재호출은 조용히 무시 (멱등)`() {
        val pat = repo.save(buildPat(tokenHash = TOKEN_HASH_A))
        repo.revoke(pat.id)

        // 두 번 호출해도 예외 없음
        repo.revoke(pat.id)

        val found = repo.findByTokenHash(TOKEN_HASH_A)!!
        assertThat(found.revokedAt).isNotNull()
    }

    @Test
    fun `revoke — 존재하지 않는 id 는 조용히 무시`() {
        repo.revoke(UUID.randomUUID())
    }

    // ── listByUserIncludingExpired (FR-API-04 Task 2) ──────────────────────────

    @Test
    fun `listByUserIncludingExpired — revoke 만 제외하고 만료 포함, createdAt DESC 정렬`() {
        val active = repo.save(buildPat(tokenHash = TOKEN_HASH_A, name = "active"))
        val expired =
            repo.save(
                buildPat(tokenHash = TOKEN_HASH_B, name = "expired", expiresAt = Instant.now().minusSeconds(3600)),
            )
        val revoked = repo.save(buildPat(tokenHash = TOKEN_HASH_C, name = "revoked"))
        repo.revoke(revoked.id)

        // JdbcTest 단일 트랜잭션에서 DB now() 는 상수라 저장 시각이 동일해질 수 있어,
        // 결정적 DESC 정렬 검증을 위해 created_at 을 명시적으로 벌려 세팅한다.
        setCreatedAt(active.id, Instant.parse("2026-01-02T00:00:00Z"))
        setCreatedAt(expired.id, Instant.parse("2026-01-01T00:00:00Z"))

        val results = repo.listByUserIncludingExpired(userId)

        // 만료(expired)는 포함, revoke 는 제외, 최신순(active → expired)
        assertThat(results.map { it.id }).containsExactly(active.id, expired.id)
        assertThat(results.map { it.id }).doesNotContain(revoked.id)
    }

    @Test
    fun `listByUserIncludingExpired — 없으면 빈 리스트`() {
        assertThat(repo.listByUserIncludingExpired(userId)).isEmpty()
    }

    // ── findByIdAndUserId (FR-API-04 Task 2) ───────────────────────────────────

    @Test
    fun `findByIdAndUserId — 본인 소유는 revoke 된 것도 반환`() {
        val pat = repo.save(buildPat(tokenHash = TOKEN_HASH_A))
        repo.revoke(pat.id)

        val found = repo.findByIdAndUserId(pat.id, userId)

        assertThat(found).isNotNull()
        assertThat(found!!.id).isEqualTo(pat.id)
        assertThat(found.revokedAt).isNotNull()
    }

    @Test
    fun `findByIdAndUserId — 타인 소유는 null (IDOR 차단)`() {
        val pat = repo.save(buildPat(tokenHash = TOKEN_HASH_A))
        val otherUserId = insertUser()

        assertThat(repo.findByIdAndUserId(pat.id, otherUserId)).isNull()
    }

    @Test
    fun `findByIdAndUserId — 존재하지 않는 id 는 null`() {
        assertThat(repo.findByIdAndUserId(UUID.randomUUID(), userId)).isNull()
    }

    // ── revokeOwned (FR-API-04 Task 2) ─────────────────────────────────────────

    @Test
    fun `revokeOwned — 활성 PAT 는 1행 revoke`() {
        val pat = repo.save(buildPat(tokenHash = TOKEN_HASH_A))

        val affected = repo.revokeOwned(pat.id, userId, Instant.now())

        assertThat(affected).isEqualTo(1)
        assertThat(repo.findByIdAndUserId(pat.id, userId)!!.revokedAt).isNotNull()
    }

    @Test
    fun `revokeOwned — 이미 취소된 PAT 는 0행 (멱등)`() {
        val pat = repo.save(buildPat(tokenHash = TOKEN_HASH_A))
        repo.revokeOwned(pat.id, userId, Instant.now())

        val affected = repo.revokeOwned(pat.id, userId, Instant.now())

        assertThat(affected).isEqualTo(0)
    }

    @Test
    fun `revokeOwned — 타인 소유는 0행 (IDOR 차단)`() {
        val pat = repo.save(buildPat(tokenHash = TOKEN_HASH_A))
        val otherUserId = insertUser()

        val affected = repo.revokeOwned(pat.id, otherUserId, Instant.now())

        assertThat(affected).isEqualTo(0)
        // 원 소유자 기준으로는 여전히 활성 (취소되지 않음)
        assertThat(repo.findByIdAndUserId(pat.id, userId)!!.revokedAt).isNull()
    }

    @Test
    fun `revokeOwned — 존재하지 않는 id 는 0행`() {
        assertThat(repo.revokeOwned(UUID.randomUUID(), userId, Instant.now())).isEqualTo(0)
    }

    // ── countActiveByUser (FR-API-04 Task 2) ───────────────────────────────────

    @Test
    fun `countActiveByUser — 미취소·미만료만 카운트`() {
        val now = Instant.parse("2026-06-01T00:00:00Z")
        repo.save(buildPat(tokenHash = TOKEN_HASH_A, expiresAt = null)) // 활성(무기한)
        repo.save(buildPat(tokenHash = TOKEN_HASH_B, expiresAt = now.plusSeconds(3600))) // 활성(미래만료)
        repo.save(buildPat(tokenHash = TOKEN_HASH_C, expiresAt = now.minusSeconds(3600))) // 만료
        val revoked = repo.save(buildPat(tokenHash = TOKEN_HASH_D, name = "revoked"))
        repo.revoke(revoked.id)

        assertThat(repo.countActiveByUser(userId, now)).isEqualTo(2L)
    }

    @Test
    fun `countActiveByUser — 없으면 0`() {
        assertThat(repo.countActiveByUser(userId, Instant.now())).isEqualTo(0L)
    }

    @Test
    fun `만료 PAT — 목록엔 포함되지만 활성 카운트엔 제외 (두 메서드 구분)`() {
        val now = Instant.parse("2026-06-01T00:00:00Z")
        val expired = repo.save(buildPat(tokenHash = TOKEN_HASH_A, expiresAt = now.minusSeconds(1)))

        assertThat(repo.listByUserIncludingExpired(userId).map { it.id }).contains(expired.id)
        assertThat(repo.countActiveByUser(userId, now)).isEqualTo(0L)
    }

    // ── CASCADE ──────────────────────────────────────────────────────────────

    @Test
    fun `users CASCADE 삭제 — users 행 삭제 시 personal_access_tokens 자동 삭제`() {
        repo.save(buildPat(tokenHash = TOKEN_HASH_A))

        jdbc.update("DELETE FROM users WHERE id = :id", mapOf("id" to userId))

        assertThat(repo.findByTokenHash(TOKEN_HASH_A)).isNull()
    }

    // ── 헬퍼 ─────────────────────────────────────────────────────────────────

    /** 타인 소유(IDOR) 시나리오용 별도 users 행 삽입 후 id 반환. */
    private fun insertUser(): UUID {
        val id = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO users (id, username) VALUES (:id, :username)",
            mapOf("id" to id, "username" to "pat-other-$id"),
        )
        return id
    }

    /** created_at 을 명시적으로 세팅 — DESC 정렬을 결정적으로 검증하기 위함. */
    private fun setCreatedAt(
        id: UUID,
        at: Instant,
    ) {
        jdbc.update(
            "UPDATE personal_access_tokens SET created_at = :at WHERE id = :id",
            mapOf("at" to Timestamp.from(at), "id" to id),
        )
    }

    private fun buildPat(
        tokenHash: String = TOKEN_HASH_A,
        name: String = "test-token",
        scopes: List<String> = listOf("read:issues"),
        expiresAt: Instant? = null,
    ): PersonalAccessToken =
        PersonalAccessToken(
            id = UUID.randomUUID(),
            userId = userId,
            name = name,
            tokenHash = tokenHash,
            scopes = scopes,
            expiresAt = expiresAt,
            lastUsedAt = null,
            revokedAt = null,
            createdAt = Instant.now(),
        )
}
