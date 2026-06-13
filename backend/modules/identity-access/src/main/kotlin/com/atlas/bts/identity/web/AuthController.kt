// 인증 엔드포인트 — login / logout / refresh / sessions / revokeSession (FR-AU-09 Task 21 / Task 2 / Task 3)

package com.atlas.bts.identity.web

import com.atlas.bts.identity.audit.AuthAuditLog
import com.atlas.bts.identity.audit.AuthAuditLogService
import com.atlas.bts.identity.audit.AuthEventType
import com.atlas.bts.identity.auth.CompositeAuthenticationManager
import com.atlas.bts.identity.jwt.JwtIssuer
import com.atlas.bts.identity.mfa.MfaBackupCodeService
import com.atlas.bts.identity.mfa.MfaChallengeClaims
import com.atlas.bts.identity.mfa.MfaChallengeTokenService
import com.atlas.bts.identity.mfa.MfaService
import com.atlas.bts.identity.mfa.MfaService.VerifyResult
import com.atlas.bts.identity.mfa.TrustedDeviceService
import com.atlas.bts.identity.mfa.WebAuthnSecurityKeyService
import com.atlas.bts.identity.provider.ldap.ProviderUnavailableException
import com.atlas.bts.identity.session.RefreshToken
import com.atlas.bts.identity.session.RefreshTokenRepository
import com.atlas.bts.identity.session.RefreshTokenService
import com.atlas.bts.identity.session.RefreshTokenService.FailureReason
import com.atlas.bts.identity.session.RefreshTokenService.RotateResult
import com.atlas.bts.identity.session.Session
import com.atlas.bts.identity.session.SessionService
import com.atlas.bts.identity.spi.AuthnResult
import com.atlas.bts.identity.spi.Principal
import com.atlas.bts.identity.systemrole.SystemRoleAssignmentRepository
import com.atlas.bts.identity.web.dto.SessionResponse
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.JsonNode
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * 인증 엔드포인트 컨트롤러 (FR-AU-09 Task 21 / SDD §19.5).
 *
 * ## 엔드포인트
 * - [login]: POST /api/v1/auth/login — provider 명시 디스패처([CompositeAuthenticationManager])로 인증
 *   → Session 생성 → RefreshToken 발급 → JWT 발급
 * - [logout]: POST /api/v1/auth/logout — sid 로 Session revoke + refresh chain revoke + Cookie 만료
 * - [refresh]: POST /api/v1/auth/refresh — Cookie 의 refresh_token → RefreshTokenService.rotate
 * - [listSessions]: GET /api/v1/auth/sessions — 본인 활성 세션 목록 조회 (JWT 전용, PAT 403)
 * - [revokeSession]: DELETE /api/v1/auth/sessions/{sid} — 본인 다른 활성 세션 강제 종료 (JWT 전용, PAT 403)
 *
 * ## 트랜잭션 경계
 * @Transactional 없음 — service layer(SessionService, RefreshTokenService) 가 각자 @Transactional 보장.
 * (FR-09-21 Brainstorming 발견 #21 — AuthController 는 @Transactional 부착 안 함)
 *
 * ## Cookie 속성 (FR-09-21 정정 — Cookie Path 통일)
 * login Set-Cookie / logout 만료 / refresh rotation 모두 `Path=/api/v1/auth` 로 일관 (RFC 6265).
 * HttpOnly; Secure; SameSite=Strict.
 *
 * ## CSRF 처리
 * - login: SecurityConfig 에서 CSRF skip (/api/v1/auth/login ignoringRequestMatchers)
 * - logout: Authorization Bearer 인증 사용 — Spring Security Bearer stateless CSRF 면제
 * - refresh: Cookie 기반 (Bearer 없음) — CSRF skip 추가 필요 시 SecurityConfig 에서 처리
 *
 * ## EC-04/22/23 처리
 * - EC-04 (refresh 만료): RotateResult.Failure(Expired) → 401 + "refresh_token_expired"
 * - EC-23 (replay 탐지): RotateResult.Failure(Replay) → 401 + "refresh_token_reused"
 * - EC-22 (race loser): RotateResult.Failure(Race) → 401 + "refresh_token_reused" (replay 와 동일 응답)
 * - EC-18 (logout idempotent): Authorization 헤더 없으면 Spring Security 가 401 반환 (Controller 미도달)
 */
@RestController
@RequestMapping("/api/v1/auth")
// LongParameterList 억제 — 모두 생성자 의존성 주입(DI)이며 임의 그룹핑은 응집도를 해친다.
// FR-PM-08 에서 systemRoleAssignmentRepository, FR-MF-03 에서 webAuthnSecurityKeyService 가 추가됐다.
// TooManyFunctions 억제 — login/logout/refresh/sessions/revokeSession 엔드포인트 + 응집된 private 헬퍼.
// FR-AU-06 에서 login 을 30줄 이내로 유지하려 issueTokens/errorResponse 헬퍼를 분리해 12개가 됐다.
@Suppress("LongParameterList", "TooManyFunctions")
class AuthController(
    private val authenticationManager: CompositeAuthenticationManager,
    private val sessionService: SessionService,
    private val refreshTokenRepository: RefreshTokenRepository,
    private val refreshTokenService: RefreshTokenService,
    private val jwtIssuer: JwtIssuer,
    private val systemRoleAssignmentRepository: SystemRoleAssignmentRepository,
    private val authAuditLogService: AuthAuditLogService,
    private val mfaService: MfaService,
    private val mfaChallengeTokenService: MfaChallengeTokenService,
    private val mfaBackupCodeService: MfaBackupCodeService,
    private val webAuthnSecurityKeyService: WebAuthnSecurityKeyService,
    private val trustedDeviceService: TrustedDeviceService,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val log = LoggerFactory.getLogger(AuthController::class.java)

    /**
     * POST /api/v1/auth/login — provider 명시 후 username/password 인증 → 토큰 발급 (FR-AU-06).
     *
     * 1. `body.provider` 필수 — blank/누락이면 **400 `provider_required`** (디스패처 미진입).
     * 2. [CompositeAuthenticationManager.authenticate]`(provider, username, password)` 로 위임.
     *    명시 선택만 수행하며 자동 fallback 은 없다 (CompositeAuthenticationManager 보안 결정 참조).
     * 3. [AuthnResult.Success] → [completeLogin] 으로 2단계(TOTP) 필요 여부를 분기한다 (FR-MF-01).
     *    TOTP 미활성이면 [issueTokens] (Session/RefreshToken/JWT 발급) → 200 + Set-Cookie,
     *    TOTP 활성이면 정식 세션 대신 챌린지 토큰만 발급 → 200 `mfa_required`.
     * 4. [AuthnResult.Failure] → **401 `invalid_credentials`** (reason 무관 — 계정/구성 열거 방지 NFR-06-01).
     * 5. [AuthnResult.RequiresMfa] → 401 `mfa_required` (디스패처 챌린지 — 현재 provider 미반환 dead path, 보존).
     *
     * ## 503 처리 (catch-all @ExceptionHandler 우회)
     * 디스패처가 전파하는 [ProviderUnavailableException](LDAP/디렉터리 장애)을 이 메서드 안에서 직접
     * catch 하여 **503 `provider_unavailable`** 로 응답한다. 전역 catch-all 핸들러가 이를 500 으로
     * 변질시키는 회귀를 막기 위함이다 (learning: catch-all-exceptionhandler-swallows-responsestatusexception).
     * catch 절은 예외 type(providerType)만 다루며 password/PII 는 로깅하지 않는다.
     *
     * EC-04 대응: 실패 시 session/token INSERT 없음.
     * 로그에 password 절대 미기록 (DEVELOPMENT.md §1.1 규칙 2).
     *
     * @param request HTTP 요청 (IP/UserAgent 추출용)
     * @param body 로그인 요청 body (provider 필수)
     * @return 200 TokenResponse / 400 provider_required / 401 invalid_credentials|mfa_required /
     *   503 provider_unavailable
     *
     * ReturnCount 억제 — 400(provider_required) / 503(provider_unavailable) guard early return 이
     * 중첩 if 보다 가독성 우수 (DEVELOPMENT.md §2.3 Early return 권장).
     */
    @Suppress("ReturnCount")
    @PostMapping("/login")
    fun login(
        request: HttpServletRequest,
        @RequestBody body: LoginRequest,
    ): ResponseEntity<*> {
        val provider =
            body.provider?.takeIf { it.isNotBlank() }
                ?: return errorResponse(HttpStatus.BAD_REQUEST, "provider_required")

        val result =
            try {
                authenticationManager.authenticate(provider, body.username, body.password.toCharArray())
            } catch (ex: ProviderUnavailableException) {
                // 503 직접 생성 — catch-all 핸들러가 500 으로 변질시키지 않도록. password/PII 미로깅.
                log.warn("login provider unavailable: providerType={}", ex.providerType)
                return errorResponse(HttpStatus.SERVICE_UNAVAILABLE, "provider_unavailable")
            }

        return when (result) {
            is AuthnResult.Success -> {
                recordLoginSuccess(request, result.principal)
                completeLogin(request, result.principal)
            }
            is AuthnResult.Failure -> {
                recordLoginFailure(request, provider, body.username, result.reason)
                errorResponse(HttpStatus.UNAUTHORIZED, "invalid_credentials")
            }
            is AuthnResult.RequiresMfa -> errorResponse(HttpStatus.UNAUTHORIZED, "mfa_required")
        }
    }

    /**
     * 1단계(비밀번호) 인증을 통과한 [principal] 에 대해 2단계(TOTP) 필요 여부에 따라 응답을 분기한다 (FR-MF-01).
     *
     * - MFA **활성**(TOTP [MfaService.isEnabled] 또는 보안키 [WebAuthnSecurityKeyService.hasActiveKey]):
     *   정식 세션을 발급하지 않고 단명 챌린지 토큰만 발급해 **200** `{mfa_required:true, mfa_challenge_token,
     *   expires_in}` 으로 응답한다. 클라이언트는 이 토큰과 2차 요소(TOTP/백업코드/보안키)를 `POST /mfa/verify`
     *   (보안키는 `webauthn/authenticate/start` 후 verify)로 보내 2단계를 마쳐야 정식 세션을 받는다.
     * - MFA **미활성**: 기존 흐름 그대로 [issueTokens] 로 정식 세션(`mfaVerified=false`)을 발급한다(회귀 0).
     *
     * ## MFA 활성 판정 — OR 합성(TOTP 우선 단락, FR-MF-03)
     * TOTP 또는 보안키 중 하나라도 활성이면 2단계로 진입한다. [MfaService.isEnabled] 를 먼저 평가해
     * TOTP 활성이면 [WebAuthnSecurityKeyService.hasActiveKey] 조회를 단락(short-circuit)한다(불필요한 DB 조회 회피).
     *
     * ## C7 타이밍 누출 방지
     * MFA 활성 조회는 1단계 Success 이후에만 수행한다(이 헬퍼는 Success 분기에서만 호출).
     * 비밀번호 오답(Failure) 경로는 조회하지 않으므로 MFA 보유 여부가 노출되지 않는다.
     *
     * @param request IP/UserAgent 추출용 HTTP 요청
     * @param principal 1단계 인증을 통과한 주체
     * @return MFA 활성 시 200 [MfaRequiredResponse], 미활성 시 200 [TokenResponse] + Set-Cookie
     */
    private fun completeLogin(
        request: HttpServletRequest,
        principal: Principal,
    ): ResponseEntity<*> {
        val providerId = principal.providerType.name.lowercase()
        if (!isAnyMfaEnabled(principal.userId)) {
            return issueTokens(request, principal.userId, providerId)
        }
        if (isTrustedDevice(request, principal.userId)) {
            // 신뢰 디바이스 우회 — 과거 MFA 통과로 신뢰가 성립했으므로 mfaVerified=true 세션을 발급한다.
            return issueTokens(request, principal.userId, providerId, mfaVerified = true)
        }
        val challengeToken = mfaChallengeTokenService.issueChallenge(principal.userId, providerId)
        return ResponseEntity.ok(
            MfaRequiredResponse(mfaChallengeToken = challengeToken, expiresIn = MFA_CHALLENGE_TTL_SECONDS),
        )
    }

    /**
     * 요청의 [TRUSTED_DEVICE_COOKIE] 쿠키가 [userId] 의 미만료 신뢰 디바이스와 일치하는지 판정한다 (FR-MF-05).
     *
     * 쿠키가 없으면 false 로 폴백해 기존 챌린지 경로를 탄다(쿠키 부재/차단 환경 fail-safe). 쿠키가 있으면
     * [TrustedDeviceService.verifyAndTouch] 로 user-bound + 미만료를 판정하며, 만료/타인/미상은 모두 false 로
     * 수렴한다(불명은 우회하지 않고 챌린지로 폴백). 쿠키 raw 값은 로깅하지 않는다(§1.1.2).
     *
     * @param request trusted_device 쿠키 추출용 HTTP 요청
     * @param userId 1단계 인증을 통과한 사용자(우회 user-bound 판정 기준)
     * @return user 일치 + 미만료 신뢰 디바이스면 true(우회 가능), 아니면 false
     */
    private fun isTrustedDevice(
        request: HttpServletRequest,
        userId: UUID,
    ): Boolean {
        val rawToken =
            request.cookies
                ?.firstOrNull { it.name == TRUSTED_DEVICE_COOKIE }
                ?.value
                ?: return false
        return trustedDeviceService.verifyAndTouch(userId, rawToken)
    }

    /**
     * 사용자가 2단계 인증 요소(TOTP 또는 보안키)를 하나라도 활성화했는지 판정한다 (FR-MF-03).
     *
     * TOTP([MfaService.isEnabled]) 를 먼저 평가해 활성이면 보안키 조회를 단락한다(OR short-circuit).
     *
     * @param userId 1단계 인증을 통과한 사용자
     * @return TOTP 또는 보안키 중 하나라도 활성이면 true
     */
    private fun isAnyMfaEnabled(userId: UUID): Boolean {
        return mfaService.isEnabled(userId) || webAuthnSecurityKeyService.hasActiveKey(userId)
    }

    /**
     * POST /api/v1/auth/mfa/verify — 로그인 2단계(TOTP 또는 백업 코드) 검증 후 정식 세션 발급
     * (FR-MF-01 / FR-MF-02, SDD §19.7.4).
     *
     * 1. [MfaChallengeTokenService.validate] 로 챌린지 토큰을 검증한다. 만료/위조/purpose 불일치면 `null`
     *    → **401 `invalid_code`**(불명은 거부, 내부 사정 비노출).
     * 2. [MfaChallengeTokenService.consume] 으로 토큰을 일회용 소비한다. 이미 소비된 토큰(재사용)이면
     *    **401 `invalid_code`** (C1 replay 방어 — 코드 검증 전에 차단).
     * 3. [MfaVerifyRequest.method] 로 검증기를 분기한다([verifyByMethod]).
     *    - `"totp"`(기본값) → [MfaService.verifyLogin] (FR-MF-01 경로 그대로 — 회귀 0).
     *    - `"backup_code"` → [MfaBackupCodeService.verifyAndConsume] (FR-MF-02 — 1회용 백업 코드 소진).
     *    - 그 외(미지원) → **400 `invalid_method`** (fail-safe — totp 로의 자동 fallback 금지, 불명은 거부).
     *    어느 경로든 성공이면 [issueTokens]`(mfaVerified=true)` 로 정식 세션을 발급한다(EC-10 — 백업 코드
     *    로그인도 TOTP 와 동일한 세션 효과: JWT `mfa_verified=true`).
     *
     * ## 토큰 소비 순서 (method 무관 공통, EC-12)
     * 토큰 validate/consume 은 **method 분기보다 먼저** 수행한다. 따라서 코드(백업/TOTP)가 오답이어도
     * 챌린지 토큰은 이미 1회 소비된 상태가 되어, 같은 토큰 재제출은 (2)에서 `invalid_code` 로 거부된다
     * (재로그인 필요). method 가 미지원(`invalid_method`)이어도 토큰은 소비된다 — 정상 로그인 흐름에서
     * 클라이언트가 보내는 method 는 둘 중 하나뿐이라, 미지원 method 는 비정상 요청이므로 토큰 폐기가 안전하다.
     *
     * ## 인증·CSRF
     * SecurityConfig 가 이 경로를 `permitAll` + `csrf.ignoringRequestMatchers` 양쪽에 등록한다(FR-MF-01).
     * 정식 세션 발급 전(JWT 없음)이라 인증을 요구하지 않으며, 토큰 자체가 1단계 통과 증명이다.
     *
     * ## 4xx/429 직접 매핑 (catch-all 변질 회귀 가드)
     * 도메인 결과를 컨트롤러에서 직접 [ResponseEntity] 로 매핑하고 예외를 throw 하지 않아, catch-all
     * @ExceptionHandler 가 401/429 를 500 으로 변질시키지 않는다(catch-all-exceptionhandler 교훈).
     * 비밀값(토큰/코드)은 로깅하지 않는다(§1.1.2).
     *
     * @param request IP/UserAgent 추출용 HTTP 요청
     * @param body 챌린지 토큰 + 코드 + method(생략 시 "totp")
     * @return 200 TokenResponse + Set-Cookie / 400 invalid_method / 401 invalid_code / 429 too_many_attempts
     *
     * ReturnCount 억제 — 토큰 무효/재사용 guard early return 이 중첩 if 보다 가독성 우수.
     */
    @Suppress("ReturnCount")
    @PostMapping("/mfa/verify")
    fun verifyMfa(
        request: HttpServletRequest,
        @RequestBody body: MfaVerifyRequest,
    ): ResponseEntity<*> {
        val claims =
            mfaChallengeTokenService.validate(body.mfaChallengeToken)
                ?: return errorResponse(HttpStatus.UNAUTHORIZED, "invalid_code")
        if (!mfaChallengeTokenService.consume(claims.jti)) {
            // C1 — 이미 소비된 토큰(재사용). 코드 검증 없이 거부한다(EC-12 재로그인 필요).
            return errorResponse(HttpStatus.UNAUTHORIZED, "invalid_code")
        }

        return verifyByMethod(request, claims, body)
    }

    /**
     * [MfaVerifyRequest.method] 에 따라 2차 요소를 검증하고 결과를 [ResponseEntity] 로 매핑한다.
     *
     * 토큰 validate/consume 통과 후에만 호출된다([verifyMfa]). `totp`/`backup_code` 각 도메인 결과를
     * 동일한 HTTP 의미(성공→세션 발급, 오답→401 invalid_code, rate-limit→429 too_many_attempts)로
     * 통일한다. 미지원 method 는 어느 검증 경로로도 떨어뜨리지 않고 **400 invalid_method** 로 명시 거부한다
     * (fail-safe — totp 자동 fallback 금지).
     *
     * @param request IP/UserAgent 추출용 HTTP 요청
     * @param claims 검증된 챌린지 토큰 클레임(userId/providerId)
     * @param body method + 코드
     * @return 200/400/401/429 응답
     */
    private fun verifyByMethod(
        request: HttpServletRequest,
        claims: MfaChallengeClaims,
        body: MfaVerifyRequest,
    ): ResponseEntity<*> =
        when (body.method) {
            METHOD_TOTP -> mapTotpResult(request, claims, body, mfaService.verifyLogin(claims.userId, body.code))
            METHOD_BACKUP_CODE ->
                mapBackupResult(request, claims, body, mfaBackupCodeService.verifyAndConsume(claims.userId, body.code))
            METHOD_WEBAUTHN ->
                mapWebauthnResult(
                    request,
                    claims,
                    body,
                    webAuthnSecurityKeyService.verifyLogin(claims.userId, credentialJsonOf(body)),
                )
            else -> errorResponse(HttpStatus.BAD_REQUEST, "invalid_method")
        }

    /**
     * webauthn 검증에 넘길 assertion JSON 문자열을 추출한다.
     *
     * [MfaVerifyRequest.credential] 은 보안키 인증 응답(JSON 트리)이며, 다시 직렬화해
     * [WebAuthnSecurityKeyService.verifyLogin] 에 문자열로 넘긴다. credential 부재 시 빈 문자열을 넘기면
     * 서비스가 파싱 실패로 fail-closed([WebAuthnSecurityKeyService.VerifyResult.InvalidAssertion]) 처리한다.
     */
    private fun credentialJsonOf(body: MfaVerifyRequest): String = body.credential?.toString() ?: ""

    /** TOTP 검증 결과를 HTTP 응답으로 매핑한다(Success→세션, InvalidCode/NotEnabled→401, TooManyAttempts→429). */
    private fun mapTotpResult(
        request: HttpServletRequest,
        claims: MfaChallengeClaims,
        body: MfaVerifyRequest,
        result: VerifyResult,
    ): ResponseEntity<*> =
        when (result) {
            VerifyResult.Success -> issueMfaVerifiedSession(request, claims, body)
            VerifyResult.InvalidCode, VerifyResult.NotEnabled ->
                errorResponse(HttpStatus.UNAUTHORIZED, "invalid_code")
            VerifyResult.TooManyAttempts -> errorResponse(HttpStatus.TOO_MANY_REQUESTS, "too_many_attempts")
        }

    /**
     * 백업 코드 검증 결과를 HTTP 응답으로 매핑한다(EC-10 — Success 시 TOTP 와 동일하게 mfaVerified=true 세션).
     *
     * InvalidCode(오답/이미 사용/미발급)→401 invalid_code, TooManyAttempts→429 로 TOTP 와 동일 의미로 통일한다.
     */
    private fun mapBackupResult(
        request: HttpServletRequest,
        claims: MfaChallengeClaims,
        body: MfaVerifyRequest,
        result: MfaBackupCodeService.VerifyResult,
    ): ResponseEntity<*> =
        when (result) {
            MfaBackupCodeService.VerifyResult.Success ->
                issueMfaVerifiedSession(request, claims, body)
            MfaBackupCodeService.VerifyResult.InvalidCode ->
                errorResponse(HttpStatus.UNAUTHORIZED, "invalid_code")
            MfaBackupCodeService.VerifyResult.TooManyAttempts ->
                errorResponse(HttpStatus.TOO_MANY_REQUESTS, "too_many_attempts")
        }

    /**
     * 보안키(assertion) 검증 결과를 HTTP 응답으로 매핑한다 (FR-MF-03).
     *
     * Success 면 TOTP/백업코드와 동일하게 `issueTokens(mfaVerified=true)` 로 정식 세션을 발급한다(동일 세션 효과).
     * 만료/검증실패/미등록/타인소유/clone 의심은 모두 [WebAuthnSecurityKeyService.VerifyResult.InvalidAssertion]
     * 로 수렴하며 **401 invalid_code** 로 일반화한다(원인 비노출 fail-closed). rate-limit→429 too_many_attempts.
     */
    private fun mapWebauthnResult(
        request: HttpServletRequest,
        claims: MfaChallengeClaims,
        body: MfaVerifyRequest,
        result: WebAuthnSecurityKeyService.VerifyResult,
    ): ResponseEntity<*> =
        when (result) {
            WebAuthnSecurityKeyService.VerifyResult.Success ->
                issueMfaVerifiedSession(request, claims, body)
            WebAuthnSecurityKeyService.VerifyResult.InvalidAssertion ->
                errorResponse(HttpStatus.UNAUTHORIZED, "invalid_code")
            WebAuthnSecurityKeyService.VerifyResult.TooManyAttempts ->
                errorResponse(HttpStatus.TOO_MANY_REQUESTS, "too_many_attempts")
        }

    /**
     * 2차 요소 검증 성공 시 mfaVerified=true 정식 세션을 발급하고, [MfaVerifyRequest.trustDevice] opt-in 이면
     * 신뢰 디바이스를 등록해 trusted_device 쿠키를 함께 내려준다 (FR-MF-05, 3개 매퍼 공용).
     *
     * trust_device 가 false(기본값)면 기존 [issueTokens] 응답 그대로다(회귀 0). true 면
     * [TrustedDeviceService.trust] 로 raw 토큰을 받아 [buildTrustedDeviceCookie] 쿠키를 만들어 refresh 쿠키와
     * 병존시킨다.
     *
     * ## C3 best-effort 등록 (로그인 가용성 우선, fail-safe)
     * 신뢰 등록은 정식 세션 발급의 부가 기능이다. token_hash UNIQUE 충돌 같은 극저확률 실패가 정식 세션
     * 발급을 막으면 안 되므로, [TrustedDeviceService.trust] 예외를 catch 해 흐름을 계속한다 — 실패 시
     * trusted_device 쿠키만 누락하고 정식 세션은 그대로 발급한다. raw 토큰/쿠키 값은 로깅하지 않는다(§1.1.2).
     *
     * `TooGenericExceptionCaught` 억제 — 가용성 우선 정책상 어떤 RuntimeException(DataAccess/UNIQUE 충돌 등)
     * 이든 세션 발급은 계속해야 하며, error 로그로 등록 갭을 경보하므로 generic catch 가 의도적이다(B-1 선례 일관).
     *
     * @param request IP/UserAgent 추출용 HTTP 요청(User-Agent 는 신뢰 라벨로도 쓰인다)
     * @param claims 검증된 챌린지 토큰 클레임(userId/providerId)
     * @param body trust_device opt-in 여부
     * @return 200 + [TokenResponse] + refresh 쿠키 (+ trust_device 동의 시 trusted_device 쿠키)
     */
    @Suppress("TooGenericExceptionCaught")
    private fun issueMfaVerifiedSession(
        request: HttpServletRequest,
        claims: MfaChallengeClaims,
        body: MfaVerifyRequest,
    ): ResponseEntity<*> {
        val trustedDeviceCookie =
            if (body.trustDevice) {
                try {
                    val rawToken = trustedDeviceService.trust(claims.userId, request.getHeader(HttpHeaders.USER_AGENT))
                    buildTrustedDeviceCookie(rawToken)
                } catch (ex: RuntimeException) {
                    // 신뢰 등록 실패는 세션 발급을 막지 않는다(C3 fail-safe). 쿠키만 누락. raw 토큰 미로깅.
                    log.error("trusted device registration failed (session still issued)", ex)
                    null
                }
            } else {
                null
            }
        return issueTokens(request, claims.userId, claims.providerId, mfaVerified = true, trustedDeviceCookie)
    }

    /**
     * LOGIN_SUCCESS 감사 이벤트를 best-effort 로 기록한다 (FR-AU-10 Task 4 / spec §5, NFR-3 B-1).
     *
     * userId=주체, providerId=provider 유형 소문자, ip/userAgent=요청에서 캡처.
     *
     * @param request IP/UserAgent 캡처용 HTTP 요청
     * @param principal 인증 성공한 주체
     */
    private fun recordLoginSuccess(
        request: HttpServletRequest,
        principal: Principal,
    ) {
        recordAuditBestEffort(
            AuthAuditLog(
                userId = principal.userId,
                eventType = AuthEventType.LOGIN_SUCCESS,
                providerId = principal.providerType.name.lowercase(),
                ipAddress = request.remoteAddr.takeIf { it.isNotBlank() },
                userAgent = request.getHeader(HttpHeaders.USER_AGENT),
            ),
        )
    }

    /**
     * LOGIN_FAILURE 감사 이벤트를 best-effort 로 기록한다 (FR-AU-10 Task 4 / spec §5, EC-1/EC-11).
     *
     * **PROVIDER_UNAVAILABLE 은 기록하지 않는다** — provider-unavailable 실패는 LdapProvider 깊은
     * 지점에서 [AuthEventType.LDAP_UNAVAILABLE] 로만 기록되므로 여기서 추가 기록하면 이중 기록이 된다(EC-11).
     * userId 는 항상 null 이다 — username→userId 역조회를 하지 않아 계정 존재 probe 를 차단한다(NFR-2).
     *
     * @param request IP/UserAgent 캡처용 HTTP 요청
     * @param provider 인증 시도 대상 provider 식별자 (검증된 non-blank 요청값)
     * @param username 인증 시도 username (metadata 에만 기록, 역조회 안 함)
     * @param reason 디스패처가 반환한 실패 원인
     */
    private fun recordLoginFailure(
        request: HttpServletRequest,
        provider: String,
        username: String,
        reason: com.atlas.bts.identity.spi.FailureReason,
    ) {
        if (reason == com.atlas.bts.identity.spi.FailureReason.PROVIDER_UNAVAILABLE) {
            return
        }
        recordAuditBestEffort(
            AuthAuditLog(
                userId = null,
                eventType = AuthEventType.LOGIN_FAILURE,
                providerId = provider,
                ipAddress = request.remoteAddr.takeIf { it.isNotBlank() },
                userAgent = request.getHeader(HttpHeaders.USER_AGENT),
                metadata = mapOf("username" to username, "reason" to reason.name),
            ),
        )
    }

    /**
     * 인증 성공한 사용자에 대해 Session 생성 → RefreshToken 발급 → JWT 발급 후 200 응답을 만든다.
     *
     * Session INSERT, RefreshToken save, JwtIssuer.issue 를 순서대로 수행하고
     * refresh_token HttpOnly Secure SameSite=Strict 쿠키를 Set-Cookie 로 내려준다 (현행 발급 로직 유지).
     *
     * 1단계 로그인([login])과 2단계 검증([verifyMfa]) 양쪽이 공유한다. 1단계는 [providerId] 를
     * `principal.providerType.name.lowercase()` 로, 2단계는 챌린지 토큰의 providerId 로 전달한다.
     * [Principal] 전체가 아니라 식별자만 받아, 챌린지 토큰만 가진 2단계 경로도 재구성 없이 재사용한다.
     *
     * @param request IP/UserAgent 추출용 HTTP 요청
     * @param userId 인증된 사용자 UUID
     * @param providerId 인증 공급자 식별자(소문자, e.g. "local")
     * @param mfaVerified 2차 요소(TOTP) 통과 여부 (FR-MF-01). 1단계 로그인은 기본 `false`,
     *   [verifyMfa] 성공 경로·신뢰 디바이스 우회([completeLogin])만 `true`.
     *   [Session.mfaVerified] → JWT `mfa_verified` 클레임 원천이다.
     * @param trustedDeviceCookie trusted_device Set-Cookie 헤더 값 (FR-MF-05). null(기본)이면 부착하지
     *   않는다(회귀 0). non-null 이면 refresh_token Set-Cookie 와 **병존**시켜 복수 Set-Cookie 헤더로 내린다.
     * @return 200 + [TokenResponse] + Set-Cookie refresh_token (+ trusted_device 동의 시 trusted_device)
     */
    private fun issueTokens(
        request: HttpServletRequest,
        userId: UUID,
        providerId: String,
        mfaVerified: Boolean = false,
        trustedDeviceCookie: String? = null,
    ): ResponseEntity<*> {
        val ipAddress = request.remoteAddr.takeIf { it.isNotBlank() }
        val userAgent = request.getHeader(HttpHeaders.USER_AGENT)

        val session =
            sessionService.create(
                userId = userId,
                providerId = providerId,
                ipAddress = ipAddress,
                userAgent = userAgent,
                mfaVerified = mfaVerified,
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

        val roles = systemRoleAssignmentRepository.findRolesByUser(session.userId).map { it.name }
        val accessToken =
            jwtIssuer.issue(
                userId = session.userId,
                sessionId = session.id,
                providerId = session.providerId,
                scopes = emptyList(),
                roles = roles,
                mfaVerified = session.mfaVerified,
            )

        val builder =
            ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, buildRefreshCookie(rawToken, REFRESH_MAX_AGE))
        trustedDeviceCookie?.let { builder.header(HttpHeaders.SET_COOKIE, it) }
        return builder.body(TokenResponse(accessToken = accessToken))
    }

    /** `{"error": <code>}` 본문을 가진 [status] 응답을 생성한다 (login 에러 응답 일원화). */
    private fun errorResponse(
        status: HttpStatus,
        errorCode: String,
    ): ResponseEntity<Map<String, String>> = ResponseEntity.status(status).body(mapOf("error" to errorCode))

    /**
     * POST /api/v1/auth/logout — 현재 디바이스 세션 폐기.
     *
     * Authorization Bearer 의 `sid` 클레임으로 Session 을 특정하여:
     * 1. SessionService.revoke(sid, "logout")
     * 2. RefreshTokenRepository.revokeChainFromSession(sid) — 해당 세션의 미사용 refresh 토큰 전체 무효화
     * 3. Set-Cookie refresh_token= Max-Age=0 Path=/api/v1/auth — 브라우저 Cookie 삭제 (RFC 6265 EC-28)
     *
     * EC-18 (idempotent): Authorization 헤더 없으면 Spring Security 가 401 반환. Controller 미도달.
     * 이미 폐기된 세션 revoke 는 SessionService.revoke 가 멱등으로 처리 (0 row 영향).
     *
     * @param jwt 인증된 JWT (Spring Security @AuthenticationPrincipal 주입)
     * @return 204 No Content + Set-Cookie 만료
     */
    @PostMapping("/logout")
    fun logout(
        @AuthenticationPrincipal jwt: Jwt,
    ): ResponseEntity<Unit> {
        val sidStr = jwt.getClaimAsString("sid")
        if (!sidStr.isNullOrBlank()) {
            val sid = UUID.fromString(sidStr)
            sessionService.revoke(sid, REVOKE_REASON_LOGOUT)
            refreshTokenRepository.revokeChainFromSession(sid)
            recordLogout(jwt, sidStr)
        }

        return ResponseEntity.noContent()
            .header(HttpHeaders.SET_COOKIE, buildRefreshCookie("", 0))
            .build()
    }

    /**
     * LOGOUT 감사 이벤트를 best-effort 로 기록한다 (FR-AU-10 Task 4 / spec §5, NFR-3 B-1).
     *
     * userId=JWT subject, providerId=JWT providerId 클레임(부재 시 "unknown"), metadata.sid=폐기 세션.
     * ip/userAgent 는 LOGOUT 에서는 채우지 않는다(spec §5 — logout 은 sid metadata 중심).
     *
     * @param jwt 인증된 JWT (subject / providerId 클레임 추출용)
     * @param sid 폐기된 세션 ID 문자열 (metadata)
     */
    private fun recordLogout(
        jwt: Jwt,
        sid: String,
    ) {
        val userId = runCatching { UUID.fromString(jwt.subject) }.getOrNull()
        val providerId = jwt.getClaimAsString(JWT_CLAIM_PROVIDER_ID)?.takeIf { it.isNotBlank() } ?: PROVIDER_UNKNOWN
        recordAuditBestEffort(
            AuthAuditLog(
                userId = userId,
                eventType = AuthEventType.LOGOUT,
                providerId = providerId,
                metadata = mapOf("sid" to sid),
            ),
        )
    }

    /**
     * 감사 이벤트를 best-effort 로 기록한다 (NFR-3 B-1 — web 레이어 emit 정책).
     *
     * AuthController 는 의도적 무-트랜잭션이므로(클래스 KDoc 참조), 감사 INSERT 실패가 로그인/로그아웃
     * 가용성을 인질로 잡으면 안 된다. 따라서 [record][AuthAuditLogService.record] 예외를 catch 해
     * 흐름을 계속한다. 단 **silent 삼킴은 금지** — high-severity 에러 로그로 감사 갭을 탐지 가능하게 한다.
     * 로그에는 PII(ip/userAgent/username)를 출력하지 않고 이벤트 유형만 남긴다(DEVELOPMENT.md §1.2).
     *
     * `TooGenericExceptionCaught` 억제 — B-1 가용성 우선 정책상 어떤 RuntimeException 이든(DataAccess/
     * 직렬화/타임아웃 등) 흐름을 계속해야 하며, error 로그로 감사 갭을 경보하므로 generic catch 가 의도적이다.
     *
     * @param event 기록할 감사 이벤트
     */
    @Suppress("TooGenericExceptionCaught")
    private fun recordAuditBestEffort(event: AuthAuditLog) {
        try {
            authAuditLogService.record(event)
        } catch (ex: RuntimeException) {
            // 감사 갭 경보 — 가용성 우선이라 흐름은 계속하되 silent 삼킴은 아니다(B-1). PII 미출력.
            log.error("audit emit failed (availability preserved): eventType={}", event.eventType, ex)
        }
    }

    /**
     * POST /api/v1/auth/refresh — Refresh Token rotation.
     *
     * Cookie 의 refresh_token raw 값 → SHA-256 hash → RefreshTokenService.rotate.
     * rotation 성공 시 새 access token + 새 refresh_token Cookie 응답.
     *
     * ## 실패 매핑 (EC-04/22/23)
     * | FailureReason | HTTP | error body |
     * |---|---|---|
     * | Expired | 401 | refresh_token_expired |
     * | Replay | 401 | refresh_token_reused |
     * | Race | 401 | refresh_token_reused |
     * | NotFound / Revoked | 401 | refresh_token_invalid |
     *
     * @param request HTTP 요청 (refresh_token Cookie 추출용)
     * @return 200 + TokenResponse + 새 Set-Cookie / 401
     */
    @PostMapping("/refresh")
    fun refresh(request: HttpServletRequest): ResponseEntity<*> {
        val rawToken =
            request.cookies
                ?.firstOrNull { it.name == REFRESH_COOKIE_NAME }
                ?.value
                ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(mapOf("error" to "refresh_token_invalid"))

        val tokenHash = sha256Hex(rawToken)

        return when (val result = refreshTokenService.rotate(tokenHash)) {
            is RotateResult.Success ->
                ResponseEntity.ok()
                    .header(HttpHeaders.SET_COOKIE, buildRefreshCookie(result.newRefreshTokenRaw, REFRESH_MAX_AGE))
                    .body(TokenResponse(accessToken = result.accessToken))

            is RotateResult.Failure -> {
                val errorBody = mapOf("error" to result.reason.toErrorCode())
                ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(errorBody)
            }
        }
    }

    /**
     * GET /api/v1/auth/sessions — 본인 활성 세션 목록 조회 (FR-AU-09 Task 2 / spec §FR-1/FR-2/FR-6b).
     *
     * ## PAT 차단 (FR-6b / EC-8)
     * PAT 인증 시 principal 이 [Jwt] 타입이 아닌 `UsernamePasswordAuthenticationToken` 이다.
     * PAT 는 stateless 자격증명이므로 `sid` ("현재 세션") 개념이 없어 세션 관리가 불가하다.
     * [Jwt] 타입이 아니면 **403 Forbidden** + `session_management_requires_interactive_login` 반환.
     * ([WhoamiController] 의 `Jwt?` nullable + PAT 분기 선례와 동일 원칙.)
     *
     * ## current 플래그 (spec §FR-2)
     * JWT 의 `sid` 클레임과 세션 ID 가 일치하는 세션만 `current = true`.
     * 현재 세션을 사용자에게 명시적으로 표시해 강제종료 버튼을 비활성화할 수 있게 한다.
     *
     * ## 미인증
     * Spring Security 필터가 401 반환. Controller 미도달.
     *
     * @param jwt Spring Security 가 주입한 JWT principal. PAT 인증 시 null (nullable 선언)
     * @return 200 + `{ "sessions": [SessionResponse, ...] }` / 403 PAT / 401 미인증
     */
    @GetMapping("/sessions")
    fun listSessions(
        @AuthenticationPrincipal jwt: Jwt?,
    ): ResponseEntity<*> {
        val claims = resolveJwtClaims(jwt) ?: return PAT_FORBIDDEN_RESPONSE

        val sessions = sessionService.findActiveByUser(claims.userId)
        val sessionResponses = sessions.map { toSessionResponse(it, claims.currentSid) }

        return ResponseEntity.ok(mapOf("sessions" to sessionResponses))
    }

    /**
     * DELETE /api/v1/auth/sessions/{sid} — 본인 다른 활성 세션 강제 종료 (FR-AU-09 Task 3 / spec §FR-3/S-2).
     *
     * ## PAT 차단 (FR-6b / EC-8)
     * [listSessions] 와 동일 원칙 — PAT principal 은 [Jwt] 타입이 아니므로 **403 Forbidden** 반환.
     *
     * ## IDOR 방어 (NFR-1 / FR-4 / S-3)
     * `sessionService.lookup(sid)` 로 세션을 조회한 뒤 `session.userId == 인증 userId` 를 검증한다.
     * 조회 결과가 null 이거나 userId 불일치인 경우 **모두 404 Not Found** 로 응답한다.
     * 타인 세션의 존재 여부를 노출하지 않기 위해 404 를 단일 응답코드로 사용한다 (OWASP IDOR 권고).
     * 검증은 이 메서드 단일 지점에서만 수행한다 (NFR-1 — 분산 방지).
     *
     * ## 현재 세션 차단 (FR-5 / S-4)
     * sid == 요청 JWT 의 `sid` 클레임인 경우 **409 Conflict** + `cannot_revoke_current_session`.
     * 자기 세션 종료는 기존 POST /logout 로 유도한다.
     *
     * ## revoke 처리 (FR-3)
     * logout 선례(`AuthController.kt:169-170`)와 동일하게:
     * 1. `sessionService.revoke(sid, "user_revoke")` — sessions 테이블 UPDATE
     * 2. `refreshTokenRepository.revokeChainFromSession(sid)` — 귀속 refresh chain 즉시 무효화
     *
     * @param jwt Spring Security 가 주입한 JWT principal. PAT 인증 시 null (nullable 선언)
     * @param sid 강제 종료할 세션 ID (Spring 이 UUID 바인딩 실패 시 400 자동 반환 — EC-7)
     * @return 204 No Content / 400 UUID 형식 오류 / 403 PAT / 404 IDOR/미존재 / 409 현재 세션
     *
     * ReturnCount 억제 — HTTP 상태별 guard clause early return(403/404/409/204)이
     * 중첩 if 보다 가독성 우수 (DEVELOPMENT.md §2.3 Early return 권장).
     */
    @Suppress("ReturnCount")
    @DeleteMapping("/sessions/{sid}")
    fun revokeSession(
        @AuthenticationPrincipal jwt: Jwt?,
        @PathVariable sid: UUID,
    ): ResponseEntity<*> {
        val claims = resolveJwtClaims(jwt) ?: return PAT_FORBIDDEN_RESPONSE

        // 미존재 / 타인 소유(IDOR) / 이미 비활성(revoked·만료, EC-2) 세션은 모두 404 (존재 비노출 + 멱등 재폐기 방지).
        val session = sessionService.lookup(sid)
        if (session == null || session.userId != claims.userId || !session.isActive(clock.instant())) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build<Unit>()
        }

        if (sid == claims.currentSid) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(mapOf("error" to "cannot_revoke_current_session"))
        }

        sessionService.revoke(sid, REVOKE_REASON_USER)
        refreshTokenRepository.revokeChainFromSession(sid)

        return ResponseEntity.noContent().build<Unit>()
    }

    // ── private helpers ───────────────────────────────────────────────────────

    /**
     * JWT principal 에서 userId(subject) 와 currentSid(sid 클레임) 를 추출한다.
     *
     * PAT 인증 시 [jwt] 가 null 이므로 null 을 반환한다 (호출 측에서 403 반환).
     * JWT 의 subject 가 유효한 UUID 가 아닌 경우에도 null 을 반환한다.
     * sid 클레임이 없거나 UUID 파싱 실패인 경우 [JwtClaims.currentSid] 는 null 이다.
     *
     * @param jwt nullable JWT principal ([Jwt] 타입 아니면 PAT)
     * @return [JwtClaims] 또는 null (PAT/invalid_token)
     *
     * ReturnCount 억제 — null guard early return(PAT/invalid subject)이 가독성 우수.
     */
    @Suppress("ReturnCount")
    private fun resolveJwtClaims(jwt: Jwt?): JwtClaims? {
        if (jwt == null) return null
        val userId = runCatching { UUID.fromString(jwt.subject) }.getOrNull() ?: return null
        val currentSid =
            jwt.getClaimAsString("sid")?.let {
                runCatching { UUID.fromString(it) }.getOrNull()
            }
        return JwtClaims(userId = userId, currentSid = currentSid)
    }

    /**
     * [Session] 도메인 엔티티를 [SessionResponse] DTO 로 변환한다.
     *
     * `deviceFingerprint` 는 의도적으로 제외한다 (NFR-2 — 내부 식별자 비노출).
     *
     * @param session 변환할 세션 엔티티
     * @param currentSid 요청 JWT 의 `sid` 클레임 값. null 이면 current = false
     * @return [SessionResponse]
     */
    private fun toSessionResponse(
        session: Session,
        currentSid: UUID?,
    ): SessionResponse =
        SessionResponse(
            sid = session.id,
            providerId = session.providerId,
            userAgent = session.userAgent,
            ipAddress = session.ipAddress,
            lastSeenAt = session.lastSeenAt,
            createdAt = session.createdAt,
            current = session.id == currentSid,
        )

    /**
     * refresh_token Set-Cookie 헤더 값을 생성한다.
     *
     * HttpOnly; Secure; SameSite=Strict; Path=/api/v1/auth (FR-09-21 Cookie Path 통일).
     * logout 시 value="" + maxAge=0 으로 브라우저 쿠키 삭제를 요청한다 (EC-28).
     *
     * ResponseCookie 대신 수동 문자열 조합 — SameSite 속성 지원 보장 (Spring 6.x ResponseCookie 이슈 우회).
     */
    private fun buildRefreshCookie(
        value: String,
        maxAge: Int,
    ): String = "$REFRESH_COOKIE_NAME=$value; HttpOnly; Secure; SameSite=Strict; Path=$COOKIE_PATH; Max-Age=$maxAge"

    /**
     * trusted_device Set-Cookie 헤더 값을 생성한다 (FR-MF-05).
     *
     * refresh_token 쿠키 선례와 동일 속성 — HttpOnly; Secure; SameSite=Strict; Path=/api/v1/auth;
     * Max-Age=30일([TRUST_MAX_AGE]). login·verify 가 모두 이 Path 아래라 다음 로그인에 자동 전송된다.
     * SameSite=Strict 라 cross-site 자동전송을 차단해 CSRF 표면을 최소화한다(ADR 2026-06-13).
     *
     * @param value 발급된 raw 신뢰 토큰 — **로그 기록 금지**(§1.1.2)
     */
    private fun buildTrustedDeviceCookie(
        value: String,
    ): String =
        "$TRUSTED_DEVICE_COOKIE=$value; HttpOnly; Secure; SameSite=Strict; Path=$COOKIE_PATH; Max-Age=$TRUST_MAX_AGE"

    /** [TOKEN_BYTES] 바이트 CSPRNG 난수를 hex 문자열로 인코딩한다. */
    private fun generateRawToken(): String {
        val bytes = ByteArray(TOKEN_BYTES)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /** SHA-256(input) hex 소문자 64자. */
    private fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val REFRESH_COOKIE_NAME = "refresh_token"

        /** Cookie Path — login / logout / refresh 모두 동일 Path (RFC 6265 EC-28 회귀 가드) */
        const val COOKIE_PATH = "/api/v1/auth"

        /** refresh_token Cookie Max-Age 14일 (초) */
        const val REFRESH_MAX_AGE = 1209600

        /** Refresh Token 유효 기간 — 14일 (RefreshTokenService 와 동일) */
        const val REFRESH_TTL_DAYS = 14L

        /** 신뢰 디바이스 쿠키 이름 — login 우회 조회 / verify 발급 공용 (FR-MF-05) */
        const val TRUSTED_DEVICE_COOKIE = "trusted_device"

        /** trusted_device Cookie Max-Age 30일 (초) — TrustedDeviceService TRUST_TTL_DAYS 와 동일 (FR-MF-05) */
        const val TRUST_MAX_AGE = 2592000

        /** CSPRNG 토큰 바이트 수 — 32 바이트 = 256비트 엔트로피 */
        const val TOKEN_BYTES = 32

        /**
         * MFA 챌린지 토큰 유효 기간 (초) — 5분. [MfaRequiredResponse.expiresIn] 으로 클라이언트에 안내한다.
         * 실제 만료는 [MfaChallengeTokenService] 의 `CHALLENGE_TTL`(5분)이 강제하며 이 값은 표시용이다.
         */
        const val MFA_CHALLENGE_TTL_SECONDS = 300L

        /** [MfaVerifyRequest.method] — TOTP 검증(기본값, FR-MF-01 경로). */
        const val METHOD_TOTP = "totp"

        /** [MfaVerifyRequest.method] — 1회용 백업 코드 검증(FR-MF-02). */
        const val METHOD_BACKUP_CODE = "backup_code"

        /** [MfaVerifyRequest.method] — 보안키(assertion) 검증(FR-MF-03). */
        const val METHOD_WEBAUTHN = "webauthn"

        /** logout 세션 폐기 사유 — 감사 로그 검색 키 */
        const val REVOKE_REASON_LOGOUT = "logout"

        /** 사용자 강제종료 세션 폐기 사유 — 감사 로그 검색 키 (spec §EC 소문자 snake 관례) */
        const val REVOKE_REASON_USER = "user_revoke"

        /** JWT providerId 클레임 키 (JwtIssuer.CLAIM_PROVIDER_ID 와 동일 값) — LOGOUT 감사 providerId 추출용 */
        const val JWT_CLAIM_PROVIDER_ID = "providerId"

        /** LOGOUT 감사 providerId 폴백 — JWT providerId 클레임 부재 시 사용 */
        const val PROVIDER_UNKNOWN = "unknown"

        /** PAT 인증 시 세션 관리 불가 응답 — listSessions / revokeSession 공용 (FR-6b / EC-8) */
        val PAT_FORBIDDEN_RESPONSE: ResponseEntity<Map<String, String>> =
            ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(mapOf("error" to "session_management_requires_interactive_login"))
    }
}

/**
 * JWT 로부터 추출된 인증 클레임 (listSessions / revokeSession 공용).
 *
 * @param userId JWT subject UUID — 인증 사용자 ID
 * @param currentSid JWT sid 클레임 UUID — 현재 요청 세션 ID. 클레임 부재/파싱 실패 시 null
 */
private data class JwtClaims(val userId: UUID, val currentSid: UUID?)

/** refresh_token_reused / refresh_token_expired / refresh_token_invalid 매핑 */
private fun FailureReason.toErrorCode(): String =
    when (this) {
        FailureReason.Expired -> "refresh_token_expired"
        FailureReason.Replay, FailureReason.Race -> "refresh_token_reused"
        FailureReason.NotFound, FailureReason.Revoked -> "refresh_token_invalid"
    }

/**
 * POST /api/v1/auth/login 요청 body (FR-AU-09 §4.1 / FR-AU-06).
 *
 * `provider` 는 nullable + 기본 null 로 선언한다. JSON 에서 필드가 **누락**된 경우와 **빈 문자열**인
 * 경우를 컨트롤러가 동일하게 처리해 400 `provider_required` 본문을 내려주기 위함이다. 필드를 non-null
 * 로 두면 누락 시 Jackson 역직렬화 단계에서 일반 400(메시지 본문 없음)이 나 spec 응답을 못 만든다.
 *
 * @param provider Provider 식별자 (예: "local", "ldap"). 누락/빈 문자열은 컨트롤러가 400 처리
 * @param username 사용자 이름
 * @param password 비밀번호 평문 — 메모리 즉시 사용 후 CharArray wipe 는 Provider 책임
 */
data class LoginRequest(
    val provider: String? = null,
    val username: String,
    val password: String,
)

/**
 * login / refresh 응답 body (FR-AU-09 §4.1 / §4.2).
 *
 * @param accessToken RS256 서명 JWT (직렬화 키: access_token)
 * @param tokenType "Bearer" 고정 (직렬화 키: token_type)
 * @param expiresIn Access Token 유효 기간 (초) — 15분 = 900 (직렬화 키: expires_in)
 */
data class TokenResponse(
    @JsonProperty("access_token") val accessToken: String,
    @JsonProperty("token_type") val tokenType: String = "Bearer",
    @JsonProperty("expires_in") val expiresIn: Long = JwtIssuer.ACCESS_TOKEN_TTL_SECONDS,
)

/**
 * POST /api/v1/auth/mfa/verify 요청 body (FR-MF-01 / FR-MF-02, SDD §19.7.4).
 *
 * 1단계(비밀번호) 통과 시 받은 챌린지 토큰과 2차 요소 코드(Authenticator 앱 TOTP 또는 백업 코드)를 함께 보낸다.
 *
 * @param mfaChallengeToken [login] 2단계 응답([MfaRequiredResponse])의 챌린지 토큰 (역직렬화 키: mfa_challenge_token)
 * @param code 사용자가 입력한 코드 — TOTP 6자리 또는 백업 코드(xxxxx-xxxxx). webauthn 은 빈 값 허용(credential 사용).
 *   검증은 서비스가 수행한다(컨트롤러는 로깅하지 않음).
 * @param method 검증 방식 — `"totp"`(기본값) / `"backup_code"` / `"webauthn"`. 기본값을 둬 method 를
 *   보내지 않는 기존 클라이언트는 TOTP 경로를 그대로 탄다(회귀 0). 미지원 값은 400 invalid_method 로 거부한다.
 * @param credential 보안키(assertion) 인증 응답 JSON 트리 — `method="webauthn"` 일 때만 사용한다. nullable +
 *   기본 null 이라 totp/backup_code 요청(credential 부재)은 그대로 호환된다(회귀 0). 검증은 서비스가 수행한다.
 * @param trustDevice "이 기기 30일 면제" 동의 여부 (FR-MF-05, 역직렬화 키: trust_device). 기본값 false 라
 *   필드를 보내지 않는 기존 클라이언트는 신뢰 디바이스를 등록하지 않는다(회귀 0). true 면 검증 성공 시
 *   서버가 신뢰 토큰을 발급해 trusted_device 쿠키로 내려준다.
 */
data class MfaVerifyRequest(
    @JsonProperty("mfa_challenge_token") val mfaChallengeToken: String,
    val code: String = "",
    val method: String = "totp",
    val credential: JsonNode? = null,
    @JsonProperty("trust_device") val trustDevice: Boolean = false,
)

/**
 * TOTP 활성 사용자의 login 2단계 응답 body (FR-MF-01 GAP-3 — login 200 discriminated union).
 *
 * 1단계(비밀번호) 통과 후 정식 세션 대신 내려가며, 클라이언트는 [mfaRequired] 가 true 임을 보고
 * MFA 코드 입력 단계로 전환한다. [TokenResponse] 와 구분되도록 `mfa_required` 식별 필드를 둔다.
 *
 * @param mfaRequired 항상 true (직렬화 키: mfa_required) — 클라이언트 분기용 식별 필드
 * @param mfaChallengeToken `POST /mfa/verify` 에 다시 보낼 단명 챌린지 토큰 (직렬화 키: mfa_challenge_token)
 * @param expiresIn 챌린지 토큰 유효 기간(초) — 표시용 (직렬화 키: expires_in)
 */
data class MfaRequiredResponse(
    @JsonProperty("mfa_required") val mfaRequired: Boolean = true,
    @JsonProperty("mfa_challenge_token") val mfaChallengeToken: String,
    @JsonProperty("expires_in") val expiresIn: Long,
)
