// MfaBackupCodeService 단위 테스트 — 백업 코드 발급/재발급·검증소진·상태 + 감사 emit + 백업 전용 rate-limit (FR-MF-02 Task 5)

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
import java.time.Instant
import java.util.UUID

/**
 * [MfaBackupCodeService] 단위 테스트 (FR-MF-02 Task 5).
 *
 * BackupCodeGenerator(생성) + BackupCodeHasher(해시) + MfaBackupCodeRepository(영속) +
 * BackupCodeAttemptLimiter(백업 전용 rate-limit) + AuthAuditLogService(감사) +
 * TotpSecretRepository(ACTIVE 확인)를 조립한 오케스트레이션을 검증한다.
 *
 * ## 검증 대상
 * - generateOrRegenerate: TOTP ACTIVE 아니면 NotActive, ACTIVE면 generator→hasher→replaceAll +
 *   평문 10개 반환 + MFA_BACKUP_CODES_GENERATED emit. 평문/해시는 metadata 에 미포함(§1.1.2).
 * - verifyAndConsume: limiter 차단→TooManyAttempts, consume true→Success + reset +
 *   MFA_BACKUP_CODE_USED emit, false→InvalidCode + recordFailure.
 * - status: countTotal>0 → generated, countUnused → remaining.
 *
 * ## 백업 전용 limiter (TOTP 와 독립)
 * [BackupCodeAttemptLimiter] 는 relaxed mock 으로 두고 isBlocked/recordFailure/reset 호출을 단언한다.
 * TOTP rate-limit(MfaAttemptLimiter)과 독립 카운터임을 타입 분리로 보장한다.
 */
class MfaBackupCodeServiceTest {
    private lateinit var generator: BackupCodeGenerator
    private lateinit var hasher: BackupCodeHasher
    private lateinit var repo: MfaBackupCodeRepository
    private lateinit var limiter: BackupCodeAttemptLimiter
    private lateinit var auditLog: AuthAuditLogService
    private lateinit var totpRepo: TotpSecretRepository
    private lateinit var service: MfaBackupCodeService

    private val userId: UUID = UUID.fromString("22222222-2222-2222-2222-222222222222")

    @BeforeEach
    fun setUp() {
        generator = mockk()
        hasher = mockk()
        repo = mockk(relaxed = true)
        limiter = mockk(relaxed = true)
        auditLog = mockk(relaxed = true)
        totpRepo = mockk(relaxed = true)
        service = MfaBackupCodeService(generator, hasher, repo, limiter, auditLog, totpRepo)
    }

    private fun activeTotp(): TotpSecret =
        TotpSecret(
            userId = userId,
            secretCipher = "cipher",
            status = TotpStatus.ACTIVE,
            lastVerifiedStep = null,
            confirmedAt = Instant.EPOCH,
            createdAt = Instant.EPOCH,
            updatedAt = Instant.EPOCH,
        )

    private fun pendingTotp(): TotpSecret = activeTotp().copy(status = TotpStatus.PENDING, confirmedAt = null)

    // ── generateOrRegenerate ────────────────────────────────────────────────────

    @Test
    fun `generateOrRegenerate — TOTP ACTIVE 면 10개를 생성·해시해 replaceAll 하고 평문 10개를 반환한다`() {
        val plainCodes = (1..10).map { "CODE$it" }
        val hashes = plainCodes.map { "hash-$it" }
        every { totpRepo.findByUser(userId) } returns activeTotp()
        every { generator.generate() } returns plainCodes
        plainCodes.forEachIndexed { i, c -> every { hasher.hash(c) } returns hashes[i] }
        justRun { repo.replaceAll(userId, any()) }

        val result = service.generateOrRegenerate(userId)

        assertThat(result).isInstanceOf(MfaBackupCodeService.GenerateResult.Generated::class.java)
        val generated = result as MfaBackupCodeService.GenerateResult.Generated
        assertThat(generated.codes).isEqualTo(plainCodes)
        // 저장은 해시 묶음만(평문 미저장 — §1.1.1).
        verify(exactly = 1) { repo.replaceAll(userId, hashes) }
    }

    @Test
    fun `generateOrRegenerate — TOTP ACTIVE 면 MFA_BACKUP_CODES_GENERATED 를 emit 한다`() {
        every { totpRepo.findByUser(userId) } returns activeTotp()
        every { generator.generate() } returns listOf("X")
        every { hasher.hash("X") } returns "h"
        val eventSlot = slot<AuthAuditLog>()
        justRun { auditLog.record(capture(eventSlot)) }

        service.generateOrRegenerate(userId)

        assertThat(eventSlot.captured.eventType).isEqualTo(AuthEventType.MFA_BACKUP_CODES_GENERATED)
        assertThat(eventSlot.captured.userId).isEqualTo(userId)
        // 평문 코드/해시가 metadata 에 새지 않아야 한다(§1.1.2).
        assertThat(eventSlot.captured.metadata.values).noneMatch { it.contains("X") || it.contains("h") }
    }

    @Test
    fun `generateOrRegenerate — TOTP 가 PENDING 이면 NotActive 를 반환하고 발급하지 않는다`() {
        every { totpRepo.findByUser(userId) } returns pendingTotp()

        val result = service.generateOrRegenerate(userId)

        assertThat(result).isEqualTo(MfaBackupCodeService.GenerateResult.NotActive)
        verify(exactly = 0) { generator.generate() }
        verify(exactly = 0) { repo.replaceAll(any(), any()) }
        verify(exactly = 0) { auditLog.record(any()) }
    }

    @Test
    fun `generateOrRegenerate — TOTP secret 이 없으면 NotActive 를 반환한다`() {
        every { totpRepo.findByUser(userId) } returns null

        val result = service.generateOrRegenerate(userId)

        assertThat(result).isEqualTo(MfaBackupCodeService.GenerateResult.NotActive)
        verify(exactly = 0) { repo.replaceAll(any(), any()) }
    }

    // ── verifyAndConsume ────────────────────────────────────────────────────────

    @Test
    fun `verifyAndConsume — 미사용 코드면 소진 성공 + limiter reset + MFA_BACKUP_CODE_USED emit`() {
        val plain = "a3k9f-2m7qx"
        every { hasher.hash(plain) } returns "hashed"
        every { repo.consumeIfUnused(userId, "hashed") } returns true
        val eventSlot = slot<AuthAuditLog>()
        justRun { auditLog.record(capture(eventSlot)) }

        val result = service.verifyAndConsume(userId, plain)

        assertThat(result).isEqualTo(MfaBackupCodeService.VerifyResult.Success)
        verify(exactly = 1) { repo.consumeIfUnused(userId, "hashed") }
        verify(exactly = 1) { limiter.reset(userId) }
        verify(exactly = 0) { limiter.recordFailure(userId) }
        assertThat(eventSlot.captured.eventType).isEqualTo(AuthEventType.MFA_BACKUP_CODE_USED)
        assertThat(eventSlot.captured.userId).isEqualTo(userId)
        // 평문 코드/해시가 metadata 에 새지 않아야 한다(§1.1.2).
        assertThat(eventSlot.captured.metadata.values).noneMatch { it.contains(plain) || it.contains("hashed") }
    }

    @Test
    fun `verifyAndConsume — 일치 코드가 없으면 InvalidCode + recordFailure (감사 미emit)`() {
        val plain = "wrong-code0"
        every { hasher.hash(plain) } returns "nohash"
        every { repo.consumeIfUnused(userId, "nohash") } returns false

        val result = service.verifyAndConsume(userId, plain)

        assertThat(result).isEqualTo(MfaBackupCodeService.VerifyResult.InvalidCode)
        verify(exactly = 1) { limiter.recordFailure(userId) }
        verify(exactly = 0) { limiter.reset(userId) }
        verify(exactly = 0) { auditLog.record(any()) }
    }

    @Test
    fun `verifyAndConsume — limiter 가 차단 상태면 TooManyAttempts 를 반환하고 소진을 시도하지 않는다`() {
        every { limiter.isBlocked(userId) } returns true

        val result = service.verifyAndConsume(userId, "a3k9f-2m7qx")

        assertThat(result).isEqualTo(MfaBackupCodeService.VerifyResult.TooManyAttempts)
        verify(exactly = 0) { repo.consumeIfUnused(any(), any()) }
        verify(exactly = 0) { hasher.hash(any()) }
    }

    @Test
    fun `verifyAndConsume — 차단 아니면 isBlocked 를 먼저 확인한다`() {
        every { limiter.isBlocked(userId) } returns false
        every { hasher.hash(any()) } returns "h"
        every { repo.consumeIfUnused(userId, "h") } returns true
        justRun { auditLog.record(any()) }

        service.verifyAndConsume(userId, "code")

        verify(exactly = 1) { limiter.isBlocked(userId) }
    }

    // ── status ──────────────────────────────────────────────────────────────────

    @Test
    fun `status — countTotal 이 0 보다 크면 generated=true 이고 remaining 은 countUnused`() {
        every { repo.countTotal(userId) } returns 10
        every { repo.countUnused(userId) } returns 7

        val status = service.status(userId)

        assertThat(status.generated).isTrue()
        assertThat(status.remaining).isEqualTo(7)
    }

    @Test
    fun `status — 발급된 적 없으면 generated=false, remaining=0`() {
        every { repo.countTotal(userId) } returns 0
        every { repo.countUnused(userId) } returns 0

        val status = service.status(userId)

        assertThat(status.generated).isFalse()
        assertThat(status.remaining).isEqualTo(0)
    }
}
