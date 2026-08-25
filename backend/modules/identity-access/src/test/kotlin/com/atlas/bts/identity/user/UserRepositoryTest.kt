// UserRepository 통합 테스트 — Testcontainers PostgreSQL + Flyway V001 적용

package com.atlas.bts.identity.user

import com.atlas.bts.identity.support.SharedPostgres
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
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
 * JdbcUserRepository 통합 테스트 (Task 32 — FR-AU-09).
 *
 * @JdbcTest + Testcontainers PostgreSQL + Flyway V001 자동 적용.
 * 검증 대상: findById / findByUsername / save (UPSERT) / updateLastLogin.
 */
@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JdbcUserRepository::class)
class UserRepositoryTest {
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
    private lateinit var repo: UserRepository

    @Autowired
    private lateinit var jdbc: NamedParameterJdbcTemplate

    @BeforeEach
    fun setUp() {
        // 의존 테이블 순서대로 삭제 (FK 제약 존중)
        jdbc.update("DELETE FROM user_external_accounts", emptyMap<String, Any>())
        jdbc.update("DELETE FROM local_credentials", emptyMap<String, Any>())
        jdbc.update("DELETE FROM users", emptyMap<String, Any>())
    }

    // ── findById ─────────────────────────────────────────────────────────────

    @Test
    fun `findById — 존재하는 id 조회 시 User 반환`() {
        val saved = repo.save("alice", "alice@bts.local", "Alice Kim")

        val found = repo.findById(saved.id)

        assertThat(found).isNotNull()
        assertThat(found!!.id).isEqualTo(saved.id)
        assertThat(found.username).isEqualTo("alice")
    }

    @Test
    fun `findById — 없는 id 조회 시 null 반환`() {
        val result = repo.findById(UUID.randomUUID())

        assertThat(result).isNull()
    }

    // ── findByUsername ────────────────────────────────────────────────────────

    @Test
    fun `findByUsername — 존재하는 username 조회 시 User 반환`() {
        repo.save("bob", "bob@bts.local", "Bob Lee")

        val found = repo.findByUsername("bob")

        assertThat(found).isNotNull()
        assertThat(found!!.username).isEqualTo("bob")
        assertThat(found.email).isEqualTo("bob@bts.local")
        assertThat(found.displayName).isEqualTo("Bob Lee")
    }

    @Test
    fun `findByUsername — 없는 username 조회 시 null 반환`() {
        val result = repo.findByUsername("nonexistent")

        assertThat(result).isNull()
    }

    // ── save (UPSERT) ─────────────────────────────────────────────────────────

    @Test
    fun `save — 신규 INSERT 시 id + createdAt + updatedAt 설정`() {
        val user = repo.save("charlie", "charlie@bts.local", "Charlie Park")

        assertThat(user.id).isNotNull()
        assertThat(user.username).isEqualTo("charlie")
        assertThat(user.email).isEqualTo("charlie@bts.local")
        assertThat(user.displayName).isEqualTo("Charlie Park")
        assertThat(user.createdAt).isNotNull()
        assertThat(user.updatedAt).isNotNull()
        assertThat(user.updatedAt).isAfterOrEqualTo(user.createdAt)
    }

    @Test
    fun `save — email null 허용`() {
        val user = repo.save("diana", null, "Diana Choi")

        assertThat(user.email).isNull()
        assertThat(user.username).isEqualTo("diana")
    }

    @Test
    fun `save UPSERT — 같은 username 두 번 호출 시 email + displayName 갱신, id 보존`() {
        val first = repo.save("eve", "eve@bts.local", "Eve Old")
        val second = repo.save("eve", "eve-new@bts.local", "Eve New")

        assertThat(second.id).isEqualTo(first.id)
        assertThat(second.email).isEqualTo("eve-new@bts.local")
        assertThat(second.displayName).isEqualTo("Eve New")
    }

    @Test
    fun `save — 다른 username 은 별개 행으로 삽입`() {
        repo.save("frank", null, "Frank A")
        repo.save("grace", null, "Grace B")

        val count =
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM users",
                emptyMap<String, Any>(),
                Int::class.java,
            )
        assertThat(count).isEqualTo(2)
    }

    // ── create (INSERT 전용) ──────────────────────────────────────────────────

    @Test
    fun `create — 신규 행 INSERT 후 User 반환`() {
        val user = repo.create("kate", "kate@bts.local", "Kate Lim")

        assertThat(user.id).isNotNull()
        assertThat(user.username).isEqualTo("kate")
        assertThat(user.email).isEqualTo("kate@bts.local")
        assertThat(user.displayName).isEqualTo("Kate Lim")

        val found = repo.findByUsername("kate")
        assertThat(found).isNotNull()
        assertThat(found!!.id).isEqualTo(user.id)
    }

    @Test
    fun `create — email null 허용`() {
        val user = repo.create("leo", null, "Leo Han")

        assertThat(user.email).isNull()
        assertThat(user.username).isEqualTo("leo")
    }

    @Test
    fun `create — 중복 username 은 예외 (기존 행 덮어쓰기 금지, save UPSERT 와 구분)`() {
        repo.create("mia", "mia@bts.local", "Mia Old")

        // 같은 username 으로 다시 create 하면 ON CONFLICT 없는 INSERT 라 unique 위반.
        // (ON CONFLICT DO UPDATE 가 없으므로 위반 시 기존 행은 절대 변경되지 않는다 — save UPSERT 와 구분.)
        // 위반 발생 후 같은 트랜잭션은 abort 되므로(25P02) 행 보존 검증은 별도 테스트로 분리한다.
        assertThatThrownBy {
            repo.create("mia", "mia-new@bts.local", "Mia New")
        }.isInstanceOf(org.springframework.dao.DuplicateKeyException::class.java)
    }

    // ── provisionFromExternal ─────────────────────────────────────────────────

    @Test
    fun `provisionFromExternal — 신규 사용자 UPSERT 후 User 반환`() {
        val result =
            repo.provisionFromExternal(
                username = "henry",
                email = "henry@ldap.bts.local",
                displayName = "Henry LDAP",
            )

        assertThat(result.user.username).isEqualTo("henry")
        assertThat(result.user.email).isEqualTo("henry@ldap.bts.local")
        assertThat(result.user.id).isNotNull()
    }

    @Test
    fun `provisionFromExternal — 동일 username 재호출 시 같은 id 반환 (멱등)`() {
        val first = repo.provisionFromExternal("ivy", "ivy@ldap.bts.local", "Ivy")
        val second = repo.provisionFromExternal("ivy", "ivy-updated@ldap.bts.local", "Ivy Updated")

        assertThat(second.user.id).isEqualTo(first.user.id)
        assertThat(second.user.displayName).isEqualTo("Ivy Updated")
    }

    // ── provisionFromExternal isNew 플래그 (FR-AU-10 — USER_PROVISIONED 신규 판정) ──

    @Test
    fun `provisionFromExternal — 신규 INSERT 시 isNew=true (xmax=0)`() {
        val result = repo.provisionFromExternal("noah", "noah@ldap.bts.local", "Noah")

        assertThat(result.isNew).isTrue()
    }

    @Test
    fun `provisionFromExternal — 동일 username 재호출 시 isNew=false (ON CONFLICT UPDATE)`() {
        repo.provisionFromExternal("olivia", "olivia@ldap.bts.local", "Olivia")
        val second = repo.provisionFromExternal("olivia", "olivia-updated@ldap.bts.local", "Olivia Updated")

        assertThat(second.isNew).isFalse()
        assertThat(second.user.displayName).isEqualTo("Olivia Updated")
    }

    // ── findByIds ─────────────────────────────────────────────────────────────

    @Test
    fun `findByIds — 주어진 id 목록에 해당하는 사용자만 반환`() {
        val alice = repo.save("findbyids-alice", "alice@bts.local", "Alice Kim")
        val bob = repo.save("findbyids-bob", "bob@bts.local", "Bob Lee")
        repo.save("findbyids-charlie", "charlie@bts.local", "Charlie Park")

        val result = repo.findByIds(listOf(alice.id, bob.id))

        assertThat(result).hasSize(2)
        assertThat(result.map { it.id }).containsExactlyInAnyOrder(alice.id, bob.id)
    }

    @Test
    fun `findByIds — 존재하지 않는 id 는 결과에서 조용히 제외`() {
        val alice = repo.save("findbyids-alice2", "alice2@bts.local", "Alice2")
        val missingId = UUID.randomUUID()

        val result = repo.findByIds(listOf(alice.id, missingId))

        assertThat(result).hasSize(1)
        assertThat(result.first().id).isEqualTo(alice.id)
    }

    @Test
    fun `findByIds — 빈 리스트 입력 시 빈 결과 반환`() {
        repo.save("findbyids-alice3", "alice3@bts.local", "Alice3")

        val result: List<User> = repo.findByIds(emptyList())

        assertThat(result).hasSize(0)
    }

    // ── updateLastLogin ───────────────────────────────────────────────────────

    @Test
    fun `updateLastLogin — 존재하지 않는 id 는 조용히 무시 (0 행 영향)`() {
        // 예외 없이 실행돼야 함
        repo.updateLastLogin(UUID.randomUUID())
    }

    @Test
    fun `updateLastLogin — 호출 후 updatedAt 이 갱신된다`() {
        val user = repo.save("jack", "jack@bts.local", "Jack")

        // updatedAt 기준점 확보 후 약간 대기
        Thread.sleep(10)
        repo.updateLastLogin(user.id)

        val found = repo.findById(user.id)!!
        assertThat(found.updatedAt).isAfterOrEqualTo(user.updatedAt)
    }

    // ── display_name source 게이트 (FR-PR-04 — LDAP 재로그인 vs 사용자 편집) ─────

    @Test
    fun `provisionFromExternal이 source=USER 행의 display_name을 보존한다`() {
        // S2: LDAP 최초 provision → 사용자가 편집(source=USER) → 재로그인 provision.
        // 편집한 이름이 LDAP 값으로 덮어써지면 안 된다 (ADR 2026-07-07 D2).
        val first = repo.provisionFromExternal("ldap-alice", "alice@ldap.bts.local", "Alice")
        repo.updateDisplayName(first.user.id, "앨리스")

        val second = repo.provisionFromExternal("ldap-alice", "alice-new@ldap.bts.local", "Alice Updated")

        val found = repo.findById(first.user.id)
        assertThat(found).isNotNull()
        assertThat(found!!.displayName).isEqualTo("앨리스") // 편집 이름 보존
        assertThat(second.user.displayName).isEqualTo("앨리스") // RETURNING 도 보존값
        assertThat(found.email).isEqualTo("alice-new@ldap.bts.local") // email 은 계속 동기화
    }

    @Test
    fun `provisionFromExternal이 source=LDAP 행의 display_name을 동기화한다`() {
        // S3: 편집 없이(source=LDAP 유지) 재로그인 → LDAP 최신 값으로 동기화되어야 한다.
        val first = repo.provisionFromExternal("ldap-bob", "bob@ldap.bts.local", "Bob")

        repo.provisionFromExternal("ldap-bob", "bob@ldap.bts.local", "Bob Jr")

        val found = repo.findById(first.user.id)
        assertThat(found).isNotNull()
        assertThat(found!!.displayName).isEqualTo("Bob Jr") // LDAP 동기화
    }

    @Test
    fun `updateDisplayName이 display_name_source를 USER로 전환한다`() {
        // 편집은 override 의도 → source 를 USER 로 전환해 이후 LDAP 동기화로부터 보호한다.
        val first = repo.provisionFromExternal("ldap-carol", "carol@ldap.bts.local", "Carol")

        repo.updateDisplayName(first.user.id, "캐롤")

        assertThat(repo.findDisplayNameSource(first.user.id)).isEqualTo("USER")
    }

    @Test
    fun `resyncDisplayNameSource가 source를 LDAP로 되돌리고 findDisplayNameSource가 현재 source를 반환한다`() {
        // 관리자 재동기화: 편집(USER)된 행의 source 를 LDAP 로 되돌려 다음 재로그인 시 동기화되게 한다.
        val first = repo.provisionFromExternal("ldap-dave", "dave@ldap.bts.local", "Dave")
        repo.updateDisplayName(first.user.id, "데이브")
        assertThat(repo.findDisplayNameSource(first.user.id)).isEqualTo("USER")

        repo.resyncDisplayNameSource(first.user.id)

        assertThat(repo.findDisplayNameSource(first.user.id)).isEqualTo("LDAP")
    }
}
