// MFA 1단계 통과 후 발급되는 단명 챌린지 토큰의 발급/검증/일회용 처리 서비스 (FR-MF-01 Task 5)

package com.atlas.bts.identity.mfa

import com.atlas.bts.identity.jwt.JwtKeyProvider
import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import com.nimbusds.jose.JOSEException
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.crypto.RSASSAVerifier
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import org.springframework.stereotype.Service
import java.text.ParseException
import java.time.Clock
import java.time.Duration
import java.util.Date
import java.util.UUID

/**
 * 검증된 MFA 챌린지 토큰의 클레임.
 *
 * [MfaChallengeTokenService.validate] 성공 시 반환되며, 토큰에 담긴 사용자/공급자 식별자와
 * 일회용 처리에 쓰일 [jti] 를 노출한다.
 *
 * @property userId 1단계(비밀번호) 인증을 통과한 사용자 UUID (sub claim).
 * @property providerId 1단계 인증 공급자 식별자 (e.g. "local").
 * @property jti 토큰 고유 식별자 — [MfaChallengeTokenService.consume] 으로 일회용 소비 처리한다.
 */
data class MfaChallengeClaims(
    val userId: UUID,
    val providerId: String,
    val jti: String,
)

/**
 * MFA 1단계(비밀번호) 통과 후 발급되는 **단명 챌린지 토큰**의 발급/검증/일회용 처리.
 *
 * ## 챌린지 메커니즘 (D3)
 * 로컬 로그인에서 비밀번호가 검증되면 아직 정식 세션을 발급하지 않고, 본 서비스가 발급하는
 * 단명(5분) RS256 서명 JWT 를 클라이언트에 돌려준다. 클라이언트는 이 토큰과 TOTP 코드를
 * `POST /auth/mfa/verify` 본문으로 보내고, 서버는 토큰을 [validate] 한 뒤 TOTP 코드를 확인해야
 * 정식 세션(`mfa_verified=true`)을 발급한다.
 *
 * 챌린지 토큰은 아직 정식 인증 세션이 아니므로 `Authorization: Bearer` 헤더가 아닌 **요청 본문**으로
 * 전달된다(권한 부여 토큰과 혼동 방지).
 *
 * ## 인프라 재사용
 * 기존 [JwtKeyProvider] (nimbus RS256 키)를 그대로 재사용해 별도 키 관리 부담 없이 서명/검증한다.
 *
 * ## C1 — 일회용 (replay 방어)
 * 검증에 성공한 토큰을 두 번 사용하지 못하도록, [consume] 호출 시 토큰의 `jti` 를 Caffeine 캐시
 * ([CHALLENGE_TTL] TTL)에 기록한다. 이미 기록된 `jti` 면 `false` 를 돌려 재사용을 거부한다.
 * 호출처(Task 10 `/auth/mfa/verify`)는 [validate] 성공 후 코드 검증 직전에 [consume] 으로 토큰을
 * 소비해야 한다. TTL 이 토큰 수명과 같으므로 만료된 jti 는 어차피 [validate] 단계에서 걸러진다.
 *
 * ## 시각 의존 — Clock 주입
 * 만료(exp) 비교와 발급 시각은 모두 주입 [clock] 기준으로 계산한다. 핸들러에서 `Instant.now()` 를
 * 하드코딩하면 특정 시각에 깨지는 time-bomb 이 되므로 테스트에서 `Clock.fixed` 로 고정한다
 * (메모리 authcontroller-revokesession-timebomb).
 *
 * ## 보안 주의
 * 토큰 문자열/jti/userId 등 식별자는 로깅하지 않는다(§1.1.2). [validate] 는 서명/만료/purpose 중
 * 하나라도 어긋나면 `null` 을 반환한다(불명은 거부 — fail-closed).
 *
 * ## 참조
 * - FR-MF-01 Task 5, D3 (단기 챌린지 토큰)
 * - SDD §19.7.4 (2FA 로그인 흐름)
 */
@Service
class MfaChallengeTokenService(
    private val keyProvider: JwtKeyProvider,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val signer = RSASSASigner(keyProvider.privateKey)
    private val verifier = RSASSAVerifier(keyProvider.publicKey)

    /** C1 — 이미 소비된 챌린지 토큰 jti 집합. 값은 의미 없음(존재 여부만 사용). */
    private val consumedJti: Cache<String, Boolean> =
        Caffeine.newBuilder()
            .expireAfterWrite(CHALLENGE_TTL)
            .build()

    /**
     * 사용자에게 단명(5분) MFA 챌린지 토큰을 발급한다.
     *
     * @param userId 1단계 인증을 통과한 사용자 UUID (sub claim).
     * @param providerId 1단계 인증 공급자 식별자 (e.g. "local").
     * @return 서명된 챌린지 JWT 문자열.
     */
    fun issueChallenge(
        userId: UUID,
        providerId: String,
    ): String {
        val now = clock.instant()
        val header = JWSHeader.Builder(JWSAlgorithm.RS256).keyID(keyProvider.kid).build()
        val claims =
            JWTClaimsSet.Builder()
                .subject(userId.toString())
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(CHALLENGE_TTL)))
                .jwtID(UUID.randomUUID().toString())
                .claim(CLAIM_PURPOSE, PURPOSE_MFA_CHALLENGE)
                .claim(CLAIM_PROVIDER_ID, providerId)
                .build()
        val jwt = SignedJWT(header, claims)
        jwt.sign(signer)
        return jwt.serialize()
    }

    /**
     * 챌린지 토큰을 검증한다.
     *
     * 서명(본 키), 만료(주입 [clock] 기준), purpose([PURPOSE_MFA_CHALLENGE])를 모두 통과해야
     * 성공이며, 하나라도 어긋나거나 토큰 형식이 깨지면 `null` 을 반환한다(불명은 거부).
     *
     * `null` 반환은 인증 실패를 숨기는 것이 아니라 **명시적 거부 신호**다 — 호출처는 `null` 을
     * "유효하지 않은 챌린지"로 처리해 401 로 응답한다.
     *
     * @param token 챌린지 JWT 문자열.
     * @return 검증된 [MfaChallengeClaims], 또는 검증 실패 시 `null`.
     *
     * ReturnCount 억제 — 각 return 은 서명/만료/purpose/subject/providerId/jti 별 **명시적 fail-closed
     * 거부**다. 단일 return 으로 합치면 보안 가드의 가독성이 떨어지고 누락 위험이 커진다(early-return 권장).
     */
    @Suppress("ReturnCount")
    fun validate(token: String): MfaChallengeClaims? {
        val claims = parseVerified(token) ?: return null

        val expiration = claims.expirationTime?.toInstant() ?: return null
        if (!clock.instant().isBefore(expiration)) {
            return null
        }
        if (claims.getStringClaim(CLAIM_PURPOSE) != PURPOSE_MFA_CHALLENGE) {
            return null
        }

        val userId = parseUuid(claims.subject) ?: return null
        val providerId = claims.getStringClaim(CLAIM_PROVIDER_ID) ?: return null
        val jti = claims.jwtid ?: return null

        return MfaChallengeClaims(userId = userId, providerId = providerId, jti = jti)
    }

    /**
     * C1 — 챌린지 토큰을 일회용으로 소비 처리한다.
     *
     * 처음 소비되는 `jti` 면 캐시에 기록하고 `true` 를, 이미 소비된 `jti` 면 `false` 를 반환한다.
     * 호출처는 `false` 일 때 재사용된 토큰으로 간주해 거부해야 한다.
     *
     * @param jti [validate] 가 반환한 [MfaChallengeClaims.jti].
     * @return 새로 소비됐으면 `true`, 이미 소비된 토큰이면 `false`.
     */
    fun consume(jti: String): Boolean {
        // asMap().putIfAbsent 는 기존 매핑이 있으면 그 값을, 없으면 null 을 반환한다(원자적).
        val previous = consumedJti.asMap().putIfAbsent(jti, true)
        return previous == null
    }

    /**
     * 서명을 검증한 클레임셋을 반환한다. 파싱 실패·서명 불일치 시 `null`.
     *
     * 변조/형식오류 토큰은 검증 실패가 정상 동작이므로, 외부 입력 파싱에서 발생하는
     * [ParseException]/[JOSEException] 을 거부(null)로 수렴시킨다(예외 메시지·토큰 미로깅).
     */
    private fun parseVerified(token: String): JWTClaimsSet? =
        try {
            val signedJwt = SignedJWT.parse(token)
            if (!signedJwt.verify(verifier)) {
                null
            } else {
                signedJwt.jwtClaimsSet
            }
        } catch (_: ParseException) {
            null
        } catch (_: JOSEException) {
            null
        }

    /** sub claim 의 UUID 파싱. 형식 오류면 `null`. */
    private fun parseUuid(raw: String?): UUID? =
        if (raw == null) {
            null
        } else {
            try {
                UUID.fromString(raw)
            } catch (_: IllegalArgumentException) {
                null
            }
        }

    internal companion object {
        /** 챌린지 토큰 유효 기간 — 5분. 일회용 jti 캐시 TTL 과 동일. */
        val CHALLENGE_TTL: Duration = Duration.ofMinutes(5)

        /** purpose claim 키 — 토큰 용도 구분(다른 용도 토큰 재사용 차단). */
        const val CLAIM_PURPOSE = "purpose"

        /** MFA 챌린지 토큰의 purpose 값. */
        const val PURPOSE_MFA_CHALLENGE = "mfa_challenge"

        /** 1단계 인증 공급자 식별자 claim 키. */
        const val CLAIM_PROVIDER_ID = "providerId"
    }
}
