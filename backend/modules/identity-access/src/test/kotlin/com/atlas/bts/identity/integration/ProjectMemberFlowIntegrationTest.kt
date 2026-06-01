// 프로젝트 멤버 관리 전 흐름 통합 테스트 — 부트스트랩·초대·역할변경·제거·동시성 (FR-PM-01 Task 7)

package com.atlas.bts.identity.integration

import com.atlas.bts.identity.credential.LocalCredentialService
import com.atlas.bts.identity.provider.ldap.AutoProvisionService
import com.atlas.bts.identity.provider.ldap.ExternalAccountRepository
import com.atlas.bts.identity.provider.ldap.LdapProvider
import com.atlas.bts.identity.provider.ldap.LdapProviderConfigService
import com.atlas.bts.identity.user.UserRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.ldap.core.LdapTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.security.MessageDigest
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * 프로젝트 멤버 관리 통합 테스트 (FR-PM-01 Task 7).
 *
 * ## 테스트 환경
 * - `@SpringBootTest(RANDOM_PORT)` — 실제 내장 Tomcat + Spring Security 필터 체인 전체 구동.
 * - Testcontainers PostgreSQL 16 — Flyway V001~V007 자동 마이그레이션 적용.
 * - projects 테이블: 이 BC의 Flyway에 없으므로 setUp에서 CREATE TABLE IF NOT EXISTS로 생성.
 *
 * ## 검증 시나리오
 * | 번호 | 시나리오 | 기대 결과 |
 * |---|---|---|
 * | S1 | JWT 부트스트랩 | 201 + role=PROJECT_ADMIN |
 * | S1-PAT | PAT 부트스트랩 거부 | 403 bootstrap_requires_jwt |
 * | S1-OTHER | 타인 부트스트랩 (멤버0) | 404 project_not_found |
 * | S2 | ADMIN이 MEMBER 초대 | 201 |
 * | S3 | MEMBER가 추가 시도 | 403 not_project_admin |
 * | S3-NONMEMBER | 비멤버 GET/PATCH/DELETE | 404 project_not_found |
 * | S4 | 역할 변경 (ADMIN→MEMBER) | 200 |
 * | S5 | 멤버 제거 | 204 |
 * | S6 | 마지막 ADMIN 강등 시도 | 409 last_admin_protected |
 * | S8 | 마지막 ADMIN 제거 시도 | 409 last_admin_protected |
 * | PAT-CRUD | ADMIN PAT로 멤버 추가/제거 | 201 / 204 (CSRF skip 검증) |
 * | EC-1 | 부트스트랩 동시성 | advisory lock 직렬화 |
 * | EC-2b | 두 ADMIN 동시 상호제거 | admin ≥ 1 유지 |
 * | SOFT-DEL | soft-deleted 프로젝트 | 404 |
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.security.oauth2.client.servlet.OAuth2ClientAutoConfiguration",
    ],
)
@Testcontainers
class ProjectMemberFlowIntegrationTest {

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
        fun configureProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            registry.add("spring.datasource.username") { postgres.username }
            registry.add("spring.datasource.password") { postgres.password }
            registry.add("bts.auth.issuer-uri") { "http://localhost:8090" }
            registry.add("bts.security.cors.allowed-origins") { "http://localhost:5173" }
            registry.add("spring.ldap.urls") { "ldap://localhost:389" }
            registry.add("spring.ldap.base") { "dc=bts,dc=local" }
        }

        /** SHA-256 hex — PAT token_hash 계산에 사용 (EC-26 선례 동일). */
        internal fun sha256Hex(raw: String): String {
            val bytes = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }

    // LDAP Bean 목킹 — 실제 LDAP 서버 없이 부팅 (PatAndConcurrencyIntegrationTest 선례 동일)
    @MockBean lateinit var ldapProvider: LdapProvider
    @MockBean lateinit var ldapProviderConfigService: LdapProviderConfigService
    @MockBean lateinit var externalAccountRepository: ExternalAccountRepository
    @MockBean lateinit var autoProvisionService: AutoProvisionService
    @MockBean lateinit var ldapTemplate: LdapTemplate

    @LocalServerPort
    var port: Int = 0

    @Autowired lateinit var restTemplate: TestRestTemplate
    @Autowired lateinit var userRepository: UserRepository
    @Autowired lateinit var localCredentialService: LocalCredentialService
    @Autowired lateinit var jdbc: NamedParameterJdbcTemplate

    /** 테스트 픽스처 — 매 테스트 @BeforeEach에서 재생성. */
    private lateinit var aliceId: UUID
    private lateinit var bobId: UUID
    private lateinit var carolId: UUID
    private lateinit var activeProjectId: UUID
    private lateinit var deletedProjectId: UUID

    private val alicePassword = "S3cur3@Alice1"
    private val bobPassword = "S3cur3@Bob001"
    private val carolPassword = "S3cur3@Carol1"

    @BeforeEach
    fun setUp() {
        ensureProjectsTableExists()
        cleanAllTables()
        seedUsers()
        seedProjects()
    }

    // ── S1. JWT 부트스트랩 ─────────────────────────────────────────────────────

    /**
     * S1: 멤버 0명 프로젝트에 JWT로 본인 추가 → 201, role=PROJECT_ADMIN.
     *
     * 부트스트랩 규칙: actor == target + JWT 전용 + 역할 강제 PROJECT_ADMIN.
     */
    @Test
    fun `S1 JWT로 본인 부트스트랩 — 201 PROJECT_ADMIN`() {
        val token = loginJwt("alice", alicePassword)

        val resp = postMember(token, activeProjectId, aliceId, "MEMBER")

        assertThat(resp.statusCode).isEqualTo(HttpStatus.CREATED)
        val body = resp.body as Map<*, *>
        assertThat(body["role"]).isEqualTo("PROJECT_ADMIN")
        assertThat(body["userId"].toString()).isEqualTo(aliceId.toString())
    }

    // ── S1-PAT. PAT로 부트스트랩 거부 ─────────────────────────────────────────

    /**
     * S1-PAT: PAT로 멤버 0명 프로젝트에 본인 부트스트랩 시도 → 403 bootstrap_requires_jwt.
     *
     * SecurityConfig PAT CSRF skip이 동작한다면 403이 CSRF 차단이 아닌 서비스 거부여야 한다.
     */
    @Test
    fun `S1-PAT PAT로 부트스트랩 시도 — 403 bootstrap_requires_jwt`() {
        val (rawPat, _) = insertPat(aliceId)

        val resp = postMemberWithPat(rawPat, activeProjectId, aliceId, "PROJECT_ADMIN")

        assertThat(resp.statusCode).isEqualTo(HttpStatus.FORBIDDEN)
        val body = resp.body as Map<*, *>
        assertThat(body["error"]).isEqualTo("bootstrap_requires_jwt")
    }

    // ── S1-OTHER. 타인 부트스트랩 → 존재숨김 404 ──────────────────────────────

    /**
     * S1-OTHER: JWT로 멤버 0명 프로젝트에 타인(bob) 추가 시도 → 404 project_not_found.
     *
     * B2 규칙: 부트스트랩 경로에서 target != actor이면 존재숨김 처리.
     */
    @Test
    fun `S1-OTHER JWT로 타인 부트스트랩 시도 — 404 project_not_found 존재숨김`() {
        val token = loginJwt("alice", alicePassword)

        val resp = postMember(token, activeProjectId, bobId, "PROJECT_ADMIN")

        assertThat(resp.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
        val body = resp.body as Map<*, *>
        assertThat(body["error"]).isEqualTo("project_not_found")
    }

    // ── S2. ADMIN 초대 ─────────────────────────────────────────────────────────

    /**
     * S2: ADMIN(alice)이 bob을 MEMBER로 초대 → 201.
     */
    @Test
    fun `S2 ADMIN이 MEMBER 초대 — 201`() {
        bootstrapAdmin(aliceId, alicePassword)
        val token = loginJwt("alice", alicePassword)

        val resp = postMember(token, activeProjectId, bobId, "MEMBER")

        assertThat(resp.statusCode).isEqualTo(HttpStatus.CREATED)
        val body = resp.body as Map<*, *>
        assertThat(body["userId"].toString()).isEqualTo(bobId.toString())
        assertThat(body["role"]).isEqualTo("MEMBER")
    }

    // ── S3. 비ADMIN 추가 거부 ──────────────────────────────────────────────────

    /**
     * S3: MEMBER(bob)가 carol 추가 시도 → 403 not_project_admin.
     */
    @Test
    fun `S3 MEMBER가 추가 시도 — 403 not_project_admin`() {
        bootstrapAdmin(aliceId, alicePassword)
        seedMember(aliceId, bobId, "MEMBER")
        val token = loginJwt("bob", bobPassword)

        val resp = postMember(token, activeProjectId, carolId, "MEMBER")

        assertThat(resp.statusCode).isEqualTo(HttpStatus.FORBIDDEN)
        val body = resp.body as Map<*, *>
        assertThat(body["error"]).isEqualTo("not_project_admin")
    }

    // ── S3-NONMEMBER. 비멤버 GET/PATCH/DELETE → 존재숨김 404 ─────────────────

    /**
     * S3-NONMEMBER: 비멤버(carol)가 GET/PATCH/DELETE 시도 → 404 project_not_found (B3 존재숨김).
     */
    @Test
    fun `S3-NONMEMBER 비멤버 GET 요청 — 404 project_not_found`() {
        bootstrapAdmin(aliceId, alicePassword)
        val token = loginJwt("carol", carolPassword)

        val resp = getMembers(token, activeProjectId)

        assertThat(resp.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
        val body = resp.body as Map<*, *>
        assertThat(body["error"]).isEqualTo("project_not_found")
    }

    @Test
    fun `S3-NONMEMBER 비멤버 PATCH 요청 — 404 project_not_found`() {
        bootstrapAdmin(aliceId, alicePassword)
        val token = loginJwt("carol", carolPassword)

        val resp = patchRole(token, activeProjectId, aliceId, "MEMBER")

        assertThat(resp.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
        val body = resp.body as Map<*, *>
        assertThat(body["error"]).isEqualTo("project_not_found")
    }

    @Test
    fun `S3-NONMEMBER 비멤버 DELETE 요청 — 404 project_not_found`() {
        bootstrapAdmin(aliceId, alicePassword)
        val token = loginJwt("carol", carolPassword)

        val resp = deleteMember(token, activeProjectId, aliceId)

        assertThat(resp.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
        val body = resp.body as Map<*, *>
        assertThat(body["error"]).isEqualTo("project_not_found")
    }

    // ── S4. 역할 변경 ──────────────────────────────────────────────────────────

    /**
     * S4: ADMIN(alice)이 bob(ADMIN)을 MEMBER로 강등 → 200.
     *
     * alice가 ADMIN으로 남아있으므로 마지막 admin 보호에 걸리지 않는다.
     */
    @Test
    fun `S4 역할 변경 ADMIN to MEMBER — 200`() {
        bootstrapAdmin(aliceId, alicePassword)
        seedMember(aliceId, bobId, "PROJECT_ADMIN")
        val token = loginJwt("alice", alicePassword)

        val resp = patchRole(token, activeProjectId, bobId, "MEMBER")

        assertThat(resp.statusCode).isEqualTo(HttpStatus.OK)
        val body = resp.body as Map<*, *>
        assertThat(body["role"]).isEqualTo("MEMBER")
    }

    // ── S5. 멤버 제거 ──────────────────────────────────────────────────────────

    /**
     * S5: ADMIN(alice)이 bob(MEMBER) 제거 → 204.
     */
    @Test
    fun `S5 멤버 제거 — 204`() {
        bootstrapAdmin(aliceId, alicePassword)
        seedMember(aliceId, bobId, "MEMBER")
        val token = loginJwt("alice", alicePassword)

        val resp = deleteMember(token, activeProjectId, bobId)

        assertThat(resp.statusCode).isEqualTo(HttpStatus.NO_CONTENT)
    }

    // ── S6. 마지막 ADMIN 강등 보호 ─────────────────────────────────────────────

    /**
     * S6: ADMIN 1명인 프로젝트에서 자신을 MEMBER로 강등 시도 → 409 last_admin_protected.
     */
    @Test
    fun `S6 마지막 ADMIN 강등 시도 — 409 last_admin_protected`() {
        bootstrapAdmin(aliceId, alicePassword)
        val token = loginJwt("alice", alicePassword)

        val resp = patchRole(token, activeProjectId, aliceId, "MEMBER")

        assertThat(resp.statusCode).isEqualTo(HttpStatus.CONFLICT)
        val body = resp.body as Map<*, *>
        assertThat(body["error"]).isEqualTo("last_admin_protected")
    }

    // ── S8. 마지막 ADMIN 제거 보호 ─────────────────────────────────────────────

    /**
     * S8: ADMIN 1명인 프로젝트에서 자신을 제거 시도 → 409 last_admin_protected.
     */
    @Test
    fun `S8 마지막 ADMIN 제거 시도 — 409 last_admin_protected`() {
        bootstrapAdmin(aliceId, alicePassword)
        val token = loginJwt("alice", alicePassword)

        val resp = deleteMember(token, activeProjectId, aliceId)

        assertThat(resp.statusCode).isEqualTo(HttpStatus.CONFLICT)
        val body = resp.body as Map<*, *>
        assertThat(body["error"]).isEqualTo("last_admin_protected")
    }

    // ── PAT-CRUD. ADMIN PAT로 멤버 추가/제거 ──────────────────────────────────

    /**
     * PAT-CRUD: ADMIN PAT로 bob을 추가(201) 후 제거(204) — SecurityConfig PAT CSRF skip 검증.
     *
     * PAT Bearer 요청은 SecurityConfig의 patBearerMatcher가 CSRF를 skip한다.
     * 이 테스트가 통과하면 PAT mutation 경로에서 CSRF가 403을 반환하지 않음을 확인한다.
     */
    @Test
    fun `PAT-CRUD ADMIN PAT로 멤버 추가 후 제거 — 201 then 204`() {
        bootstrapAdmin(aliceId, alicePassword)
        val (rawPat, _) = insertPat(aliceId)

        val addResp = postMemberWithPat(rawPat, activeProjectId, bobId, "MEMBER")
        assertThat(addResp.statusCode).isEqualTo(HttpStatus.CREATED)

        val deleteResp = deleteMemberWithPat(rawPat, activeProjectId, bobId)
        assertThat(deleteResp.statusCode).isEqualTo(HttpStatus.NO_CONTENT)
    }

    // ── EC-1. 부트스트랩 동시성 ───────────────────────────────────────────────

    /**
     * EC-1: 멤버 0명 프로젝트에 alice와 bob이 동시에 (각자 본인을) 부트스트랩 시도.
     *
     * advisory lock 직렬화로 결과는 다음 둘 중 하나.
     * - 둘 중 1명만 201 성공, 나머지는 409(AlreadyMember 불가 — 둘은 다른 userId이므로)
     *   또는 lock 직렬화로 두 번째 요청이 count>0 경로로 빠져 403(not_project_admin)
     * - 어떤 결과든 최종 admin 수는 정확히 1명이어야 한다.
     *
     * 핵심 단언: 첫 번째 성공 후 두 번째는 서비스 레이어 가드에 의해 차단됨.
     * → DB에 PROJECT_ADMIN이 정확히 1명.
     */
    @Test
    fun `EC-1 부트스트랩 동시성 — advisory lock 직렬화로 PROJECT_ADMIN 정확히 1명`() {
        val tokenAlice = loginJwt("alice", alicePassword)
        val tokenBob = loginJwt("bob", bobPassword)
        val projectId = UUID.randomUUID()
        insertActiveProject(projectId)

        val successCount = AtomicInteger(0)
        val latch = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)

        val futures = listOf(
            executor.submit {
                latch.await()
                val resp = postMember(tokenAlice, projectId, aliceId, "PROJECT_ADMIN")
                if (resp.statusCode == HttpStatus.CREATED) successCount.incrementAndGet()
            },
            executor.submit {
                latch.await()
                val resp = postMember(tokenBob, projectId, bobId, "PROJECT_ADMIN")
                if (resp.statusCode == HttpStatus.CREATED) successCount.incrementAndGet()
            },
        )

        latch.countDown()
        futures.forEach { it.get() }
        executor.shutdown()

        // DB에 저장된 최종 admin 수가 1명이어야 한다 (두 건 다 성공하면 2명 — 이것이 결함)
        val adminCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM project_memberships WHERE project_id = :pid AND role = 'PROJECT_ADMIN'",
            mapOf("pid" to projectId),
            Int::class.java,
        ) ?: 0
        assertThat(adminCount)
            .withFailMessage("EC-1 위반: advisory lock이 동작하지 않아 admin이 2명 생성됨")
            .isEqualTo(1)
        assertThat(successCount.get())
            .withFailMessage("EC-1: 성공 건수는 정확히 1이어야 한다")
            .isEqualTo(1)
    }

    // ── EC-2b. 두 ADMIN 동시 상호제거 ─────────────────────────────────────────

    /**
     * EC-2b: ADMIN A(alice)와 ADMIN B(bob)가 동시에 서로를 제거.
     *
     * guardLastAdmin + advisory lock으로 admin 0명 상태를 방지한다.
     * 둘 다 성공해 admin 0명이 되는 것이 결함. 최소 1명의 admin이 남아야 한다.
     *
     * 허용 결과.
     * - 1명 성공(204) + 1명 409(last_admin_protected) → admin 1명 남음. (이상적)
     * - 둘 다 409 → admin 2명 그대로. (보수적 잠금)
     * - 둘 다 204 → admin 0명 → 결함 (BLOCKED 조건)
     */
    @Test
    fun `EC-2b 두 ADMIN 동시 상호제거 — admin 최소 1명 유지`() {
        bootstrapAdmin(aliceId, alicePassword)
        seedMember(aliceId, bobId, "PROJECT_ADMIN")
        val tokenAlice = loginJwt("alice", alicePassword)
        val tokenBob = loginJwt("bob", bobPassword)

        val latch = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)

        val futures = listOf(
            executor.submit { latch.await(); deleteMember(tokenAlice, activeProjectId, bobId) },
            executor.submit { latch.await(); deleteMember(tokenBob, activeProjectId, aliceId) },
        )

        latch.countDown()
        futures.forEach { it.get() }
        executor.shutdown()

        val adminCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM project_memberships WHERE project_id = :pid AND role = 'PROJECT_ADMIN'",
            mapOf("pid" to activeProjectId),
            Int::class.java,
        ) ?: 0
        assertThat(adminCount)
            .withFailMessage("EC-2b 위반: 동시 상호제거로 admin 0명 상태가 발생함")
            .isGreaterThanOrEqualTo(1)
    }

    // ── SOFT-DEL. soft-deleted 프로젝트 → 404 ─────────────────────────────────

    /**
     * SOFT-DEL: deleted_at이 설정된 프로젝트에 멤버 추가 시도 → 404 project_not_found.
     */
    @Test
    fun `SOFT-DEL soft-deleted 프로젝트 접근 — 404 project_not_found`() {
        val token = loginJwt("alice", alicePassword)

        val resp = postMember(token, deletedProjectId, aliceId, "PROJECT_ADMIN")

        assertThat(resp.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
        val body = resp.body as Map<*, *>
        assertThat(body["error"]).isEqualTo("project_not_found")
    }

    // ── private 헬퍼 — fixture 생성 ───────────────────────────────────────────

    /**
     * projects 테이블을 생성한다.
     *
     * identity-access Flyway에 없는 cross-BC 테이블이므로 테스트 DB에 직접 생성한다.
     * ProjectDirectory.exists()가 이 테이블을 쿼리한다.
     */
    private fun ensureProjectsTableExists() {
        jdbc.jdbcTemplate.execute(
            """
            CREATE TABLE IF NOT EXISTS projects (
                id         UUID PRIMARY KEY,
                deleted_at TIMESTAMPTZ
            )
            """.trimIndent(),
        )
    }

    /** 모든 관련 테이블을 FK 순서대로 초기화한다. */
    private fun cleanAllTables() {
        jdbc.update("DELETE FROM project_memberships", emptyMap<String, Any>())
        jdbc.update("DELETE FROM personal_access_tokens", emptyMap<String, Any>())
        jdbc.update("DELETE FROM refresh_tokens", emptyMap<String, Any>())
        jdbc.update("DELETE FROM sessions", emptyMap<String, Any>())
        jdbc.update("DELETE FROM local_credentials", emptyMap<String, Any>())
        jdbc.update("DELETE FROM user_external_accounts", emptyMap<String, Any>())
        jdbc.update("DELETE FROM users", emptyMap<String, Any>())
        jdbc.update("DELETE FROM projects", emptyMap<String, Any>())
    }

    /** 테스트 사용자 3명을 생성한다. */
    private fun seedUsers() {
        val alice = userRepository.save("alice", "alice@example.com", "Alice")
        val bob = userRepository.save("bob", "bob@example.com", "Bob")
        val carol = userRepository.save("carol", "carol@example.com", "Carol")
        aliceId = alice.id
        bobId = bob.id
        carolId = carol.id

        localCredentialService.store(aliceId, alicePassword.toCharArray())
        localCredentialService.store(bobId, bobPassword.toCharArray())
        localCredentialService.store(carolId, carolPassword.toCharArray())
    }

    /** 활성 프로젝트 1개 + soft-deleted 프로젝트 1개를 시드한다. */
    private fun seedProjects() {
        activeProjectId = UUID.randomUUID()
        deletedProjectId = UUID.randomUUID()
        insertActiveProject(activeProjectId)
        insertDeletedProject(deletedProjectId)
    }

    private fun insertActiveProject(id: UUID) {
        jdbc.update(
            "INSERT INTO projects (id, deleted_at) VALUES (:id, NULL)",
            mapOf("id" to id),
        )
    }

    private fun insertDeletedProject(id: UUID) {
        jdbc.update(
            "INSERT INTO projects (id, deleted_at) VALUES (:id, :deletedAt)",
            mapOf("id" to id, "deletedAt" to Timestamp.from(Instant.now())),
        )
    }

    /**
     * alice로 부트스트랩(JWT 자기자신 추가)하여 PROJECT_ADMIN 픽스처를 만든다.
     *
     * S2·S3·S4·S5·S6·S8·EC-2b 시나리오의 선행 조건.
     */
    private fun bootstrapAdmin(userId: UUID, password: String) {
        val username = when (userId) {
            aliceId -> "alice"
            bobId -> "bob"
            else -> "carol"
        }
        val token = loginJwt(username, password)
        val resp = postMember(token, activeProjectId, userId, "PROJECT_ADMIN")
        check(resp.statusCode == HttpStatus.CREATED) {
            "bootstrapAdmin 실패: ${resp.statusCode} ${resp.body}"
        }
    }

    /**
     * 이미 ADMIN이 있는 프로젝트에 직접 멤버십 행을 INSERT한다.
     *
     * S3·S4·S5·EC-2b 시나리오에서 ADMIN 권한 없이 멤버를 추가해야 할 때 사용한다.
     */
    private fun seedMember(actorAdminId: UUID, targetUserId: UUID, role: String) {
        // actor가 이미 ADMIN인 상태에서 direct INSERT — 서비스 레이어 우회로 시드
        jdbc.update(
            """
            INSERT INTO project_memberships (project_id, user_id, role)
            VALUES (:pid, :uid, :role)
            ON CONFLICT (project_id, user_id) DO UPDATE SET role = :role
            """.trimIndent(),
            mapOf("pid" to activeProjectId, "uid" to targetUserId, "role" to role),
        )
    }

    /**
     * personal_access_tokens에 유효한 PAT를 직접 INSERT한다.
     *
     * @return Pair(rawToken, patId)
     */
    private fun insertPat(userId: UUID): Pair<String, UUID> {
        val rawPat = "pat_${"x".repeat(48)}"
        val tokenHash = sha256Hex(rawPat)
        val patId = UUID.randomUUID()
        jdbc.update(
            """
            INSERT INTO personal_access_tokens
                (id, user_id, name, token_hash, scopes, expires_at, revoked_at, created_at)
            VALUES
                (:id, :userId, :name, :tokenHash, '["*"]'::jsonb, NULL, NULL, :createdAt)
            """.trimIndent(),
            mapOf(
                "id" to patId,
                "userId" to userId,
                "name" to "test-pat",
                "tokenHash" to tokenHash,
                "createdAt" to Timestamp.from(Instant.now()),
            ),
        )
        return rawPat to patId
    }

    // ── private 헬퍼 — HTTP 요청 ───────────────────────────────────────────────

    /**
     * POST /api/v1/auth/login으로 JWT access_token을 발급받는다.
     *
     * login 엔드포인트는 SecurityConfig에서 CSRF skip이므로 X-XSRF-TOKEN 불필요.
     */
    private fun loginJwt(username: String, password: String): String {
        val headers = HttpHeaders().apply { contentType = MediaType.APPLICATION_JSON }
        val body = """{"provider":"local","username":"$username","password":"$password"}"""
        val resp = restTemplate.exchange(
            "http://localhost:$port/api/v1/auth/login",
            HttpMethod.POST,
            HttpEntity(body, headers),
            Map::class.java,
        )
        check(resp.statusCode == HttpStatus.OK) {
            "loginJwt 실패 ($username): ${resp.statusCode}"
        }
        return (resp.body as Map<*, *>)["access_token"] as String
    }

    /** JWT Bearer로 POST /api/v1/projects/{projectId}/members 요청을 보낸다. */
    private fun postMember(
        jwt: String,
        projectId: UUID,
        targetUserId: UUID,
        role: String,
    ) = restTemplate.exchange(
        "http://localhost:$port/api/v1/projects/$projectId/members",
        HttpMethod.POST,
        HttpEntity(
            """{"userId":"$targetUserId","role":"$role"}""",
            bearerHeaders(jwt),
        ),
        Map::class.java,
    )

    /** PAT Bearer로 POST /api/v1/projects/{projectId}/members 요청을 보낸다. */
    private fun postMemberWithPat(
        rawPat: String,
        projectId: UUID,
        targetUserId: UUID,
        role: String,
    ) = restTemplate.exchange(
        "http://localhost:$port/api/v1/projects/$projectId/members",
        HttpMethod.POST,
        HttpEntity(
            """{"userId":"$targetUserId","role":"$role"}""",
            bearerHeaders(rawPat),
        ),
        Map::class.java,
    )

    /** JWT Bearer로 GET /api/v1/projects/{projectId}/members 요청을 보낸다. */
    private fun getMembers(jwt: String, projectId: UUID) = restTemplate.exchange(
        "http://localhost:$port/api/v1/projects/$projectId/members",
        HttpMethod.GET,
        HttpEntity<Void>(bearerHeaders(jwt)),
        Map::class.java,
    )

    /** JWT Bearer로 PATCH /api/v1/projects/{projectId}/members/{userId} 요청을 보낸다. */
    private fun patchRole(
        jwt: String,
        projectId: UUID,
        targetUserId: UUID,
        role: String,
    ) = restTemplate.exchange(
        "http://localhost:$port/api/v1/projects/$projectId/members/$targetUserId",
        HttpMethod.PATCH,
        HttpEntity(
            """{"role":"$role"}""",
            bearerHeaders(jwt),
        ),
        Map::class.java,
    )

    /** JWT Bearer로 DELETE /api/v1/projects/{projectId}/members/{userId} 요청을 보낸다. */
    private fun deleteMember(jwt: String, projectId: UUID, targetUserId: UUID) = restTemplate.exchange(
        "http://localhost:$port/api/v1/projects/$projectId/members/$targetUserId",
        HttpMethod.DELETE,
        HttpEntity<Void>(bearerHeaders(jwt)),
        Map::class.java,
    )

    /** PAT Bearer로 DELETE /api/v1/projects/{projectId}/members/{userId} 요청을 보낸다. */
    private fun deleteMemberWithPat(rawPat: String, projectId: UUID, targetUserId: UUID) = restTemplate.exchange(
        "http://localhost:$port/api/v1/projects/$projectId/members/$targetUserId",
        HttpMethod.DELETE,
        HttpEntity<Void>(bearerHeaders(rawPat)),
        Map::class.java,
    )

    /**
     * Authorization: Bearer 헤더를 포함한 HttpHeaders를 생성한다.
     *
     * Content-Type은 application/json으로 고정한다 (POST/PATCH body 포함 요청에도 동일).
     */
    private fun bearerHeaders(token: String) = HttpHeaders().apply {
        contentType = MediaType.APPLICATION_JSON
        set(HttpHeaders.AUTHORIZATION, "Bearer $token")
    }
}
