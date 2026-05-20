// PersonalAccessTokenService 단위 테스트 — MockK 기반, verify/markLastUsed/hasScope 검증 (FR-AU-09 Task 18)

package com.atlas.bts.identity.pat

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/**
 * PersonalAccessTokenService 단위 테스트 (FR-AU-09 Task 18).
 *
 * MockK 기반 순수 단위 테스트 — PersonalAccessTokenRepository / UserRepository 를 mock 처리.
 *
 * 검증 대상.
 * - verify(token) 성공. hash 매칭 + 만료 안 됨 + revoked_at NULL → Success(pat)
 * - verify 실패. token 미존재 / expired / revoked / prefix 불일치
 * - markLastUsed(patId). last_used_at 갱신 위임 확인
 * - hasScope helper. scope 포함 → true / 미포함 → false / 와일드카드 * → true
 * - @Service + @Transactional 어노테이션 부착 확인 (회귀 가드)
 *
 * EC-26. token_hash = SHA-256("pat_" + body) 전체 (prefix 포함).
 * EC-27. expiresAt nullable — null 이면 무기한.
 */
class PersonalAccessTokenServiceTest {

    private lateinit var patRepository: PersonalAccessTokenRepository
    private lateinit var service: PersonalAccessTokenService

    private val userId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
    private val patId = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb")
    private val fixedNow = Instant.parse("2026-05-21T00:00:00Z")

    /** 테스트 환경에서 SHA-256 계산 없이 고정 hash 를 사용하기 위한 raw token.
     *  PersonalAccessTokenService 내부의 sha256Hex(raw) 결과가 아래 KNOWN_HASH 와 일치해야 통과. */
    private val rawToken = "pat_testbody000000000000000000000000000000000000"   // "pat_" + 44자 (≠ 48자, 검증용)
    private val rawTokenValid = "pat_" + "x".repeat(48)                          // 규격 맞는 52자 raw token

    /** SHA-256("pat_" + "x".repeat(48)) 를 미리 계산해둔 hex — GREEN 단계에서 실제 계산값과 일치해야 함. */
    private val knownHash = sha256Hex(rawTokenValid)

    private fun buildActivePat(
        tokenHash: String = knownHash,
        expiresAt: Instant? = null,
        revokedAt: Instant? = null,
        scopes: List<String> = listOf("read:issues"),
    ) = PersonalAccessToken(
        id = patId,
        userId = userId,
        name = "ci-token",
        tokenHash = tokenHash,
        scopes = scopes,
        expiresAt = expiresAt,
        lastUsedAt = null,
        revokedAt = revokedAt,
        createdAt = fixedNow,
    )

    @BeforeEach
    fun setUp() {
        patRepository = mockk()
        service = PersonalAccessTokenService(patRepository)
    }

    // ── verify 성공 흐름 ─────────────────────────────────────────────────────

    @Test
    fun `verify 성공 — hash 매칭 + 만료 안 됨 + revoked NULL → Result success`() {
        val pat = buildActivePat()
        every { patRepository.findByTokenHash(knownHash) } returns pat
        every { patRepository.updateLastUsed(patId) } returns Unit

        val result = service.verify(rawTokenValid)

        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrNull()).isEqualTo(pat)
        verify(exactly = 1) { patRepository.updateLastUsed(patId) }
    }

    @Test
    fun `verify 성공 — expiresAt null (무기한) 토큰도 성공 반환 (EC-27)`() {
        val pat = buildActivePat(expiresAt = null)
        every { patRepository.findByTokenHash(knownHash) } returns pat
        every { patRepository.updateLastUsed(patId) } returns Unit

        val result = service.verify(rawTokenValid)

        assertThat(result.isSuccess).isTrue()
    }

    // ── verify 실패 — token 미존재 ────────────────────────────────────────────

    @Test
    fun `verify 실패 — DB hash 미존재 → Result failure`() {
        every { patRepository.findByTokenHash(knownHash) } returns null

        val result = service.verify(rawTokenValid)

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).isInstanceOf(PatVerificationException::class.java)
    }

    // ── verify 실패 — 만료 ───────────────────────────────────────────────────

    @Test
    fun `verify 실패 — 만료된 PAT → Result failure`() {
        val expiredPat = buildActivePat(expiresAt = fixedNow.minusSeconds(1))
        every { patRepository.findByTokenHash(knownHash) } returns expiredPat

        val result = service.verify(rawTokenValid)

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).isInstanceOf(PatVerificationException::class.java)
        verify(exactly = 0) { patRepository.updateLastUsed(any()) }
    }

    // ── verify 실패 — revoked ─────────────────────────────────────────────────

    @Test
    fun `verify 실패 — revoked PAT (revokedAt not null) → Result failure`() {
        val revokedPat = buildActivePat(revokedAt = fixedNow.minusSeconds(3600))
        every { patRepository.findByTokenHash(knownHash) } returns revokedPat

        val result = service.verify(rawTokenValid)

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).isInstanceOf(PatVerificationException::class.java)
        verify(exactly = 0) { patRepository.updateLastUsed(any()) }
    }

    // ── markLastUsed ──────────────────────────────────────────────────────────

    @Test
    fun `markLastUsed — patRepository updateLastUsed 에 patId 를 위임`() {
        every { patRepository.updateLastUsed(patId) } returns Unit

        service.markLastUsed(patId)

        verify(exactly = 1) { patRepository.updateLastUsed(patId) }
    }

    // ── hasScope helper ──────────────────────────────────────────────────────

    @Test
    fun `hasScope — PAT scopes 에 포함된 scope → true`() {
        val pat = buildActivePat(scopes = listOf("read:issues", "write:comments"))

        assertThat(service.hasScope(pat, "read:issues")).isTrue()
        assertThat(service.hasScope(pat, "write:comments")).isTrue()
    }

    @Test
    fun `hasScope — PAT scopes 에 없는 scope → false`() {
        val pat = buildActivePat(scopes = listOf("read:issues"))

        assertThat(service.hasScope(pat, "write:issues")).isFalse()
    }

    @Test
    fun `hasScope — scopes 에 와일드카드 * 포함 시 모든 scope → true`() {
        val pat = buildActivePat(scopes = listOf("*"))

        assertThat(service.hasScope(pat, "any:scope")).isTrue()
        assertThat(service.hasScope(pat, "admin:delete")).isTrue()
    }

    @Test
    fun `hasScope — scopes 빈 리스트 → false`() {
        val pat = buildActivePat(scopes = emptyList())

        assertThat(service.hasScope(pat, "read:issues")).isFalse()
    }

    // ── @Service + @Transactional 어노테이션 회귀 가드 ───────────────────────

    @Test
    fun `@Service 어노테이션이 PersonalAccessTokenService 에 부착돼 있음`() {
        assertThat(PersonalAccessTokenService::class.java.isAnnotationPresent(Service::class.java)).isTrue()
    }

    @Test
    fun `@Transactional 어노테이션이 PersonalAccessTokenService 에 부착돼 있음`() {
        assertThat(PersonalAccessTokenService::class.java.isAnnotationPresent(Transactional::class.java)).isTrue()
    }
}
