// TrustedDeviceService 단위 테스트 — 신뢰 등록/우회검증·갱신/목록/취소(단건·전체) + 감사 emit + Clock 주입 (FR-MF-05 Task 4)

package com.atlas.bts.identity.mfa

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
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * [TrustedDeviceService] 단위 테스트 (FR-MF-05 Task 4).
 *
 * TrustedDeviceRepository(영속) + AuthAuditLogService(감사) + 주입 [Clock] 을 조립한 오케스트레이션을
 * 검증한다. 시각 비교는 모두 주입 Clock 기준이라 [Clock.fixed] 로 고정해 time-bomb 회귀를 차단한다
 * (authcontroller-revokesession-timebomb 교훈).
 *
 * ## 검증 대상
 * - trust: rawToken 반환 + repo.insert(hash·createdAt=now·expiresAt=now+30일·label=UA) + TRUSTED_DEVICE_ADDED emit.
 * - verifyAndTouch: 유효(user 일치+미만료) true + updateLastUsedAt; 만료/타인user/미상 false(touch 없음).
 * - list: repo.listByUser(userId, now) 위임.
 * - revoke: 소유 true + TRUSTED_DEVICE_REVOKED emit, 타인 false + 미emit.
 * - revokeAll: count>0 이면 TRUSTED_DEVICE_REVOKED emit(metadata count 만), 0 이면 미emit.
 *
 * ## 비밀값 미로깅 (§1.1.2)
 * rawToken/tokenHash 는 감사 metadata 에 담기지 않는다(count 만 기록).
 */
class TrustedDeviceServiceTest {
    private lateinit var repo: TrustedDeviceRepository
    private lateinit var auditLog: AuthAuditLogService
    private lateinit var service: TrustedDeviceService

    private val userId: UUID = UUID.fromString("11111111-1111-1111-1111-111111111111")
    private val otherUserId: UUID = UUID.fromString("99999999-9999-9999-9999-999999999999")

    // 시각은 주입 Clock 으로 고정 (time-bomb 회피).
    private val now: Instant = Instant.parse("2026-06-13T00:00:00Z")
    private val clock: Clock = Clock.fixed(now, ZoneOffset.UTC)

    @BeforeEach
    fun setUp() {
        repo = mockk(relaxed = true)
        auditLog = mockk(relaxed = true)
        service = TrustedDeviceService(repo, clock, auditLog)
    }

    private fun device(
        owner: UUID = userId,
        tokenHash: String,
        expiresAt: Instant = now.plus(Duration.ofDays(30)),
    ): TrustedDevice =
        TrustedDevice(
            id = UUID.randomUUID(),
            userId = owner,
            tokenHash = tokenHash,
            label = "Mozilla/5.0",
            createdAt = now,
            expiresAt = expiresAt,
            lastUsedAt = null,
        )

    // ── trust ──────────────────────────────────────────────────────────────

    @Test
    fun `trust — rawToken 을 반환하고 createdAt=now·expiresAt=now+30일·label=UA 로 insert 한다`() {
        val deviceSlot = slot<TrustedDevice>()
        justRun { repo.insert(capture(deviceSlot)) }

        val raw = service.trust(userId, "Mozilla/5.0")

        // rawToken 은 hex 64자 — DB 에는 그 해시만 저장된다(§1.1.1).
        assertThat(raw).hasSize(64)
        val inserted = deviceSlot.captured
        assertThat(inserted.userId).isEqualTo(userId)
        assertThat(inserted.tokenHash).isEqualTo(TrustedDeviceToken.hash(raw))
        assertThat(inserted.createdAt).isEqualTo(now)
        assertThat(inserted.expiresAt).isEqualTo(now.plus(Duration.ofDays(30)))
        assertThat(inserted.label).isEqualTo("Mozilla/5.0")
        verify(exactly = 1) { repo.insert(any()) }
    }

    @Test
    fun `trust — 256자를 넘는 User-Agent 는 256자로 잘라 label 로 저장한다 (저장형 XSS 표면 축소)`() {
        val deviceSlot = slot<TrustedDevice>()
        justRun { repo.insert(capture(deviceSlot)) }
        // 길이 300 의 적대적 User-Agent — defense-in-depth 로 백엔드에서 256자 cap.
        val longUserAgent = "x".repeat(300)

        service.trust(userId, longUserAgent)

        val inserted = deviceSlot.captured
        assertThat(inserted.label).hasSize(256)
        assertThat(inserted.label).isEqualTo("x".repeat(256))
    }

    @Test
    fun `trust — TRUSTED_DEVICE_ADDED 를 emit 하고 비밀값을 metadata 에 담지 않는다`() {
        val eventSlot = slot<AuthAuditLog>()
        justRun { auditLog.record(capture(eventSlot)) }

        val raw = service.trust(userId, "Mozilla/5.0")

        assertThat(eventSlot.captured.eventType).isEqualTo(AuthEventType.TRUSTED_DEVICE_ADDED)
        assertThat(eventSlot.captured.userId).isEqualTo(userId)
        // rawToken/해시가 metadata 에 새지 않아야 한다(§1.1.2).
        val hash = TrustedDeviceToken.hash(raw)
        assertThat(eventSlot.captured.metadata.values).noneMatch { it.contains(raw) || it.contains(hash) }
    }

    // ── verifyAndTouch ─────────────────────────────────────────────────────

    @Test
    fun `verifyAndTouch — user 일치 + 미만료 + 갱신 1행이면 true 이고 updateLastUsedAt 호출`() {
        val raw = "a".repeat(64)
        val hash = TrustedDeviceToken.hash(raw)
        val found = device(tokenHash = hash, expiresAt = now.plus(Duration.ofDays(1)))
        every { repo.findByTokenHash(hash) } returns found
        // 행이 여전히 존재 → 갱신 1행 → 우회 허용.
        every { repo.updateLastUsedAt(found.id, now) } returns 1

        val result = service.verifyAndTouch(userId, raw)

        assertThat(result).isTrue()
        verify(exactly = 1) { repo.updateLastUsedAt(found.id, now) }
    }

    @Test
    fun `verifyAndTouch — 읽기 후 행이 삭제되어 갱신 0행이면 false 이다 (TOCTOU 우회 차단)`() {
        val raw = "a".repeat(64)
        val hash = TrustedDeviceToken.hash(raw)
        val found = device(tokenHash = hash, expiresAt = now.plus(Duration.ofDays(1)))
        every { repo.findByTokenHash(hash) } returns found
        // findByTokenHash 읽기와 updateLastUsedAt 쓰기 사이 revoke/revokeAll(DELETE) 커밋 → 0행.
        every { repo.updateLastUsedAt(found.id, now) } returns 0

        val result = service.verifyAndTouch(userId, raw)

        // 행이 그 사이 사라졌으므로 우회 불가 — fail-safe 로 거부한다.
        assertThat(result).isFalse()
        verify(exactly = 1) { repo.updateLastUsedAt(found.id, now) }
    }

    @Test
    fun `verifyAndTouch — 만료 디바이스면 false 이고 touch 하지 않는다`() {
        val raw = "b".repeat(64)
        val hash = TrustedDeviceToken.hash(raw)
        // expiresAt == now 는 만료(EC10 경계).
        val found = device(tokenHash = hash, expiresAt = now)
        every { repo.findByTokenHash(hash) } returns found

        val result = service.verifyAndTouch(userId, raw)

        assertThat(result).isFalse()
        verify(exactly = 0) { repo.updateLastUsedAt(any(), any()) }
    }

    @Test
    fun `verifyAndTouch — 타인 user 의 토큰이면 false 이고 touch 하지 않는다 (user-bound)`() {
        val raw = "c".repeat(64)
        val hash = TrustedDeviceToken.hash(raw)
        val found = device(owner = otherUserId, tokenHash = hash, expiresAt = now.plus(Duration.ofDays(1)))
        every { repo.findByTokenHash(hash) } returns found

        val result = service.verifyAndTouch(userId, raw)

        assertThat(result).isFalse()
        verify(exactly = 0) { repo.updateLastUsedAt(any(), any()) }
    }

    @Test
    fun `verifyAndTouch — 미상(조회 부재) 토큰이면 false 이고 touch 하지 않는다 (fail-safe)`() {
        val raw = "d".repeat(64)
        val hash = TrustedDeviceToken.hash(raw)
        every { repo.findByTokenHash(hash) } returns null

        val result = service.verifyAndTouch(userId, raw)

        assertThat(result).isFalse()
        verify(exactly = 0) { repo.updateLastUsedAt(any(), any()) }
    }

    // ── list ───────────────────────────────────────────────────────────────

    @Test
    fun `list — repo_listByUser(userId, now) 결과를 그대로 반환한다`() {
        val devices = listOf(device(tokenHash = "e".repeat(64)), device(tokenHash = "f".repeat(64)))
        every { repo.listByUser(userId, now) } returns devices

        val result = service.list(userId)

        assertThat(result).isEqualTo(devices)
        verify(exactly = 1) { repo.listByUser(userId, now) }
    }

    // ── revoke (단건) ──────────────────────────────────────────────────────

    @Test
    fun `revoke — 소유 디바이스면 true 이고 TRUSTED_DEVICE_REVOKED emit`() {
        val id = UUID.randomUUID()
        every { repo.deleteByIdAndUser(userId, id) } returns true
        val eventSlot = slot<AuthAuditLog>()
        justRun { auditLog.record(capture(eventSlot)) }

        val result = service.revoke(userId, id)

        assertThat(result).isTrue()
        assertThat(eventSlot.captured.eventType).isEqualTo(AuthEventType.TRUSTED_DEVICE_REVOKED)
        assertThat(eventSlot.captured.userId).isEqualTo(userId)
    }

    @Test
    fun `revoke — 타인·미존재면 false 이고 emit 하지 않는다 (IDOR 차단)`() {
        val id = UUID.randomUUID()
        every { repo.deleteByIdAndUser(userId, id) } returns false

        val result = service.revoke(userId, id)

        assertThat(result).isFalse()
        verify(exactly = 0) { auditLog.record(any()) }
    }

    // ── revokeAll (전체) ───────────────────────────────────────────────────

    @Test
    fun `revokeAll — count 가 0 보다 크면 TRUSTED_DEVICE_REVOKED 를 emit 한다 (metadata count 만)`() {
        every { repo.deleteAllByUser(userId) } returns 3
        val eventSlot = slot<AuthAuditLog>()
        justRun { auditLog.record(capture(eventSlot)) }

        val result = service.revokeAll(userId)

        assertThat(result).isEqualTo(3)
        assertThat(eventSlot.captured.eventType).isEqualTo(AuthEventType.TRUSTED_DEVICE_REVOKED)
        assertThat(eventSlot.captured.userId).isEqualTo(userId)
        // metadata 는 count 만 — 비밀값(rawToken/hash) 미포함(§1.1.2).
        assertThat(eventSlot.captured.metadata["count"]).isEqualTo("3")
    }

    @Test
    fun `revokeAll — 삭제 0건이면 emit 하지 않는다`() {
        every { repo.deleteAllByUser(userId) } returns 0

        val result = service.revokeAll(userId)

        assertThat(result).isEqualTo(0)
        verify(exactly = 0) { auditLog.record(any()) }
    }
}
