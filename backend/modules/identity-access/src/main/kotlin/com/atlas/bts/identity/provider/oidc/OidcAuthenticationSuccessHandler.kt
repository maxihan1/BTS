// OIDC 인증 성공 후 BTS 세션/JWT 발급 + 복귀 경로 핸들러 (FR-AU-04)

package com.atlas.bts.identity.provider.oidc

import com.atlas.bts.identity.jwt.JwtIssuer
import com.atlas.bts.identity.provider.ldap.AutoProvisionService
import com.atlas.bts.identity.provider.ldap.LdapProvisionAttrs
import com.atlas.bts.identity.provider.saml.RelayStateValidator
import com.atlas.bts.identity.session.RefreshToken
import com.atlas.bts.identity.session.RefreshTokenRepository
import com.atlas.bts.identity.session.SessionService
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.security.core.Authentication
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken
import org.springframework.security.oauth2.core.oidc.user.OidcUser
import org.springframework.security.web.authentication.AuthenticationSuccessHandler
import org.springframework.stereotype.Component
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * OIDC 인증 성공 후 BTS 세션/JWT 를 발급하는 [AuthenticationSuccessHandler] (FR-AU-04).
 *
 * **검증 주체**:
 * OIDC ID Token 의 서명·issuer·audience·만료 검증과 authorization code 교환은
 * Spring Security OAuth2/OIDC 필터가 이미 수행한 뒤 이 핸들러가 호출된다. 따라서 이 핸들러는
 * 인증 검증을 하지 않고, 검증된 식별 정보로 BTS 의 세션/JWT 를 발급하는 후처리만 담당한다.
 *
 * **처리 흐름** ([Saml2AuthenticationSuccessHandler] 와 동형):
 * 1. [OAuth2AuthenticationToken] 의 principal([OidcUser]) 에서 sub / email / name / registrationId 추출
 * 2. [OidcProviderConfigReader.findByRegistrationId] 로 registration_id → authn_provider_id 해소
 * 3. [AutoProvisionService.provision] 로 JIT 프로비저닝 (users + user_external_accounts UPSERT 단일 트랜잭션)
 * 4. [SessionService.create] 로 세션 생성
 * 5. [RefreshTokenRepository.save] 로 refresh token 발급 + HttpOnly Cookie 설정
 * 6. [JwtIssuer.issue] 로 access token 발급
 * 7. [RelayStateValidator] 로 복귀 경로를 검증한 뒤 리다이렉트 (open-redirect 차단)
 *
 * **세션/JWT 발급 컴포넌트 재사용**:
 * SAML 핸들러와 동일하게 [SessionService]/[RefreshTokenRepository]/[JwtIssuer] 를 직접 호출하며,
 * rawToken/해시 유틸은 이 핸들러가 자체 보유한다.
 *
 * **복귀 경로 검증 재사용**:
 * open-redirect 차단 로직(N5)은 SAML 이 쓰는 [RelayStateValidator] 를 그대로 재사용한다
 * (상태 없는 순수 검증기). 같은 origin 상대 경로만 허용하고 그 외는 [RelayStateValidator] 기본 경로.
 *
 * **보안 (DEVELOPMENT.md §1.1, §1.2)**:
 * - refresh token raw 값은 응답 Cookie 에만 한 번 노출되고 DB 에는 SHA-256 해시만 저장한다 (§1.1.1).
 * - 로그에 sub/email(PII) 를 출력하지 않는다 — providerId / registrationId 만 출력한다 (§1.1.2).
 *
 * **시각 의존 (time-bomb 회귀 방지)**:
 * refresh token TTL 계산은 주입된 [Clock] 기준으로 한다 (기본 systemUTC, 테스트는 Clock.fixed).
 *
 * **@Transactional 경계**:
 * JIT 의 트랜잭션은 [AutoProvisionService] 가 보장한다. 세션/토큰 저장은 각 서비스가
 * 자체 트랜잭션을 갖는다 (SAML 핸들러와 동일하게 핸들러에는 @Transactional 미부착).
 */
@Component
class OidcAuthenticationSuccessHandler(
    private val configRepo: OidcProviderConfigReader,
    private val autoProvisionService: AutoProvisionService,
    private val sessionService: SessionService,
    private val refreshTokenRepository: RefreshTokenRepository,
    private val jwtIssuer: JwtIssuer,
    private val clock: Clock = Clock.systemUTC(),
) : AuthenticationSuccessHandler {
    private val log = LoggerFactory.getLogger(OidcAuthenticationSuccessHandler::class.java)

    /** 복귀 경로 open-redirect 검증기 — SAML 과 공유하는 상태 없는 순수 검증기 (DI 불요). */
    private val relayStateValidator = RelayStateValidator()

    /**
     * OIDC 인증 성공 후 세션/JWT 발급 + 복귀 경로 리다이렉트.
     *
     * @param authentication 필터가 생성한 [OAuth2AuthenticationToken] (principal = [OidcUser])
     */
    override fun onAuthenticationSuccess(
        request: HttpServletRequest,
        response: HttpServletResponse,
        authentication: Authentication,
    ) {
        val token = authentication as OAuth2AuthenticationToken
        val principal = token.principal as OidcUser
        val registrationId = token.authorizedClientRegistrationId
        val sub = principal.subject

        val config =
            configRepo.findByRegistrationId(registrationId)
                ?: error("OIDC Provider 설정 없음 — registrationId=$registrationId")
        val providerId = config.authnProviderId

        val account =
            autoProvisionService.provision(
                providerId = providerId,
                attrs = toProvisionAttrs(sub, principal),
            )

        issueTokens(request, response, account.userId)

        val target = relayStateValidator.resolve(request.getParameter(RETURN_PARAM))
        log.debug("OIDC 인증 성공 — providerId={}, registrationId={}", providerId, registrationId)
        response.sendRedirect(target)
    }

    /**
     * OIDC principal 을 [LdapProvisionAttrs] 로 매핑한다 (공통 프로비저닝 VO 재사용).
     *
     * sub 를 [LdapProvisionAttrs.externalSubject] 로, displayName 은 name → preferred_username →
     * sub 순으로 fallback 한다. username 은 sub 를 사용한다 (외부 IdP 고유 식별자).
     */
    private fun toProvisionAttrs(
        sub: String,
        principal: OidcUser,
    ): LdapProvisionAttrs {
        val displayName = principal.fullName ?: principal.preferredUsername ?: sub
        return LdapProvisionAttrs(
            username = sub,
            email = principal.email,
            displayName = displayName,
            externalSubject = sub,
            groups = emptyList(),
        )
    }

    /**
     * 세션 생성 + refresh token 저장(HttpOnly Cookie) + access token 발급.
     *
     * [Saml2AuthenticationSuccessHandler] 와 동일한 컴포넌트를 호출한다.
     */
    private fun issueTokens(
        request: HttpServletRequest,
        response: HttpServletResponse,
        userId: UUID,
    ) {
        val session =
            sessionService.create(
                userId = userId,
                providerId = PROVIDER_ID,
                ipAddress = request.remoteAddr.takeIf { it.isNotBlank() },
                userAgent = request.getHeader(HttpHeaders.USER_AGENT),
            )

        val rawToken = generateRawToken()
        val now = clock.instant()
        refreshTokenRepository.save(
            RefreshToken(
                id = UUID.randomUUID(),
                sessionId = session.id,
                tokenHash = sha256Hex(rawToken),
                issuedAt = now,
                expiresAt = now.plus(REFRESH_TTL_DAYS, ChronoUnit.DAYS),
                usedAt = null,
                replacedBy = null,
            ),
        )
        response.addHeader(HttpHeaders.SET_COOKIE, buildRefreshCookie(rawToken))

        jwtIssuer.issue(
            userId = session.userId,
            sessionId = session.id,
            providerId = session.providerId,
            scopes = emptyList(),
        )
    }

    /** refresh_token HttpOnly Secure SameSite=Strict Cookie 헤더 값 (SAML 핸들러와 동일 속성). */
    private fun buildRefreshCookie(value: String): String =
        "$REFRESH_COOKIE_NAME=$value; HttpOnly; Secure; SameSite=Strict; Path=$COOKIE_PATH; Max-Age=$REFRESH_MAX_AGE"

    /** [TOKEN_BYTES] 바이트 CSPRNG 난수를 hex 문자열로 인코딩한다. */
    private fun generateRawToken(): String {
        val bytes = ByteArray(TOKEN_BYTES)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /** SHA-256(input) hex 소문자 64자. */
    private fun sha256Hex(input: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private companion object {
        /** 복귀 경로 요청 파라미터명 (OIDC 인증 후 돌아갈 상대 경로). */
        const val RETURN_PARAM = "returnTo"

        /** 세션 providerId — OIDC 공급자 식별자 (소문자 관례, SAML 핸들러와 동일 패턴). */
        const val PROVIDER_ID = "oidc"

        const val REFRESH_COOKIE_NAME = "refresh_token"
        const val COOKIE_PATH = "/api/v1/auth"
        const val REFRESH_MAX_AGE = 1_209_600
        const val REFRESH_TTL_DAYS = 14L
        const val TOKEN_BYTES = 32
    }
}
