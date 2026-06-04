// SidRevokeJwtConverter 단위 테스트 — sid claim 기반 세션 revoke 검증 회로 (FR-09-11 / EC-29)

package com.atlas.bts.identity.jwt

import com.atlas.bts.identity.session.Session
import com.atlas.bts.identity.session.SessionService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * SidRevokeJwtConverter 단위 테스트 (FR-AU-09 Task 34).
 *
 * 검증 대상:
 * - (a) 활성 세션 sid → 인증 토큰 정상 반환
 * - (b) 폐기된 세션 sid → [InvalidBearerTokenException] 발생
 * - (c) 만료된 세션 sid → [InvalidBearerTokenException] 발생
 * - (d) sid claim 없는 JWT → [InvalidBearerTokenException] 발생
 * - (e) sid가 UUID 형식이 아닌 경우 → [InvalidBearerTokenException] 발생
 * - (f) EC-29 Caffeine 5s TTL 캐시 — 동일 sid 재요청 시 DB 1회만 조회
 */
class SidRevokeJwtConverterTest {

    private lateinit var sessionService: SessionService
    private lateinit var converter: SidRevokeJwtConverter

    private val fixedNow: Instant = Instant.parse("2026-05-21T10:00:00Z")
    private val clock: Clock = Clock.fixed(fixedNow, ZoneOffset.UTC)

    private val activeSid: UUID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
    private val revokedSid: UUID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb")
    private val expiredSid: UUID = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc")
    private val missSid: UUID = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd")

    @BeforeEach
    fun setUp() {
        sessionService = mockk()
        converter = SidRevokeJwtConverter(sessionService, clock)
    }

    // ── (a) 활성 세션 ────────────────────────────────────────────────────────

    @Test
    fun `활성 세션의 sid를 가진 JWT는 인증 토큰을 정상 반환한다`() {
        every { sessionService.lookup(activeSid) } returns buildActiveSession(activeSid)

        val jwt = buildJwt(sid = activeSid.toString())
        val result = converter.convert(jwt)

        assertThat(result).isNotNull
    }

    // ── (b) 폐기된 세션 ──────────────────────────────────────────────────────

    @Test
    fun `폐기된 세션의 sid를 가진 JWT는 InvalidBearerTokenException을 던진다`() {
        every { sessionService.lookup(revokedSid) } returns buildRevokedSession(revokedSid)

        val jwt = buildJwt(sid = revokedSid.toString())

        assertThatThrownBy { converter.convert(jwt) }
            .isInstanceOf(InvalidBearerTokenException::class.java)
            .hasMessageContaining("session_revoked")
    }

    // ── (c) 만료된 세션 ──────────────────────────────────────────────────────

    @Test
    fun `만료된 세션의 sid를 가진 JWT는 InvalidBearerTokenException을 던진다`() {
        every { sessionService.lookup(expiredSid) } returns buildExpiredSession(expiredSid)

        val jwt = buildJwt(sid = expiredSid.toString())

        assertThatThrownBy { converter.convert(jwt) }
            .isInstanceOf(InvalidBearerTokenException::class.java)
            .hasMessageContaining("session_revoked")
    }

    // ── (d) sid claim 없는 JWT ───────────────────────────────────────────────

    @Test
    fun `sid claim이 없는 JWT는 InvalidBearerTokenException을 던진다`() {
        val jwt = buildJwt(sid = null)

        assertThatThrownBy { converter.convert(jwt) }
            .isInstanceOf(InvalidBearerTokenException::class.java)
            .hasMessageContaining("sid")
    }

    // ── (e) sid가 UUID 형식이 아닌 경우 ─────────────────────────────────────

    @Test
    fun `sid claim이 UUID 형식이 아닌 경우 InvalidBearerTokenException을 던진다`() {
        val jwt = buildJwt(sid = "not-a-uuid")

        assertThatThrownBy { converter.convert(jwt) }
            .isInstanceOf(InvalidBearerTokenException::class.java)
            .hasMessageContaining("sid")
    }

    // ── roles → ROLE_ authority (FR-PM-08 Task 4) ───────────────────────────

    @Test
    fun `roles claim의 SYSTEM_ADMIN은 ROLE_SYSTEM_ADMIN authority로 변환된다`() {
        every { sessionService.lookup(activeSid) } returns buildActiveSession(activeSid)

        val jwt = buildJwt(sid = activeSid.toString(), roles = listOf("SYSTEM_ADMIN"))
        val result = converter.convert(jwt)

        assertThat(result.authorities.map { it.authority }).contains("ROLE_SYSTEM_ADMIN")
    }

    @Test
    fun `roles claim이 없으면 ROLE_ authority가 부여되지 않는다`() {
        every { sessionService.lookup(activeSid) } returns buildActiveSession(activeSid)

        val jwt = buildJwt(sid = activeSid.toString())
        val result = converter.convert(jwt)

        assertThat(result.authorities.map { it.authority }).noneMatch { it.startsWith("ROLE_") }
    }

    // ── (f) EC-29 Caffeine 5s TTL 캐시 ──────────────────────────────────────

    @Test
    fun `동일 sid를 5초 안에 두 번 조회하면 SessionService는 1번만 호출된다 (EC-29 캐시)`() {
        every { sessionService.lookup(activeSid) } returns buildActiveSession(activeSid)

        val jwt = buildJwt(sid = activeSid.toString())

        // 같은 converter 인스턴스로 두 번 호출 — 캐시 히트
        converter.convert(jwt)
        converter.convert(jwt)

        // DB는 1회만 조회돼야 한다
        verify(exactly = 1) { sessionService.lookup(activeSid) }
    }

    @Test
    fun `존재하지 않는 sid는 활성 세션 없음으로 처리하여 InvalidBearerTokenException을 던진다`() {
        every { sessionService.lookup(missSid) } returns null

        val jwt = buildJwt(sid = missSid.toString())

        assertThatThrownBy { converter.convert(jwt) }
            .isInstanceOf(InvalidBearerTokenException::class.java)
            .hasMessageContaining("session_revoked")
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun buildJwt(sid: String?, roles: List<String>? = null): Jwt {
        val claims = mutableMapOf<String, Any>(
            "sub" to "user-001",
            "iss" to "https://bts.example.com",
        )
        if (sid != null) claims["sid"] = sid
        if (roles != null) claims["roles"] = roles

        return Jwt.withTokenValue("token")
            .header("alg", "RS256")
            .claims { it.putAll(claims) }
            .build()
    }

    private fun buildActiveSession(id: UUID): Session =
        Session(
            id = id,
            userId = UUID.randomUUID(),
            providerId = "local",
            deviceFingerprint = null,
            ipAddress = null,
            userAgent = null,
            createdAt = fixedNow,
            expiresAt = fixedNow.plus(14, ChronoUnit.DAYS),
            lastSeenAt = fixedNow,
            revokedAt = null,
            revokeReason = null,
        )

    private fun buildRevokedSession(id: UUID): Session =
        buildActiveSession(id).copy(
            revokedAt = fixedNow.minusSeconds(60),
            revokeReason = "logout",
        )

    private fun buildExpiredSession(id: UUID): Session =
        buildActiveSession(id).copy(
            expiresAt = fixedNow.minus(1, ChronoUnit.HOURS),
        )
}
