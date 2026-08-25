// JdbcAuthAuditLogService 영속 통합테스트 — Testcontainers PostgreSQL + Flyway V001~V021 적용 — FR-AU-10 Task 3

package com.atlas.bts.identity.audit

import com.atlas.bts.identity.support.SharedPostgres
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
import java.util.UUID

/**
 * [JdbcAuthAuditLogService] 영속 통합테스트 (FR-AU-10 Task 3).
 *
 * `@JdbcTest` + Testcontainers PostgreSQL + Flyway V001~V021 자동 적용.
 * 검증 대상: record(INSERT) / findRecent(SELECT 최신순) + metadata JSONB 직렬화 + nullable 필드 왕복.
 *
 * ## 검증 시나리오
 * - record → findRecent 왕복: INSERT 후 즉시 조회로 동일 이벤트 복원.
 * - 최신순 정렬: created_at DESC, 동률 시 id DESC tiebreaker (EC-10).
 * - limit: 요청한 건수만 반환.
 * - 사용자 격리: findRecent(userId)는 해당 user_id 행만 반환.
 * - userId=null 영속/조회: LOGIN_FAILURE/LDAP_UNAVAILABLE 수용 (EC-2).
 * - metadata JSONB 왕복: Map<String,String> 직렬화/역직렬화 (EC-9).
 * - nullable 필드 왕복: ipAddress/userAgent/deviceFingerprint null 보존.
 *
 * PersonalAccessTokenRepositoryTest 의 @JdbcTest + @Import + JacksonAutoConfiguration 패턴을 복제(경량 부팅).
 * @Import(JdbcAuthAuditLogService) — @Service 빈을 슬라이스에 명시 등록.
 * @Import(JacksonAutoConfiguration) — metadata JSONB 직렬화에 필요한 ObjectMapper Bean 공급.
 */
@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JdbcAuthAuditLogService::class, JacksonAutoConfiguration::class)
class JdbcAuthAuditLogServiceIntegrationTest {
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
    private lateinit var service: JdbcAuthAuditLogService

    @Autowired
    private lateinit var jdbc: NamedParameterJdbcTemplate

    private lateinit var userId: UUID

    @BeforeEach
    fun setUp() {
        // auth_audit_logs 는 FK 없음(append-only). 매 테스트 격리를 위해 전체 삭제(테스트 전용).
        jdbc.update("DELETE FROM auth_audit_logs", emptyMap<String, Any>())
        userId = UUID.randomUUID()
    }

    // ── record → findRecent 왕복 ──────────────────────────────────────────────────

    @Test
    fun `record 후 findRecent 로 동일 이벤트를 복원한다`() {
        service.record(
            AuthAuditLog(
                userId = userId,
                eventType = AuthEventType.LOGIN_SUCCESS,
                providerId = "local",
                ipAddress = "203.0.113.5",
                userAgent = "Mozilla/5.0 test-agent",
                deviceFingerprint = "fp-abc-123",
                metadata = mapOf("sid" to "session-xyz", "reason" to "ok"),
            ),
        )

        val logs = service.findRecent(userId, limit = 10)

        assertThat(logs).hasSize(1)
        val restored = logs.first()
        assertThat(restored.userId).isEqualTo(userId)
        assertThat(restored.eventType).isEqualTo(AuthEventType.LOGIN_SUCCESS)
        assertThat(restored.providerId).isEqualTo("local")
        assertThat(restored.ipAddress).isEqualTo("203.0.113.5")
        assertThat(restored.userAgent).isEqualTo("Mozilla/5.0 test-agent")
        assertThat(restored.deviceFingerprint).isEqualTo("fp-abc-123")
        assertThat(restored.metadata).containsExactlyInAnyOrderEntriesOf(
            mapOf("sid" to "session-xyz", "reason" to "ok"),
        )
    }

    // ── 최신순 정렬 (created_at DESC, id DESC tiebreaker — EC-10) ────────────────────

    @Test
    fun `findRecent 는 최신순으로 반환하며 created_at 동률은 id DESC 로 tiebreak 한다`() {
        // 세 이벤트를 같은 createdAt 으로 기록 — id(IDENTITY 단조 증가) DESC tiebreaker 검증
        repeat(3) { idx ->
            service.record(
                AuthAuditLog(
                    userId = userId,
                    eventType = AuthEventType.TOKEN_REFRESHED,
                    providerId = "local",
                    metadata = mapOf("seq" to idx.toString()),
                ),
            )
        }

        val logs = service.findRecent(userId, limit = 10)

        assertThat(logs).hasSize(3)
        // 마지막에 INSERT한 seq=2 가 가장 큰 id → 가장 먼저(최신) 나와야 한다
        assertThat(logs.map { it.metadata["seq"] }).containsExactly("2", "1", "0")
    }

    // ── limit ────────────────────────────────────────────────────────────────────

    @Test
    fun `findRecent 는 limit 건수만 반환한다`() {
        repeat(5) {
            service.record(
                AuthAuditLog(
                    userId = userId,
                    eventType = AuthEventType.PAT_USED,
                    providerId = "pat",
                ),
            )
        }

        val logs = service.findRecent(userId, limit = 2)

        assertThat(logs).hasSize(2)
    }

    // ── 사용자 격리 ────────────────────────────────────────────────────────────────

    @Test
    fun `findRecent 는 요청한 사용자 행만 반환한다`() {
        val otherUserId = UUID.randomUUID()
        service.record(
            AuthAuditLog(userId = userId, eventType = AuthEventType.LOGIN_SUCCESS, providerId = "local"),
        )
        service.record(
            AuthAuditLog(userId = otherUserId, eventType = AuthEventType.LOGIN_SUCCESS, providerId = "local"),
        )

        val logs = service.findRecent(userId, limit = 10)

        assertThat(logs).hasSize(1)
        assertThat(logs.first().userId).isEqualTo(userId)
    }

    // ── userId = null 영속/조회 (EC-2) ─────────────────────────────────────────────

    @Test
    fun `userId 가 null 인 이벤트를 영속하고 SQL 로 직접 조회한다`() {
        service.record(
            AuthAuditLog(
                userId = null,
                eventType = AuthEventType.LOGIN_FAILURE,
                providerId = "local",
                metadata = mapOf("username" to "ghost", "reason" to "INVALID_CREDENTIALS"),
            ),
        )

        // findRecent(userId)는 non-null userId 조회용이므로, userId=null 행은 SQL 로 직접 검증한다.
        val sql = "SELECT user_id, event_type, metadata FROM auth_audit_logs WHERE user_id IS NULL"
        val rows = jdbc.queryForList(sql, emptyMap<String, Any>())

        assertThat(rows).hasSize(1)
        assertThat(rows.first()["user_id"]).isNull()
        assertThat(rows.first()["event_type"]).isEqualTo("LOGIN_FAILURE")
    }

    // ── metadata JSONB 왕복 (EC-9) ────────────────────────────────────────────────

    @Test
    fun `빈 metadata 도 안전하게 왕복한다`() {
        service.record(
            AuthAuditLog(
                userId = userId,
                eventType = AuthEventType.LOGOUT,
                providerId = "local",
                metadata = emptyMap(),
            ),
        )

        val logs = service.findRecent(userId, limit = 10)

        assertThat(logs).hasSize(1)
        assertThat(logs.first().metadata).isEmpty()
    }

    // ── nullable 필드 왕복 ─────────────────────────────────────────────────────────

    @Test
    fun `ip userAgent deviceFingerprint 가 null 인 이벤트를 그대로 보존한다`() {
        service.record(
            AuthAuditLog(
                userId = userId,
                eventType = AuthEventType.LOGOUT_ALL_DEVICES,
                providerId = "local",
                ipAddress = null,
                userAgent = null,
                deviceFingerprint = null,
                metadata = mapOf("revokedCount" to "3"),
            ),
        )

        val logs = service.findRecent(userId, limit = 10)

        assertThat(logs).hasSize(1)
        val restored = logs.first()
        assertThat(restored.ipAddress).isNull()
        assertThat(restored.userAgent).isNull()
        assertThat(restored.deviceFingerprint).isNull()
        assertThat(restored.metadata["revokedCount"]).isEqualTo("3")
    }
}
