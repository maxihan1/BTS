// PersonalAccessTokenService 단위 테스트 — verify/markLastUsed/hasScope
// + issue/listByUser/revoke (FR-AU-09 Task 18 · FR-API-04 Task 4)

package com.atlas.bts.identity.pat

import com.atlas.bts.identity.audit.AuthAuditLog
import com.atlas.bts.identity.audit.AuthAuditLogService
import com.atlas.bts.identity.audit.AuthEventType
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * PersonalAccessTokenService 단위 테스트 (FR-AU-09 Task 18 · FR-API-04 Task 4).
 *
 * MockK 기반 순수 단위 테스트 — Repository / AuditLogService / JdbcTemplate 를 mock 처리한다.
 *
 * 검증 대상.
 * - verify(token) 성공/실패 (기존 FR-AU-09 커버리지, 무회귀)
 * - markLastUsed / hasScope helper (기존)
 * - issue(...) 정상 발급 — pat_ 접두 52자 raw + SHA-256 저장 + PAT_ISSUED 감사
 * - issue(...) 검증 실패 — 이름 공백 / 만료일 null·0·366 / 미지 scope / 활성 20개 상한 → 예외
 * - issue(...) 감사 fail-closed — record 실패 시 예외 전파(트랜잭션 롤백)
 * - listByUser(...) — listByUserIncludingExpired 위임(만료 포함)
 * - revoke(...) — 본인 활성 취소 + PAT_REVOKED / 이미취소 멱등(감사 미emit) / 타인·미존재 404
 * - @Service + @Transactional 어노테이션 부착 확인 (회귀 가드)
 *
 * EC-26. token_hash = SHA-256("pat_" + body) 전체 (prefix 포함).
 * EC-27. expiresAt nullable — null 이면 무기한(단 발급 경로는 무기한을 거부한다).
 * 시각. 발급/폐기 시각 비교는 주입 [Clock] 기준 — time-bomb 회피(authcontroller-revokesession-timebomb 교훈).
 */
class PersonalAccessTokenServiceTest {
    private lateinit var patRepository: PersonalAccessTokenRepository
    private lateinit var auditLogService: AuthAuditLogService
    private lateinit var jdbc: NamedParameterJdbcTemplate
    private lateinit var service: PersonalAccessTokenService

    private val userId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
    private val patId = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb")
    private val fixedNow = Instant.parse("2026-05-21T00:00:00Z")
    private val fixedClock = Clock.fixed(fixedNow, ZoneOffset.UTC)

    /** 테스트 환경에서 SHA-256 계산 없이 고정 hash 를 사용하기 위한 raw token.
     *  PersonalAccessTokenService 내부의 sha256Hex(raw) 결과가 아래 KNOWN_HASH 와 일치해야 통과. */
    private val rawToken = "pat_testbody000000000000000000000000000000000000" // "pat_" + 44자 (≠ 48자, 검증용)
    private val rawTokenValid = "pat_" + "x".repeat(48) // 규격 맞는 52자 raw token

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
        auditLogService = mockk()
        jdbc = mockk(relaxed = true)
        service = PersonalAccessTokenService(patRepository, auditLogService, jdbc, fixedClock)
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
        // expiresAt 을 충분히 과거로 설정하여 시스템 시각과 무관하게 만료 상태 보장
        val expiredPat = buildActivePat(expiresAt = Instant.parse("2020-01-01T00:00:00Z"))
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

    // ── issue 정상 발급 ───────────────────────────────────────────────────────

    @Test
    fun `issue — pat_ 접두 52자 raw + SHA-256 해시만 저장 + PAT_ISSUED 감사`() {
        every { patRepository.countActiveByUser(userId, fixedNow) } returns 0L
        val savedSlot = slot<PersonalAccessToken>()
        every { patRepository.save(capture(savedSlot)) } answers { savedSlot.captured }
        val auditSlot = slot<AuthAuditLog>()
        justRun { auditLogService.record(capture(auditSlot)) }

        val issued = service.issue(userId, "ci-token", listOf("read:issues"), 30)

        // raw token 형식: "pat_" + 48자 base62 = 52자, 발급 응답에만 노출
        assertThat(issued.rawToken).startsWith("pat_")
        assertThat(issued.rawToken).hasSize(52)
        val body = issued.rawToken.removePrefix("pat_")
        assertThat(body).hasSize(48)
        assertThat(body).matches("[0-9A-Za-z]{48}")

        // DB 저장은 SHA-256 해시만 (평문 미저장, EC-26)
        assertThat(savedSlot.captured.tokenHash).isEqualTo(sha256Hex(issued.rawToken))
        assertThat(savedSlot.captured.tokenHash).hasSize(64)
        assertThat(savedSlot.captured.tokenHash).isNotEqualTo(issued.rawToken)
        assertThat(savedSlot.captured.scopes).containsExactly("read:issues")
        assertThat(savedSlot.captured.revokedAt).isNull()
        assertThat(savedSlot.captured.expiresAt).isEqualTo(fixedNow.plus(Duration.ofDays(30)))

        // 감사: PAT_ISSUED + metadata patId/name, token/hash 미포함
        assertThat(auditSlot.captured.eventType).isEqualTo(AuthEventType.PAT_ISSUED)
        assertThat(auditSlot.captured.userId).isEqualTo(userId)
        assertThat(auditSlot.captured.metadata).containsKeys("patId", "name")
        assertThat(auditSlot.captured.metadata["patId"]).isEqualTo(savedSlot.captured.id.toString())
        assertThat(auditSlot.captured.metadata.values)
            .noneMatch { it == savedSlot.captured.tokenHash || it.contains(issued.rawToken) }
    }

    @Test
    fun `issue — scope 정규화(중복제거·순서보존) 후 저장`() {
        every { patRepository.countActiveByUser(userId, fixedNow) } returns 0L
        val savedSlot = slot<PersonalAccessToken>()
        every { patRepository.save(capture(savedSlot)) } answers { savedSlot.captured }
        justRun { auditLogService.record(any()) }

        service.issue(userId, "ci-token", listOf("write:issues", "read:issues", "write:issues"), 90)

        assertThat(savedSlot.captured.scopes).containsExactly("write:issues", "read:issues")
    }

    @Test
    fun `issue — 개수 판정 전 advisory lock 으로 직렬화(TOCTOU 차단)`() {
        every { patRepository.countActiveByUser(userId, fixedNow) } returns 0L
        every { patRepository.save(any()) } answers { firstArg() }
        justRun { auditLogService.record(any()) }

        service.issue(userId, "ci-token", listOf("read:issues"), 30)

        verify {
            jdbc.queryForList(
                match<String> { it.contains("pg_advisory_xact_lock") },
                any<Map<String, Any>>(),
            )
        }
    }

    // ── issue 검증 실패 ───────────────────────────────────────────────────────

    @Test
    fun `issue — 이름 공백 금지 → 400`() {
        assertThatThrownBy { service.issue(userId, "   ", listOf("read:issues"), 30) }
            .isInstanceOf(BlankPatNameException::class.java)

        verify(exactly = 0) { patRepository.save(any()) }
    }

    @Test
    fun `issue — 만료일 null(무기한) 금지 → 400`() {
        assertThatThrownBy { service.issue(userId, "ci-token", listOf("read:issues"), null) }
            .isInstanceOf(InvalidPatExpiryException::class.java)

        verify(exactly = 0) { patRepository.save(any()) }
    }

    @Test
    fun `issue — 만료일 0일 금지 → 400`() {
        assertThatThrownBy { service.issue(userId, "ci-token", listOf("read:issues"), 0) }
            .isInstanceOf(InvalidPatExpiryException::class.java)

        verify(exactly = 0) { patRepository.save(any()) }
    }

    @Test
    fun `issue — 만료일 366일(상한 초과) 금지 → 400`() {
        assertThatThrownBy { service.issue(userId, "ci-token", listOf("read:issues"), 366) }
            .isInstanceOf(InvalidPatExpiryException::class.java)

        verify(exactly = 0) { patRepository.save(any()) }
    }

    @Test
    fun `issue — 미지 scope → 400`() {
        assertThatThrownBy { service.issue(userId, "ci-token", listOf("delete:everything"), 30) }
            .isInstanceOf(UnknownScopeException::class.java)

        verify(exactly = 0) { patRepository.save(any()) }
    }

    @Test
    fun `issue — 빈 scope → 400`() {
        assertThatThrownBy { service.issue(userId, "ci-token", emptyList(), 30) }
            .isInstanceOf(EmptyScopeException::class.java)

        verify(exactly = 0) { patRepository.save(any()) }
    }

    @Test
    fun `issue — 활성 PAT 20개 도달 시 상한 초과 → 400`() {
        every { patRepository.countActiveByUser(userId, fixedNow) } returns 20L

        assertThatThrownBy { service.issue(userId, "ci-token", listOf("read:issues"), 30) }
            .isInstanceOf(PatQuotaExceededException::class.java)

        // 상한 초과 시 토큰 저장/감사 없음
        verify(exactly = 0) { patRepository.save(any()) }
        verify(exactly = 0) { auditLogService.record(any()) }
        // 개수 판정 전 advisory lock 직렬화 확인
        verify {
            jdbc.queryForList(
                match<String> { it.contains("pg_advisory_xact_lock") },
                any<Map<String, Any>>(),
            )
        }
    }

    // ── issue 감사 fail-closed ────────────────────────────────────────────────

    @Test
    fun `issue — 감사 기록 실패 시 예외 전파(트랜잭션 롤백, fail-closed)`() {
        // 실제 DB 롤백은 @Transactional 이 런타임에 보장한다. 단위테스트는 "감사 예외를 삼키지 않고
        // 그대로 전파" 하는지(=fail-closed) 를 검증한다. 삼켰다면 발급이 감사 없이 커밋되는 사고가 된다.
        every { patRepository.countActiveByUser(userId, fixedNow) } returns 0L
        every { patRepository.save(any()) } answers { firstArg() }
        every { auditLogService.record(any()) } throws IllegalStateException("audit sink down")

        assertThatThrownBy { service.issue(userId, "ci-token", listOf("read:issues"), 30) }
            .isInstanceOf(IllegalStateException::class.java)
    }

    // ── listByUser ────────────────────────────────────────────────────────────

    @Test
    fun `listByUser — listByUserIncludingExpired 위임(만료 포함)`() {
        val pats = listOf(buildActivePat(), buildActivePat(expiresAt = fixedNow.minusSeconds(1)))
        every { patRepository.listByUserIncludingExpired(userId) } returns pats

        val result = service.listByUser(userId)

        assertThat(result).isEqualTo(pats)
        verify(exactly = 1) { patRepository.listByUserIncludingExpired(userId) }
    }

    // ── revoke ────────────────────────────────────────────────────────────────

    @Test
    fun `revoke — 본인 활성 PAT 취소 + PAT_REVOKED 감사`() {
        every { patRepository.findByIdAndUserId(patId, userId) } returns buildActivePat(revokedAt = null)
        every { patRepository.revokeOwned(patId, userId, fixedNow) } returns 1
        val auditSlot = slot<AuthAuditLog>()
        justRun { auditLogService.record(capture(auditSlot)) }

        service.revoke(patId, userId)

        verify(exactly = 1) { patRepository.revokeOwned(patId, userId, fixedNow) }
        assertThat(auditSlot.captured.eventType).isEqualTo(AuthEventType.PAT_REVOKED)
        assertThat(auditSlot.captured.userId).isEqualTo(userId)
        assertThat(auditSlot.captured.metadata["patId"]).isEqualTo(patId.toString())
    }

    @Test
    fun `revoke — 본인 이미 취소된 PAT 멱등 성공 + 감사 미emit`() {
        every { patRepository.findByIdAndUserId(patId, userId) } returns
            buildActivePat(revokedAt = fixedNow.minusSeconds(60))
        every { patRepository.revokeOwned(patId, userId, fixedNow) } returns 0

        service.revoke(patId, userId) // 예외 없이 멱등 성공

        verify(exactly = 0) { auditLogService.record(any()) }
    }

    @Test
    fun `revoke — 타인·미존재 PAT → NotFound(IDOR 동일응답)`() {
        every { patRepository.findByIdAndUserId(patId, userId) } returns null

        assertThatThrownBy { service.revoke(patId, userId) }
            .isInstanceOf(PersonalAccessTokenNotFoundException::class.java)

        verify(exactly = 0) { patRepository.revokeOwned(any(), any(), any()) }
        verify(exactly = 0) { auditLogService.record(any()) }
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
