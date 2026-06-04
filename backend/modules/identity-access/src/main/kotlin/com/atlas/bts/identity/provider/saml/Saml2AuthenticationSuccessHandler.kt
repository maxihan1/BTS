// SAML2 인증 성공 후 BTS 세션/JWT 발급 + RelayState 복귀 핸들러 (FR-AU-03)

package com.atlas.bts.identity.provider.saml

import com.atlas.bts.identity.jwt.JwtIssuer
import com.atlas.bts.identity.provider.ldap.AutoProvisionService
import com.atlas.bts.identity.provider.ldap.LdapProvisionAttrs
import com.atlas.bts.identity.session.RefreshToken
import com.atlas.bts.identity.session.RefreshTokenRepository
import com.atlas.bts.identity.session.SessionService
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.security.core.Authentication
import org.springframework.security.saml2.provider.service.authentication.Saml2AuthenticatedPrincipal
import org.springframework.security.saml2.provider.service.authentication.Saml2Authentication
import org.springframework.security.web.authentication.AuthenticationSuccessHandler
import org.springframework.stereotype.Component
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * SAML2 인증 성공 후 BTS 세션/JWT 를 발급하는 [AuthenticationSuccessHandler] (FR-AU-03).
 *
 * **검증 주체 (게이트1 D4)**:
 * SAML Assertion 의 서명·시각·audience 검증은 Spring Security SAML2 필터가 이미 수행한 뒤
 * 이 핸들러가 호출된다. 따라서 이 핸들러는 인증 검증을 하지 않고, 검증된 식별 정보로
 * BTS 의 세션/JWT 를 발급하는 후처리만 담당한다.
 *
 * **처리 흐름** ([com.atlas.bts.identity.web.AuthController] 의 login 성공 경로와 동일):
 * 1. [Saml2Authentication] 의 principal 에서 nameId / registrationId 추출
 * 2. [SamlIdpConfigRepository] 로 registration_id → authn_provider_id 해소 (C9)
 * 3. [AutoProvisionService.provision] 로 JIT 프로비저닝 (users + user_external_accounts UPSERT 단일 트랜잭션)
 * 4. [SessionService.create] 로 세션 생성
 * 5. [RefreshTokenRepository.save] 로 refresh token 발급 + HttpOnly Cookie 설정
 * 6. [JwtIssuer.issue] 로 access token 발급
 * 7. [RelayStateValidator] 로 복귀 경로를 검증한 뒤 리다이렉트
 *
 * **세션/JWT 발급 컴포넌트 재사용**:
 * AuthController.login 의 토큰 발급 로직(rawToken 생성 + sha256 + refresh save)이 컨트롤러
 * 내부에 인라인되어 공용 컴포넌트로 추출되어 있지 않다. AuthController 는 동시 작업(Task 6)
 * 영역이라 본 PR 에서 추출하지 못한다. 따라서 동일한 [SessionService]/[RefreshTokenRepository]/
 * [JwtIssuer] 를 직접 호출하되, rawToken/해시 유틸은 이 핸들러가 자체 보유한다.
 *
 * **보안 (DEVELOPMENT.md §1.1, §1.2)**:
 * - refresh token raw 값은 응답 Cookie 에만 한 번 노출되고 DB 에는 SHA-256 해시만 저장한다.
 * - 로그에 nameId(PII) 를 출력하지 않는다 — providerId / registrationId 만 출력한다.
 *
 * **시각 의존 (N3, time-bomb 회귀 방지)**:
 * refresh token TTL 계산은 주입된 [Clock] 기준으로 한다 (기본 systemUTC, 테스트는 Clock.fixed).
 *
 * **@Transactional 경계**:
 * JIT 의 트랜잭션은 [AutoProvisionService] 가 보장한다. 세션/토큰 저장은 각 서비스가
 * 자체 트랜잭션을 갖는다 (AuthController 와 동일하게 핸들러에는 @Transactional 미부착).
 */
@Component
class Saml2AuthenticationSuccessHandler(
    private val configRepo: SamlIdpConfigRepository,
    private val autoProvisionService: AutoProvisionService,
    private val sessionService: SessionService,
    private val refreshTokenRepository: RefreshTokenRepository,
    private val jwtIssuer: JwtIssuer,
    private val relayStateValidator: RelayStateValidator = RelayStateValidator(),
    private val clock: Clock = Clock.systemUTC(),
) : AuthenticationSuccessHandler {
    private val log = LoggerFactory.getLogger(Saml2AuthenticationSuccessHandler::class.java)

    /**
     * SAML2 인증 성공 후 세션/JWT 발급 + RelayState 복귀 리다이렉트.
     *
     * @param authentication 필터가 생성한 [Saml2Authentication] (principal = [Saml2AuthenticatedPrincipal])
     */
    override fun onAuthenticationSuccess(
        request: HttpServletRequest,
        response: HttpServletResponse,
        authentication: Authentication,
    ) {
        val principal = (authentication as Saml2Authentication).principal as Saml2AuthenticatedPrincipal
        val registrationId = principal.relyingPartyRegistrationId
        val nameId = principal.name

        val config =
            configRepo.findEnabledByRegistrationId(registrationId)
                ?: error("활성 SAML IdP 설정 없음 — registrationId=$registrationId")
        val providerId = config.authnProviderId

        val account =
            autoProvisionService.provision(
                providerId = providerId,
                attrs = toProvisionAttrs(nameId, principal),
            )

        issueTokens(request, response, account.userId)

        val target = relayStateValidator.resolve(request.getParameter(RELAY_STATE_PARAM))
        log.debug("SAML 인증 성공 — providerId={}, registrationId={}", providerId, registrationId)
        response.sendRedirect(target)
    }

    /**
     * SAML principal 을 [LdapProvisionAttrs] 로 매핑한다 (공통 프로비저닝 VO 재사용).
     *
     * nameId 를 [LdapProvisionAttrs.externalSubject] 로, email/displayName 은 SAML Attribute 에서
     * 추출하되 없으면 nameId 로 fallback 한다.
     */
    private fun toProvisionAttrs(
        nameId: String,
        principal: Saml2AuthenticatedPrincipal,
    ): LdapProvisionAttrs {
        val email = principal.getFirstAttribute<String>(ATTR_EMAIL)
        val displayName = principal.getFirstAttribute<String>(ATTR_DISPLAY_NAME) ?: nameId
        return LdapProvisionAttrs(
            username = nameId,
            email = email,
            displayName = displayName,
            externalSubject = nameId,
            groups = emptyList(),
        )
    }

    /**
     * 세션 생성 + refresh token 저장(HttpOnly Cookie) + access token 발급.
     *
     * [com.atlas.bts.identity.web.AuthController] login 성공 경로와 동일한 컴포넌트를 호출한다.
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

    /** refresh_token HttpOnly Secure SameSite=Strict Cookie 헤더 값 (AuthController 와 동일 속성). */
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
        /** RelayState 요청 파라미터명 (SAML SP-initiated 복귀 경로). */
        const val RELAY_STATE_PARAM = "RelayState"

        /** 세션 providerId — SAML 공급자 식별자 (소문자 관례, AuthController 와 동일). */
        const val PROVIDER_ID = "saml"

        /** SAML email 속성명 (IdP Attribute). */
        const val ATTR_EMAIL = "email"

        /** SAML displayName 속성명 (IdP Attribute). */
        const val ATTR_DISPLAY_NAME = "displayName"

        const val REFRESH_COOKIE_NAME = "refresh_token"
        const val COOKIE_PATH = "/api/v1/auth"
        const val REFRESH_MAX_AGE = 1_209_600
        const val REFRESH_TTL_DAYS = 14L
        const val TOKEN_BYTES = 32
    }
}

/**
 * SAML RelayState(인증 후 복귀 경로) open-redirect 차단 검증기 (N2).
 *
 * RelayState 는 외부 IdP 를 거쳐 돌아오는 사용자 제어 입력이므로 신뢰하지 않는다.
 * **같은 origin 의 상대 경로만 허용**하고, 그 외(절대 URL / 프로토콜 상대 `//host` /
 * 백슬래시 우회 / 빈 값)는 모두 기본 경로([DEFAULT_TARGET])로 강등한다.
 *
 * 허용 조건:
 * - `/` 로 시작하고
 * - `//` 또는 `/\` 로 시작하지 않으며 (프로토콜 상대 URL / 브라우저 백슬래시 정규화 우회 차단)
 * - `:` 가 경로 앞부분에 없음 (scheme 형태 차단)
 */
class RelayStateValidator {
    /**
     * [relayState] 가 안전한 상대 경로면 그대로, 아니면 [DEFAULT_TARGET] 을 반환한다.
     */
    fun resolve(relayState: String?): String {
        val candidate = relayState?.trim().orEmpty()
        return if (isSafeRelativePath(candidate)) candidate else DEFAULT_TARGET
    }

    private fun isSafeRelativePath(value: String): Boolean {
        if (value.length < MIN_PATH_LENGTH || value[0] != '/') return false
        // 프로토콜 상대(`//host`) 및 백슬래시 우회(`/\host`) 차단
        if (value[1] == '/' || value[1] == '\\') return false
        // scheme 형태(`/javascript:` 등 콜론 포함) 차단
        if (value.contains(':')) return false
        return true
    }

    private companion object {
        /** RelayState 가 비었거나 안전하지 않을 때의 기본 복귀 경로. */
        const val DEFAULT_TARGET = "/dashboard"

        /** 안전 판단을 위한 최소 길이 — `/` + 최소 1자 (인덱스 1 접근 보장). */
        const val MIN_PATH_LENGTH = 2
    }
}
