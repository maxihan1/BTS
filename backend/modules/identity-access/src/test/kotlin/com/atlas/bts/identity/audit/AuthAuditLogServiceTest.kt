// AuthAuditLogService 단위 테스트 — 12종 enum + record/findRecent 계약 검증 (FR-09-31 + FR-PM-01)

package com.atlas.bts.identity.audit

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

class AuthAuditLogServiceTest {

    private lateinit var service: AuthAuditLogService

    @BeforeEach
    fun setUp() {
        service = InMemoryAuthAuditLogService()
    }

    // ── AuthEventType 12종 enum 망라 ──────────────────────────────

    @Test
    fun `AuthEventType 은 12종을 정확히 포함한다`() {
        val expected = setOf(
            "LOGIN_SUCCESS",
            "LOGIN_FAILURE",
            "LOGOUT",
            "LOGOUT_ALL_DEVICES",
            "TOKEN_REFRESHED",
            "SUSPICIOUS_REFRESH_REPLAY",
            "USER_PROVISIONED",
            "PAT_USED",
            "LDAP_UNAVAILABLE",
            "PROJECT_MEMBER_ADDED",
            "PROJECT_ROLE_CHANGED",
            "PROJECT_MEMBER_REMOVED",
        )
        val actual = AuthEventType.entries.map { it.name }.toSet()
        assertThat(actual).isEqualTo(expected)
    }

    // ── record + findRecent 기본 흐름 ────────────────────────────

    @Test
    fun `record 후 findRecent 로 조회되어야 한다`() {
        val userId = UUID.randomUUID()
        val event = AuthAuditLog(
            userId = userId,
            eventType = AuthEventType.LOGIN_SUCCESS,
            providerId = "local",
            ipAddress = "127.0.0.1",
            userAgent = "Mozilla/5.0",
            deviceFingerprint = null,
            metadata = emptyMap(),
        )

        service.record(event)

        val logs = service.findRecent(userId, limit = 10)
        assertThat(logs).hasSize(1)
        assertThat(logs.first().eventType).isEqualTo(AuthEventType.LOGIN_SUCCESS)
    }

    @Test
    fun `findRecent — 가장 최근 이벤트가 첫 번째로 반환된다`() {
        val userId = UUID.randomUUID()

        service.record(AuthAuditLog(userId = userId, eventType = AuthEventType.LOGIN_SUCCESS, providerId = "local"))
        service.record(AuthAuditLog(userId = userId, eventType = AuthEventType.LOGOUT, providerId = "local"))

        val logs = service.findRecent(userId, limit = 10)
        assertThat(logs.first().eventType).isEqualTo(AuthEventType.LOGOUT)
    }

    @Test
    fun `findRecent — limit 이 적용되어야 한다`() {
        val userId = UUID.randomUUID()

        repeat(5) {
            service.record(AuthAuditLog(userId = userId, eventType = AuthEventType.LOGIN_FAILURE, providerId = "local"))
        }

        val logs = service.findRecent(userId, limit = 3)
        assertThat(logs).hasSize(3)
    }

    @Test
    fun `findRecent — 다른 userId 의 로그는 반환되지 않는다`() {
        val userA = UUID.randomUUID()
        val userB = UUID.randomUUID()

        service.record(AuthAuditLog(userId = userA, eventType = AuthEventType.LOGIN_SUCCESS, providerId = "local"))
        service.record(AuthAuditLog(userId = userB, eventType = AuthEventType.LOGOUT, providerId = "local"))

        val logsA = service.findRecent(userA, limit = 10)
        assertThat(logsA).hasSize(1)
        assertThat(logsA.first().userId).isEqualTo(userA)
    }

    // ── 각 EventType 별 record 가능 확인 ────────────────────────

    @Test
    fun `모든 12종 EventType 을 record 할 수 있다`() {
        val userId = UUID.randomUUID()

        AuthEventType.entries.forEach { eventType ->
            service.record(
                AuthAuditLog(
                    userId = userId,
                    eventType = eventType,
                    providerId = "local",
                    metadata = mapOf("test" to eventType.name),
                )
            )
        }

        val logs = service.findRecent(userId, limit = 20)
        assertThat(logs).hasSize(12)
        val recordedTypes = logs.map { it.eventType }.toSet()
        assertThat(recordedTypes).isEqualTo(AuthEventType.entries.toSet())
    }

    // ── thread-safety 기초 확인 ──────────────────────────────────

    @Test
    fun `동시 record 호출 시 데이터 유실 없이 저장된다`() {
        val userId = UUID.randomUUID()
        val threads = (1..20).map {
            Thread {
                service.record(
                    AuthAuditLog(
                        userId = userId,
                        eventType = AuthEventType.LOGIN_SUCCESS,
                        providerId = "local",
                    )
                )
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }

        val logs = service.findRecent(userId, limit = 100)
        assertThat(logs).hasSize(20)
    }

    // ── metadata / nullable 필드 ─────────────────────────────────

    @Test
    fun `metadata 는 빈 map 이 기본값이고 null 필드는 허용된다`() {
        val userId = UUID.randomUUID()
        val event = AuthAuditLog(
            userId = userId,
            eventType = AuthEventType.SUSPICIOUS_REFRESH_REPLAY,
            providerId = "local",
            ipAddress = null,
            userAgent = null,
            deviceFingerprint = null,
        )

        service.record(event)

        val log = service.findRecent(userId, limit = 1).first()
        assertThat(log.ipAddress).isNull()
        assertThat(log.userAgent).isNull()
        assertThat(log.metadata).isEmpty()
    }
}
