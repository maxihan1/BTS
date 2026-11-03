// 확인된 back-channel logout_token 대상 세션을 refresh chain 과 함께 폐기하는 서비스 (S24 · FR-AU-04 · FR-AU-09)

package com.atlas.bts.identity.provider.oidc

import com.atlas.bts.identity.provider.ldap.ExternalAccountRepository
import com.atlas.bts.identity.session.RefreshTokenRepository
import com.atlas.bts.identity.session.SessionRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * logout_token 의 대상을 끊는다 (S24 계약 §1 「처리」).
 *
 * - `sid` 가 있으면 그 Auth 세션으로 로그인한 살아 있는 세션만.
 * - `sid` 가 없으면 그 `sub`(Auth user id — `user_external_accounts` 의 Auth provider 행) 사용자의
 *   `oidc` 세션 중 `created_at < iat + 1초` 인 것 전부 — `iat` 는 초로 내린 값이라 끄기와 같은 초에 먼저 만든
 *   세션(ms)도 끊게 1초를 더한다. 로컬 비밀번호 세션은 남긴다. 기준이 `iat` 라 다시 보낸 token 이
 *   그 뒤 로그인을 끊지 못한다 — `jti` 를 기억하지 않는다.
 *
 * 세션 폐기와 refresh chain 폐기는 늘 짝이다 (로그아웃 · 비밀번호 변경과 같음). 한 트랜잭션.
 * 끊긴 세션의 access token 은 [com.atlas.bts.identity.jwt.SidRevokeJwtConverter] 캐시(5초) 안에 거절된다.
 */
@Service
class BackChannelLogoutService(
    private val sessions: SessionRepository,
    private val refreshTokens: RefreshTokenRepository,
    private val externalAccounts: ExternalAccountRepository,
    private val oidcConfigs: OidcProviderConfigReader,
) {
    /** @return 끊은 세션 수 (없으면 0 — 이미 끊겼거나 BTS 에 로그인한 적 없음) */
    @Transactional
    fun logout(claims: LogoutClaims): Int {
        val revoked =
            if (claims.sid != null) {
                sessions.revokeActiveByAuthSid(claims.sid, REVOKE_REASON)
            } else {
                revokeBySubject(claims)
            }
        revoked.forEach(refreshTokens::revokeChainFromSession)
        return revoked.size
    }

    private fun revokeBySubject(claims: LogoutClaims) =
        claims.sub
            ?.let { sub ->
                oidcConfigs.findByRegistrationId(AuthPlatformRegistrationRunner.REGISTRATION_ID)
                    ?.let { externalAccounts.findByProviderIdAndExternalSubject(it.authnProviderId, sub) }
            }
            ?.let {
                val before = claims.issuedAt.plusSeconds(1)
                sessions.revokeActiveByUserCreatedBefore(it.userId, OIDC_SESSION_PROVIDER, before, REVOKE_REASON)
            }
            .orEmpty()

    internal companion object {
        /** sessions.revoke_reason — 감사 · 세션 목록에서 「Auth 가 끊음」을 가른다 */
        const val REVOKE_REASON = "backchannel_logout"

        /** OIDC 로그인 세션의 sessions.provider_id ([OidcAuthenticationSuccessHandler] 와 같음) */
        const val OIDC_SESSION_PROVIDER = "oidc"
    }
}
