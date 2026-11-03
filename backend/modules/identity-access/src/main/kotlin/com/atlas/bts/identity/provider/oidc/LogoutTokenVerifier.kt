// Auth back-channel logout_token 을 JWKS 서명 · Back-Channel Logout 1.0 claim 규칙으로 확인 (S24 · FR-AU-04)

package com.atlas.bts.identity.provider.oidc

import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.proc.DefaultJOSEObjectTypeVerifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator
import org.springframework.security.oauth2.core.OAuth2Error
import org.springframework.security.oauth2.core.OAuth2TokenValidator
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtException
import org.springframework.security.oauth2.jwt.JwtValidators
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.stereotype.Component
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
        NimbusJwtDecoder.withJwkSetUri("$issuer$JWKS_PATH")
            .jwsAlgorithm(SignatureAlgorithm.RS256)
            .jwtProcessorCustomizer {
                it.jwsTypeVerifier = DefaultJOSEObjectTypeVerifier(JOSEObjectType(LOGOUT_JWT_TYPE))
            }
            .build()
            .apply { setJwtValidator(validatorFor(issuer)) }
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
