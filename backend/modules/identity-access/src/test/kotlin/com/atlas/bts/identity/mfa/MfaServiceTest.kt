// MfaService 단위 테스트 — TOTP 설정/활성화/로그인검증/비활성화 오케스트레이션 + 감사 emit (FR-MF-01 Task 8)

package com.atlas.bts.identity.mfa

import com.atlas.bts.identity.audit.AuthAuditLog
import com.atlas.bts.identity.audit.AuthAuditLogService
import com.atlas.bts.identity.audit.AuthEventType
import dev.samstevens.totp.code.DefaultCodeGenerator
import dev.samstevens.totp.code.HashingAlgorithm
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
import java.util.UUID

/**
 * [MfaService] 단위 테스트 (FR-MF-01 Task 8).
 *
 * TotpService(연산) + TotpSecretRepository(영속) + MfaSecretEncryptor(암호화) +
 * MfaAttemptLimiter(rate-limit) + AuthAuditLogService(감사)를 조립한 오케스트레이션을 검증한다.
 *
 * ## 검증 대상
 * - setup: secret 암호화 저장(PENDING) + otpauth/QR 반환, 이미 ACTIVE면 AlreadyEnabled
 * - enable: 코드 검증→ACTIVE + reset + MFA_ENABLED emit, 오답→recordFailure + InvalidCode,
 *   limiter 차단→TooManyAttempts, PENDING 없으면 NoPending
 * - verifyLogin: 코드+replay step 검증→Success + reset + MFA_CHALLENGE_SUCCESS emit,
 *   오답→recordFailure + MFA_CHALLENGE_FAILURE emit + InvalidCode, replay→InvalidCode,
 *   limiter 차단→TooManyAttempts
 * - disable: 코드 검증→삭제 + MFA_DISABLED emit, 오답→InvalidCode, 미활성→NotEnabled
 * - isEnabled: ACTIVE 여부
 *
 * ## 결정성
 * 실제 [TotpService] 를 고정 [Clock] 으로 주입해 결정적으로 검증한다. 정답 코드는 라이브러리
 * [DefaultCodeGenerator] 로 동일 파라미터·time-step 에 직접 계산한다(하드코딩 상수 회피).
 * [MfaSecretEncryptor] 는 키 설정이 필요하므로 mock 으로 두고 round-trip(encrypt/decrypt)을 흉내 낸다.
 *
 * ## 감사 emit
 * [AuthAuditLogService] 는 relaxed mock 으로 두고 [slot] 캡처로 이벤트 유형/주체를 단언한다
 * (secret·code 등 비밀값은 metadata 에 담지 않음을 함께 확인).
 */
class MfaServiceTest {
    private val fixedNow: Instant = Instant.parse("2024-06-10T07:33:20Z") // epochSecond=1718004800
    private val clock: Clock = Clock.fixed(fixedNow, ZoneOffset.UTC)
    private val totpService = TotpService(clock)
    private val codeGenerator = DefaultCodeGenerator(HashingAlgorithm.SHA1, 6)

    private lateinit var repo: TotpSecretRepository
    private lateinit var encryptor: MfaSecretEncryptor
    private lateinit var limiter: MfaAttemptLimiter
    private lateinit var auditLog: AuthAuditLogService
    private lateinit var service: MfaService

    private val userId: UUID = UUID.fromString("11111111-1111-1111-1111-111111111111")
    private val label = "alice@example.com"

    // 유효한 base32 secret (라이브러리 코드 생성기 입력으로 사용).
    private val knownSecret = "ABCDEFGHIJKLMNOP"
    private val cipher = "deadbeef"

    @BeforeEach
    fun setUp() {
        repo = mockk(relaxed = true)
        encryptor = mockk()
        limiter = mockk(relaxed = true)
        auditLog = mockk(relaxed = true)
        service = MfaService(totpService, repo, encryptor, limiter, auditLog, clock)
    }

    /** 현재 fixed time-step 의 정답 코드. */
    private fun validCode(): String = codeGenerator.generate(knownSecret, totpService.currentTimeStep(fixedNow))

    private fun pendingSecret(): TotpSecret =
        TotpSecret(
            userId = userId,
            secretCipher = cipher,
            status = TotpStatus.PENDING,
            lastVerifiedStep = null,
            confirmedAt = null,
            createdAt = fixedNow,
            updatedAt = fixedNow,
        )

    private fun activeSecret(lastVerifiedStep: Long? = null): TotpSecret =
        pendingSecret().copy(
            status = TotpStatus.ACTIVE,
            lastVerifiedStep = lastVerifiedStep,
            confirmedAt = fixedNow,
        )

    // ── setup ─────────────────────────────────────────────────────────────────

    @Test
    fun `setup — 새 secret 을 암호화해 PENDING 으로 저장하고 otpauth·QR 을 반환한다`() {
        every { repo.findByUser(userId) } returns null
        every { encryptor.encrypt(any()) } returns cipher
        val cipherSlot = slot<String>()
        justRun { repo.upsertPending(userId, capture(cipherSlot)) }

        val result = service.setup(userId, label)

        assertThat(result).isInstanceOf(MfaService.SetupResult.Created::class.java)
        val created = result as MfaService.SetupResult.Created
        assertThat(created.otpauthUri).startsWith("otpauth://totp/BTS:alice%40example.com?")
        assertThat(created.qrPngDataUri).startsWith("data:image/png;base64,")
        // 평문 secret(base32)을 setup 응답에 그대로 노출해 QR 스캔 불가 환경의 수동입력 fallback 을 지원한다(spec §API).
        assertThat(created.secretBase32).isNotBlank()
        // 응답 secret 과 otpauth URI 의 secret 파라미터가 동일해야 한다(같은 secret).
        assertThat(created.otpauthUri).contains("secret=${created.secretBase32}")
        // 저장된 값은 암호문이어야 한다(평문 secret 저장 금지 — §1.1.1).
        assertThat(cipherSlot.captured).isEqualTo(cipher)
        verify(exactly = 1) { repo.upsertPending(userId, cipher) }
    }

    @Test
    fun `setup — 평문 secret 을 그대로 저장하지 않는다 (암호화 거친다)`() {
        every { repo.findByUser(userId) } returns null
        val plainSlot = slot<String>()
        every { encryptor.encrypt(capture(plainSlot)) } returns cipher
        justRun { repo.upsertPending(userId, any()) }

        service.setup(userId, label)

        // encryptor 입력(평문 secret)과 저장 입력(암호문)이 달라야 한다.
        verify(exactly = 1) { encryptor.encrypt(plainSlot.captured) }
        verify(exactly = 1) { repo.upsertPending(userId, cipher) }
    }

    @Test
    fun `setup — 이미 ACTIVE 면 AlreadyEnabled 를 반환하고 덮어쓰지 않는다`() {
        every { repo.findByUser(userId) } returns activeSecret()

        val result = service.setup(userId, label)

        assertThat(result).isEqualTo(MfaService.SetupResult.AlreadyEnabled)
        verify(exactly = 0) { repo.upsertPending(any(), any()) }
    }

    @Test
    fun `setup — PENDING 상태면 re-setup 을 허용한다 (새 secret 으로 덮어씀)`() {
        every { repo.findByUser(userId) } returns pendingSecret()
        every { encryptor.encrypt(any()) } returns cipher
        justRun { repo.upsertPending(userId, any()) }

        val result = service.setup(userId, label)

        assertThat(result).isInstanceOf(MfaService.SetupResult.Created::class.java)
        verify(exactly = 1) { repo.upsertPending(userId, cipher) }
    }

    // ── enable ────────────────────────────────────────────────────────────────

    @Test
    fun `enable — 정답 코드면 ACTIVE 로 전이하고 limiter reset 후 MFA_ENABLED 를 emit 한다`() {
        every { limiter.isBlocked(userId) } returns false
        every { repo.findByUser(userId) } returns pendingSecret()
        every { encryptor.decrypt(cipher) } returns knownSecret
        justRun { repo.activate(userId) }
        val eventSlot = slot<AuthAuditLog>()
        justRun { auditLog.record(capture(eventSlot)) }

        val result = service.enable(userId, validCode())

        assertThat(result).isEqualTo(MfaService.EnableResult.Success)
        verify(exactly = 1) { repo.activate(userId) }
        verify(exactly = 1) { limiter.reset(userId) }
        verify(exactly = 0) { limiter.recordFailure(userId) }
        val event = eventSlot.captured
        assertThat(event.eventType).isEqualTo(AuthEventType.MFA_ENABLED)
        assertThat(event.userId).isEqualTo(userId)
        // 비밀값·코드가 metadata 에 새지 않아야 한다(§1.1.2).
        assertThat(event.metadata.values).noneMatch { it.contains(knownSecret) }
    }

    @Test
    fun `enable — 오답 코드면 recordFailure 후 InvalidCode 를 반환하고 활성화하지 않는다`() {
        every { limiter.isBlocked(userId) } returns false
        every { repo.findByUser(userId) } returns pendingSecret()
        every { encryptor.decrypt(cipher) } returns knownSecret

        val result = service.enable(userId, "000000")

        assertThat(result).isEqualTo(MfaService.EnableResult.InvalidCode)
        verify(exactly = 0) { repo.activate(userId) }
        verify(exactly = 1) { limiter.recordFailure(userId) }
        verify(exactly = 0) { auditLog.record(any()) }
    }

    @Test
    fun `enable — limiter 가 차단 상태면 TooManyAttempts 를 반환한다`() {
        every { limiter.isBlocked(userId) } returns true

        val result = service.enable(userId, validCode())

        assertThat(result).isEqualTo(MfaService.EnableResult.TooManyAttempts)
        verify(exactly = 0) { repo.findByUser(userId) }
        verify(exactly = 0) { repo.activate(userId) }
    }

    @Test
    fun `enable — PENDING secret 이 없으면 NoPending 을 반환한다`() {
        every { limiter.isBlocked(userId) } returns false
        every { repo.findByUser(userId) } returns null

        val result = service.enable(userId, validCode())

        assertThat(result).isEqualTo(MfaService.EnableResult.NoPending)
        verify(exactly = 0) { repo.activate(userId) }
    }

    @Test
    fun `enable — 이미 ACTIVE 면 NoPending 을 반환한다 (재활성화 차단)`() {
        every { limiter.isBlocked(userId) } returns false
        every { repo.findByUser(userId) } returns activeSecret()

        val result = service.enable(userId, validCode())

        assertThat(result).isEqualTo(MfaService.EnableResult.NoPending)
        verify(exactly = 0) { repo.activate(userId) }
    }

    // ── verifyLogin ─────────────────────────────────────────────────────────────

    @Test
    fun `verifyLogin — 정답 코드면 step 전진 후 Success + reset + MFA_CHALLENGE_SUCCESS emit`() {
        every { limiter.isBlocked(userId) } returns false
        every { repo.findByUser(userId) } returns activeSecret()
        every { encryptor.decrypt(cipher) } returns knownSecret
        val expectedStep = totpService.currentTimeStep(fixedNow)
        every { repo.advanceVerifiedStep(userId, any()) } returns true
        val eventSlot = slot<AuthAuditLog>()
        justRun { auditLog.record(capture(eventSlot)) }

        val result = service.verifyLogin(userId, validCode())

        assertThat(result).isEqualTo(MfaService.VerifyResult.Success)
        // replay 방어 — 성공한 time-step 을 조건부 기록.
        verify(exactly = 1) { repo.advanceVerifiedStep(userId, expectedStep) }
        verify(exactly = 1) { limiter.reset(userId) }
        assertThat(eventSlot.captured.eventType).isEqualTo(AuthEventType.MFA_CHALLENGE_SUCCESS)
        assertThat(eventSlot.captured.userId).isEqualTo(userId)
    }

    @Test
    fun `verifyLogin — 정답이라도 step 재사용(replay)이면 InvalidCode 를 반환한다`() {
        every { limiter.isBlocked(userId) } returns false
        every { repo.findByUser(userId) } returns activeSecret()
        every { encryptor.decrypt(cipher) } returns knownSecret
        // advanceVerifiedStep=false → 이미 같거나 큰 step 기록됨(replay).
        every { repo.advanceVerifiedStep(userId, any()) } returns false

        val result = service.verifyLogin(userId, validCode())

        assertThat(result).isEqualTo(MfaService.VerifyResult.InvalidCode)
        verify(exactly = 1) { limiter.recordFailure(userId) }
        verify(exactly = 0) { limiter.reset(userId) }
    }

    @Test
    fun `verifyLogin — 오답 코드면 recordFailure + MFA_CHALLENGE_FAILURE emit + InvalidCode`() {
        every { limiter.isBlocked(userId) } returns false
        every { repo.findByUser(userId) } returns activeSecret()
        every { encryptor.decrypt(cipher) } returns knownSecret
        val eventSlot = slot<AuthAuditLog>()
        justRun { auditLog.record(capture(eventSlot)) }

        val result = service.verifyLogin(userId, "000000")

        assertThat(result).isEqualTo(MfaService.VerifyResult.InvalidCode)
        verify(exactly = 1) { limiter.recordFailure(userId) }
        verify(exactly = 0) { repo.advanceVerifiedStep(any(), any()) }
        assertThat(eventSlot.captured.eventType).isEqualTo(AuthEventType.MFA_CHALLENGE_FAILURE)
    }

    @Test
    fun `verifyLogin — limiter 가 차단 상태면 TooManyAttempts 를 반환한다`() {
        every { limiter.isBlocked(userId) } returns true

        val result = service.verifyLogin(userId, validCode())

        assertThat(result).isEqualTo(MfaService.VerifyResult.TooManyAttempts)
        verify(exactly = 0) { repo.findByUser(userId) }
        verify(exactly = 0) { repo.advanceVerifiedStep(any(), any()) }
    }

    @Test
    fun `verifyLogin — ACTIVE secret 이 없으면 NotEnabled 를 반환한다`() {
        every { limiter.isBlocked(userId) } returns false
        every { repo.findByUser(userId) } returns null

        val result = service.verifyLogin(userId, validCode())

        assertThat(result).isEqualTo(MfaService.VerifyResult.NotEnabled)
    }

    @Test
    fun `verifyLogin — PENDING secret 은 2단계 로그인에 쓸 수 없어 NotEnabled 를 반환한다`() {
        every { limiter.isBlocked(userId) } returns false
        every { repo.findByUser(userId) } returns pendingSecret()

        val result = service.verifyLogin(userId, validCode())

        assertThat(result).isEqualTo(MfaService.VerifyResult.NotEnabled)
    }

    // ── disable ───────────────────────────────────────────────────────────────

    @Test
    fun `disable — 정답 코드면 secret 을 삭제하고 MFA_DISABLED 를 emit 한다`() {
        every { limiter.isBlocked(userId) } returns false
        every { repo.findByUser(userId) } returns activeSecret()
        every { encryptor.decrypt(cipher) } returns knownSecret
        every { repo.deleteByUser(userId) } returns true
        val eventSlot = slot<AuthAuditLog>()
        justRun { auditLog.record(capture(eventSlot)) }

        val result = service.disable(userId, validCode())

        assertThat(result).isEqualTo(MfaService.DisableResult.Success)
        verify(exactly = 1) { repo.deleteByUser(userId) }
        verify(exactly = 1) { limiter.reset(userId) }
        assertThat(eventSlot.captured.eventType).isEqualTo(AuthEventType.MFA_DISABLED)
        assertThat(eventSlot.captured.userId).isEqualTo(userId)
    }

    @Test
    fun `disable — 오답 코드면 InvalidCode 를 반환하고 삭제하지 않는다 (무단 비활성화 차단)`() {
        every { limiter.isBlocked(userId) } returns false
        every { repo.findByUser(userId) } returns activeSecret()
        every { encryptor.decrypt(cipher) } returns knownSecret

        val result = service.disable(userId, "000000")

        assertThat(result).isEqualTo(MfaService.DisableResult.InvalidCode)
        verify(exactly = 0) { repo.deleteByUser(userId) }
        verify(exactly = 1) { limiter.recordFailure(userId) }
        verify(exactly = 0) { auditLog.record(any()) }
    }

    @Test
    fun `disable — 미활성(없음)이면 NotEnabled 를 반환한다`() {
        every { limiter.isBlocked(userId) } returns false
        every { repo.findByUser(userId) } returns null

        val result = service.disable(userId, validCode())

        assertThat(result).isEqualTo(MfaService.DisableResult.NotEnabled)
        verify(exactly = 0) { repo.deleteByUser(userId) }
    }

    @Test
    fun `disable — PENDING(미확인)이면 NotEnabled 를 반환한다`() {
        every { limiter.isBlocked(userId) } returns false
        every { repo.findByUser(userId) } returns pendingSecret()

        val result = service.disable(userId, validCode())

        assertThat(result).isEqualTo(MfaService.DisableResult.NotEnabled)
        verify(exactly = 0) { repo.deleteByUser(userId) }
    }

    @Test
    fun `disable — limiter 가 차단 상태면 TooManyAttempts 를 반환한다`() {
        every { limiter.isBlocked(userId) } returns true

        val result = service.disable(userId, validCode())

        assertThat(result).isEqualTo(MfaService.DisableResult.TooManyAttempts)
        verify(exactly = 0) { repo.deleteByUser(userId) }
    }

    // ── isEnabled ─────────────────────────────────────────────────────────────

    @Test
    fun `isEnabled — ACTIVE 면 true`() {
        every { repo.findByUser(userId) } returns activeSecret()

        assertThat(service.isEnabled(userId)).isTrue()
    }

    @Test
    fun `isEnabled — PENDING 이면 false`() {
        every { repo.findByUser(userId) } returns pendingSecret()

        assertThat(service.isEnabled(userId)).isFalse()
    }

    @Test
    fun `isEnabled — secret 이 없으면 false`() {
        every { repo.findByUser(userId) } returns null

        assertThat(service.isEnabled(userId)).isFalse()
    }
}
