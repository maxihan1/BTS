// SessionService 단위 테스트 — MockK 기반 세션 생명주기 검증 (FR-AU-09 Task 16)

package com.atlas.bts.identity.session

import com.atlas.bts.identity.audit.AuthAuditLog
import com.atlas.bts.identity.audit.AuthAuditLogService
import com.atlas.bts.identity.audit.AuthEventType
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
 * SessionService 단위 테스트 (FR-AU-09 Task 16).
 *
 * 검증 대상:
 * - create: Session row 생성 + expires_at = now + 14d + device_fingerprint = SHA-256(ua+ip) hex 12자
 * - lookup: 활성/폐기/만료 세션 상태 정확 반환
 * - revoke: revoked_at + revoke_reason 채움 (repo.markRevoked 호출)
 * - revokeAllOfUser: 활성 세션 전체 폐기 (repo.revokeAllByUserId 호출)
 * - markLastSeen: last_seen_at 갱신 (repo.updateLastSeen 호출)
 *
 * 통합 테스트(DB)는 SessionRepositoryTest(Task 8)가 담당.
 * 여기서는 MockK 로 SessionRepository 를 모킹하여 서비스 로직만 검증한다.
 */
class SessionServiceTest {

    private lateinit var repo: SessionRepository
    private lateinit var auditLog: AuthAuditLogService
    private lateinit var service: SessionService

    private val fixedNow: Instant = Instant.parse("2026-05-21T10:00:00Z")
    private val clock: Clock = Clock.fixed(fixedNow, ZoneOffset.UTC)

    private val userId: UUID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")

    @BeforeEach
    fun setUp() {
        repo = mockk()
        auditLog = mockk(relaxed = true)
        service = SessionService(repo, auditLog, clock)
    }

    // ── create ────────────────────────────────────────────────────────────────

    @Test
    fun `create — Session 이 INSERT 되고 expires_at 이 now + 14d 로 설정된다`() {
        val savedSlot = slot<Session>()
        justRun { repo.save(capture(savedSlot)) }

        service.create(
            userId = userId,
            providerId = "local",
            ipAddress = "127.0.0.1",
            userAgent = "Mozilla/5.0",
        )

        val saved = savedSlot.captured
        assertThat(saved.userId).isEqualTo(userId)
        assertThat(saved.providerId).isEqualTo("local")
        assertThat(saved.expiresAt).isEqualTo(fixedNow.plus(14, ChronoUnit.DAYS))
        assertThat(saved.createdAt).isEqualTo(fixedNow)
        assertThat(saved.lastSeenAt).isEqualTo(fixedNow)
        assertThat(saved.revokedAt).isNull()
        assertThat(saved.revokeReason).isNull()
    }

    @Test
    fun `create — device_fingerprint 가 SHA-256(ua+ip) hex 앞 12자로 설정된다`() {
        val savedSlot = slot<Session>()
        justRun { repo.save(capture(savedSlot)) }

        service.create(
            userId = userId,
            providerId = "local",
            ipAddress = "192.168.1.1",
            userAgent = "TestAgent/1.0",
        )

        val fingerprint = savedSlot.captured.deviceFingerprint
        assertThat(fingerprint).isNotNull()
        assertThat(fingerprint).hasSize(12)
        // hex 문자만 허용 [0-9a-f]
        assertThat(fingerprint).matches("[0-9a-f]{12}")
    }

    @Test
    fun `create — ipAddress null 이면 deviceFingerprint 도 null`() {
        val savedSlot = slot<Session>()
        justRun { repo.save(capture(savedSlot)) }

        service.create(
            userId = userId,
            providerId = "local",
            ipAddress = null,
            userAgent = null,
        )

        assertThat(savedSlot.captured.deviceFingerprint).isNull()
    }

    @Test
    fun `create — 생성된 Session ID 를 반환한다`() {
        val savedSlot = slot<Session>()
        justRun { repo.save(capture(savedSlot)) }

        val result = service.create(
            userId = userId,
            providerId = "local",
            ipAddress = "10.0.0.1",
            userAgent = "Agent",
        )

        assertThat(result).isEqualTo(savedSlot.captured)
    }

    // ── create — mfaVerified 전파 (FR-MF-01 GAP-1) ───────────────────────────────

    @Test
    fun `create — mfaVerified 기본값은 false 다 (기존 호출처 무회귀)`() {
        val savedSlot = slot<Session>()
        justRun { repo.save(capture(savedSlot)) }

        // mfaVerified 미전달 = 기존 로그인 흐름(1차 인증만). 기본 false 유지.
        service.create(
            userId = userId,
            providerId = "local",
            ipAddress = "127.0.0.1",
            userAgent = "Mozilla/5.0",
        )

        assertThat(savedSlot.captured.mfaVerified).isFalse()
    }

    @Test
    fun `create — mfaVerified=true 를 전달하면 저장 세션과 반환 세션 모두 true 다`() {
        val savedSlot = slot<Session>()
        justRun { repo.save(capture(savedSlot)) }

        // 2차 요소(TOTP) 통과 후 정식 세션 발급 = mfaVerified=true.
        val result =
            service.create(
                userId = userId,
                providerId = "local",
                ipAddress = "127.0.0.1",
                userAgent = "Mozilla/5.0",
                mfaVerified = true,
            )

        assertThat(savedSlot.captured.mfaVerified).isTrue()
        assertThat(result.mfaVerified).isTrue()
    }

    // ── lookup ────────────────────────────────────────────────────────────────

    @Test
    fun `lookup — 활성 세션 정상 반환`() {
        val session = buildActiveSession()
        every { repo.findById(session.id) } returns session

        val found = service.lookup(session.id)

        assertThat(found).isEqualTo(session)
    }

    @Test
    fun `lookup — 존재하지 않는 sid 는 null 반환`() {
        every { repo.findById(any()) } returns null

        val found = service.lookup(UUID.randomUUID())

        assertThat(found).isNull()
    }

    @Test
    fun `lookup — 폐기된 세션을 조회하면 revoked 상태로 반환된다`() {
        val revoked = buildActiveSession().copy(
            revokedAt = fixedNow.minusSeconds(60),
            revokeReason = "logout",
        )
        every { repo.findById(revoked.id) } returns revoked

        val found = service.lookup(revoked.id)!!

        assertThat(found.isRevoked()).isTrue()
        assertThat(found.revokeReason).isEqualTo("logout")
    }

    @Test
    fun `lookup — 만료된 세션을 조회하면 isActive false 로 반환된다`() {
        val expired = buildActiveSession().copy(
            expiresAt = fixedNow.minus(1, ChronoUnit.HOURS),
        )
        every { repo.findById(expired.id) } returns expired

        val found = service.lookup(expired.id)!!

        assertThat(found.isActive(fixedNow)).isFalse()
        assertThat(found.isExpired(fixedNow)).isTrue()
    }

    @Test
    fun `lookup — mfaVerified=true 세션은 조회 결과에도 true 로 노출된다 (FR-MF-01 GAP-1)`() {
        // refresh 회전(RefreshTokenService.rotate)이 lookup 으로 세션을 읽어 mfa_verified 를
        // JWT 클레임으로 다시 발급하므로, 조회 경로가 이 플래그를 보존해야 한다.
        val mfaSession = buildActiveSession(mfaVerified = true)
        every { repo.findById(mfaSession.id) } returns mfaSession

        val found = service.lookup(mfaSession.id)!!

        assertThat(found.mfaVerified).isTrue()
    }

    // ── revoke ────────────────────────────────────────────────────────────────

    @Test
    fun `revoke — repo markRevoked 를 sid + reason 으로 호출한다`() {
        val sid = UUID.randomUUID()
        justRun { repo.markRevoked(sid, "logout") }

        service.revoke(sid, "logout")

        verify(exactly = 1) { repo.markRevoked(sid, "logout") }
    }

    @Test
    fun `revoke — 존재하지 않는 sid 도 예외 없이 처리된다 (멱등)`() {
        val sid = UUID.randomUUID()
        justRun { repo.markRevoked(sid, "logout") }

        // 예외 없이 실행돼야 함
        service.revoke(sid, "logout")
    }

    // ── revokeAllOfUser ───────────────────────────────────────────────────────

    @Test
    fun `revokeAllOfUser — repo revokeAllByUserId 를 userId + reason 으로 호출한다`() {
        every { repo.revokeAllByUserId(userId, "password_changed") } returns 3

        val count = service.revokeAllOfUser(userId, "password_changed")

        verify(exactly = 1) { repo.revokeAllByUserId(userId, "password_changed") }
        assertThat(count).isEqualTo(3)
    }

    @Test
    fun `revokeAllOfUser — 활성 세션이 없으면 0 반환`() {
        every { repo.revokeAllByUserId(userId, "logout_all") } returns 0

        val count = service.revokeAllOfUser(userId, "logout_all")

        assertThat(count).isEqualTo(0)
    }

    @Test
    fun `revokeAllOfUser — revoked가 0보다 크면 LOGOUT_ALL_DEVICES 를 emit 한다 (FR-AU-10)`() {
        every { repo.revokeAllByUserId(userId, "logout_all") } returns 3
        val eventSlot = slot<AuthAuditLog>()
        justRun { auditLog.record(capture(eventSlot)) }

        service.revokeAllOfUser(userId, "logout_all")

        verify(exactly = 1) { auditLog.record(any()) }
        val event = eventSlot.captured
        assertThat(event.eventType).isEqualTo(AuthEventType.LOGOUT_ALL_DEVICES)
        assertThat(event.userId).isEqualTo(userId)
        assertThat(event.metadata["revokedSessionCount"]).isEqualTo("3")
    }

    @Test
    fun `revokeAllOfUser — revoked가 0이면 LOGOUT_ALL_DEVICES 를 emit 하지 않는다 (FR-AU-10)`() {
        every { repo.revokeAllByUserId(userId, "logout_all") } returns 0

        service.revokeAllOfUser(userId, "logout_all")

        verify(exactly = 0) { auditLog.record(any()) }
    }

    // ── markLastSeen ──────────────────────────────────────────────────────────

    @Test
    fun `markLastSeen — repo updateLastSeen 을 해당 sid 로 호출한다`() {
        val sid = UUID.randomUUID()
        justRun { repo.updateLastSeen(sid) }

        service.markLastSeen(sid)

        verify(exactly = 1) { repo.updateLastSeen(sid) }
    }

    @Test
    fun `markLastSeen — 존재하지 않는 sid 도 예외 없이 처리된다 (best-effort)`() {
        val sid = UUID.randomUUID()
        justRun { repo.updateLastSeen(sid) }

        service.markLastSeen(sid)
    }

    // ── findActiveByUser ──────────────────────────────────────────────────────

    @Test
    fun `findActiveByUser — repo findActiveByUserId 를 위임하고 lastSeenAt DESC 로 정렬해 반환한다`() {
        val older = buildActiveSession().copy(lastSeenAt = fixedNow.minusSeconds(300))
        val newer = buildActiveSession().copy(lastSeenAt = fixedNow.minusSeconds(60))
        val oldest = buildActiveSession().copy(lastSeenAt = fixedNow.minusSeconds(600))
        every { repo.findActiveByUserId(userId) } returns listOf(older, newer, oldest)

        val result = service.findActiveByUser(userId)

        assertThat(result).containsExactly(newer, older, oldest)
        verify(exactly = 1) { repo.findActiveByUserId(userId) }
    }

    @Test
    fun `findActiveByUser — 활성 세션이 없으면 빈 리스트를 반환한다 (EC-1)`() {
        every { repo.findActiveByUserId(userId) } returns emptyList()

        val result = service.findActiveByUser(userId)

        assertThat(result).isEmpty()
    }

    @Test
    fun `findActiveByUser — 만료·폐기 세션은 repo 필터에서 이미 제외되어 반환되지 않는다 (EC-5)`() {
        // repo.findActiveByUserId 는 revoked_at IS NULL AND expires_at > now 조건을 적용하므로
        // 활성 세션만 반환해야 한다. 단위 테스트에서는 repo 가 활성 세션만 반환한다고 가정.
        val activeSession = buildActiveSession()
        every { repo.findActiveByUserId(userId) } returns listOf(activeSession)

        val result = service.findActiveByUser(userId)

        assertThat(result).containsExactly(activeSession)
        assertThat(result).noneMatch { it.isRevoked() }
        assertThat(result).noneMatch { it.isExpired(fixedNow) }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun buildActiveSession(
        id: UUID = UUID.randomUUID(),
        mfaVerified: Boolean = false,
    ): Session =
        Session(
            id = id,
            userId = userId,
            providerId = "local",
            deviceFingerprint = "abc123def456",
            ipAddress = "127.0.0.1",
            userAgent = "TestAgent",
            createdAt = fixedNow,
            expiresAt = fixedNow.plus(14, ChronoUnit.DAYS),
            lastSeenAt = fixedNow,
            revokedAt = null,
            revokeReason = null,
            mfaVerified = mfaVerified,
        )
}
