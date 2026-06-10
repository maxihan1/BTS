// MfaChallengeTokenService 단위 테스트 — 단명 MFA 챌린지 토큰 발급/검증 + jti 일회용(C1) (FR-MF-01 Task 5)

package com.atlas.bts.identity.mfa

import com.atlas.bts.identity.jwt.DevMemoryKeyProvider
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.Date
import java.util.UUID

/**
 * [MfaChallengeTokenService] 단위 테스트.
 *
 * MFA 1단계(비밀번호) 통과 후 발급되는 단명(5분) 챌린지 토큰의 발급/검증/일회용을 검증한다.
 * 시각 의존 로직(만료)은 주입 [Clock] (`Clock.fixed`)로 고정해 time-bomb 회귀를 방지한다
 * (메모리 authcontroller-revokesession-timebomb).
 *
 * ## 검증 시나리오
 * - 발급: 단명 JWT(고유 jti), validate 성공 시 userId/providerId 복원
 * - 만료: exp 경과 후 validate null
 * - 위조: 다른 키 서명 토큰 validate null
 * - purpose 불일치: purpose 클레임이 다르면 validate null
 * - C1 일회용: 같은 jti consume 2회 시 2번째 거부
 */
class MfaChallengeTokenServiceTest {
    private val keyProvider = DevMemoryKeyProvider()
    private val fixedNow: Instant = Instant.parse("2026-06-11T10:00:00Z")
    private val clock: Clock = Clock.fixed(fixedNow, ZoneOffset.UTC)
    private lateinit var service: MfaChallengeTokenService

    private val userId = UUID.fromString("55555555-0000-0000-0000-000000000005")
    private val providerId = "local"

    @BeforeEach
    fun setUp() {
        service = MfaChallengeTokenService(keyProvider, clock)
    }

    // ── 발급 + 검증 happy path ────────────────────────────────────────────────

    @Test
    fun `issueChallenge 는 서명된 JWT 문자열을 반환한다`() {
        val token = service.issueChallenge(userId, providerId)

        assertThat(token).isNotBlank()
        assertThat(token.split(".")).hasSize(3)
    }

    @Test
    fun `validate 는 발급한 토큰에서 userId 와 providerId 를 복원한다`() {
        val token = service.issueChallenge(userId, providerId)

        val claims = service.validate(token)

        assertThat(claims).isNotNull
        assertThat(claims!!.userId).isEqualTo(userId)
        assertThat(claims.providerId).isEqualTo(providerId)
    }

    @Test
    fun `issueChallenge 는 매번 고유한 jti 를 발급한다`() {
        val token1 = service.issueChallenge(userId, providerId)
        val token2 = service.issueChallenge(userId, providerId)

        assertThat(jtiOf(token1)).isNotEqualTo(jtiOf(token2))
    }

    @Test
    fun `발급 토큰의 exp 는 발급 시각 기준 5분 후이다`() {
        val token = service.issueChallenge(userId, providerId)
        val claims = SignedJWT.parse(token).jwtClaimsSet

        val exp = claims.expirationTime.toInstant()
        assertThat(exp).isEqualTo(fixedNow.plus(Duration.ofMinutes(5)))
    }

    @Test
    fun `발급 토큰의 purpose 클레임은 mfa_challenge 이다`() {
        val token = service.issueChallenge(userId, providerId)
        val claims = SignedJWT.parse(token).jwtClaimsSet

        assertThat(claims.getStringClaim("purpose")).isEqualTo("mfa_challenge")
    }

    // ── 만료 ─────────────────────────────────────────────────────────────────

    @Test
    fun `validate 는 만료된 토큰에 대해 null 을 반환한다`() {
        // 발급 시각 기준 5분 + 1초 경과한 시점의 검증기로 검증.
        val token = service.issueChallenge(userId, providerId)
        val laterClock = Clock.fixed(fixedNow.plus(Duration.ofMinutes(5)).plusSeconds(1), ZoneOffset.UTC)
        val laterService = MfaChallengeTokenService(keyProvider, laterClock)

        assertThat(laterService.validate(token)).isNull()
    }

    // ── 위조 ─────────────────────────────────────────────────────────────────

    @Test
    fun `validate 는 다른 키로 서명된 위조 토큰에 대해 null 을 반환한다`() {
        val forgedToken = signWith(DevMemoryKeyProvider(), purpose = "mfa_challenge")

        assertThat(service.validate(forgedToken)).isNull()
    }

    @Test
    fun `validate 는 형식이 깨진 토큰에 대해 null 을 반환한다`() {
        assertThat(service.validate("not-a-jwt")).isNull()
        assertThat(service.validate("")).isNull()
    }

    // ── purpose 불일치 ────────────────────────────────────────────────────────

    @Test
    fun `validate 는 purpose 가 다른 토큰에 대해 null 을 반환한다`() {
        // 올바른 키로 서명했지만 purpose 가 mfa_challenge 가 아닌 토큰 — 다른 용도 토큰 재사용 차단.
        val wrongPurposeToken = signWith(keyProvider, purpose = "password_reset")

        assertThat(service.validate(wrongPurposeToken)).isNull()
    }

    // ── C1 일회용 (consumed jti) ──────────────────────────────────────────────

    @Test
    fun `consume 은 처음 소비된 jti 에 대해 true 를 반환한다`() {
        val token = service.issueChallenge(userId, providerId)
        val claims = service.validate(token)!!

        assertThat(service.consume(claims.jti)).isTrue()
    }

    @Test
    fun `consume 은 이미 소비된 jti 에 대해 false 를 반환한다 (일회용)`() {
        val token = service.issueChallenge(userId, providerId)
        val claims = service.validate(token)!!

        assertThat(service.consume(claims.jti)).isTrue()
        assertThat(service.consume(claims.jti)).isFalse()
    }

    // ── 헬퍼 ─────────────────────────────────────────────────────────────────

    private fun jtiOf(token: String): String = SignedJWT.parse(token).jwtClaimsSet.jwtid

    /**
     * 임의 keyProvider/purpose 로 서명한 챌린지 형태의 토큰을 만든다(위조·purpose 불일치 케이스용).
     * 유효한 exp(fixedNow + 5분)를 넣어 만료가 아닌 다른 사유(키/purpose)로만 검증 실패하도록 한다.
     */
    private fun signWith(
        otherKeyProvider: DevMemoryKeyProvider,
        purpose: String,
    ): String {
        val header = JWSHeader.Builder(JWSAlgorithm.RS256).keyID(otherKeyProvider.kid).build()
        val claims =
            JWTClaimsSet.Builder()
                .subject(userId.toString())
                .issueTime(Date.from(fixedNow))
                .expirationTime(Date.from(fixedNow.plus(Duration.ofMinutes(5))))
                .jwtID(UUID.randomUUID().toString())
                .claim("purpose", purpose)
                .claim("providerId", providerId)
                .build()
        val jwt = SignedJWT(header, claims)
        jwt.sign(RSASSASigner(otherKeyProvider.privateKey))
        return jwt.serialize()
    }
}
