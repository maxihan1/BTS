// Auth back-channel logout_token 을 JWKS 서명 · Back-Channel Logout 1.0 claim 규칙으로 확인 (S24 · FR-AU-04)

package com.atlas.bts.identity.provider.oidc

import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.jwk.source.JWKSourceBuilder
import com.nimbusds.jose.proc.DefaultJOSEObjectTypeVerifier
import com.nimbusds.jose.proc.JWSVerificationKeySelector
import com.nimbusds.jose.proc.SecurityContext
import com.nimbusds.jose.util.DefaultResourceRetriever
import com.nimbusds.jwt.proc.DefaultJWTProcessor
import com.nimbusds.jwt.proc.JWTClaimsSetVerifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator
import org.springframework.security.oauth2.core.OAuth2Error
import org.springframework.security.oauth2.core.OAuth2TokenValidator
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtException
import org.springframework.security.oauth2.jwt.JwtValidators
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.stereotype.Component
import java.net.URI
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * logout_token 확인 (S24 계약 §1 — OIDC Back-Channel Logout 1.0 §2.6).
 *
 * - 서명 — `<issuer>/oauth2/jwks` 의 RS256 키. header `typ` 은 `logout+jwt` 만 (Nimbus 기본은 `JWT` 만 받아 거절한다).
 * - `iss` = Auth issuer · `aud` 에 이 앱 client_id · `exp` 있음 · 안 지남 · `iat` 가 지금 ±5분 ·
 *   `events` 에 back-channel logout 키 · `nonce` 없음 · `sid` 나 `sub` 하나는 있음.
 *
 * decoder 는 이 컴포넌트 안에만 둔다 — 두 번째 [JwtDecoder] @Bean 은 BTS 자신의 access token resource server 를
 * 모호하게 만든다. Auth issuer 가 설정되지 않은 설치(로컬 · test)는 모든 token 을 거절한다.
 *
 * **JWKS 받기 — 로그인 없는 경로라 공격자가 부르는 횟수를 정한다.** 모르는 `kid` 마다 다시 받으면 익명 요청이
 * 그대로 Auth 로 가는 요청이 되고, Auth 가 멈춰 있으면 worker 가 묶인다. 그래서 Nimbus [JWKSourceBuilder]
 * 기본값(캐시 5분 · 다시 받기 30초에 한 번)에 연결 3초 · 읽기 5초 timeout 을 건다. 제한에 걸린 token 은 400.
 */
@Component
class LogoutTokenVerifier(
    @param:Value("\${bts.auth-platform.issuer-uri:}") issuerUri: String,
    @param:Value("\${bts.auth-platform.client-id:bts}") private val clientId: String,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val issuer = issuerUri.trimEnd('/')

    private val decoder: JwtDecoder? by lazy {
        if (issuer.isEmpty()) return@lazy null
        val retriever = DefaultResourceRetriever(CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS, JWKS_SIZE_LIMIT)
        val jwks = JWKSourceBuilder.create<SecurityContext>(URI("$issuer$JWKS_PATH").toURL(), retriever).build()
        val processor =
            DefaultJWTProcessor<SecurityContext>().apply {
                jwsTypeVerifier = DefaultJOSEObjectTypeVerifier(JOSEObjectType(LOGOUT_JWT_TYPE))
                jwsKeySelector = JWSVerificationKeySelector(JWSAlgorithm.RS256, jwks)
                // claim 은 아래 Spring validator 가 본다 (NimbusJwtDecoder.withJwkSetUri 와 같은 분담)
                jwtClaimsSetVerifier = JWTClaimsSetVerifier { _, _ -> }
            }
        NimbusJwtDecoder(processor).apply { setJwtValidator(validatorFor(issuer)) }
    }

    /**
     * [token] 을 확인해 끊을 대상을 돌려준다.
     *
     * @throws JwtException 서명 · header · claim 중 하나라도 틀리면 (호출자는 400)
     */
    fun verify(token: String): LogoutClaims {
        val jwt = (decoder ?: throw BadLogoutTokenException("Auth issuer 미설정")).decode(token)
        return LogoutClaims(
            sid = jwt.getClaimAsString(SID)?.takeIf { it.isNotBlank() },
            sub = jwt.subject?.takeIf { it.isNotBlank() },
            // validatorFor 가 이미 확인했다
            issuedAt = requireNotNull(jwt.issuedAt),
        )
    }

    /** 이 앱 client_id 를 아는 validator — 테스트가 서명 없이 claim 규칙만 볼 수 있게 internal */
    internal fun validatorFor(issuer: String): OAuth2TokenValidator<Jwt> =
        DelegatingOAuth2TokenValidator(
            JwtValidators.createDefaultWithIssuer(issuer),
            OAuth2TokenValidator(::checkClaims),
        )

    private fun checkClaims(jwt: Jwt): OAuth2TokenValidatorResult {
        val now = clock.instant()
        val iat = jwt.issuedAt
        val events = jwt.getClaim<Any>(EVENTS) as? Map<*, *>
        val problem =
            when {
                clientId !in jwt.audience.orEmpty() -> "aud"
                jwt.expiresAt == null -> "exp"
                iat == null || Duration.between(iat, now).abs() > IAT_WINDOW -> "iat"
                events?.containsKey(BACKCHANNEL_EVENT) != true -> "events"
                jwt.hasClaim(NONCE) -> "nonce"
                jwt.getClaimAsString(SID).isNullOrBlank() && jwt.subject.isNullOrBlank() -> "sid/sub"
                else -> null
            }
        return if (problem == null) {
            OAuth2TokenValidatorResult.success()
        } else {
            OAuth2TokenValidatorResult.failure(OAuth2Error("invalid_request", "logout_token $problem", null))
        }
    }

    internal companion object {
        const val JWKS_PATH = "/oauth2/jwks"
        const val LOGOUT_JWT_TYPE = "logout+jwt"
        const val BACKCHANNEL_EVENT = "http://schemas.openid.net/event/backchannel-logout"
        const val EVENTS = "events"
        const val NONCE = "nonce"
        const val SID = "sid"

        const val CONNECT_TIMEOUT_MS = 3_000
        const val READ_TIMEOUT_MS = 5_000

        /** JWKS 응답 상한 — 키 몇 개짜리 JSON 이다 */
        const val JWKS_SIZE_LIMIT = 50 * 1024

        /** `iat` 허용 폭 — 계약 §1 「5분 안」 */
        val IAT_WINDOW: Duration = Duration.ofMinutes(5)
    }
}

/**
 * 확인된 logout_token 의 끊을 대상.
 *
 * @param sid 있으면 그 Auth 세션으로 로그인한 세션만
 * @param sub sid 가 없으면 이 Auth 사용자의 [issuedAt] 전 로그인 세션 전부
 */
data class LogoutClaims(val sid: String?, val sub: String?, val issuedAt: Instant)

/** 설정이 없어 확인할 수 없는 token — 서명 · claim 실패와 같이 400 */
class BadLogoutTokenException(message: String) : JwtException(message)
