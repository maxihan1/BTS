// RefreshTokenService 단위 테스트 — rotation + replay 감지 (EC-23) + race-safe (EC-22) + 만료 (EC-04) 검증

package com.atlas.bts.identity.session

import com.atlas.bts.identity.audit.AuthAuditLog
import com.atlas.bts.identity.audit.AuthAuditLogService
import com.atlas.bts.identity.audit.AuthEventType
import com.atlas.bts.identity.jwt.JwtIssuer
import com.atlas.bts.identity.systemrole.SystemRoleAssignmentRepository
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * RefreshTokenService 단위 테스트 (FR-AU-09 Task 17 / SDD §19.5).
 *
 * ## 검증 케이스
 * - rotate 성공: 옛 토큰 무효화 + 새 access JWT + 새 refresh token 발급
 * - EC-04 (만료): 만료된 refresh 입력 → [RefreshTokenService.RotateResult.Failure] (Expired)
 * - revoked 케이스: revokedAt != null → [RefreshTokenService.RotateResult.Failure] (Revoked)
 * - EC-23 (replay 감지): used_at != null → session 전체 revoke + [RefreshTokenService.RotateResult.Failure] (Replay)
 * - EC-22 (race-safe): markUsedAndChain race loser → [RefreshTokenService.RotateResult.Failure] (Race)
 *
 * 통합 테스트(DB)는 RefreshTokenRepositoryTest(Task 9)가 담당.
 * 여기서는 MockK 로 의존성을 모킹하여 서비스 로직만 검증한다.
 */
class RefreshTokenServiceTest {

    private lateinit var repo: RefreshTokenRepository
    private lateinit var sessionService: SessionService
    private lateinit var jwtIssuer: JwtIssuer
    private lateinit var systemRoleAssignmentRepository: SystemRoleAssignmentRepository
    private lateinit var auditLog: AuthAuditLogService
    private lateinit var service: RefreshTokenService

    private val fixedNow: Instant = Instant.parse("2026-05-21T10:00:00Z")
    private val clock: Clock = Clock.fixed(fixedNow, ZoneOffset.UTC)

    private val sessionId: UUID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
    private val userId: UUID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb")
    private val oldTokenId: UUID = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc")

    /** 정상 사용 가능한 refresh token (미사용, 미만료) */
    private fun buildUsableToken(): RefreshToken =
        RefreshToken(
            id = oldTokenId,
            sessionId = sessionId,
            tokenHash = "a".repeat(64),
            issuedAt = fixedNow.minus(1, ChronoUnit.HOURS),
            expiresAt = fixedNow.plus(13, ChronoUnit.DAYS),
            usedAt = null,
            replacedBy = null,
        )

    @BeforeEach
    fun setUp() {
        repo = mockk()
        sessionService = mockk()
        jwtIssuer = mockk()
        systemRoleAssignmentRepository = mockk()
        auditLog = mockk(relaxed = true)
        // 기본값: 전역 역할 없음 (일반 사용자). 역할 의존 케이스는 개별 테스트에서 재정의.
        every { systemRoleAssignmentRepository.findRolesByUser(any()) } returns emptySet()
        service =
            RefreshTokenService(repo, sessionService, jwtIssuer, systemRoleAssignmentRepository, auditLog, clock)
    }

    // ── rotate 성공 ───────────────────────────────────────────────────────────

    @Test
    fun `rotate 성공 — 옛 토큰이 무효화되고 새 access JWT + 새 refresh token 이 반환된다`() {
        val oldToken = buildUsableToken()
        val newTokenSlot = slot<RefreshToken>()

        every { repo.findByTokenHash("a".repeat(64)) } returns oldToken
        every { sessionService.lookup(sessionId) } returns buildSession()
        every { repo.save(capture(newTokenSlot)) } answers { Unit }
        every { repo.markUsedAndChain(oldId = oldTokenId, newId = any()) } returns oldTokenId
        every { jwtIssuer.issue(any(), sessionId, any(), any(), any()) } returns "access.jwt.token"

        val result = service.rotate("a".repeat(64))

        assertThat(result).isInstanceOf(RefreshTokenService.RotateResult.Success::class.java)
        val success = result as RefreshTokenService.RotateResult.Success
        assertThat(success.accessToken).isEqualTo("access.jwt.token")
        assertThat(success.newRefreshTokenRaw).isNotBlank()

        // 새 토큰이 oldToken 의 sessionId 를 그대로 이어받아야 한다
        val newToken = newTokenSlot.captured
        assertThat(newToken.sessionId).isEqualTo(sessionId)
        assertThat(newToken.usedAt).isNull()
        assertThat(newToken.replacedBy).isNull()
        assertThat(newToken.expiresAt).isAfter(fixedNow)
    }

    @Test
    fun `rotate 성공 — markUsedAndChain 이 oldId 와 새 토큰 id 로 호출된다`() {
        val oldToken = buildUsableToken()
        val newIdSlot = slot<UUID>()

        every { repo.findByTokenHash("a".repeat(64)) } returns oldToken
        every { sessionService.lookup(sessionId) } returns buildSession()
        every { repo.save(any()) } answers { Unit }
        every { repo.markUsedAndChain(oldId = oldTokenId, newId = capture(newIdSlot)) } returns oldTokenId
        every { jwtIssuer.issue(any(), sessionId, any(), any(), any()) } returns "access.jwt.token"

        service.rotate("a".repeat(64))

        verify(exactly = 1) { repo.markUsedAndChain(oldId = oldTokenId, newId = newIdSlot.captured) }
    }

    @Test
    fun `rotate 성공 — replaced_by 와 used_at 이 설정된 새 토큰은 DB 에 먼저 저장된다`() {
        val oldToken = buildUsableToken()

        every { repo.findByTokenHash("a".repeat(64)) } returns oldToken
        every { sessionService.lookup(sessionId) } returns buildSession()
        every { repo.save(any()) } answers { Unit }
        every { repo.markUsedAndChain(any(), any()) } returns oldTokenId
        every { jwtIssuer.issue(any(), sessionId, any(), any(), any()) } returns "jwt"

        service.rotate("a".repeat(64))

        // save 가 markUsedAndChain 보다 먼저 호출돼야 한다 (체인 연결 전 새 토큰 존재 보장)
        io.mockk.verifyOrder {
            repo.save(any())
            repo.markUsedAndChain(any(), any())
        }
    }

    // ── EC-04 만료 ────────────────────────────────────────────────────────────

    @Test
    fun `EC-04 — 만료된 refresh token 입력 시 Expired 실패를 반환한다`() {
        val expiredToken = buildUsableToken().copy(
            expiresAt = fixedNow.minus(1, ChronoUnit.SECONDS),
        )
        every { repo.findByTokenHash("a".repeat(64)) } returns expiredToken

        val result = service.rotate("a".repeat(64))

        assertThat(result).isEqualTo(RefreshTokenService.RotateResult.Failure(RefreshTokenService.FailureReason.Expired))
        // 만료 케이스에서는 새 토큰 발급 없음
        verify(exactly = 0) { repo.save(any()) }
        verify(exactly = 0) { repo.markUsedAndChain(any(), any()) }
    }

    // ── revoked 케이스 ────────────────────────────────────────────────────────

    @Test
    fun `revoked — revokedAt 이 설정된 세션의 토큰 입력 시 Revoked 실패를 반환한다`() {
        // RefreshToken 자체에는 revokedAt 필드가 없다.
        // 세션이 폐기됐을 때의 행동은 Session 레벨에서 처리되므로,
        // 여기서는 토큰 레벨에서 usedAt != null (revoke 과정에서 표시됨) 케이스로 검증한다.
        // 단, "already used" (usedAt != null, replacedBy == null) 는 replay로 처리.
        // "revokedAt" 필드가 RefreshToken 에 없으므로 Session 조회 후 isActive 확인 케이스를 테스트.
        // 실제 구현에서 session.isActive 를 체크하도록 강제한다.
        val session = buildSession(revoked = true)
        val token = buildUsableToken()

        every { repo.findByTokenHash("a".repeat(64)) } returns token
        every { sessionService.lookup(sessionId) } returns session

        val result = service.rotate("a".repeat(64))

        assertThat(result).isEqualTo(RefreshTokenService.RotateResult.Failure(RefreshTokenService.FailureReason.Revoked))
        verify(exactly = 0) { repo.save(any()) }
    }

    // ── EC-23 replay 감지 ─────────────────────────────────────────────────────

    @Test
    fun `EC-23 replay 감지 — 이미 used_at 이 설정된 토큰 재제출 시 session 전체 revoke + Replay 실패`() {
        val usedToken = buildUsableToken().copy(
            usedAt = fixedNow.minus(5, ChronoUnit.MINUTES),
            replacedBy = UUID.randomUUID(),
        )
        every { repo.findByTokenHash("a".repeat(64)) } returns usedToken
        // 감사 주체 식별용 세션 조회 — 이미 폐기됐을 수 있어 null 반환 케이스 (FR-AU-10)
        every { sessionService.lookup(sessionId) } returns null
        every { repo.revokeChainFromSession(sessionId) } returns 1
        justRun { sessionService.revoke(sessionId, "REFRESH_REPLAY") }

        val result = service.rotate("a".repeat(64))

        assertThat(result).isEqualTo(RefreshTokenService.RotateResult.Failure(RefreshTokenService.FailureReason.Replay))
        // 핵심 보안 단언 — 세션 전체 폐기가 반드시 호출돼야 한다
        verify(exactly = 1) { repo.revokeChainFromSession(sessionId) }
        verify(exactly = 1) { sessionService.revoke(sessionId, "REFRESH_REPLAY") }
        // replay 케이스에서는 새 토큰 발급 없음
        verify(exactly = 0) { repo.save(any()) }
        verify(exactly = 0) { jwtIssuer.issue(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `EC-23 replay 감지 — replacedBy 만 설정된 경우에도 session 전체 revoke 가 실행된다`() {
        // replacedBy != null 이지만 usedAt == null 인 엣지 케이스 (비정상 상태) — 방어적으로 replay 처리
        val replacedToken = buildUsableToken().copy(
            replacedBy = UUID.randomUUID(),
        )
        every { repo.findByTokenHash("a".repeat(64)) } returns replacedToken
        // 감사 주체 식별용 세션 조회 — 이미 폐기됐을 수 있어 null 반환 케이스 (FR-AU-10)
        every { sessionService.lookup(sessionId) } returns null
        every { repo.revokeChainFromSession(sessionId) } returns 1
        justRun { sessionService.revoke(sessionId, "REFRESH_REPLAY") }

        val result = service.rotate("a".repeat(64))

        assertThat(result).isEqualTo(RefreshTokenService.RotateResult.Failure(RefreshTokenService.FailureReason.Replay))
        verify(exactly = 1) { repo.revokeChainFromSession(sessionId) }
        verify(exactly = 1) { sessionService.revoke(sessionId, "REFRESH_REPLAY") }
    }

    // ── EC-22 race-safe (concurrent rotate) ──────────────────────────────────

    @Test
    fun `EC-22 race loser — markUsedAndChain 이 null 반환하면 Race 실패를 반환한다`() {
        val oldToken = buildUsableToken()

        every { repo.findByTokenHash("a".repeat(64)) } returns oldToken
        every { sessionService.lookup(sessionId) } returns buildSession()
        every { repo.save(any()) } answers { Unit }
        // markUsedAndChain null → DB optimistic locking race loser
        every { repo.markUsedAndChain(any(), any()) } returns null
        every { repo.revokeChainFromSession(sessionId) } returns 1
        justRun { sessionService.revoke(sessionId, "REFRESH_REPLAY") }

        val result = service.rotate("a".repeat(64))

        assertThat(result).isEqualTo(RefreshTokenService.RotateResult.Failure(RefreshTokenService.FailureReason.Race))
        // race loser 에서도 세션을 revoke 해야 한다 (동시 rotate = replay 위험)
        verify(exactly = 1) { repo.revokeChainFromSession(sessionId) }
        verify(exactly = 1) { sessionService.revoke(sessionId, "REFRESH_REPLAY") }
        // access token 미발급
        verify(exactly = 0) { jwtIssuer.issue(any(), any(), any(), any(), any()) }
    }

    // ── FR-AU-10 감사 emit ────────────────────────────────────────────────────

    @Test
    fun `FR-AU-10 — rotate 성공 시 TOKEN_REFRESHED 를 emit 한다 (session userId + old new tokenId)`() {
        val oldToken = buildUsableToken()
        val newIdSlot = slot<UUID>()
        val eventSlot = slot<AuthAuditLog>()

        every { repo.findByTokenHash("a".repeat(64)) } returns oldToken
        every { sessionService.lookup(sessionId) } returns buildSession()
        every { repo.save(any()) } answers { Unit }
        every { repo.markUsedAndChain(oldId = oldTokenId, newId = capture(newIdSlot)) } returns oldTokenId
        every { jwtIssuer.issue(any(), sessionId, any(), any(), any()) } returns "access.jwt.token"
        justRun { auditLog.record(capture(eventSlot)) }

        service.rotate("a".repeat(64))

        verify(exactly = 1) { auditLog.record(any()) }
        val event = eventSlot.captured
        assertThat(event.eventType).isEqualTo(AuthEventType.TOKEN_REFRESHED)
        assertThat(event.userId).isEqualTo(userId)
        assertThat(event.providerId).isEqualTo("local")
        assertThat(event.metadata["oldTokenId"]).isEqualTo(oldTokenId.toString())
        assertThat(event.metadata["newTokenId"]).isEqualTo(newIdSlot.captured.toString())
    }

    @Test
    fun `FR-AU-10 — replay 분기에서 SUSPICIOUS_REFRESH_REPLAY 를 emit 한다 (reason=replay)`() {
        val usedToken =
            buildUsableToken().copy(
                usedAt = fixedNow.minus(5, ChronoUnit.MINUTES),
                replacedBy = UUID.randomUUID(),
            )
        val eventSlot = slot<AuthAuditLog>()

        every { repo.findByTokenHash("a".repeat(64)) } returns usedToken
        every { sessionService.lookup(sessionId) } returns buildSession()
        every { repo.revokeChainFromSession(sessionId) } returns 1
        justRun { sessionService.revoke(sessionId, "REFRESH_REPLAY") }
        justRun { auditLog.record(capture(eventSlot)) }

        service.rotate("a".repeat(64))

        verify(exactly = 1) { auditLog.record(any()) }
        val event = eventSlot.captured
        assertThat(event.eventType).isEqualTo(AuthEventType.SUSPICIOUS_REFRESH_REPLAY)
        assertThat(event.userId).isEqualTo(userId)
        assertThat(event.metadata["reason"]).isEqualTo("replay")
    }

    @Test
    fun `FR-AU-10 — race-loser 분기에서 SUSPICIOUS_REFRESH_REPLAY 를 emit 한다 (reason=race) (C-5)`() {
        val oldToken = buildUsableToken()
        val eventSlot = slot<AuthAuditLog>()

        every { repo.findByTokenHash("a".repeat(64)) } returns oldToken
        every { sessionService.lookup(sessionId) } returns buildSession()
        every { repo.save(any()) } answers { Unit }
        every { repo.markUsedAndChain(any(), any()) } returns null
        every { repo.revokeChainFromSession(sessionId) } returns 1
        justRun { sessionService.revoke(sessionId, "REFRESH_REPLAY") }
        justRun { auditLog.record(capture(eventSlot)) }

        service.rotate("a".repeat(64))

        verify(exactly = 1) { auditLog.record(any()) }
        val event = eventSlot.captured
        assertThat(event.eventType).isEqualTo(AuthEventType.SUSPICIOUS_REFRESH_REPLAY)
        assertThat(event.userId).isEqualTo(userId)
        assertThat(event.metadata["reason"]).isEqualTo("race")
    }

    // ── 존재하지 않는 토큰 ────────────────────────────────────────────────────

    @Test
    fun `존재하지 않는 token hash 입력 시 NotFound 실패를 반환한다`() {
        every { repo.findByTokenHash(any()) } returns null

        val result = service.rotate("b".repeat(64))

        assertThat(result).isEqualTo(RefreshTokenService.RotateResult.Failure(RefreshTokenService.FailureReason.NotFound))
        verify(exactly = 0) { repo.save(any()) }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun buildSession(revoked: Boolean = false): com.atlas.bts.identity.session.Session =
        Session(
            id = sessionId,
            userId = userId,
            providerId = "local",
            deviceFingerprint = null,
            ipAddress = null,
            userAgent = null,
            createdAt = fixedNow.minus(1, ChronoUnit.HOURS),
            expiresAt = fixedNow.plus(13, ChronoUnit.DAYS),
            lastSeenAt = fixedNow,
            revokedAt = if (revoked) fixedNow.minus(30, ChronoUnit.SECONDS) else null,
            revokeReason = if (revoked) "test_revoke" else null,
        )
}
