// RefreshTokenRepository 통합 테스트 — Testcontainers PostgreSQL + Flyway V001~V005 적용

package com.atlas.bts.identity.session

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
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * JdbcRefreshTokenRepository 통합 테스트 (FR-AU-09 Task 9).
 *
 * @JdbcTest + Testcontainers PostgreSQL + Flyway V001~V005 자동 적용.
 * 검증 대상: save / findByTokenHash / markUsedAndChain / revokeChainFromSession.
 *
 * ## EC-23 race 안전성
 * [markUsedAndChain] 은 `UPDATE ... WHERE used_at IS NULL RETURNING id` 로 optimistic locking 을 구현한다.
 * 동시 요청 중 정확히 1개만 성공(non-null 반환)하고 나머지는 null 을 반환해야 한다.
 *
 * ## 보안 가드
 * - token_hash 는 로그에 절대 기록하지 않는다. (DEVELOPMENT.md §1.1 규칙 2)
 * - raw token 은 발급 응답에만 한 번 포함된다.
 */
@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JdbcRefreshTokenRepository::class)
class RefreshTokenRepositoryTest {
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
    private lateinit var repo: RefreshTokenRepository

    @Autowired
    private lateinit var jdbc: NamedParameterJdbcTemplate

    /** 모든 테스트에서 공유하는 사용자 + 세션 픽스처 */
    private lateinit var userId: UUID
    private lateinit var sessionId: UUID

    @BeforeEach
    fun setUp() {
        // FK 의존 순서대로 삭제
        jdbc.update("DELETE FROM refresh_tokens", emptyMap<String, Any>())
        jdbc.update("DELETE FROM sessions", emptyMap<String, Any>())
        jdbc.update("DELETE FROM users", emptyMap<String, Any>())

        userId = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO users (id, username) VALUES (:id, :username)",
            mapOf("id" to userId, "username" to "test-user-$userId"),
        )

        sessionId = UUID.randomUUID()
        jdbc.update(
            """
            INSERT INTO sessions (id, user_id, provider_id, created_at, expires_at, last_seen_at)
            VALUES (:id, :userId, 'local', NOW(), NOW() + INTERVAL '14 days', NOW())
            """,
            mapOf("id" to sessionId, "userId" to userId),
        )
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun buildToken(
        id: UUID = UUID.randomUUID(),
        sessionId: UUID = this.sessionId,
        tokenHash: String = randomHash(),
        expiresAt: Instant = Instant.now().plus(14, ChronoUnit.DAYS),
    ): RefreshToken =
        RefreshToken(
            id = id,
            sessionId = sessionId,
            tokenHash = tokenHash,
            issuedAt = Instant.now(),
            expiresAt = expiresAt,
            usedAt = null,
            replacedBy = null,
        )

    /** 64자 소문자 hex 임의 생성. 실제 raw token 노출 없이 테스트 픽스처만 사용한다. */
    private fun randomHash(): String =
        UUID.randomUUID().toString().replace("-", "") +
            UUID.randomUUID().toString().replace("-", "")

    // ── save ──────────────────────────────────────────────────────────────────

    @Test
    fun `save — INSERT 후 findByTokenHash 로 동일 토큰 조회 성공`() {
        val token = buildToken()

        repo.save(token)

        val found = repo.findByTokenHash(token.tokenHash)
        assertThat(found).isNotNull()
        assertThat(found!!.id).isEqualTo(token.id)
        assertThat(found.sessionId).isEqualTo(token.sessionId)
        assertThat(found.tokenHash).isEqualTo(token.tokenHash)
        assertThat(found.usedAt).isNull()
        assertThat(found.replacedBy).isNull()
    }

    @Test
    fun `save — issuedAt expiresAt 이 DB에 정확히 저장된다`() {
        val issuedAt = Instant.now().truncatedTo(ChronoUnit.MILLIS)
        val expiresAt = issuedAt.plus(14, ChronoUnit.DAYS)
        val token = buildToken(expiresAt = expiresAt)

        repo.save(token)

        val found = repo.findByTokenHash(token.tokenHash)!!
        assertThat(found.expiresAt.truncatedTo(ChronoUnit.MILLIS)).isEqualTo(expiresAt)
    }

    // ── findByTokenHash ───────────────────────────────────────────────────────

    @Test
    fun `findByTokenHash — 없는 hash 조회 시 null 반환`() {
        val result = repo.findByTokenHash(randomHash())

        assertThat(result).isNull()
    }

    @Test
    fun `findByTokenHash — 사용 완료된 토큰도 조회 가능 (reuse detection 용)`() {
        val token = buildToken()
        repo.save(token)

        // 직접 used_at 을 설정해 사용 완료 상태로 만든다
        jdbc.update(
            "UPDATE refresh_tokens SET used_at = NOW() WHERE id = :id",
            mapOf("id" to token.id),
        )

        val found = repo.findByTokenHash(token.tokenHash)
        assertThat(found).isNotNull()
        assertThat(found!!.usedAt).isNotNull()
    }

    // ── markUsedAndChain ──────────────────────────────────────────────────────

    @Test
    fun `markUsedAndChain — 미사용 토큰에 used_at 설정 후 신 토큰 ID replaced_by 연결`() {
        val oldToken = buildToken()
        val newToken = buildToken()
        repo.save(oldToken)
        repo.save(newToken)

        val result = repo.markUsedAndChain(oldToken.id, newToken.id)

        assertThat(result).isEqualTo(oldToken.id)

        val updated = repo.findByTokenHash(oldToken.tokenHash)!!
        assertThat(updated.usedAt).isNotNull()
        assertThat(updated.replacedBy).isEqualTo(newToken.id)
    }

    @Test
    fun `markUsedAndChain — 이미 사용된 토큰에 재요청하면 null 반환 (race loser)`() {
        val oldToken = buildToken()
        val newToken = buildToken()
        val anotherToken = buildToken()
        repo.save(oldToken)
        repo.save(newToken)
        repo.save(anotherToken)

        // 첫 번째 요청으로 토큰 사용 완료
        repo.markUsedAndChain(oldToken.id, newToken.id)

        // 두 번째 요청 — race loser, used_at IS NULL 조건 불충족
        val result = repo.markUsedAndChain(oldToken.id, anotherToken.id)

        assertThat(result).isNull()
    }

    @Test
    fun `markUsedAndChain — 존재하지 않는 oldId 는 null 반환`() {
        val newToken = buildToken()
        repo.save(newToken)

        val result = repo.markUsedAndChain(UUID.randomUUID(), newToken.id)

        assertThat(result).isNull()
    }

    /**
     * EC-23 동시성 테스트.
     *
     * `@Transactional(NOT_SUPPORTED)` — @JdbcTest 기본 테스트 트랜잭션을 비활성화한다.
     * 이렇게 해야 save() 결과가 즉시 커밋되어 별개 스레드의 트랜잭션에서 행이 보인다.
     * 테스트 후 데이터는 @BeforeEach 의 DELETE 로 정리된다.
     */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `markUsedAndChain — 두 트랜잭션이 동시에 요청하면 정확히 1개만 성공한다 (EC-23 race-safe)`() {
        val oldToken = buildToken()
        repo.save(oldToken)

        val successCount = AtomicInteger(0)
        val latch = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)

        val futures =
            (1..2).map {
                executor.submit {
                    latch.await()
                    val newId = UUID.randomUUID()
                    // FK refresh_tokens.replaced_by → refresh_tokens(id) 충족.
                    // prod RefreshTokenService.rotate() 도 markUsedAndChain 전에 새 RT 를 먼저 INSERT (FK 제약).
                    repo.save(buildToken(id = newId))
                    val r = repo.markUsedAndChain(oldToken.id, newId)
                    if (r != null) successCount.incrementAndGet()
                }
            }

        latch.countDown()
        futures.forEach { it.get() }
        executor.shutdown()

        assertThat(successCount.get()).isEqualTo(1)
    }

    // ── revokeChainFromSession ────────────────────────────────────────────────

    @Test
    fun `revokeChainFromSession — session 에 속한 모든 미사용 토큰을 used_at 으로 폐기`() {
        val token1 = buildToken()
        val token2 = buildToken()
        repo.save(token1)
        repo.save(token2)

        val revokedCount = repo.revokeChainFromSession(sessionId)

        assertThat(revokedCount).isEqualTo(2)

        val t1 = repo.findByTokenHash(token1.tokenHash)!!
        val t2 = repo.findByTokenHash(token2.tokenHash)!!
        assertThat(t1.usedAt).isNotNull()
        assertThat(t2.usedAt).isNotNull()
    }

    @Test
    fun `revokeChainFromSession — 이미 used_at 이 설정된 토큰은 카운트에 포함하지 않는다`() {
        val usedToken = buildToken()
        val unusedToken = buildToken()
        repo.save(usedToken)
        repo.save(unusedToken)

        // usedToken 을 먼저 사용 완료 처리
        jdbc.update(
            "UPDATE refresh_tokens SET used_at = NOW() WHERE id = :id",
            mapOf("id" to usedToken.id),
        )

        val revokedCount = repo.revokeChainFromSession(sessionId)

        // 미사용 토큰 1개만 폐기
        assertThat(revokedCount).isEqualTo(1)
    }

    @Test
    fun `revokeChainFromSession — 다른 session 의 토큰에 영향을 주지 않는다`() {
        val anotherSessionId = UUID.randomUUID()
        jdbc.update(
            """
            INSERT INTO sessions (id, user_id, provider_id, created_at, expires_at, last_seen_at)
            VALUES (:id, :userId, 'local', NOW(), NOW() + INTERVAL '14 days', NOW())
            """,
            mapOf("id" to anotherSessionId, "userId" to userId),
        )

        val myToken = buildToken(sessionId = sessionId)
        val otherToken = buildToken(sessionId = anotherSessionId)
        repo.save(myToken)
        repo.save(otherToken)

        repo.revokeChainFromSession(sessionId)

        // 다른 session 의 토큰은 폐기되지 않아야 한다
        val otherFound = repo.findByTokenHash(otherToken.tokenHash)!!
        assertThat(otherFound.usedAt).isNull()
    }

    @Test
    fun `revokeChainFromSession — 토큰이 없으면 0 반환`() {
        val revokedCount = repo.revokeChainFromSession(sessionId)

        assertThat(revokedCount).isEqualTo(0)
    }
}
