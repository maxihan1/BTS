// JdbcAuthAuditLogAdminQueryRepository 관리자 전역 조회 통합테스트 — Testcontainers PostgreSQL + Flyway V001~V021 — FR-AU-10 D6/D7

package com.atlas.bts.identity.audit

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

/**
 * [JdbcAuthAuditLogAdminQueryRepository] 관리자 전역 조회 통합테스트 (FR-AU-10 D6/D7).
 *
 * `@JdbcTest` + Testcontainers PostgreSQL + Flyway V001~V021 자동 적용
 * (JdbcAuthAuditLogServiceIntegrationTest 의 경량 부팅 셋업 차용).
 *
 * ## 검증 시나리오
 * - 무필터 전체 조회 + 정렬(created_at DESC, id DESC) + totalElements.
 * - eventType 필터.
 * - userId 필터.
 * - from~to 경계(포함).
 * - users JOIN: 존재하는 user_id 는 username/displayName 채움, null·미존재 user_id 는 null.
 * - 페이지네이션: size=2 page=0/1, totalElements 불변.
 * - 복합필터 AND (eventType + userId).
 *
 * @Import(JacksonAutoConfiguration) — metadata JSONB 역직렬화에 필요한 ObjectMapper Bean 공급.
 */
@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JdbcAuthAuditLogAdminQueryRepository::class, JacksonAutoConfiguration::class)
@Testcontainers
class JdbcAuthAuditLogAdminQueryRepositoryIntegrationTest {
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

        private val ALICE_ID: UUID = UUID.fromString("a1111111-1111-1111-1111-111111111111")
        private val BOB_ID: UUID = UUID.fromString("b2222222-2222-2222-2222-222222222222")

        // users 행이 없는(미존재) user_id — JOIN 시 username/displayName null 검증용
        private val ORPHAN_ID: UUID = UUID.fromString("c3333333-3333-3333-3333-333333333333")

        // 시드 기준 시각 — 1초 간격으로 created_at 을 증가시켜 정렬을 결정적으로 만든다.
        private val T0: Instant = Instant.parse("2026-06-01T00:00:00Z")
    }

    @Autowired
    private lateinit var repository: JdbcAuthAuditLogAdminQueryRepository

    @Autowired
    private lateinit var jdbc: NamedParameterJdbcTemplate

    @BeforeEach
    fun setUp() {
        jdbc.update("DELETE FROM auth_audit_logs", emptyMap<String, Any>())
        jdbc.update("DELETE FROM users", emptyMap<String, Any>())

        insertUser(ALICE_ID, "alice", "Alice Anderson")
        insertUser(BOB_ID, "bob", "Bob Brown")

        // 시드: 5건. created_at 을 오름차순(T0+0..+4)으로 부여.
        // idx 0: alice LOGIN_SUCCESS    @ T0+0
        // idx 1: bob   LOGIN_FAILURE    @ T0+1
        // idx 2: null  LOGIN_FAILURE    @ T0+2 (사용자 미상)
        // idx 3: orphan LOGIN_SUCCESS   @ T0+3 (users 미존재 user_id)
        // idx 4: alice TOKEN_REFRESHED  @ T0+4 (최신)
        insertLog(ALICE_ID, AuthEventType.LOGIN_SUCCESS, "local", "203.0.113.1", "agent-a", mapOf("sid" to "s1"), T0)
        insertLog(
            userId = BOB_ID,
            eventType = AuthEventType.LOGIN_FAILURE,
            providerId = "local",
            ipAddress = null,
            userAgent = null,
            metadata = mapOf("reason" to "BAD"),
            createdAt = T0.plusSeconds(1),
        )
        insertLog(
            userId = null,
            eventType = AuthEventType.LOGIN_FAILURE,
            providerId = "local",
            ipAddress = null,
            userAgent = null,
            metadata = mapOf("username" to "ghost"),
            createdAt = T0.plusSeconds(2),
        )
        insertLog(
            userId = ORPHAN_ID,
            eventType = AuthEventType.LOGIN_SUCCESS,
            providerId = "ldap",
            ipAddress = "203.0.113.9",
            userAgent = "agent-x",
            metadata = emptyMap(),
            createdAt = T0.plusSeconds(3),
        )
        insertLog(
            userId = ALICE_ID,
            eventType = AuthEventType.TOKEN_REFRESHED,
            providerId = "local",
            ipAddress = "203.0.113.1",
            userAgent = "agent-a",
            metadata = emptyMap(),
            createdAt = T0.plusSeconds(4),
        )
    }

    // ── 무필터 전체 조회 + 정렬 + totalElements ───────────────────────────────────

    @Test
    fun `무필터는 전체를 created_at DESC id DESC 로 반환하고 totalElements 를 채운다`() {
        val page = repository.search(AuthAuditLogSearchCriteria(size = 50))

        assertThat(page.totalElements).isEqualTo(5)
        assertThat(page.items).hasSize(5)
        // 최신순: T0+4(alice TOKEN_REFRESHED) → T0+3(orphan) → T0+2(null) → T0+1(bob) → T0+0(alice)
        assertThat(page.items.map { it.eventType }).containsExactly(
            AuthEventType.TOKEN_REFRESHED,
            AuthEventType.LOGIN_SUCCESS,
            AuthEventType.LOGIN_FAILURE,
            AuthEventType.LOGIN_FAILURE,
            AuthEventType.LOGIN_SUCCESS,
        )
        // created_at 단조 감소 확인
        val times = page.items.map { it.createdAt }
        assertThat(times).isEqualTo(times.sortedDescending())
    }

    // ── eventType 필터 ─────────────────────────────────────────────────────────────

    @Test
    fun `eventType 필터는 해당 유형만 반환한다`() {
        val page = repository.search(AuthAuditLogSearchCriteria(eventType = AuthEventType.LOGIN_FAILURE))

        assertThat(page.totalElements).isEqualTo(2)
        assertThat(page.items).hasSize(2)
        assertThat(page.items).allMatch { it.eventType == AuthEventType.LOGIN_FAILURE }
    }

    // ── userId 필터 ────────────────────────────────────────────────────────────────

    @Test
    fun `userId 필터는 해당 사용자 행만 반환한다`() {
        val page = repository.search(AuthAuditLogSearchCriteria(userId = ALICE_ID))

        assertThat(page.totalElements).isEqualTo(2)
        assertThat(page.items).hasSize(2)
        assertThat(page.items).allMatch { it.userId == ALICE_ID }
        // alice 의 두 행은 username/displayName 이 JOIN 으로 채워진다.
        assertThat(page.items).allMatch { it.username == "alice" && it.displayName == "Alice Anderson" }
    }

    // ── from~to 경계(포함) ─────────────────────────────────────────────────────────

    @Test
    fun `from to 는 경계를 포함하는 시간 범위로 필터한다`() {
        // T0+1 ~ T0+3 포함 → idx 1,2,3 (3건)
        val page =
            repository.search(
                AuthAuditLogSearchCriteria(
                    from = T0.plusSeconds(1),
                    to = T0.plusSeconds(3),
                ),
            )

        assertThat(page.totalElements).isEqualTo(3)
        assertThat(page.items).hasSize(3)
        assertThat(page.items.map { it.createdAt }).containsExactly(
            T0.plusSeconds(3),
            T0.plusSeconds(2),
            T0.plusSeconds(1),
        )
    }

    @Test
    fun `from 단독 경계는 from 이상만 반환한다`() {
        val page = repository.search(AuthAuditLogSearchCriteria(from = T0.plusSeconds(3)))

        assertThat(page.totalElements).isEqualTo(2)
        assertThat(page.items.map { it.createdAt }).containsExactly(T0.plusSeconds(4), T0.plusSeconds(3))
    }

    @Test
    fun `to 단독 경계는 to 이하만 반환한다`() {
        val page = repository.search(AuthAuditLogSearchCriteria(to = T0.plusSeconds(1)))

        assertThat(page.totalElements).isEqualTo(2)
        assertThat(page.items.map { it.createdAt }).containsExactly(T0.plusSeconds(1), T0)
    }

    // ── users JOIN (존재→채움, null·미존재→null) ──────────────────────────────────

    @Test
    fun `존재하는 userId 는 username displayName 을 채우고 null 또는 미존재 userId 는 null 이다`() {
        val all = repository.search(AuthAuditLogSearchCriteria(size = 50)).items

        val bobRow = all.first { it.userId == BOB_ID }
        assertThat(bobRow.username).isEqualTo("bob")
        assertThat(bobRow.displayName).isEqualTo("Bob Brown")

        // user_id = null 행
        val nullUserRow = all.first { it.userId == null }
        assertThat(nullUserRow.username).isNull()
        assertThat(nullUserRow.displayName).isNull()
        assertThat(nullUserRow.metadata["username"]).isEqualTo("ghost")

        // users 미존재 user_id(orphan) 행 — LEFT JOIN 미스 → username/displayName null
        val orphanRow = all.first { it.userId == ORPHAN_ID }
        assertThat(orphanRow.username).isNull()
        assertThat(orphanRow.displayName).isNull()
    }

    @Test
    fun `metadata JSONB 와 nullable ip userAgent 를 그대로 복원한다`() {
        val aliceSuccess =
            repository.search(AuthAuditLogSearchCriteria(userId = ALICE_ID)).items
                .first { it.eventType == AuthEventType.LOGIN_SUCCESS }

        assertThat(aliceSuccess.ipAddress).isEqualTo("203.0.113.1")
        assertThat(aliceSuccess.userAgent).isEqualTo("agent-a")
        assertThat(aliceSuccess.metadata).containsExactlyInAnyOrderEntriesOf(mapOf("sid" to "s1"))

        val bobFailure =
            repository.search(AuthAuditLogSearchCriteria(userId = BOB_ID)).items.first()
        assertThat(bobFailure.ipAddress).isNull()
        assertThat(bobFailure.userAgent).isNull()
    }

    // ── 페이지네이션 (totalElements 불변) ─────────────────────────────────────────

    @Test
    fun `페이지네이션은 size 만큼 잘라 반환하고 totalElements 는 전체로 불변이다`() {
        val page0 = repository.search(AuthAuditLogSearchCriteria(page = 0, size = 2))
        val page1 = repository.search(AuthAuditLogSearchCriteria(page = 1, size = 2))
        val page2 = repository.search(AuthAuditLogSearchCriteria(page = 2, size = 2))

        assertThat(page0.totalElements).isEqualTo(5)
        assertThat(page1.totalElements).isEqualTo(5)
        assertThat(page2.totalElements).isEqualTo(5)

        assertThat(page0.items).hasSize(2)
        assertThat(page1.items).hasSize(2)
        assertThat(page2.items).hasSize(1)

        // 페이지 간 겹침 없음 + 전역 정렬 연속성 (created_at DESC 연속)
        val combined = (page0.items + page1.items + page2.items)
        assertThat(combined.map { it.createdAt }).isEqualTo(combined.map { it.createdAt }.sortedDescending())
        assertThat(combined.map { it.id }.distinct()).hasSize(5)
    }

    // ── 복합필터 AND ───────────────────────────────────────────────────────────────

    @Test
    fun `복합필터는 eventType 과 userId 를 AND 로 결합한다`() {
        val page =
            repository.search(
                AuthAuditLogSearchCriteria(
                    eventType = AuthEventType.LOGIN_SUCCESS,
                    userId = ALICE_ID,
                ),
            )

        assertThat(page.totalElements).isEqualTo(1)
        assertThat(page.items).hasSize(1)
        assertThat(page.items.first().userId).isEqualTo(ALICE_ID)
        assertThat(page.items.first().eventType).isEqualTo(AuthEventType.LOGIN_SUCCESS)
    }

    // ── 시드 헬퍼 ──────────────────────────────────────────────────────────────────

    private fun insertUser(
        id: UUID,
        username: String,
        displayName: String,
    ) {
        jdbc.update(
            """
            INSERT INTO users (id, username, display_name)
            VALUES (:id, :username, :displayName)
            """,
            mapOf("id" to id, "username" to username, "displayName" to displayName),
        )
    }

    // LongParameterList 억제 — auth_audit_logs 컬럼을 1:1 로 시드하는 테스트 전용 헬퍼(임의 그룹핑은 가독성 저하).
    @Suppress("LongParameterList")
    private fun insertLog(
        userId: UUID?,
        eventType: AuthEventType,
        providerId: String,
        ipAddress: String?,
        userAgent: String?,
        metadata: Map<String, String>,
        createdAt: Instant,
    ) {
        val metadataJson =
            metadata.entries.joinToString(",", "{", "}") { (k, v) -> "\"$k\":\"$v\"" }
        jdbc.update(
            """
            INSERT INTO auth_audit_logs
                (user_id, event_type, provider_id, ip_address, user_agent, metadata, created_at)
            VALUES
                (:userId, :eventType, :providerId, :ipAddress, :userAgent, :metadata::jsonb, :createdAt)
            """,
            mapOf(
                "userId" to userId,
                "eventType" to eventType.name,
                "providerId" to providerId,
                "ipAddress" to ipAddress,
                "userAgent" to userAgent,
                "metadata" to metadataJson,
                "createdAt" to Timestamp.from(createdAt),
            ),
        )
    }
}
