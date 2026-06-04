// SAML2 인증 성공 후 BTS 세션/JWT 발급 + RelayState 복귀 핸들러 (FR-AU-03)

package com.atlas.bts.identity.provider.saml

import com.atlas.bts.identity.jwt.JwtIssuer
import com.atlas.bts.identity.provider.ldap.AutoProvisionService
import com.atlas.bts.identity.session.RefreshTokenRepository
import com.atlas.bts.identity.session.SessionService
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.core.Authentication
import org.springframework.security.web.authentication.AuthenticationSuccessHandler
import org.springframework.stereotype.Component
import java.time.Clock

/**
 * SAML2 인증 성공 후 BTS 세션/JWT 를 발급하는 [AuthenticationSuccessHandler] (FR-AU-03).
 *
 * RED 단계 골격 — GREEN 에서 본문 구현.
 */
@Component
class Saml2AuthenticationSuccessHandler(
    private val configRepo: SamlIdpConfigRepository,
    private val autoProvisionService: AutoProvisionService,
    private val sessionService: SessionService,
    private val refreshTokenRepository: RefreshTokenRepository,
    private val jwtIssuer: JwtIssuer,
    private val clock: Clock = Clock.systemUTC(),
) : AuthenticationSuccessHandler {
    override fun onAuthenticationSuccess(
        request: HttpServletRequest,
        response: HttpServletResponse,
        authentication: Authentication,
    ) {
        TODO("GREEN 단계에서 구현")
    }
}
