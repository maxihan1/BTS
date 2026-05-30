// 비밀번호 변경 서비스 단위 테스트 — 정책·동일성·인증 실패 + 세션 무효화 7개 케이스

package com.atlas.bts.identity.credential

import com.atlas.bts.identity.session.RefreshTokenRepository
import com.atlas.bts.identity.session.Session
import com.atlas.bts.identity.session.SessionService
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * ChangePasswordService 단위 테스트.
 *
 * - LocalCredentialService, SessionService, RefreshTokenRepository 는 모두 mockk 으로 대체한다.
 * - 검증 순서: policy → same → rotate → 세션무효화.
 * - CharArray wipe: early-return 경로(policy 위반·same)에서도 finally 가 wipe 를 보장해야 한다.
 */
class ChangePasswordServiceTest {

    private val localCredentialService = mockk<LocalCredentialService>(relaxed = true)
    private val sessionService = mockk<SessionService>(relaxed = true)
    private val refreshTokenRepository = mockk<RefreshTokenRepository>(relaxed = true)

    private val sut = ChangePasswordService(
        localCredentialService = localCredentialService,
        sessionService = sessionService,
        refreshTokenRepository = refreshTokenRepository,
    )

    private val userId = UUID.randomUUID()
    private val currentSid = UUID.randomUUID()

    /** 정책을 통과하는 새 비밀번호 (12자 이상, 대소문자+숫자+특수문자) */
    private fun validNew() = "NewValid@1234".toCharArray()

    /** 현재 비밀번호 (현재 세션 식별자와 별개) */
    private fun currentPw() = "OldP@ssw0rd!1".toCharArray()

    private fun makeSession(id: UUID = UUID.randomUUID()): Session =
        Session(
            id = id,
            userId = userId,
            providerId = "local",
            deviceFingerprint = null,
            ipAddress = null,
            userAgent = null,
            createdAt = Instant.now(),
            expiresAt = Instant.now().plusSeconds(3600),
            lastSeenAt = Instant.now(),
            revokedAt = null,
            revokeReason = null,
        )

    @BeforeEach
    fun setUp() {
        clearMocks(localCredentialService, sessionService, refreshTokenRepository)
    }

    // ── 1. 정책 위반 ──────────────────────────────────────────────────────────

    @Test
    fun `정책 위반 시 PolicyViolation 반환, rotate·세션무효화 미호출`() {
        val tooShort = "short".toCharArray()   // MIN_LENGTH 미달

        val result = sut.change(userId, currentSid, currentPw(), tooShort)

        assertThat(result).isInstanceOf(ChangePasswordResult.PolicyViolation::class.java)
        val pv = result as ChangePasswordResult.PolicyViolation
        assertThat(pv.violations).isNotEmpty

        verify(exactly = 0) { localCredentialService.rotate(any(), any(), any()) }
        verify(exactly = 0) { sessionService.revoke(any(), any()) }
        verify(exactly = 0) { refreshTokenRepository.revokeChainFromSession(any()) }
    }

    // ── 2. new == current 평문 동일 ──────────────────────────────────────────

    @Test
    fun `new와 current 평문이 동일하면 SameAsCurrent 반환, rotate 미호출`() {
        val pw = "OldP@ssw0rd!1".toCharArray()
        val pwCopy = "OldP@ssw0rd!1".toCharArray()   // 내용 동일, 별도 배열

        val result = sut.change(userId, currentSid, pw, pwCopy)

        assertThat(result).isEqualTo(ChangePasswordResult.SameAsCurrent)

        verify(exactly = 0) { localCredentialService.rotate(any(), any(), any()) }
    }

    // ── 3. current 불일치 (rotate = false) ──────────────────────────────────

    @Test
    fun `rotate가 false 반환 시 CurrentMismatch, 세션 무효화 미호출`() {
        every { localCredentialService.rotate(userId, any(), any()) } returns false

        val result = sut.change(userId, currentSid, currentPw(), validNew())

        assertThat(result).isEqualTo(ChangePasswordResult.CurrentMismatch)

        verify(exactly = 0) { sessionService.revoke(any(), any()) }
        verify(exactly = 0) { refreshTokenRepository.revokeChainFromSession(any()) }
    }

    // ── 4. 성공 — 다른 세션 무효화 ───────────────────────────────────────────

    @Test
    fun `성공 시 현재 sid 제외한 각 세션마다 revoke + revokeChainFromSession 쌍 호출`() {
        val otherSid1 = UUID.randomUUID()
        val otherSid2 = UUID.randomUUID()
        val otherSession1 = makeSession(otherSid1)
        val otherSession2 = makeSession(otherSid2)
        val currentSession = makeSession(currentSid)

        every { localCredentialService.rotate(userId, any(), any()) } returns true
        every { sessionService.findActiveByUser(userId) } returns
            listOf(currentSession, otherSession1, otherSession2)

        val result = sut.change(userId, currentSid, currentPw(), validNew())

        assertThat(result).isEqualTo(ChangePasswordResult.Success)

        // 현재 세션은 무효화하지 않음
        verify(exactly = 0) { sessionService.revoke(currentSid, any()) }
        verify(exactly = 0) { refreshTokenRepository.revokeChainFromSession(currentSid) }

        // 다른 세션 2개 각각 쌍 호출
        verify(exactly = 1) { sessionService.revoke(otherSid1, ChangePasswordService.REVOKE_REASON) }
        verify(exactly = 1) { refreshTokenRepository.revokeChainFromSession(otherSid1) }
        verify(exactly = 1) { sessionService.revoke(otherSid2, ChangePasswordService.REVOKE_REASON) }
        verify(exactly = 1) { refreshTokenRepository.revokeChainFromSession(otherSid2) }
    }

    // ── 5. 성공 — 다른 세션 0개 ─────────────────────────────────────────────

    @Test
    fun `성공 시 다른 세션이 0개여도 정상 Success`() {
        every { localCredentialService.rotate(userId, any(), any()) } returns true
        every { sessionService.findActiveByUser(userId) } returns listOf(makeSession(currentSid))

        val result = sut.change(userId, currentSid, currentPw(), validNew())

        assertThat(result).isEqualTo(ChangePasswordResult.Success)
        verify(exactly = 0) { sessionService.revoke(any(), any()) }
        verify(exactly = 0) { refreshTokenRepository.revokeChainFromSession(any()) }
    }

    // ── 6. 검증 순서 ─────────────────────────────────────────────────────────

    @Test
    fun `검증 순서 — policy 위반 시 rotate 미도달, rotate 성공 후에만 세션무효화`() {
        // 정책 통과 + rotate 성공 시나리오로 순서 검증
        every { localCredentialService.rotate(userId, any(), any()) } returns true
        every { sessionService.findActiveByUser(userId) } returns emptyList()

        sut.change(userId, currentSid, currentPw(), validNew())

        // rotate 호출 후 findActiveByUser 호출 순서 보장
        verifyOrder {
            localCredentialService.rotate(userId, any(), any())
            sessionService.findActiveByUser(userId)
        }
    }

    // ── 7. early-return 경로에서도 CharArray wipe ────────────────────────────

    @Test
    fun `policy 위반 early-return 경로에서도 current와 new CharArray가 wipe됨`() {
        val currentArr = "OldP@ssw0rd!1".toCharArray()
        val newArr = "short".toCharArray()   // 정책 위반

        sut.change(userId, currentSid, currentArr, newArr)

        // wipe 후에는 배열 내용이 원본과 달라야 한다 (모두 동일 문자로 채워짐)
        assertThat(currentArr).doesNotContain('O', 'l', 'd')
        assertThat(newArr).doesNotContain('s', 'h', 'o', 'r', 't')
    }

    @Test
    fun `SameAsCurrent early-return 경로에서도 CharArray가 wipe됨`() {
        val pw1 = "OldP@ssw0rd!1".toCharArray()
        val pw2 = "OldP@ssw0rd!1".toCharArray()

        sut.change(userId, currentSid, pw1, pw2)

        assertThat(pw1).doesNotContain('O', 'l', 'd')
        assertThat(pw2).doesNotContain('O', 'l', 'd')
    }
}
