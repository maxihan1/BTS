// SAML2 인증 성공 후 BTS 세션/JWT 발급 + RelayState 복귀 핸들러 (FR-AU-03)

package com.atlas.bts.identity.provider.saml

import com.atlas.bts.identity.account.SsoLinkingCallbackProcessor
import com.atlas.bts.identity.account.SsoLinkingIntent
import com.atlas.bts.identity.account.SsoLinkingIntentStore
import com.atlas.bts.identity.audit.AuthAuditLog
import com.atlas.bts.identity.audit.AuthAuditLogService
import com.atlas.bts.identity.audit.AuthEventType
import com.atlas.bts.identity.jwt.JwtIssuer
import com.atlas.bts.identity.provider.ldap.AutoProvisionService
import com.atlas.bts.identity.provider.ldap.LdapProvisionAttrs
import com.atlas.bts.identity.session.RefreshToken
import com.atlas.bts.identity.session.RefreshTokenRepository
import com.atlas.bts.identity.session.SessionService
import com.atlas.bts.identity.spi.ProviderType
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
 *
 * **연결 모드 분기 (FR-AU-08b, FR3 fail-closed)**:
 * 콜백 시점의 HttpSession 에 SSO 연결 인텐트가 있으면(start XHR 가 심어둠) 일반 로그인(위 1~7)
 * 대신 [SsoLinkingCallbackProcessor] 로 위임한다. 인텐트가 있으면 [issueTokens]/[AutoProvisionService.provision]
 * 경로에 **물리적으로 진입하지 않는다**(early return) — 분기 누락 시에도 발급이 일어나지 않는 fail-closed.
 * 콜백 providerId 는 **enabled 재해소**([SamlIdpConfigRepository.findEnabledByRegistrationId])로만 얻는다
 * (start↔콜백 TOCTOU 차단, EC16). 인텐트가 없으면 위 일반 로그인 흐름이 무변경(EC1 회귀).
 */
@Component
@Suppress("LongParameterList") // DI 생성자 — 세션/JWT 발급 협력자 + 연결 모드 분기(callbackProcessor) + Clock 주입
class Saml2AuthenticationSuccessHandler(
    private val configRepo: SamlIdpConfigRepository,
    private val autoProvisionService: AutoProvisionService,
    private val sessionService: SessionService,
    private val refreshTokenRepository: RefreshTokenRepository,
    private val jwtIssuer: JwtIssuer,
    private val callbackProcessor: SsoLinkingCallbackProcessor,
    private val intentStore: SsoLinkingIntentStore,
    private val auditLogService: AuthAuditLogService,
    private val clock: Clock = Clock.systemUTC(),
) : AuthenticationSuccessHandler {
    private val log = LoggerFactory.getLogger(Saml2AuthenticationSuccessHandler::class.java)

    /** RelayState open-redirect 검증기 — 상태 없는 순수 검증기이므로 내부 보유 (DI 불요). */
    private val relayStateValidator = RelayStateValidator()

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

        // 연결 모드 분기 (FR3 fail-closed) — 인텐트가 있으면 발급 경로로 진입하지 않는다.
        if (linkingIntentOrNull(request) != null) {
            handleLinkingMode(request, response, registrationId, nameId)
            return
        }

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
        // 일반 로그인 경로에서만 LOGIN_SUCCESS 기록 — 연결 모드 early-return 은 위에서 처리되어 도달하지 않는다(C-3).
        recordLoginSuccess(request, account.userId)

        val target = relayStateValidator.resolve(request.getParameter(RELAY_STATE_PARAM))
        log.debug("SAML 인증 성공 — providerId={}, registrationId={}", providerId, registrationId)
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
     * userId=프로비저닝된 주체, providerId="saml", ip/userAgent=요청에서 캡처(PII 는 로그 미출력).
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
     * PII(ip/userAgent/nameId)를 출력하지 않고 이벤트 유형만 남긴다(DEVELOPMENT.md §1.2).
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
     * 콜백 시점 세션의 SSO 연결 인텐트를 **비소비 peek** 한다 (분기 판단용).
     *
     * 실제 1회용 소비/만료 검사는 [SsoLinkingCallbackProcessor] 가 [SsoLinkingIntentStore.consume] 으로
     * 단일 지점에서 수행한다. 여기서는 인텐트 존재 여부와 mode(error 경로 분기)만 본다.
     * 세션이 없거나(`getSession(false)` == null) 속성이 없으면 일반 로그인 경로(null 반환).
     */
    private fun linkingIntentOrNull(request: HttpServletRequest): SsoLinkingIntent? =
        request.getSession(false)?.getAttribute(SsoLinkingIntentStore.ATTRIBUTE_KEY) as? SsoLinkingIntent

    /**
     * 연결 모드 처리 (FR3) — enabled 재해소 후 [SsoLinkingCallbackProcessor] 로 위임한다.
     *
     * - 비활성/미해소(콜백 TOCTOU, EC16): 발급 없이 mode 별 error 경로로 리다이렉트한다.
     * - [SsoLinkingCallbackProcessor.process] 가 false(인텐트 만료/소실, EC5): 발급 없이 error 경로.
     * - true: processor 가 이미 리다이렉트를 썼다(성공/충돌/실패 모두 리다이렉트로 종결).
     *
     * 어느 경로든 [issueTokens]/[AutoProvisionService.provision] 에 진입하지 않는다(fail-closed).
     *
     * **intent 1회용 소비 대칭 (C3)**: 활성 경로는 [SsoLinkingCallbackProcessor.process] 가
     * [SsoLinkingIntentStore.consume] 으로 intent 를 제거한다. processor 를 타지 않는 비활성/미해소
     * 경로(EC16)에서는 여기서 [SsoLinkingIntentStore.consume] 으로 명시 제거해 세션 잔류를 막는다
     * (단명 만료와 무관하게 1회용 계약을 양 경로에서 대칭으로 유지).
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
            log.debug("SAML 연결 모드 거부 — registrationId={}, enabled 미해소", registrationId)
            response.sendRedirect(errorPath(intent?.mode))
            return
        }
        val handled =
            callbackProcessor.process(
                session = session,
                providerType = ProviderType.SAML,
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
     * 연결/재인증 거부 시 돌아갈 서버 고정 설정 경로 + mode 별 error status 를 만든다 (open-redirect 0, EC14).
     *
     * 비활성 provider(EC16)·인텐트 만료(EC5) 등 발급 없이 거부할 때 쓴다. 사용자 입력을 echo 하지 않는다.
     * mode 가 null(인텐트 소실)이면 LINK error 로 폴백한다.
     */
    private fun errorPath(mode: SsoLinkingIntent.Mode?): String =
        SETTINGS_PATH +
            if (mode == SsoLinkingIntent.Mode.REAUTH) REAUTH_FAILED else LINK_ERROR

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

        /** 연결 모드 거부 시 돌아갈 서버 고정 설정 경로 (open-redirect 0, EC14 — SsoLinkingCallbackProcessor 와 동일). */
        const val SETTINGS_PATH = "/settings/account-links"

        /** LINK 모드 거부 status 쿼리 (비활성/만료). */
        const val LINK_ERROR = "?link=error"

        /** REAUTH 모드 거부 status 쿼리 (비활성/만료). */
        const val REAUTH_FAILED = "?reauth=failed"
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

    private fun isSafeRelativePath(value: String): Boolean =
        value.length >= MIN_PATH_LENGTH &&
            value[0] == '/' &&
            // 프로토콜 상대(`//host`) 및 백슬래시 우회(`/\host`) 차단
            value[1] != '/' &&
            value[1] != '\\' &&
            // scheme 형태(`/javascript:` 등 콜론 포함) 차단
            !value.contains(':')

    private companion object {
        /** RelayState 가 비었거나 안전하지 않을 때의 기본 복귀 경로. */
        const val DEFAULT_TARGET = "/dashboard"

        /** 안전 판단을 위한 최소 길이 — `/` + 최소 1자 (인덱스 1 접근 보장). */
        const val MIN_PATH_LENGTH = 2
    }
}
