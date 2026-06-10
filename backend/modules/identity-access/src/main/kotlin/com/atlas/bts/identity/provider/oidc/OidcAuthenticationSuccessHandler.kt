// OIDC 인증 성공 후 BTS 세션/JWT 발급 + 복귀 경로 핸들러 (FR-AU-04)

package com.atlas.bts.identity.provider.oidc

import com.atlas.bts.identity.account.SsoLinkingCallbackProcessor
import com.atlas.bts.identity.account.SsoLinkingIntent
import com.atlas.bts.identity.account.SsoLinkingIntentStore
import com.atlas.bts.identity.audit.AuthAuditLog
import com.atlas.bts.identity.audit.AuthAuditLogService
import com.atlas.bts.identity.audit.AuthEventType
import com.atlas.bts.identity.jwt.JwtIssuer
import com.atlas.bts.identity.provider.ldap.AutoProvisionService
import com.atlas.bts.identity.provider.ldap.LdapProvisionAttrs
import com.atlas.bts.identity.provider.saml.RelayStateValidator
import com.atlas.bts.identity.session.RefreshToken
import com.atlas.bts.identity.session.RefreshTokenRepository
import com.atlas.bts.identity.session.SessionService
import com.atlas.bts.identity.spi.ProviderType
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
 *
 * **연결 모드 분기 (FR-AU-08b, FR3 fail-closed — SAML 핸들러 동형)**:
 * 콜백 시점의 HttpSession 에 SSO 연결 인텐트가 있으면 일반 로그인(위 1~7) 대신
 * [SsoLinkingCallbackProcessor] 로 위임하고, [issueTokens]/[AutoProvisionService.provision] 경로에
 * **진입하지 않는다**(early return, fail-closed). 콜백 providerId 는 일반 로그인의
 * [OidcProviderConfigReader.findByRegistrationId](enabled 무관)와 달리 **enabled 재해소**
 * ([OidcProviderConfigReader.findEnabledByRegistrationId])로만 얻는다 — SAML 과 동일하게 enabled 를
 * 거른다(OIDC 의 enabled 미필터 비대칭 보정, start↔콜백 TOCTOU 차단, EC16). 인텐트가 없으면 무변경(EC1).
 */
@Component
@Suppress("LongParameterList") // DI 생성자 — 세션/JWT 발급 협력자 + 연결 모드 분기(callbackProcessor) + Clock 주입
class OidcAuthenticationSuccessHandler(
    private val configRepo: OidcProviderConfigReader,
    private val autoProvisionService: AutoProvisionService,
    private val sessionService: SessionService,
    private val refreshTokenRepository: RefreshTokenRepository,
    private val jwtIssuer: JwtIssuer,
    private val callbackProcessor: SsoLinkingCallbackProcessor,
    private val intentStore: SsoLinkingIntentStore,
    private val auditLogService: AuthAuditLogService,
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

        // 연결 모드 분기 (FR3 fail-closed) — 인텐트가 있으면 발급 경로로 진입하지 않는다.
        if (linkingIntentOrNull(request) != null) {
            handleLinkingMode(request, response, registrationId, sub)
            return
        }

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
        // 일반 로그인 경로에서만 LOGIN_SUCCESS 기록 — 연결 모드 early-return 은 위에서 처리되어 도달하지 않는다(C-3).
        recordLoginSuccess(request, account.userId)

        val target = relayStateValidator.resolve(request.getParameter(RETURN_PARAM))
        log.debug("OIDC 인증 성공 — providerId={}, registrationId={}", providerId, registrationId)
        response.sendRedirect(target)
    }

    /**
     * LOGIN_SUCCESS 감사 이벤트를 best-effort 로 기록한다 (FR-AU-10 Task 8 / spec §5, EC-12, NFR-3 B-1).
     *
     * **연결 모드 비-emit (C-3)**: 이 헬퍼는 일반 로그인 경로([onAuthenticationSuccess] 의 [issueTokens] 직후)
     * 에서만 호출된다. 연결 모드는 그 위에서 early-return 으로 분기되므로 여기에 도달하지 않는다 — 연결은 로그인이 아니다.
     *
     * **best-effort (B-1)**: 이 핸들러는 web 레이어이고 @Transactional 이 아니므로(클래스 KDoc 참조), 감사 INSERT 실패가
     * 로그인 가용성을 인질로 잡으면 안 된다. [recordAuditBestEffort] 로 감싸 실패해도 발급/리다이렉트 흐름은 계속한다.
     *
     * userId=프로비저닝된 주체, providerId="oidc", ip/userAgent=요청에서 캡처(PII 는 로그 미출력).
     *
     * @param request IP/UserAgent 캡처용 HTTP 요청
     * @param userId 프로비저닝된 주체 사용자 ID
     */
    private fun recordLoginSuccess(
        request: HttpServletRequest,
        userId: UUID,
    ) {
        recordAuditBestEffort(
            AuthAuditLog(
                userId = userId,
                eventType = AuthEventType.LOGIN_SUCCESS,
                providerId = PROVIDER_ID,
                ipAddress = request.remoteAddr.takeIf { it.isNotBlank() },
                userAgent = request.getHeader(HttpHeaders.USER_AGENT),
            ),
        )
    }

    /**
     * 감사 이벤트를 best-effort 로 기록한다 (NFR-3 B-1 — web 레이어 emit 정책, AuthController 동형).
     *
     * SSO 성공 핸들러는 의도적 무-트랜잭션이므로(클래스 KDoc 참조), 감사 INSERT 실패가 로그인 가용성을
     * 인질로 잡으면 안 된다. 따라서 [record][AuthAuditLogService.record] 예외를 catch 해 흐름을 계속한다.
     * 단 **silent 삼킴은 금지** — high-severity 에러 로그로 감사 갭을 탐지 가능하게 한다. 로그에는
     * PII(ip/userAgent/sub)를 출력하지 않고 이벤트 유형만 남긴다(DEVELOPMENT.md §1.2).
     *
     * `TooGenericExceptionCaught` 억제 — B-1 가용성 우선 정책상 어떤 RuntimeException 이든(DataAccess/
     * 직렬화/타임아웃 등) 흐름을 계속해야 하며, error 로그로 감사 갭을 경보하므로 generic catch 가 의도적이다.
     *
     * @param event 기록할 감사 이벤트
     */
    @Suppress("TooGenericExceptionCaught")
    private fun recordAuditBestEffort(event: AuthAuditLog) {
        try {
            auditLogService.record(event)
        } catch (ex: RuntimeException) {
            // 감사 갭 경보 — 가용성 우선이라 흐름은 계속하되 silent 삼킴은 아니다(B-1). PII 미출력.
            log.error("audit emit failed (availability preserved): eventType={}", event.eventType, ex)
        }
    }

    /**
     * 콜백 시점 세션의 SSO 연결 인텐트를 **비소비 peek** 한다 (분기 판단용, SAML 핸들러 동형).
     *
     * 실제 1회용 소비/만료 검사는 [SsoLinkingCallbackProcessor] 가 [SsoLinkingIntentStore.consume] 으로
     * 단일 지점에서 수행한다. 세션이 없거나 속성이 없으면 일반 로그인 경로(null 반환).
     */
    private fun linkingIntentOrNull(request: HttpServletRequest): SsoLinkingIntent? =
        request.getSession(false)?.getAttribute(SsoLinkingIntentStore.ATTRIBUTE_KEY) as? SsoLinkingIntent

    /**
     * 연결 모드 처리 (FR3) — **enabled 재해소** 후 [SsoLinkingCallbackProcessor] 로 위임한다 (SAML 동형).
     *
     * 일반 로그인의 [OidcProviderConfigReader.findByRegistrationId] 와 달리
     * [OidcProviderConfigReader.findEnabledByRegistrationId] 로 enabled 를 거른다(EC16).
     * 비활성/미해소·인텐트 만료(processor false) 모두 발급 없이 mode 별 error 경로로 리다이렉트한다(fail-closed).
     *
     * **intent 1회용 소비 대칭 (C3, SAML 동형)**: 활성 경로는 [SsoLinkingCallbackProcessor.process] 가
     * [SsoLinkingIntentStore.consume] 으로 intent 를 제거한다. processor 를 타지 않는 비활성/미해소
     * 경로(EC16)에서는 여기서 [SsoLinkingIntentStore.consume] 으로 명시 제거해 세션 잔류를 막는다.
     */
    private fun handleLinkingMode(
        request: HttpServletRequest,
        response: HttpServletResponse,
        registrationId: String,
        externalSubject: String,
    ) {
        val intent = linkingIntentOrNull(request)
        val session = request.getSession(false)
        val config = configRepo.findEnabledByRegistrationId(registrationId)
        if (intent == null || session == null || config == null) {
            // processor 를 안 타는 거부 경로 — intent 를 명시 제거(1회용 대칭, C3).
            session?.let { intentStore.consume(it) }
            log.debug("OIDC 연결 모드 거부 — registrationId={}, enabled 미해소", registrationId)
            response.sendRedirect(errorPath(intent?.mode))
            return
        }
        val handled =
            callbackProcessor.process(
                session = session,
                providerType = ProviderType.OIDC,
                registrationId = registrationId,
                providerId = config.authnProviderId,
                externalSubject = externalSubject,
                groups = emptyList(),
                response = response,
            )
        if (!handled) {
            response.sendRedirect(errorPath(intent.mode))
        }
    }

    /**
     * 연결/재인증 거부 시 돌아갈 서버 고정 설정 경로 + mode 별 error status (open-redirect 0, SAML 동형).
     *
     * mode 가 null(인텐트 소실)이면 LINK error 로 폴백한다.
     */
    private fun errorPath(mode: SsoLinkingIntent.Mode?): String =
        SETTINGS_PATH +
            if (mode == SsoLinkingIntent.Mode.REAUTH) REAUTH_FAILED else LINK_ERROR

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

        /** 연결 모드 거부 시 돌아갈 서버 고정 설정 경로 (open-redirect 0, EC14 — SsoLinkingCallbackProcessor 와 동일). */
        const val SETTINGS_PATH = "/settings/account-links"

        /** LINK 모드 거부 status 쿼리 (비활성/만료). */
        const val LINK_ERROR = "?link=error"

        /** REAUTH 모드 거부 status 쿼리 (비활성/만료). */
        const val REAUTH_FAILED = "?reauth=failed"
    }
}
