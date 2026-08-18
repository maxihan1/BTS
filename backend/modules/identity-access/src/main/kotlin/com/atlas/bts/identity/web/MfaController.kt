// TOTP(2FA) 설정/활성화/상태조회/비활성화 엔드포인트 — JWT 전용, PAT 차단 (FR-MF-01 Task 9)

package com.atlas.bts.identity.web

import com.atlas.bts.identity.mfa.MfaBackupCodeService
import com.atlas.bts.identity.mfa.MfaBackupCodeService.GenerateResult
import com.atlas.bts.identity.mfa.MfaChallengeTokenService
import com.atlas.bts.identity.mfa.MfaService
import com.atlas.bts.identity.mfa.MfaService.DisableResult
import com.atlas.bts.identity.mfa.MfaService.EnableResult
import com.atlas.bts.identity.mfa.MfaService.SetupResult
import com.atlas.bts.identity.mfa.WebAuthnSecurityKeyService
import com.atlas.bts.identity.mfa.WebAuthnSecurityKeyService.KeySummary
import com.atlas.bts.identity.mfa.WebAuthnSecurityKeyService.RegisterResult
import com.atlas.bts.identity.user.UserRepository
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.JsonNode
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
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
import java.time.Instant
import java.util.UUID

/**
 * TOTP(Time-based One-Time Password, RFC 6238) 기반 2FA 설정 엔드포인트 (FR-MF-01 Task 9 / SDD §19.7).
 *
 * 본인 2FA self-service(설정/활성화/상태/비활성화)는 [MfaService] 오케스트레이션을 HTTP 로 노출한다.
 * 로그인 2단계 검증(`/api/v1/auth/mfa/verify`)은 Task 10(AuthController) 소관으로 본 컨트롤러에 없다.
 *
 * ## 엔드포인트
 * - [setup]   POST /totp/setup — secret 생성(PENDING) + Authenticator 앱용 otpauth/QR 반환.
 * - [enable]  POST /totp/enable — 첫 코드 검증 → ACTIVE 전환.
 * - [status]  GET  /totp — ACTIVE 여부.
 * - [disable] DELETE /totp — 현재 코드 검증(step-up) 후 비활성화.
 * - [generateBackupCodes] POST /backup-codes — 1회용 백업 코드 10개 발급/재발급(평문 1회 노출, FR-MF-02 Task 7).
 * - [backupCodesStatus]   GET  /backup-codes — 백업 코드 발급 여부 + 남은 미사용 개수.
 * - [webAuthnRegisterStart]  POST /webauthn/register/start — 보안키 등록(attestation) 옵션 발급 (FR-MF-03).
 * - [webAuthnRegisterFinish] POST /webauthn/register/finish — 등록 응답 검증 후 자격증명 저장.
 * - [webAuthnList]   GET    /webauthn — 등록 보안키 요약 목록.
 * - [webAuthnDelete] DELETE /webauthn/{id} — 소유 검증 후 보안키 삭제.
 * - [webAuthnAuthenticateStart] POST /webauthn/authenticate/start — 로그인 2단계 보안키 인증 옵션 발급
 *   (정식 세션 전 — 챌린지 토큰만으로 호출, permitAll).
 *
 * ## JWT 전용 (PAT 차단)
 * 2FA self-service 는 본인 세션 관리에 준하는 민감 작업이므로 대화형 로그인(JWT)만 허용한다.
 * PAT(Personal Access Token) 인증 시 principal 이 [Jwt] 타입이 아니어서 [AuthenticationPrincipal]
 * 주입이 null 이 되며, 이 경우 **403** + `session_management_requires_interactive_login` 으로 거부한다
 * ([AuthController.listSessions] 의 PAT 분기와 동일 원칙 — session-management-pat-exclusion).
 * 미인증 요청은 Spring Security 필터가 401 을 반환해 컨트롤러에 도달하지 않는다.
 * 예외로 [webAuthnAuthenticateStart] 는 정식 세션 발급 전(챌린지 토큰만) 호출되므로 JWT/PAT 가 아니라
 * 챌린지 토큰으로 사용자를 식별한다 (SecurityConfig 가 permitAll + CSRF-ignore 로 노출).
 *
 * ## 4xx 직접 매핑 (catch-all 변질 회귀 가드)
 * 도메인 결과([SetupResult]/[EnableResult]/[DisableResult])를 컨트롤러에서 직접 [ResponseEntity]
 * 상태로 매핑한다. 도메인 예외를 throw 하지 않으므로 catch-all @ExceptionHandler 가 4xx 를 500 으로
 * 변질시키는 회귀가 없다(catch-all-exceptionhandler-swallows-responsestatusexception 교훈).
 *
 * ## 보안 (DEVELOPMENT.md §1.1)
 * secret·입력 코드 등 비밀값은 로깅하지 않는다(§1.1.2). 본 컨트롤러는 로거를 두지 않는다.
 * 에러 응답은 [errorCode] 한 줄로 일반화해 내부 사정(존재 여부/정책)을 노출하지 않는다.
 *
 * @see SecurityConfig `/api/v1/auth/mfa/verify` permitAll/CSRF-ignore 등록 (Task 10 진입점)
 */
@RestController
@RequestMapping("/api/v1/auth/mfa")
// TooManyFunctions 억제 — TOTP self-service + 백업코드 + WebAuthn(FR-MF-03) ceremony 가 한 MFA
// self-service 책임에 응집한다. 엔드포인트별 분리는 공유 PAT/label 헬퍼·SecurityConfig 등록을 분산시킨다.
@Suppress("TooManyFunctions")
class MfaController(
    private val mfaService: MfaService,
    private val userRepo: UserRepository,
    private val backupCodeService: MfaBackupCodeService,
    private val webAuthnService: WebAuthnSecurityKeyService,
    private val challengeTokenService: MfaChallengeTokenService,
) {
    /**
     * POST /api/v1/auth/mfa/totp/setup — secret 생성(PENDING) + Authenticator 앱 provisioning 반환.
     *
     * otpauth label 은 Authenticator 앱 표시명이므로 UUID 가 아니라 사람이 식별 가능한 값으로 해소한다
     * (email 우선, 없으면 username — spec FR-2/GAP-4). label 해소는 web 레이어 책임이고
     * [MfaService.setup] 시그니처(userId, label)는 유지한다.
     *
     * @param jwt 인증된 JWT principal. PAT 인증 시 null → 403.
     * @return 200 `{otpauth_uri, qr_png_data_uri, secret_base32}` / 409 `already_enabled` / 403 PAT / 401 미인증.
     */
    @PostMapping("/totp/setup")
    fun setup(
        @AuthenticationPrincipal jwt: Jwt?,
    ): ResponseEntity<*> {
        val userId = userIdOrNull(jwt) ?: return PAT_FORBIDDEN_RESPONSE
        val label = resolveLabel(userId)
        return when (val result = mfaService.setup(userId, label = label)) {
            is SetupResult.Created ->
                ResponseEntity.ok(
                    SetupResponse(
                        otpauthUri = result.otpauthUri,
                        qrPngDataUri = result.qrPngDataUri,
                        secretBase32 = result.secretBase32,
                    ),
                )
            SetupResult.AlreadyEnabled -> errorResponse(HttpStatus.CONFLICT, "already_enabled")
        }
    }

    /**
     * POST /api/v1/auth/mfa/totp/enable — 첫 코드 검증 후 PENDING → ACTIVE 전환.
     *
     * @param jwt 인증된 JWT principal. PAT 인증 시 null → 403.
     * @param body 사용자가 입력한 6자리 코드.
     * @return 204 / 400 `invalid_code` / 409 `no_pending_setup` / 429 `too_many_attempts` / 403 / 401.
     */
    @PostMapping("/totp/enable")
    fun enable(
        @AuthenticationPrincipal jwt: Jwt?,
        @RequestBody body: MfaCodeRequest,
    ): ResponseEntity<*> {
        val userId = userIdOrNull(jwt) ?: return PAT_FORBIDDEN_RESPONSE
        return when (mfaService.enable(userId, body.code)) {
            EnableResult.Success -> ResponseEntity.noContent().build<Unit>()
            EnableResult.InvalidCode -> errorResponse(HttpStatus.BAD_REQUEST, "invalid_code")
            EnableResult.NoPending -> errorResponse(HttpStatus.CONFLICT, "no_pending_setup")
            EnableResult.TooManyAttempts -> errorResponse(HttpStatus.TOO_MANY_REQUESTS, "too_many_attempts")
        }
    }

    /**
     * GET /api/v1/auth/mfa/totp — ACTIVE 여부 조회.
     *
     * @param jwt 인증된 JWT principal. PAT 인증 시 null → 403.
     * @return 200 `{enabled}` / 403 PAT / 401 미인증.
     */
    @GetMapping("/totp")
    fun status(
        @AuthenticationPrincipal jwt: Jwt?,
    ): ResponseEntity<*> {
        val userId = userIdOrNull(jwt) ?: return PAT_FORBIDDEN_RESPONSE
        return ResponseEntity.ok(StatusResponse(enabled = mfaService.isEnabled(userId)))
    }

    /**
     * DELETE /api/v1/auth/mfa/totp — 현재 코드 검증(step-up) 후 2FA 비활성화.
     *
     * @param jwt 인증된 JWT principal. PAT 인증 시 null → 403.
     * @param body 사용자가 입력한 6자리 코드.
     * @return 204 / 400 `invalid_code` / 404 `not_enabled` / 429 `too_many_attempts` / 403 / 401.
     */
    @DeleteMapping("/totp")
    fun disable(
        @AuthenticationPrincipal jwt: Jwt?,
        @RequestBody body: MfaCodeRequest,
    ): ResponseEntity<*> {
        val userId = userIdOrNull(jwt) ?: return PAT_FORBIDDEN_RESPONSE
        return when (mfaService.disable(userId, body.code)) {
            DisableResult.Success -> ResponseEntity.noContent().build<Unit>()
            DisableResult.InvalidCode -> errorResponse(HttpStatus.BAD_REQUEST, "invalid_code")
            DisableResult.NotEnabled -> errorResponse(HttpStatus.NOT_FOUND, "not_enabled")
            DisableResult.TooManyAttempts -> errorResponse(HttpStatus.TOO_MANY_REQUESTS, "too_many_attempts")
        }
    }

    /**
     * POST /api/v1/auth/mfa/backup-codes — 1회용 백업 코드 10개 발급/재발급(기존 묶음 전량 교체).
     *
     * Authenticator 앱/하드웨어 키 분실 시의 로그인 복구 수단이다. 평문 코드 10개는 본 응답
     * ([BackupCodesResponse.codes])에만 한 번 노출되고 저장은 해시만이다(§1.1.1) — 이후 재확인이 불가하므로
     * 사용자에게 안전 보관을 안내한다. 평문 코드는 로깅하지 않으며(§1.1.2) 본 컨트롤러는 로거를 두지 않는다.
     * 2FA 가 켜진(TOTP ACTIVE) 사용자에게만 부여하므로 미활성 시 409 `totp_not_active` 로 거부한다(fail-closed).
     *
     * @param jwt 인증된 JWT principal. PAT 인증 시 null → 403.
     * @return 200 `{codes:[10개]}` / 409 `totp_not_active` / 403 PAT / 401 미인증.
     */
    @PostMapping("/backup-codes")
    fun generateBackupCodes(
        @AuthenticationPrincipal jwt: Jwt?,
    ): ResponseEntity<*> {
        val userId = userIdOrNull(jwt) ?: return PAT_FORBIDDEN_RESPONSE
        return when (val result = backupCodeService.generateOrRegenerate(userId)) {
            is GenerateResult.Generated -> ResponseEntity.ok(BackupCodesResponse(codes = result.codes))
            GenerateResult.NotActive -> errorResponse(HttpStatus.CONFLICT, "totp_not_active")
        }
    }

    /**
     * GET /api/v1/auth/mfa/backup-codes — 백업 코드 발급 여부 + 남은 미사용 개수 조회(상태 표시용).
     *
     * 평문 코드는 노출하지 않고 발급 여부([BackupCodesStatusResponse.generated])와 남은 개수
     * ([BackupCodesStatusResponse.remaining])만 반환한다 — UI 의 "백업 코드 N개 남음" 표시 및 재발급 유도용이다.
     *
     * @param jwt 인증된 JWT principal. PAT 인증 시 null → 403.
     * @return 200 `{generated, remaining}` / 403 PAT / 401 미인증.
     */
    @GetMapping("/backup-codes")
    fun backupCodesStatus(
        @AuthenticationPrincipal jwt: Jwt?,
    ): ResponseEntity<*> {
        val userId = userIdOrNull(jwt) ?: return PAT_FORBIDDEN_RESPONSE
        val status = backupCodeService.status(userId)
        return ResponseEntity.ok(
            BackupCodesStatusResponse(generated = status.generated, remaining = status.remaining),
        )
    }

    // ── WebAuthn(보안키/패스키) self-service (FR-MF-03, SDD §19.8) ────────────────

    /**
     * POST /api/v1/auth/mfa/webauthn/register/start — 보안키 등록(attestation) 옵션을 발급한다.
     *
     * `navigator.credentials.create()` 에 넘길 등록 옵션 JSON 을 반환한다. JSON String 을 그대로
     * application/json 으로 내려보내 컨트롤러가 다시 직렬화하지 않는다(서비스가 완성된 JSON 생성).
     *
     * @param jwt 인증된 JWT principal. PAT 인증 시 null → 403.
     * @return 200 등록 옵션 JSON / 403 PAT / 401 미인증.
     */
    @PostMapping("/webauthn/register/start", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun webAuthnRegisterStart(
        @AuthenticationPrincipal jwt: Jwt?,
    ): ResponseEntity<*> {
        val userId = userIdOrNull(jwt) ?: return PAT_FORBIDDEN_RESPONSE
        return ResponseEntity.ok(webAuthnService.registerStart(userId))
    }

    /**
     * POST /api/v1/auth/mfa/webauthn/register/finish — 등록 응답을 검증하고 자격증명을 저장한다.
     *
     * challenge 만료([RegisterResult.Expired])와 검증 실패([RegisterResult.InvalidRegistration])는
     * 모두 **400 invalid_registration** 으로 일반화해 내부 사정(만료/서명실패)을 노출하지 않는다(§1.1).
     *
     * @param jwt 인증된 JWT principal. PAT 인증 시 null → 403.
     * @param body 인증기 등록 응답([WebAuthnRegisterFinishRequest.credential]) + 별칭(name).
     * @return 201 `{id, name}` / 400 invalid_registration / 409 already_registered / 403 PAT / 401.
     */
    @PostMapping("/webauthn/register/finish")
    fun webAuthnRegisterFinish(
        @AuthenticationPrincipal jwt: Jwt?,
        @RequestBody body: WebAuthnRegisterFinishRequest,
    ): ResponseEntity<*> {
        val userId = userIdOrNull(jwt) ?: return PAT_FORBIDDEN_RESPONSE
        return when (webAuthnService.registerFinish(userId, body.credential.toString(), body.name)) {
            RegisterResult.Success -> ResponseEntity.status(HttpStatus.CREATED).build<Unit>()
            RegisterResult.Expired, RegisterResult.InvalidRegistration ->
                errorResponse(HttpStatus.BAD_REQUEST, "invalid_registration")
            RegisterResult.AlreadyRegistered -> errorResponse(HttpStatus.CONFLICT, "already_registered")
        }
    }

    /**
     * GET /api/v1/auth/mfa/webauthn — 등록 보안키 요약 목록을 반환한다(관리 화면용).
     *
     * @param jwt 인증된 JWT principal. PAT 인증 시 null → 403.
     * @return 200 `{keys:[{id,name,createdAt,lastUsedAt}]}` / 403 PAT / 401 미인증.
     */
    @GetMapping("/webauthn")
    fun webAuthnList(
        @AuthenticationPrincipal jwt: Jwt?,
    ): ResponseEntity<*> {
        val userId = userIdOrNull(jwt) ?: return PAT_FORBIDDEN_RESPONSE
        val keys = webAuthnService.listKeys(userId).map(::toKeyResponse)
        return ResponseEntity.ok(WebAuthnKeysResponse(keys = keys))
    }

    /**
     * DELETE /api/v1/auth/mfa/webauthn/{id} — 소유 검증과 함께 보안키를 삭제한다.
     *
     * 미존재/타인 소유는 모두 **404 not_found** 로 응답해 타인 키의 존재 여부를 노출하지 않는다(OWASP IDOR).
     *
     * @param jwt 인증된 JWT principal. PAT 인증 시 null → 403.
     * @param id 삭제할 자격증명 PK (Spring 이 UUID 바인딩 실패 시 400 자동).
     * @return 204 / 404 not_found / 403 PAT / 401 미인증.
     */
    @DeleteMapping("/webauthn/{id}")
    fun webAuthnDelete(
        @AuthenticationPrincipal jwt: Jwt?,
        @PathVariable id: UUID,
    ): ResponseEntity<*> {
        val userId = userIdOrNull(jwt) ?: return PAT_FORBIDDEN_RESPONSE
        return if (webAuthnService.deleteKey(userId, id)) {
            ResponseEntity.noContent().build<Unit>()
        } else {
            errorResponse(HttpStatus.NOT_FOUND, "not_found")
        }
    }

    /**
     * POST /api/v1/auth/mfa/webauthn/authenticate/start — 로그인 2단계 보안키 인증(assertion) 옵션을 발급한다.
     *
     * 정식 세션 발급 전(JWT 없음)에 호출되므로 1단계(pw) 통과 증명인 챌린지 토큰으로 사용자를 식별한다
     * (SecurityConfig 가 permitAll + CSRF-ignore 로 노출). 토큰을 validate 만 하고 **consume 하지 않는다**
     * — 실제 nonce 1회 소비는 verify(2단계 완료) 경로([WebAuthnSecurityKeyService.verifyLogin] 내부)에서만 한다.
     * 만료/위조 토큰은 **401 mfa_challenge_expired**(불명은 거부).
     *
     * @param body 1단계 응답의 챌린지 토큰.
     * @return 200 인증 옵션 JSON / 401 mfa_challenge_expired.
     */
    @PostMapping("/webauthn/authenticate/start", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun webAuthnAuthenticateStart(
        @RequestBody body: WebAuthnAuthenticateStartRequest,
    ): ResponseEntity<*> {
        val claims =
            challengeTokenService.validate(body.mfaChallengeToken)
                ?: return errorResponse(HttpStatus.UNAUTHORIZED, "mfa_challenge_expired")
        return ResponseEntity.ok(webAuthnService.authenticateStart(claims.userId))
    }

    /** [KeySummary] 도메인 요약을 응답 DTO 로 변환한다(민감 raw 데이터 제외). */
    private fun toKeyResponse(summary: KeySummary): WebAuthnKeyResponse =
        WebAuthnKeyResponse(
            id = summary.id,
            name = summary.name,
            createdAt = summary.createdAt,
            lastUsedAt = summary.lastUsedAt,
        )

    /**
     * JWT subject(UUID) 를 추출한다. PAT(=[jwt] null) 또는 subject 가 UUID 가 아니면 null.
     *
     * @param jwt nullable JWT principal ([Jwt] 타입 아니면 PAT).
     * @return 사용자 UUID 또는 null (호출 측에서 403 반환).
     */
    private fun userIdOrNull(jwt: Jwt?): UUID? = jwt?.subject?.let { runCatching { UUID.fromString(it) }.getOrNull() }

    /**
     * otpauth label 을 해소한다 — email 우선, 없으면 username, 사용자 미조회 시 userId 문자열 fallback.
     *
     * Authenticator 앱은 이 label 을 계정 표시명으로 쓰므로 UUID 대신 사람이 식별 가능한 값을 노출한다
     * (spec FR-2/GAP-4). 인증된 userId 라 findById 가 null 일 일은 사실상 없으나, 이론상 부재 시
     * userId 문자열로 안전하게 fallback 한다.
     *
     * @param userId 설정 주체 사용자 UUID.
     * @return email → username → userId.toString() 순으로 해소된 label.
     */
    private fun resolveLabel(userId: UUID): String {
        val user = userRepo.findById(userId) ?: return userId.toString()
        return user.email ?: user.username
    }

    /** `{"error": <code>}` 본문을 가진 [status] 응답을 생성한다 (AuthController 에러 응답 일원화 선례). */
    private fun errorResponse(
        status: HttpStatus,
        errorCode: String,
    ): ResponseEntity<Map<String, String>> = ResponseEntity.status(status).body(mapOf("error" to errorCode))

    private companion object {
        /** PAT 인증 시 2FA self-service 불가 응답 (session-management-pat-exclusion, AuthController 와 동일 코드). */
        val PAT_FORBIDDEN_RESPONSE: ResponseEntity<Map<String, String>> =
            ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(mapOf("error" to "session_management_requires_interactive_login"))
    }
}

/**
 * enable/disable 요청 body — 사용자가 입력한 6자리 TOTP 코드.
 *
 * @param code Authenticator 앱이 표시한 코드. 검증은 [MfaService] 가 수행한다.
 */
data class MfaCodeRequest(
    val code: String,
)

/**
 * [MfaController.setup] 200 응답 — Authenticator 앱 provisioning 정보.
 *
 * 평문 secret 은 [otpauthUri] 안과 [secretBase32] 필드에 노출되지만, 어느 쪽도 저장하지 않는다(§1.1.1).
 * [secretBase32] 는 QR 스캔 불가 환경의 수동입력 fallback 전용이며 로그에 절대 담지 않는다(§1.1.2).
 *
 * @param otpauthUri Authenticator 앱이 스캔할 otpauth:// provisioning URI (직렬화 키: otpauth_uri).
 * @param qrPngDataUri [otpauthUri] 를 인코딩한 QR PNG data URI (직렬화 키: qr_png_data_uri).
 * @param secretBase32 평문 base32 secret — 수동입력 fallback 용 (직렬화 키: secret_base32).
 */
data class SetupResponse(
    @JsonProperty("otpauth_uri") val otpauthUri: String,
    @JsonProperty("qr_png_data_uri") val qrPngDataUri: String,
    @JsonProperty("secret_base32") val secretBase32: String,
)

/**
 * [MfaController.status] 200 응답 — TOTP ACTIVE 여부.
 *
 * @param enabled TOTP 가 ACTIVE 면 true.
 */
data class StatusResponse(
    val enabled: Boolean,
)

/**
 * [MfaController.generateBackupCodes] 200 응답 — 새로 발급한 1회용 백업 코드 평문 묶음.
 *
 * 평문 10개는 이 응답에만 한 번 노출되고 저장은 해시만이다(§1.1.1). 사용자가 안전 보관하지 않으면
 * 재확인이 불가하며, 분실 시 재발급으로 새 묶음을 받아야 한다.
 *
 * @param codes 1회용 백업 코드 평문 10개 (직렬화 키: codes).
 */
data class BackupCodesResponse(
    val codes: List<String>,
)

/**
 * [MfaController.backupCodesStatus] 200 응답 — 백업 코드 발급 여부와 남은 미사용 개수.
 *
 * @param generated 백업 코드가 한 번이라도 발급됐으면 true (직렬화 키: generated).
 * @param remaining 아직 사용하지 않은 백업 코드 수 (직렬화 키: remaining).
 */
data class BackupCodesStatusResponse(
    val generated: Boolean,
    val remaining: Int,
)

/**
 * [MfaController.webAuthnRegisterFinish] 요청 body — 인증기 등록 응답 + 사용자 지정 별칭 (FR-MF-03).
 *
 * [credential] 은 브라우저 `navigator.credentials.create()` 결과 PublicKeyCredential JSON 트리이며,
 * 컨트롤러는 이를 다시 직렬화해 [WebAuthnSecurityKeyService.registerFinish] 에 문자열로 넘긴다.
 *
 * @param credential 인증기 등록 응답 JSON 트리 (역직렬화 키: credential).
 * @param name 사용자 지정 별칭(예: "회사 노트북 Touch ID"). null 허용.
 */
data class WebAuthnRegisterFinishRequest(
    val credential: JsonNode,
    val name: String? = null,
)

/**
 * [MfaController.webAuthnAuthenticateStart] 요청 body — 로그인 2단계 보안키 인증 시작 (FR-MF-03).
 *
 * 정식 세션 발급 전(JWT 없음) 호출이라 1단계 통과 증명인 챌린지 토큰으로 사용자를 식별한다.
 *
 * @param mfaChallengeToken 1단계 응답([MfaRequiredResponse])의 챌린지 토큰 (역직렬화 키: mfa_challenge_token).
 */
data class WebAuthnAuthenticateStartRequest(
    @JsonProperty("mfa_challenge_token") val mfaChallengeToken: String,
)

/**
 * [MfaController.webAuthnList] 200 응답 — 등록 보안키 요약 목록.
 *
 * @param keys 등록 키 요약 목록(없으면 빈 목록). 공개키 등 민감 raw 데이터는 제외 (직렬화 키: keys).
 */
data class WebAuthnKeysResponse(
    val keys: List<WebAuthnKeyResponse>,
)

/**
 * 보안키 요약 항목 — 관리 화면 표시용(민감 raw 데이터 제외).
 *
 * @param id 자격증명 PK (직렬화 키: id).
 * @param name 사용자 지정 별칭. null 허용 (직렬화 키: name).
 * @param createdAt 등록 시각 (직렬화 키: createdAt).
 * @param lastUsedAt 마지막 로그인 성공 시각. null=미사용 (직렬화 키: lastUsedAt).
 */
data class WebAuthnKeyResponse(
    val id: UUID,
    val name: String?,
    val createdAt: Instant,
    val lastUsedAt: Instant?,
)
