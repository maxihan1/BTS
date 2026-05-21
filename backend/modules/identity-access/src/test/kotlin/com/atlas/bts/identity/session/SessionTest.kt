// Session 도메인 엔티티 단위 테스트 — 필드 11개 / isActive 만료·폐기 경계 검증 (SDD 19.5)

package com.atlas.bts.identity.session

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class SessionTest {

    private val fixedNow = Instant.parse("2026-05-20T12:00:00Z")
    private val userId = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private val sessionId = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001")

    private fun activeSession(
        expiresAt: Instant = fixedNow.plusSeconds(3600),
        revokedAt: Instant? = null,
        revokeReason: String? = null,
    ) = Session(
        id = sessionId,
        userId = userId,
        providerId = "local",
        deviceFingerprint = "fp-abc123",
        ipAddress = "127.0.0.1",
        userAgent = "Mozilla/5.0",
        createdAt = fixedNow.minusSeconds(60),
        expiresAt = expiresAt,
        lastSeenAt = fixedNow.minusSeconds(10),
        revokedAt = revokedAt,
        revokeReason = revokeReason,
    )

    // ── 필드 노출 ──────────────────────────────────────────────

    @Test
    fun `Session 생성 후 11개 필드가 정상 노출된다`() {
        val session = activeSession()

        assertEquals(sessionId, session.id)
        assertEquals(userId, session.userId)
        assertEquals("local", session.providerId)
        assertEquals("fp-abc123", session.deviceFingerprint)
        assertEquals("127.0.0.1", session.ipAddress)
        assertEquals("Mozilla/5.0", session.userAgent)
        assertEquals(fixedNow.minusSeconds(60), session.createdAt)
        assertEquals(fixedNow.plusSeconds(3600), session.expiresAt)
        assertEquals(fixedNow.minusSeconds(10), session.lastSeenAt)
        assertNull(session.revokedAt)
        assertNull(session.revokeReason)
    }

    @Test
    fun `nullable 필드는 null 허용된다`() {
        val session = Session(
            id = sessionId,
            userId = userId,
            providerId = "ldap-corp",
            deviceFingerprint = null,
            ipAddress = null,
            userAgent = null,
            createdAt = fixedNow,
            expiresAt = fixedNow.plusSeconds(100),
            lastSeenAt = fixedNow,
            revokedAt = null,
            revokeReason = null,
        )

        assertNull(session.deviceFingerprint)
        assertNull(session.ipAddress)
        assertNull(session.userAgent)
        assertNull(session.revokedAt)
        assertNull(session.revokeReason)
    }

    // ── isActive ───────────────────────────────────────────────

    @Test
    fun `isActive — revokedAt null이고 expiresAt이 now 이후이면 true`() {
        val session = activeSession(expiresAt = fixedNow.plusSeconds(1))
        assertTrue(session.isActive(fixedNow))
    }

    @Test
    fun `isActive — revokedAt이 설정되면 만료 전이어도 false`() {
        val session = activeSession(
            expiresAt = fixedNow.plusSeconds(3600),
            revokedAt = fixedNow.minusSeconds(1),
            revokeReason = "ADMIN_REVOKE",
        )
        assertFalse(session.isActive(fixedNow))
    }

    @Test
    fun `isActive — expiresAt이 now와 같으면 false (경계값, 만료 포함)`() {
        val session = activeSession(expiresAt = fixedNow)
        assertFalse(session.isActive(fixedNow))
    }

    @Test
    fun `isActive — expiresAt이 now보다 과거이면 false`() {
        val session = activeSession(expiresAt = fixedNow.minusSeconds(1))
        assertFalse(session.isActive(fixedNow))
    }

    @Test
    fun `isActive — revokedAt 설정 + expiresAt 과거이면 false`() {
        val session = activeSession(
            expiresAt = fixedNow.minusSeconds(10),
            revokedAt = fixedNow.minusSeconds(20),
        )
        assertFalse(session.isActive(fixedNow))
    }

    // ── isExpired ──────────────────────────────────────────────

    @Test
    fun `isExpired — expiresAt이 now보다 미래이면 false`() {
        val session = activeSession(expiresAt = fixedNow.plusSeconds(1))
        assertFalse(session.isExpired(fixedNow))
    }

    @Test
    fun `isExpired — expiresAt이 now와 같으면 true (경계값)`() {
        val session = activeSession(expiresAt = fixedNow)
        assertTrue(session.isExpired(fixedNow))
    }

    @Test
    fun `isExpired — expiresAt이 now보다 과거이면 true`() {
        val session = activeSession(expiresAt = fixedNow.minusSeconds(1))
        assertTrue(session.isExpired(fixedNow))
    }

    // ── isRevoked ──────────────────────────────────────────────

    @Test
    fun `isRevoked — revokedAt null이면 false`() {
        val session = activeSession(revokedAt = null)
        assertFalse(session.isRevoked())
    }

    @Test
    fun `isRevoked — revokedAt 설정 시 true`() {
        val session = activeSession(revokedAt = fixedNow.minusSeconds(5), revokeReason = "LOGOUT")
        assertTrue(session.isRevoked())
    }
}
