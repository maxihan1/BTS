// JdbcTrustedDeviceRepository 통합 테스트 — Testcontainers PostgreSQL 16 + Flyway(V026) 실 repo 검증 (FR-MF-05 task-3)

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
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * [JdbcTrustedDeviceRepository] 통합 테스트 (FR-MF-05 Task 3).
 *
 * `@JdbcTest` + Testcontainers PostgreSQL 16 + Flyway(V026 `trusted_devices`)를 적용해
 * 실 repo + 실 DB 로 영속 연산을 end-to-end 검증한다(mock 미사용).
 *
 * - **insert** — 신뢰 디바이스 1건 저장 후 findByTokenHash 로 재조회(전 필드 일치).
 * - **findByTokenHash** — token_hash 단건 조회(없으면 null). 만료 여부와 무관(우회 판정은 호출측).
 * - **updateLastUsedAt** — last_used_at 갱신(우회 로그인 성공 시각 기록).
 * - **listByUser** — 사용자별 미만료(`expires_at > :now`) 행만 반환, 만료 행 제외, 타인 행 제외.
 * - **deleteByIdAndUser** — 소유 검증 삭제(자기 id true, 타인 id false → IDOR 차단).
 * - **deleteAllByUser** — 사용자 전체 폐기, 반환값 = 삭제된 행 수(count).
 *
 * users FK 충족을 위해 [setUp] 에서 users 행을 선 INSERT 한다(join-table-fk-cascade 교훈).
 *
 * **보안: token_hash(비밀값 해시)는 테스트 단언에만 쓰고 로그에 기록하지 않는다.**
 */
@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JdbcTrustedDeviceRepository::class)
class JdbcTrustedDeviceRepositoryTest {
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
    private lateinit var repo: TrustedDeviceRepository

    @Autowired
    @Suppress("VarCouldBeVal")
    private lateinit var jdbc: NamedParameterJdbcTemplate

    private lateinit var userId: UUID
    private lateinit var now: Instant

    @BeforeEach
    fun setUp() {
        jdbc.update("DELETE FROM trusted_devices", emptyMap<String, Any>())
        jdbc.update("DELETE FROM users", emptyMap<String, Any>())

        now = Instant.now().truncatedTo(ChronoUnit.MILLIS)

        // users 픽스처 — trusted_devices.user_id FK 충족
        userId = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO users (id, username) VALUES (:id, :username)",
            mapOf("id" to userId, "username" to "test-user-$userId"),
        )
    }

    private fun newDevice(
        owner: UUID = userId,
        label: String? = "회사 노트북 (Chrome / macOS)",
        expiresAt: Instant = now.plus(30, ChronoUnit.DAYS),
        lastUsedAt: Instant? = null,
    ): TrustedDevice =
        TrustedDevice(
            id = UUID.randomUUID(),
            userId = owner,
            tokenHash = randomHash(),
            label = label,
            createdAt = now,
            expiresAt = expiresAt,
            lastUsedAt = lastUsedAt,
        )

    /** SHA-256 hex 64자 형식을 만족하는 임의 해시(실제 rawToken 무관 — 도메인 init 검증 통과용). */
    private fun randomHash(): String = TrustedDeviceToken.generate().hash

    private fun insertUser(username: String): UUID {
        val id = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO users (id, username) VALUES (:id, :username)",
            mapOf("id" to id, "username" to username),
        )
        return id
    }

    // ── insert / findByTokenHash ────────────────────────────────────────────────────

    @Test
    fun `insert한 신뢰 디바이스를 findByTokenHash로 재조회한다`() {
        val device = newDevice()
        repo.insert(device)

        val found = repo.findByTokenHash(device.tokenHash)
        assertThat(found).isNotNull
        assertThat(found!!.id).isEqualTo(device.id)
        assertThat(found.userId).isEqualTo(userId)
        assertThat(found.tokenHash).isEqualTo(device.tokenHash)
        assertThat(found.label).isEqualTo("회사 노트북 (Chrome / macOS)")
        assertThat(found.createdAt).isEqualTo(device.createdAt)
        assertThat(found.expiresAt).isEqualTo(device.expiresAt)
        assertThat(found.lastUsedAt).isNull()
    }

    @Test
    fun `findByTokenHash는 없는 token_hash면 null을 반환한다`() {
        assertThat(repo.findByTokenHash(randomHash())).isNull()
    }

    @Test
    fun `insert는 label이 null이어도 저장한다`() {
        val device = newDevice(label = null)
        repo.insert(device)

        val found = repo.findByTokenHash(device.tokenHash)
        assertThat(found).isNotNull
        assertThat(found!!.label).isNull()
    }

    @Test
    fun `findByTokenHash는 만료된 행도 그대로 반환한다 (만료 판정은 호출측)`() {
        // findByTokenHash 자체는 expires_at 필터를 두지 않는다(만료 판정은 도메인 isExpired/호출측 책임).
        val expired = newDevice(expiresAt = now.minus(1, ChronoUnit.DAYS))
        repo.insert(expired)

        assertThat(repo.findByTokenHash(expired.tokenHash)).isNotNull
    }

    // ── updateLastUsedAt ──────────────────────────────────────────────────────────────

    @Test
    fun `updateLastUsedAt은 last_used_at을 지정 시각으로 갱신하고 영향 행 수 1을 반환한다`() {
        val device = newDevice()
        repo.insert(device)
        assertThat(repo.findByTokenHash(device.tokenHash)!!.lastUsedAt).isNull()

        val usedAt = now.plus(1, ChronoUnit.HOURS)
        // 존재하는 행을 갱신하면 영향 행 수 1 — 호출 측(verifyAndTouch)이 TOCTOU race 닫기에 쓴다.
        assertThat(repo.updateLastUsedAt(device.id, usedAt)).isEqualTo(1)

        assertThat(repo.findByTokenHash(device.tokenHash)!!.lastUsedAt).isEqualTo(usedAt)
    }

    @Test
    fun `updateLastUsedAt은 행이 없으면(그 사이 삭제됨) 0을 반환한다 (TOCTOU 우회 차단)`() {
        // findByTokenHash 읽기와 updateLastUsedAt 쓰기 사이에 revoke/revokeAll(DELETE)이 커밋되면
        // 대상 id 의 행이 사라진다. 그 경우 0행 → 호출 측이 우회를 거부할 수 있어야 한다.
        assertThat(repo.updateLastUsedAt(UUID.randomUUID(), now)).isEqualTo(0)
    }

    // ── listByUser (미만료만, 소유 한정) ─────────────────────────────────────────────

    @Test
    fun `listByUser는 사용자의 미만료 신뢰 디바이스를 모두 반환한다`() {
        repo.insert(newDevice())
        repo.insert(newDevice())
        repo.insert(newDevice())

        val list = repo.listByUser(userId, now)
        assertThat(list).hasSize(3)
        assertThat(list.map { it.userId }).allMatch { it == userId }
    }

    @Test
    fun `listByUser는 만료된 행을 제외한다 (expires_at은 now보다 커야 한다)`() {
        val live = newDevice(expiresAt = now.plus(1, ChronoUnit.DAYS))
        val expiredPast = newDevice(expiresAt = now.minus(1, ChronoUnit.SECONDS))
        // 만료 정각(expires_at == now)도 만료로 본다(EC10 경계 — `> now` 만 유효).
        val expiredExact = newDevice(expiresAt = now)
        repo.insert(live)
        repo.insert(expiredPast)
        repo.insert(expiredExact)

        val list = repo.listByUser(userId, now)
        assertThat(list.map { it.id }).containsExactly(live.id)
    }

    @Test
    fun `listByUser는 다른 사용자의 신뢰 디바이스를 포함하지 않는다`() {
        val other = insertUser("other-${UUID.randomUUID()}")
        val mine = newDevice()
        repo.insert(mine)
        repo.insert(newDevice(owner = other))

        val list = repo.listByUser(userId, now)
        assertThat(list.map { it.id }).containsExactly(mine.id)
    }

    @Test
    fun `listByUser는 신뢰 디바이스가 없으면 빈 목록을 반환한다`() {
        assertThat(repo.listByUser(userId, now)).isEmpty()
    }

    // ── deleteByIdAndUser (소유 검증 / IDOR 차단) ────────────────────────────────────

    @Test
    fun `deleteByIdAndUser는 소유자가 자기 디바이스를 삭제하면 true를 반환한다`() {
        val device = newDevice()
        repo.insert(device)

        assertThat(repo.deleteByIdAndUser(userId, device.id)).isTrue()
        assertThat(repo.findByTokenHash(device.tokenHash)).isNull()
    }

    @Test
    fun `deleteByIdAndUser는 다른 사용자의 디바이스는 삭제하지 못하고 false를 반환한다`() {
        val other = insertUser("other-${UUID.randomUUID()}")
        val theirs = newDevice(owner = other)
        repo.insert(theirs)

        // userId 가 other 의 디바이스 id 로 삭제 시도 → 소유 불일치로 0행 → false (IDOR 차단)
        assertThat(repo.deleteByIdAndUser(userId, theirs.id)).isFalse()
        // 삭제되지 않고 그대로 남아 있다
        assertThat(repo.findByTokenHash(theirs.tokenHash)).isNotNull
    }

    @Test
    fun `deleteByIdAndUser는 존재하지 않는 id면 false를 반환한다`() {
        assertThat(repo.deleteByIdAndUser(userId, UUID.randomUUID())).isFalse()
    }

    // ── deleteAllByUser (전체 폐기 / count 반환) ─────────────────────────────────────

    @Test
    fun `deleteAllByUser는 사용자의 모든 신뢰 디바이스를 삭제하고 삭제된 행 수를 반환한다`() {
        repo.insert(newDevice())
        repo.insert(newDevice())
        // 만료 행도 폐기 대상(전량 revoke — 보안 이벤트 자동 폐기 일관)
        repo.insert(newDevice(expiresAt = now.minus(1, ChronoUnit.DAYS)))

        assertThat(repo.deleteAllByUser(userId)).isEqualTo(3)
        assertThat(repo.listByUser(userId, now)).isEmpty()
    }

    @Test
    fun `deleteAllByUser는 다른 사용자의 디바이스는 삭제하지 않는다`() {
        val other = insertUser("other-${UUID.randomUUID()}")
        repo.insert(newDevice())
        val theirs = newDevice(owner = other)
        repo.insert(theirs)

        assertThat(repo.deleteAllByUser(userId)).isEqualTo(1)
        // 타인 디바이스는 보존
        assertThat(repo.findByTokenHash(theirs.tokenHash)).isNotNull
    }

    @Test
    fun `deleteAllByUser는 폐기할 디바이스가 없으면 0을 반환한다`() {
        assertThat(repo.deleteAllByUser(userId)).isEqualTo(0)
    }
}
