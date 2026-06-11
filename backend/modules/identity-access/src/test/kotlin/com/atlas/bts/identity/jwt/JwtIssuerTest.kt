// JwtIssuer 단위 테스트 — RS256 Access Token 발급 claims 6종 + BTS 확장 claims 검증 (FR-AU-09)

package com.atlas.bts.identity.jwt

import com.atlas.bts.identity.mfa.MfaEnforcementPolicy
import com.nimbusds.jwt.SignedJWT
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * JwtIssuer 단위 테스트.
 *
 * - FR-AU-09: Access Token 발급 (nimbus-jose-jwt 자체 발급, Spring Authorization Server 미도입)
 * - FR-09-1~5: claims 6종 (sub, iss, aud, exp, iat, jti) + BTS 확장 (sid, mfa_verified, providerId, scopes)
 * - FR-09-15: mfa_verified=false 더미 claim 포함
 * - FR-MF-04: mfa_enrollment_required claim — 발급 chokepoint(전 발급 경로 자동 포괄)
 *
 * 검증 라이브러리: nimbus-jose-jwt [SignedJWT] 직접 파싱 (JwtDecoder = Task 12 책임).
 */
class JwtIssuerTest {

    private val keyProvider = DevMemoryKeyProvider()
    private val issuerUri = "https://bts.example.com"

    /**
     * MFA 강제 정책 fake (FR-MF-04). 기본은 `false`(강제 아님) — 기존 claims 검증에 영향 없음.
     * mfa_enrollment_required 전용 테스트는 [setMfaEnrollmentRequired] 로 반환값을 토글한다.
     */
    private val mfaEnforcementPolicy: MfaEnforcementPolicy = mockk()
    private lateinit var jwtIssuer: JwtIssuer

    private val userId = UUID.fromString("11111111-0000-0000-0000-000000000001")
    private val sessionId = UUID.fromString("22222222-0000-0000-0000-000000000002")
    private val providerId = "local"
    private val scopes = listOf("issues:read", "issues:write")

    @BeforeEach
    fun setUp() {
        // 기본값 false — mfa_enrollment_required 전용 테스트 외에는 강제 아님으로 고정.
        every { mfaEnforcementPolicy.evaluate(any()) } returns false
        jwtIssuer = JwtIssuer(keyProvider, issuerUri, mfaEnforcementPolicy)
    }

    /** [mfaEnforcementPolicy] 의 evaluate 반환값을 토글한다(mfa_enrollment_required claim 검증용). */
    private fun setMfaEnrollmentRequired(required: Boolean) {
        every { mfaEnforcementPolicy.evaluate(any()) } returns required
    }

    // ── 기본 발급 ────────────────────────────────────────────────────────────

    @Test
    fun `issue 는 서명된 JWT 문자열을 반환한다`() {
        val token = jwtIssuer.issue(userId, sessionId, providerId, scopes)

        assertThat(token).isNotBlank()
        // JWT 형식 = header.payload.signature (점 2개)
        assertThat(token.split(".")).hasSize(3)
    }

    // ── claims 6종 (RFC 7519 표준) ───────────────────────────────────────────

    @Test
    fun `sub claim 은 userId 문자열과 일치한다`() {
        val claims = parseClaims(jwtIssuer.issue(userId, sessionId, providerId, scopes))

        assertThat(claims.subject).isEqualTo(userId.toString())
    }

    @Test
    fun `iss claim 은 주입된 issuerUri 와 일치한다`() {
        val claims = parseClaims(jwtIssuer.issue(userId, sessionId, providerId, scopes))

        assertThat(claims.issuer).isEqualTo(issuerUri)
    }

    @Test
    fun `aud claim 은 bts-api 를 포함한다`() {
        val claims = parseClaims(jwtIssuer.issue(userId, sessionId, providerId, scopes))

        assertThat(claims.audience).contains("bts-api")
    }

    @Test
    fun `exp - iat 는 정확히 900초 (15분) 이다`() {
        val claims = parseClaims(jwtIssuer.issue(userId, sessionId, providerId, scopes))

        val iat = claims.issueTime.toInstant().epochSecond
        val exp = claims.expirationTime.toInstant().epochSecond
        assertThat(exp - iat).isEqualTo(900L)
    }

    @Test
    fun `iat claim 이 존재한다`() {
        val claims = parseClaims(jwtIssuer.issue(userId, sessionId, providerId, scopes))

        assertThat(claims.issueTime).isNotNull()
    }

    @Test
    fun `jti claim 이 존재하고 UUID 형식이다`() {
        val claims = parseClaims(jwtIssuer.issue(userId, sessionId, providerId, scopes))

        val jti = claims.jwtid
        assertThat(jti).isNotBlank()
        // UUID 파싱 성공이면 형식 검증 통과
        assertThat(runCatching { UUID.fromString(jti) }.isSuccess).isTrue()
    }

    @Test
    fun `연속 발급된 두 토큰의 jti 는 서로 다르다 (unique)`() {
        val token1 = jwtIssuer.issue(userId, sessionId, providerId, scopes)
        val token2 = jwtIssuer.issue(userId, sessionId, providerId, scopes)

        val jti1 = parseClaims(token1).jwtid
        val jti2 = parseClaims(token2).jwtid
        assertThat(jti1).isNotEqualTo(jti2)
    }

    // ── BTS 확장 claims ──────────────────────────────────────────────────────

    @Test
    fun `sid claim 은 sessionId 문자열과 일치한다`() {
        val claims = parseClaims(jwtIssuer.issue(userId, sessionId, providerId, scopes))

        assertThat(claims.getStringClaim("sid")).isEqualTo(sessionId.toString())
    }

    @Test
    fun `mfa_verified claim 은 mfaVerified 미전달 시 false 이다 (FR-MF-01)`() {
        val claims = parseClaims(jwtIssuer.issue(userId, sessionId, providerId, scopes))

        assertThat(claims.getBooleanClaim("mfa_verified")).isFalse()
    }

    @Test
    fun `mfa_verified claim 은 mfaVerified=true 전달 시 true 이다 (FR-MF-01)`() {
        val token = jwtIssuer.issue(userId, sessionId, providerId, scopes, mfaVerified = true)
        val claims = parseClaims(token)

        assertThat(claims.getBooleanClaim("mfa_verified")).isTrue()
    }

    @Test
    fun `mfa_verified claim 은 mfaVerified=false 명시 전달 시 false 이다 (FR-MF-01)`() {
        val token = jwtIssuer.issue(userId, sessionId, providerId, scopes, mfaVerified = false)
        val claims = parseClaims(token)

        assertThat(claims.getBooleanClaim("mfa_verified")).isFalse()
    }

    // ── mfa_enrollment_required claim (FR-MF-04 — 발급 chokepoint) ────────────

    @Test
    fun `mfa_enrollment_required claim 은 정책이 true 면 true 이다 (FR-MF-04)`() {
        setMfaEnrollmentRequired(true)

        val claims = parseClaims(jwtIssuer.issue(userId, sessionId, providerId, scopes))

        assertThat(claims.getBooleanClaim("mfa_enrollment_required")).isTrue()
    }

    @Test
    fun `mfa_enrollment_required claim 은 정책이 false 면 false 로 명시된다 (FR-MF-04)`() {
        setMfaEnrollmentRequired(false)

        val claims = parseClaims(jwtIssuer.issue(userId, sessionId, providerId, scopes))

        // 부재가 아니라 명시 false — '클레임 부재=false 해석'과 일관(게이트 T7 단일 해석).
        assertThat(claims.getBooleanClaim("mfa_enrollment_required")).isFalse()
    }

    @Test
    fun `mfa_enrollment_required claim 은 mfaVerified 인자 생략 호출(SSO 방식)에도 항상 박힌다 (FR-MF-04 chokepoint)`() {
        // 핵심(B1): issue() 호출이 어떤 형태든(인자 생략·roles 포함 등) 클레임이 항상 박힌다.
        // 발급 chokepoint 가 JwtIssuer 내부 1곳에 있으므로 SSO 핸들러 같은 호출처가 누락할 수 없다.
        setMfaEnrollmentRequired(true)

        // SSO 성공 핸들러와 동일 방식(mfaVerified 생략, roles 포함) 호출 재현.
        val token = jwtIssuer.issue(userId, sessionId, "oidc", scopes, roles = listOf("SYSTEM_ADMIN"))
        val claims = parseClaims(token)

        assertThat(claims.getBooleanClaim("mfa_enrollment_required")).isTrue()
    }

    @Test
    fun `mfa_enrollment_required claim 은 sub(userId)로 정책을 평가한다 (FR-MF-04)`() {
        // 클레임 출처 = sub 사용자의 강제 여부. evaluate 가 userId 로 호출됨을 토큰 결과로 고정.
        every { mfaEnforcementPolicy.evaluate(userId) } returns true

        val claims = parseClaims(jwtIssuer.issue(userId, sessionId, providerId, scopes))

        assertThat(claims.getBooleanClaim("mfa_enrollment_required")).isTrue()
    }

    @Test
    fun `providerId claim 은 전달된 providerId 와 일치한다`() {
        val claims = parseClaims(jwtIssuer.issue(userId, sessionId, providerId, scopes))

        assertThat(claims.getStringClaim("providerId")).isEqualTo(providerId)
    }

    @Test
    fun `scopes claim 은 전달된 스코프 목록과 일치한다`() {
        val claims = parseClaims(jwtIssuer.issue(userId, sessionId, providerId, scopes))

        @Suppress("UNCHECKED_CAST")
        val claimScopes = claims.getListClaim("scopes") as List<String>
        assertThat(claimScopes).containsExactlyInAnyOrderElementsOf(scopes)
    }

    // ── roles claim (FR-PM-08 Task 4 — 전역 시스템 역할) ──────────────────────

    @Test
    fun `roles 가 전달되면 roles claim 에 포함된다`() {
        val token = jwtIssuer.issue(userId, sessionId, providerId, scopes, roles = listOf("SYSTEM_ADMIN"))
        val claims = parseClaims(token)

        @Suppress("UNCHECKED_CAST")
        val claimRoles = claims.getListClaim("roles") as List<String>
        assertThat(claimRoles).containsExactly("SYSTEM_ADMIN")
    }

    @Test
    fun `roles 기본값(미전달) 이면 roles claim 이 없다`() {
        val claims = parseClaims(jwtIssuer.issue(userId, sessionId, providerId, scopes))

        assertThat(claims.getListClaim("roles")).isNull()
    }

    // ── JWS 헤더 (kid, alg) ─────────────────────────────────────────────────

    @Test
    fun `JWS 헤더 kid 는 JwtKeyProvider 의 kid 와 일치한다`() {
        val token = jwtIssuer.issue(userId, sessionId, providerId, scopes)
        val signedJwt = SignedJWT.parse(token)

        assertThat(signedJwt.header.keyID).isEqualTo(keyProvider.kid)
    }

    @Test
    fun `JWS 헤더 alg 는 RS256 이다`() {
        val token = jwtIssuer.issue(userId, sessionId, providerId, scopes)
        val signedJwt = SignedJWT.parse(token)

        assertThat(signedJwt.header.algorithm.name).isEqualTo("RS256")
    }

    // ── round-trip 서명 검증 ─────────────────────────────────────────────────

    @Test
    fun `발급된 토큰은 동일 keyProvider 의 공개키로 서명 검증을 통과한다`() {
        val token = jwtIssuer.issue(userId, sessionId, providerId, scopes)
        val signedJwt = SignedJWT.parse(token)

        val verifier = com.nimbusds.jose.crypto.RSASSAVerifier(keyProvider.publicKey)
        assertThat(signedJwt.verify(verifier)).isTrue()
    }

    // ── 헬퍼 ─────────────────────────────────────────────────────────────────

    private fun parseClaims(token: String) = SignedJWT.parse(token).jwtClaimsSet
}
