// JdbcWebAuthnCredentialRepository 통합 테스트 — Testcontainers PostgreSQL 16 + Flyway(V025) 실 repo 검증 (FR-MF-03 task-3)

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
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * [JdbcWebAuthnCredentialRepository] 통합 테스트 (FR-MF-03 Task 3).
 *
 * `@JdbcTest` + Testcontainers PostgreSQL 16 + Flyway(V025 `webauthn_credentials`)를 적용해
 * 실 repo + 실 DB 로 영속 연산을 end-to-end 검증한다(mock 미사용).
 *
 * - **insert** — 자격증명 1건 저장 후 findByUser/findByCredentialId 로 재조회.
 * - **findByUser** — 사용자당 N건 목록 조회(타 사용자 자격증명 제외).
 * - **findByCredentialId** — 전역 UNIQUE credential_id 단건 조회(없으면 null).
 * - **deleteByIdAndUser** — 소유 검증 삭제(자기 id true, 타인 id false).
 * - **advanceSignCount** — clone 방어 조건부 UPDATE(큰 값만 성공, 이하 false, signCount=0 기기 0→0 허용).
 * - **touchLastUsed** — last_used_at 갱신(assertion 성공 시각 기록).
 *
 * users FK 충족을 위해 [setUp] 에서 users 행을 선 INSERT 한다(join-table-fk-cascade 교훈).
 */
@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JdbcWebAuthnCredentialRepository::class)
@Testcontainers
class JdbcWebAuthnCredentialRepositoryTest {
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
    @Suppress("VarCouldBeVal")
    private lateinit var repo: WebAuthnCredentialRepository

    @Autowired
    @Suppress("VarCouldBeVal")
    private lateinit var jdbc: NamedParameterJdbcTemplate

    private lateinit var userId: UUID

    @BeforeEach
    fun setUp() {
        jdbc.update("DELETE FROM webauthn_credentials", emptyMap<String, Any>())
        jdbc.update("DELETE FROM users", emptyMap<String, Any>())

        // users 픽스처 — webauthn_credentials.user_id FK 충족
        userId = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO users (id, username) VALUES (:id, :username)",
            mapOf("id" to userId, "username" to "test-user-$userId"),
        )
    }

    private fun newCredential(
        owner: UUID = userId,
        credentialId: String = "cred-${UUID.randomUUID()}",
        signCount: Long = 0,
        name: String? = "회사 노트북 Touch ID",
        aaguid: String? = "aaguid-1234",
    ): WebAuthnCredential {
        val now = Instant.now().truncatedTo(ChronoUnit.MILLIS)
        return WebAuthnCredential(
            id = UUID.randomUUID(),
            userId = owner,
            credentialId = credentialId,
            attestedCredentialData = "base64-attested-data",
            signCount = signCount,
            name = name,
            aaguid = aaguid,
            lastUsedAt = null,
            createdAt = now,
            updatedAt = now,
        )
    }

    private fun insertUser(username: String): UUID {
        val id = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO users (id, username) VALUES (:id, :username)",
            mapOf("id" to id, "username" to username),
        )
        return id
    }

    // ── insert / findByCredentialId ─────────────────────────────────────────────────

    @Test
    fun `insert한 자격증명을 findByCredentialId로 재조회한다`() {
        val cred = newCredential(credentialId = "cred-find-1")
        repo.insert(cred)

        val found = repo.findByCredentialId("cred-find-1")
        assertThat(found).isNotNull
        assertThat(found!!.id).isEqualTo(cred.id)
        assertThat(found.userId).isEqualTo(userId)
        assertThat(found.credentialId).isEqualTo("cred-find-1")
        assertThat(found.attestedCredentialData).isEqualTo("base64-attested-data")
        assertThat(found.signCount).isEqualTo(0L)
        assertThat(found.name).isEqualTo("회사 노트북 Touch ID")
        assertThat(found.aaguid).isEqualTo("aaguid-1234")
        assertThat(found.lastUsedAt).isNull()
    }

    @Test
    fun `findByCredentialId는 없는 credentialId면 null을 반환한다`() {
        assertThat(repo.findByCredentialId("ghost-credential")).isNull()
    }

    @Test
    fun `insert는 name과 aaguid가 null이어도 저장한다`() {
        val cred = newCredential(credentialId = "cred-null-meta", name = null, aaguid = null)
        repo.insert(cred)

        val found = repo.findByCredentialId("cred-null-meta")
        assertThat(found).isNotNull
        assertThat(found!!.name).isNull()
        assertThat(found.aaguid).isNull()
    }

    // ── findByUser ──────────────────────────────────────────────────────────────────

    @Test
    fun `findByUser는 사용자당 N건을 모두 반환한다`() {
        repo.insert(newCredential(credentialId = "u-1"))
        repo.insert(newCredential(credentialId = "u-2"))
        repo.insert(newCredential(credentialId = "u-3"))

        val list = repo.findByUser(userId)
        assertThat(list).hasSize(3)
        assertThat(list.map { it.credentialId }).containsExactlyInAnyOrder("u-1", "u-2", "u-3")
    }

    @Test
    fun `findByUser는 다른 사용자의 자격증명을 포함하지 않는다`() {
        val other = insertUser("other-${UUID.randomUUID()}")
        repo.insert(newCredential(credentialId = "mine-1"))
        repo.insert(newCredential(owner = other, credentialId = "theirs-1"))

        val mine = repo.findByUser(userId)
        assertThat(mine.map { it.credentialId }).containsExactly("mine-1")
    }

    @Test
    fun `findByUser는 자격증명이 없으면 빈 목록을 반환한다`() {
        assertThat(repo.findByUser(userId)).isEmpty()
    }

    // ── deleteByIdAndUser (소유 검증) ───────────────────────────────────────────────

    @Test
    fun `deleteByIdAndUser는 소유자가 자기 자격증명을 삭제하면 true를 반환한다`() {
        val cred = newCredential(credentialId = "to-delete")
        repo.insert(cred)

        assertThat(repo.deleteByIdAndUser(cred.id, userId)).isTrue()
        assertThat(repo.findByCredentialId("to-delete")).isNull()
    }

    @Test
    fun `deleteByIdAndUser는 다른 사용자의 자격증명은 삭제하지 못하고 false를 반환한다`() {
        val other = insertUser("other-${UUID.randomUUID()}")
        val cred = newCredential(owner = other, credentialId = "not-mine")
        repo.insert(cred)

        // userId 가 other 의 자격증명 id 로 삭제 시도 → 소유 불일치로 0행 → false
        assertThat(repo.deleteByIdAndUser(cred.id, userId)).isFalse()
        // 삭제되지 않고 그대로 남아 있다
        assertThat(repo.findByCredentialId("not-mine")).isNotNull
    }

    @Test
    fun `deleteByIdAndUser는 존재하지 않는 id면 false를 반환한다`() {
        assertThat(repo.deleteByIdAndUser(UUID.randomUUID(), userId)).isFalse()
    }

    // ── advanceSignCount (clone 방어 조건부 UPDATE) ──────────────────────────────────

    @Test
    fun `advanceSignCount는 더 큰 값으로 전진하면 true를 반환하고 카운트를 갱신한다`() {
        val cred = newCredential(credentialId = "sc-advance", signCount = 5)
        repo.insert(cred)

        assertThat(repo.advanceSignCount(cred.id, 6)).isTrue()
        assertThat(repo.findByCredentialId("sc-advance")!!.signCount).isEqualTo(6L)
    }

    @Test
    fun `advanceSignCount는 같거나 작은 값이면 false를 반환하고 카운트를 유지한다 (clone 방어)`() {
        val cred = newCredential(credentialId = "sc-replay", signCount = 10)
        repo.insert(cred)

        // 같은 값 → clone/replay 의심 → 거부
        assertThat(repo.advanceSignCount(cred.id, 10)).isFalse()
        // 더 작은 값 → 역행 → 거부
        assertThat(repo.advanceSignCount(cred.id, 9)).isFalse()
        assertThat(repo.findByCredentialId("sc-replay")!!.signCount).isEqualTo(10L)
    }

    @Test
    fun `advanceSignCount는 signCount가 0인 정상 기기의 0 to 0 갱신을 허용한다`() {
        // sign_count 를 항상 0 으로 보내는 정상 인증기(예: 일부 플랫폼 키)는 0→0 을 허용해야 한다.
        val cred = newCredential(credentialId = "sc-zero", signCount = 0)
        repo.insert(cred)

        assertThat(repo.advanceSignCount(cred.id, 0)).isTrue()
        assertThat(repo.findByCredentialId("sc-zero")!!.signCount).isEqualTo(0L)
    }

    @Test
    fun `advanceSignCount는 존재하지 않는 id면 false를 반환한다`() {
        assertThat(repo.advanceSignCount(UUID.randomUUID(), 1)).isFalse()
    }

    // ── touchLastUsed ───────────────────────────────────────────────────────────────

    @Test
    fun `touchLastUsed는 last_used_at을 지정 시각으로 갱신한다`() {
        val cred = newCredential(credentialId = "touch-1")
        repo.insert(cred)
        assertThat(repo.findByCredentialId("touch-1")!!.lastUsedAt).isNull()

        val at = Instant.now().truncatedTo(ChronoUnit.MILLIS)
        repo.touchLastUsed(cred.id, at)

        assertThat(repo.findByCredentialId("touch-1")!!.lastUsedAt).isEqualTo(at)
    }
}
