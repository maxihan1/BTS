// TOTP(2FA) 설정/활성화/상태조회/비활성화 엔드포인트 — JWT 전용, PAT 차단 (FR-MF-01 Task 9)

package com.atlas.bts.identity.web

import com.atlas.bts.identity.mfa.MfaService
import com.atlas.bts.identity.mfa.MfaService.DisableResult
import com.atlas.bts.identity.mfa.MfaService.EnableResult
import com.atlas.bts.identity.mfa.MfaService.SetupResult
import com.atlas.bts.identity.user.UserRepository
import com.fasterxml.jackson.annotation.JsonProperty
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * TOTP(Time-based One-Time Password, RFC 6238) 기반 2FA 설정 엔드포인트 (FR-MF-01 Task 9 / SDD §19.7).
 *
 * 본인 2FA self-service(설정/활성화/상태/비활성화)는 [MfaService] 오케스트레이션을 HTTP 로 노출한다.
 * 로그인 2단계 검증(`/api/v1/auth/mfa/verify`)은 Task 10(AuthController) 소관으로 본 컨트롤러에 없다.
 *
 * ## 엔드포인트
 * - [setup]   POST /totp/setup — secret 생성(PENDING) + Authenticator 앱용 otpauth/QR 반환.
 * - [enable]  POST /totp/enable — 첫 코드 검증 → ACTIVE 전이.
 * - [status]  GET  /totp — ACTIVE 여부.
 * - [disable] DELETE /totp — 현재 코드 검증(step-up) 후 비활성화.
 *
 * ## JWT 전용 (PAT 차단)
 * 2FA self-service 는 본인 세션 관리에 준하는 민감 작업이므로 대화형 로그인(JWT)만 허용한다.
 * PAT(Personal Access Token) 인증 시 principal 이 [Jwt] 타입이 아니어서 [AuthenticationPrincipal]
 * 주입이 null 이 되며, 이 경우 **403** + `session_management_requires_interactive_login` 으로 거부한다
 * ([AuthController.listSessions] 의 PAT 분기와 동일 원칙 — session-management-pat-exclusion).
 * 미인증 요청은 Spring Security 필터가 401 을 반환해 컨트롤러에 도달하지 않는다.
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
class MfaController(
    private val mfaService: MfaService,
    private val userRepo: UserRepository,
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
     * POST /api/v1/auth/mfa/totp/enable — 첫 코드 검증 후 PENDING → ACTIVE 전이.
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
    private fun resolveLabel(userId: UUID): String =
        userRepo.findById(userId)?.let { it.email ?: it.username } ?: userId.toString()

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
