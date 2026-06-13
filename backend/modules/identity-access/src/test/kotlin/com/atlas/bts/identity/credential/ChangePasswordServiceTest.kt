// 비밀번호 변경 서비스 단위 테스트 — 정책·동일성·인증 실패 + 세션 무효화 7개 케이스

package com.atlas.bts.identity.credential

import com.atlas.bts.identity.mfa.TrustedDeviceService
import com.atlas.bts.identity.session.RefreshTokenRepository
import com.atlas.bts.identity.session.Session
import com.atlas.bts.identity.session.SessionService
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
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
    private val trustedDeviceService = mockk<TrustedDeviceService>(relaxed = true)

    private val sut =
        ChangePasswordService(
            localCredentialService = localCredentialService,
            sessionService = sessionService,
            refreshTokenRepository = refreshTokenRepository,
            trustedDeviceService = trustedDeviceService,
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
        clearMocks(localCredentialService, sessionService, refreshTokenRepository, trustedDeviceService)
    }

    // ── 1. 정책 위반 ──────────────────────────────────────────────────────────

    @Test
    fun `정책 위반 시 PolicyViolation 반환, rotate·세션무효화 미호출`() {
        val tooShort = "short".toCharArray() // MIN_LENGTH 미달

        val result = sut.change(userId, currentSid, currentPw(), tooShort)

        assertThat(result).isInstanceOf(ChangePasswordResult.PolicyViolation::class.java)
        val pv = result as ChangePasswordResult.PolicyViolation
        assertThat(pv.violations).isNotEmpty

        verify(exactly = 0) { localCredentialService.rotate(any(), any(), any()) }
        verify(exactly = 0) { sessionService.revoke(any(), any()) }
        verify(exactly = 0) { refreshTokenRepository.revokeChainFromSession(any()) }
        // 비번이 실제로 바뀌지 않은 실패 경로 — 신뢰 디바이스 자동폐기 미발생 (FR-MF-05 Task 7).
        verify(exactly = 0) { trustedDeviceService.revokeAll(any()) }
    }

    // ── 2. new == current 평문 동일 ──────────────────────────────────────────

    @Test
    fun `new와 current 평문이 동일하면 SameAsCurrent 반환, rotate 미호출`() {
        val pw = "OldP@ssw0rd!1".toCharArray()
        val pwCopy = "OldP@ssw0rd!1".toCharArray() // 내용 동일, 별도 배열

        val result = sut.change(userId, currentSid, pw, pwCopy)

        assertThat(result).isEqualTo(ChangePasswordResult.SameAsCurrent)

        verify(exactly = 0) { localCredentialService.rotate(any(), any(), any()) }
        // rotate 미발생(비번 미변경) — 신뢰 디바이스 자동폐기 미발생 (C1: SameAsCurrent 누락 금지).
        verify(exactly = 0) { trustedDeviceService.revokeAll(any()) }
    }

    // ── 3. current 불일치 (rotate = false) ──────────────────────────────────

    @Test
    fun `rotate가 false 반환 시 CurrentMismatch, 세션 무효화 미호출`() {
        every { localCredentialService.rotate(userId, any(), any()) } returns false

        val result = sut.change(userId, currentSid, currentPw(), validNew())

        assertThat(result).isEqualTo(ChangePasswordResult.CurrentMismatch)

        verify(exactly = 0) { sessionService.revoke(any(), any()) }
        verify(exactly = 0) { refreshTokenRepository.revokeChainFromSession(any()) }
        // 현재 비번 불일치(비번 미변경) — 신뢰 디바이스 자동폐기 미발생 (FR-MF-05 Task 7).
        verify(exactly = 0) { trustedDeviceService.revokeAll(any()) }
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

        // 비번 변경 성공 = 보안 이벤트 — 신뢰 디바이스 전량 자동폐기 (FR-MF-05 Task 7, ADR D5).
        verify(exactly = 1) { trustedDeviceService.revokeAll(userId) }
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
        val newArr = "short".toCharArray() // 정책 위반

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

    // ── 8. 성공 경로에서도 CharArray wipe (C-b 회귀 가드) ────────────────────

    @Test
    fun `성공 경로에서도 current와 new CharArray가 wipe됨`() {
        every { localCredentialService.rotate(userId, any(), any()) } returns true
        every { sessionService.findActiveByUser(userId) } returns listOf(makeSession(currentSid))

        val currentArr = currentPw()
        val newArr = validNew()

        sut.change(userId, currentSid, currentArr, newArr)

        // finally 블록이 성공 경로에서도 배열을 fill(' ')로 wipe해야 한다
        assertThat(currentArr).doesNotContain('O', 'l', 'd')
        assertThat(newArr).doesNotContain('N', 'e', 'w')
    }
}

/**
 * ChangePasswordService 자동해제 통합 테스트 (실 repo + Testcontainers PostgreSQL).
 *
 * 단위 테스트는 LocalCredentialService 를 mockk 으로 대체하므로 mustChangePassword
 * 자동 해제(BLOCKER-2)를 잡지 못한다. 이 클래스는 LocalCredentialService + 실
 * StoredPasswordCredentialRepository 를 실제 PostgreSQL 에 연결해, change() 전체
 * 흐름(policy → same → rotate → 세션무효화)을 거쳐 강제 변경 플래그가 false 로
 * 자동 해제되는지를 end-to-end 로 검증한다.
 *
 * SessionService / RefreshTokenRepository 는 자동해제 경로와 무관하므로 relaxed mock
 * 으로 두며, findActiveByUser 는 빈 리스트를 반환해 세션 무효화 단계를 무해하게 통과시킨다.
 * 핵심 검증 대상인 credential UPSERT 경로는 실 DB 다.
 *
 * 최상위 클래스로 분리한 이유: @Nested inner class 는 바깥 companion 의
 * @DynamicPropertySource 를 적용받지 못해 datasource 주입이 누락된다.
 */
@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(StoredPasswordCredentialRepository::class)
@Testcontainers
class ChangePasswordServiceMustChangeIntegrationTest {
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
    }

    @Autowired
    private lateinit var repo: StoredPasswordCredentialRepository

    @Autowired
    private lateinit var jdbc: NamedParameterJdbcTemplate

    private lateinit var userId: UUID
    private lateinit var sut: ChangePasswordService

    @BeforeEach
    fun setUp() {
        jdbc.update("DELETE FROM local_credentials", emptyMap<String, Any>())
        jdbc.update("DELETE FROM users", emptyMap<String, Any>())

        userId = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO users (id, username) VALUES (:id, :username)",
            mapOf("id" to userId, "username" to "test-user-$userId"),
        )

        val localCredentialService = LocalCredentialService(repo)
        val sessionService = mockk<SessionService>(relaxed = true)
        val refreshTokenRepository = mockk<RefreshTokenRepository>(relaxed = true)
        // 신뢰 디바이스 자동폐기는 mustChange 자동해제와 무관 — relaxed mock 으로 무해하게 통과
        val trustedDeviceService = mockk<TrustedDeviceService>(relaxed = true)
        // 세션 무효화 단계는 자동해제와 무관 — 활성 세션 없음으로 무해하게 통과
        every { sessionService.findActiveByUser(userId) } returns emptyList()

        sut =
            ChangePasswordService(
                localCredentialService = localCredentialService,
                sessionService = sessionService,
                refreshTokenRepository = refreshTokenRepository,
                trustedDeviceService = trustedDeviceService,
            )
    }

    @Test
    fun `change 성공 — mustChange=true 였던 자격증명이 change 흐름 후 false 로 자동 해제`() {
        // 강제 변경 대상으로 저장 (mustChange=true)
        LocalCredentialService(repo).store(userId, "InitP@ss1!".toCharArray(), mustChange = true)
        assertThat(repo.findByUserId(userId)!!.mustChangePassword).isTrue()

        // change() 전체 흐름 통과 → rotate → store(false) → UPSERT SET 으로 자동 해제
        val result =
            sut.change(
                userId = userId,
                currentSid = UUID.randomUUID(),
                current = "InitP@ss1!".toCharArray(),
                new = "NewP@ssw0rd!1".toCharArray(),
            )

        assertThat(result).isEqualTo(ChangePasswordResult.Success)
        val after = repo.findByUserId(userId)
        assertThat(after).isNotNull()
        assertThat(after!!.mustChangePassword).isFalse()
    }

    @Test
    fun `change CurrentMismatch — old 불일치 시 mustChange 플래그 유지 (해제 안 됨)`() {
        LocalCredentialService(repo).store(userId, "InitP@ss1!".toCharArray(), mustChange = true)
        assertThat(repo.findByUserId(userId)!!.mustChangePassword).isTrue()

        val result =
            sut.change(
                userId = userId,
                currentSid = UUID.randomUUID(),
                current = "WrongOld@1234".toCharArray(),
                new = "NewP@ssw0rd!1".toCharArray(),
            )

        assertThat(result).isEqualTo(ChangePasswordResult.CurrentMismatch)
        // rotate 미수행 → UPSERT SET 미발생 → 플래그 그대로 true 유지
        assertTrue(repo.findByUserId(userId)!!.mustChangePassword)
    }
}
