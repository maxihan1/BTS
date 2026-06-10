// RS256 Access Token 발급 서비스 — nimbus-jose-jwt 자체 발급, Spring Authorization Server 미도입 (FR-AU-09)

package com.atlas.bts.identity.jwt

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.Date
import java.util.UUID

/**
 * BTS Access Token 발급 서비스.
 *
 * nimbus-jose-jwt 9.x 를 직접 사용하여 RS256 서명 JWT 를 발급한다.
 * Spring Authorization Server 미도입 결정에 따라 [RegisteredClient] 등 SAP 클래스 사용 금지.
 *
 * ## 포함 claims
 * | claim | 설명 |
 * |---|---|
 * | sub | userId (UUID 문자열) |
 * | iss | bts.auth.issuer-uri 프로퍼티 |
 * | aud | ["bts-api"] |
 * | exp | iat + [ACCESS_TOKEN_TTL_SECONDS] (15분) |
 * | iat | 발급 시각 |
 * | jti | 고유 UUID (재사용 방지) |
 * | sid | sessionId (UUID 문자열) |
 * | mfa_verified | MFA 2차 인증 통과 여부 ([mfaVerified] 파라미터, 기본 false) — FR-MF-01 |
 * | providerId | 인증 공급자 식별자 (e.g. "local", "ldap") |
 * | scopes | 허용 스코프 목록 |
 * | roles | 전역 시스템 역할 목록 (e.g. ["SYSTEM_ADMIN"]) — 비어 있으면 claim 생략 (FR-PM-08) |
 *
 * ## 키 교체 (Key Rotation)
 * JWS 헤더에 [JwtKeyProvider.kid] 를 포함하여 다중 키 공존을 지원한다.
 * 검증은 Task 12 JwtDecoder 가 담당한다.
 *
 * ## 참조
 * - FR-AU-09, FR-09-1~5 (claims 명세)
 * - FR-MF-01 (mfa_verified 실체화 — [mfaVerified] 파라미터)
 * - SDD §19.5 (login response body)
 */
@Service
class JwtIssuer(
    private val keyProvider: JwtKeyProvider,
    @Value("\${bts.auth.issuer-uri}") private val issuerUri: String,
) {
    private val signer = RSASSASigner(keyProvider.privateKey)

    /**
     * Access Token 을 발급하고 직렬화된 JWT 문자열을 반환한다.
     *
     * @param userId 인증된 사용자 UUID
     * @param sessionId 현재 세션 UUID (sid claim)
     * @param providerId 인증 공급자 식별자 (e.g. "local")
     * @param scopes 허용 스코프 목록
     * @param roles 전역 시스템 역할 목록 (FR-PM-08). 비어 있으면 [CLAIM_ROLES] claim 을 생략한다.
     * @param mfaVerified MFA 2차 인증 통과 여부 ([CLAIM_MFA_VERIFIED]). 기본 `false` (FR-MF-01).
     *   미전달 호출처(SSO 성공 핸들러 등)는 `false` 가 유지된다.
     * @return 서명된 JWT 문자열 (header.payload.signature)
     */
    fun issue(
        userId: UUID,
        sessionId: UUID,
        providerId: String,
        scopes: List<String>,
        roles: List<String> = emptyList(),
        mfaVerified: Boolean = false,
    ): String {
        val now = Instant.now()
        val exp = now.plusSeconds(ACCESS_TOKEN_TTL_SECONDS)

        val header =
            JWSHeader.Builder(JWSAlgorithm.RS256)
                .keyID(keyProvider.kid)
                .build()

        val claims =
            JWTClaimsSet.Builder()
                .subject(userId.toString())
                .issuer(issuerUri)
                .audience(AUDIENCE)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(exp))
                .jwtID(UUID.randomUUID().toString())
                .claim(CLAIM_SID, sessionId.toString())
                .claim(CLAIM_MFA_VERIFIED, mfaVerified)
                .claim(CLAIM_PROVIDER_ID, providerId)
                .claim(CLAIM_SCOPES, scopes)
                // roles 가 비어 있으면 claim 자체를 생략한다 (일반 사용자 토큰은 roles 미포함).
                .let { if (roles.isEmpty()) it else it.claim(CLAIM_ROLES, roles) }
                .build()

        val jwt = SignedJWT(header, claims)
        jwt.sign(signer)
        return jwt.serialize()
    }

    internal companion object {
        /** Access Token 유효 기간 (초) — 15분 */
        const val ACCESS_TOKEN_TTL_SECONDS = 900L

        /** JWT audience — BTS API 서버 식별자 */
        const val AUDIENCE = "bts-api"

        // BTS 확장 claim 키 상수
        const val CLAIM_SID = "sid"
        const val CLAIM_MFA_VERIFIED = "mfa_verified"
        const val CLAIM_PROVIDER_ID = "providerId"
        const val CLAIM_SCOPES = "scopes"

        /** 전역 시스템 역할 목록 claim 키 (FR-PM-08) — SidRevokeJwtConverter 가 ROLE_ authority 로 변환 */
        const val CLAIM_ROLES = "roles"
    }
}
