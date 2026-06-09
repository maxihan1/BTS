// SSO(SAML/OIDC) 연결/재인증 시작 엔드포인트 — 의도 저장 후 authorizeUrl 반환 (FR-AU-08b Task 9)

package com.atlas.bts.identity.web

import com.atlas.bts.identity.account.SsoLinkingIntent
import com.atlas.bts.identity.account.SsoLinkingIntentStore
import com.atlas.bts.identity.account.StepUpService
import com.atlas.bts.identity.provider.oidc.OidcProviderConfigReader
import com.atlas.bts.identity.provider.saml.SamlIdpConfigRepository
import com.atlas.bts.identity.spi.ProviderType
import com.atlas.bts.identity.web.dto.SsoLinkStartRequest
import com.atlas.bts.identity.web.dto.SsoLinkStartResponse
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Clock

/**
 * SSO(SAML/OIDC) 계정 연결/재인증을 개시하는 셀프서비스 컨트롤러 (FR-AU-08b / SDD §19).
 *
 * ## 2단계 흐름
 * LDAP 동기 연결과 달리 SSO 는 IdP 로 리다이렉트 왕복한다. JWT(sessionStorage)는 전체 페이지
 * 네비게이션에 전송되지 않으므로 연결을 두 단계로 나눈다.
 * 1. **XHR start**(이 컨트롤러): JWT(+step-up)로 인증된 요청이 HttpSession 에 [SsoLinkingIntent] 를
 *    저장하고 JSESSIONID 를 세팅한 뒤 authorizeUrl 을 반환한다.
 * 2. **SPA 네비게이트**: SPA 가 `window.location.assign(authorizeUrl)` 로 SSO 진입(JSESSIONID 동반).
 *    IdP 인증 후 콜백 성공 핸들러가 같은 세션의 intent 를 소비해 연결/재인증을 마친다.
 *
 * ## 엔드포인트
 * - [linkStart]: POST /links/sso/start — SSO 연결 시작(JWT + step-up 필수).
 * - [reauthStart]: POST /reauth/sso/start — SSO 재인증 시작(JWT 만, step-up 불요 — 이게 획득 경로).
 *
 * ## JWT 전용 / sid 신뢰 출처 (FR8 / 1차 불변식 계승)
 * 두 엔드포인트 모두 [AccountLinkJwtSupport] 로 JWT subject(userId)·sid 를 동시 추출한다. PAT 는
 * sid 가 없어 403 으로 차단된다([AccountLinkController] PAT 차단 선례와 동일). REAUTH intent 의
 * sid 는 이 단일 JWT 출처에서만 복사한다(위조 차단).
 *
 * ## 입력 검증 매트릭스 (FR7)
 * - providerType=SAML 은 [SamlIdpConfigRepository] 로만, OIDC 는 [OidcProviderConfigReader] 로만 조회한다.
 * - LOCAL/LDAP 등 SSO 아님 → 400. 미존재/비활성 registration → 404. registrationId 형식 위반 → 400.
 * - authorizeUrl 은 **검증 통과한 registrationId** 로만 서버가 구성한다(사용자 입력 echo 금지, open-redirect 0).
 *
 * ## 예외 → HTTP 매핑
 * 입력 검증 예외는 이 컨트롤러 **로컬 [org.springframework.web.bind.annotation.ExceptionHandler]** 가
 * 변환한다(전역 advice 미사용 — catch-all 변질 선례 회피). 메시지는 계정 열거 0 일반화 에러코드만 노출한다.
 *
 * ## 트랜잭션 경계
 * @Transactional 없음 — 읽기 전용 config 조회 + 인메모리 intent 저장이라 DB 쓰기가 없다.
 */
@RestController
@RequestMapping("/api/v1/auth/account")
// UnusedParameter 억제 — @ExceptionHandler 메서드는 매핑을 위해 예외 타입 파라미터가 필수이나 본문에서 미사용한다
// (AccountLinkController 선례와 동일).
@Suppress("UnusedParameter")
class SsoAccountLinkController(
    private val jwtSupport: AccountLinkJwtSupport,
    private val intentStore: SsoLinkingIntentStore,
    private val samlIdpConfigRepository: SamlIdpConfigRepository,
    private val oidcProviderConfigReader: OidcProviderConfigReader,
    private val clock: Clock = Clock.systemUTC(),
) {
    /**
     * POST /api/v1/auth/account/links/sso/start — SSO 연결 시작 (JWT + step-up) (FR-AU-08b FR1).
     *
     * step-up 미충족이면 config 조회 없이 403. 검증 통과 시 LINK intent(sid 불요)를 HttpSession 에
     * 저장하고 authorizeUrl 을 반환한다. JSESSIONID 가 함께 세팅된다(C2).
     *
     * @param jwt 인증 JWT principal. PAT 인증 시 null → 403.
     * @param body 연결 대상(registrationId/providerType).
     * @param request JSESSIONID 세션을 생성할 서블릿 요청.
     * @return 200 [SsoLinkStartResponse] / 403 PAT|step_up_required / 400 형식·type / 404 미존재·비활성
     *
     * ReturnCount 억제 — PAT/step-up guard early return 이 중첩 if 보다 가독성 우수(DEVELOPMENT.md §2.3).
     */
    @Suppress("ReturnCount")
    @PostMapping("/links/sso/start")
    fun linkStart(
        @AuthenticationPrincipal jwt: Jwt?,
        @RequestBody body: SsoLinkStartRequest,
        request: HttpServletRequest,
    ): ResponseEntity<*> {
        val claims = jwtSupport.resolveClaims(jwt) ?: return PAT_FORBIDDEN_RESPONSE
        jwtSupport.requireStepUp(claims.currentSid)?.let { return it }

        val registrationId = validateRegistration(body)
        val intent =
            SsoLinkingIntent(
                mode = SsoLinkingIntent.Mode.LINK,
                userId = claims.userId,
                sid = null,
                registrationId = registrationId,
                providerType = body.providerType,
                expiresAt = clock.instant().plus(StepUpService.STEP_UP_TTL),
            )
        return storeIntentAndRespond(request, intent)
    }

    /**
     * POST /api/v1/auth/account/reauth/sso/start — SSO 재인증 시작 (JWT 만) (FR-AU-08b FR2/FR8).
     *
     * step-up 을 요구하지 않는다(이게 step-up 획득 경로). 검증 통과 시 REAUTH intent 를 저장하되
     * grant 대상 sid 를 인증 JWT 의 `sid` 클레임에서 복사한다(FR8 — 위조 차단). sid 클레임이 없는
     * 비정상 JWT 는 403 으로 차단한다.
     *
     * @param jwt 인증 JWT principal. PAT 인증 시 null → 403.
     * @param body 재인증 대상(registrationId/providerType).
     * @param request JSESSIONID 세션을 생성할 서블릿 요청.
     * @return 200 [SsoLinkStartResponse] / 403 PAT|sid 부재 / 400 형식·type / 404 미존재·비활성
     *
     * ReturnCount 억제 — PAT/sid guard early return 이 중첩 if 보다 가독성 우수(DEVELOPMENT.md §2.3).
     */
    @Suppress("ReturnCount")
    @PostMapping("/reauth/sso/start")
    fun reauthStart(
        @AuthenticationPrincipal jwt: Jwt?,
        @RequestBody body: SsoLinkStartRequest,
        request: HttpServletRequest,
    ): ResponseEntity<*> {
        val claims = jwtSupport.resolveClaims(jwt) ?: return PAT_FORBIDDEN_RESPONSE
        val sid = claims.currentSid ?: return PAT_FORBIDDEN_RESPONSE

        val registrationId = validateRegistration(body)
        val intent =
            SsoLinkingIntent(
                mode = SsoLinkingIntent.Mode.REAUTH,
                userId = claims.userId,
                sid = sid,
                registrationId = registrationId,
                providerType = body.providerType,
                expiresAt = clock.instant().plus(StepUpService.STEP_UP_TTL),
            )
        return storeIntentAndRespond(request, intent)
    }

    // ── private helpers ────────────────────────────────────────────────────────

    /**
     * 입력 검증 매트릭스(FR7)를 적용하고 검증 통과한 registrationId 를 반환한다.
     *
     * 1. providerType 이 SAML/OIDC 가 아니면 [SsoLinkValidationException](400).
     * 2. registrationId 형식(영숫자+하이픈 화이트리스트) 위반이면 [SsoLinkValidationException](400) —
     *    authorizeUrl 주입 전에 거른다(EC14).
     * 3. providerType 에 맞는 config repo 로만 enabled 조회 — 미존재/비활성이면 [SsoProviderNotFoundException](404).
     *
     * @return 검증 통과한 registrationId(요청 그대로 — config 존재 확인 완료).
     *
     * ThrowsCount 억제 — 형식위반/SSO아님/미존재 3갈래 거부가 검증 매트릭스(FR7)의 본질적 분기다.
     */
    @Suppress("ThrowsCount")
    private fun validateRegistration(body: SsoLinkStartRequest): String {
        val registrationId = body.registrationId
        if (!REGISTRATION_ID_PATTERN.matches(registrationId)) {
            throw SsoLinkValidationException()
        }
        val exists =
            when (body.providerType) {
                ProviderType.SAML -> samlIdpConfigRepository.findEnabledByRegistrationId(registrationId) != null
                ProviderType.OIDC -> oidcProviderConfigReader.findEnabledByRegistrationId(registrationId) != null
                else -> throw SsoLinkValidationException()
            }
        if (!exists) throw SsoProviderNotFoundException()
        return registrationId
    }

    /**
     * intent 를 HttpSession 에 저장하고 authorizeUrl 을 반환한다.
     *
     * `getSession(true)` 가 JSESSIONID 를 방출해 IdP 왕복 동안 같은 세션이 재사용된다(C2/EC17).
     * authorizeUrl 은 providerType 별 고정 패턴 + 검증 통과 registrationId 로만 구성한다(open-redirect 0).
     */
    private fun storeIntentAndRespond(
        request: HttpServletRequest,
        intent: SsoLinkingIntent,
    ): ResponseEntity<SsoLinkStartResponse> {
        intentStore.put(request.getSession(true), intent)
        val authorizeUrl =
            when (intent.providerType) {
                ProviderType.SAML -> "$SAML_AUTHENTICATE_PREFIX/${intent.registrationId}"
                else -> "$OIDC_AUTHORIZATION_PREFIX/${intent.registrationId}"
            }
        return ResponseEntity.ok(SsoLinkStartResponse(authorizeUrl = authorizeUrl))
    }

    // ── 로컬 예외 핸들러 ──────────────────────────────────────────────────────────

    /** SSO 아님(LOCAL/LDAP)·registrationId 형식 위반 → 400. 계정 열거 0. */
    @org.springframework.web.bind.annotation.ExceptionHandler(SsoLinkValidationException::class)
    fun handleValidation(ex: SsoLinkValidationException): ResponseEntity<Map<String, String>> =
        jwtSupport.errorResponse(HttpStatus.BAD_REQUEST, ERROR_INVALID_SSO_REQUEST)

    /** 미존재/비활성 provider → 404. 어느 provider 인지 비노출. */
    @org.springframework.web.bind.annotation.ExceptionHandler(SsoProviderNotFoundException::class)
    fun handleNotFound(ex: SsoProviderNotFoundException): ResponseEntity<Map<String, String>> =
        jwtSupport.errorResponse(HttpStatus.NOT_FOUND, ERROR_PROVIDER_NOT_FOUND)

    private companion object {
        /** registrationId 화이트리스트 — 영숫자+하이픈만(authorizeUrl 경로 주입 차단, FR7 d). */
        val REGISTRATION_ID_PATTERN = Regex("^[A-Za-z0-9-]+$")

        /** SAML SSO 진입 경로 prefix(framework). */
        const val SAML_AUTHENTICATE_PREFIX = "/saml2/authenticate"

        /** OIDC SSO 진입 경로 prefix(framework). */
        const val OIDC_AUTHORIZATION_PREFIX = "/oauth2/authorization"

        const val ERROR_INVALID_SSO_REQUEST = "invalid_sso_request"
        const val ERROR_PROVIDER_NOT_FOUND = "provider_not_found"

        /** PAT 인증 시 SSO 연결 셀프서비스 불가 응답 — 두 엔드포인트 공용. */
        val PAT_FORBIDDEN_RESPONSE: ResponseEntity<Map<String, String>> =
            ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(mapOf("error" to "account_linking_requires_interactive_login"))
    }
}

/**
 * SSO 연결/재인증 시작 입력이 형식·유형 검증을 통과하지 못했을 때 던지는 예외 (FR-AU-08b, HTTP 400 의미).
 *
 * SSO 아님(LOCAL/LDAP) 또는 registrationId 형식 위반. 이름은 web 패키지 내 고유하다.
 */
class SsoLinkValidationException : RuntimeException("유효하지 않은 SSO 연결 요청입니다.")

/**
 * 대상 SSO provider 가 존재하지 않거나 비활성일 때 던지는 예외 (FR-AU-08b, HTTP 404 의미).
 *
 * 어느 provider 인지 노출하지 않는다(계정 열거 0). 이름은 web 패키지 내 고유하다.
 */
class SsoProviderNotFoundException : RuntimeException("SSO provider 를 찾을 수 없습니다.")
