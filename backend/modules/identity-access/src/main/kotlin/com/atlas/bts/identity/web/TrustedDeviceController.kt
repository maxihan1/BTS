// 신뢰 디바이스(30일 MFA 면제) 목록/취소 엔드포인트 — JWT 전용, PAT 차단 (FR-MF-05 Task 5)

package com.atlas.bts.identity.web

import com.atlas.bts.identity.mfa.TrustedDevice
import com.atlas.bts.identity.mfa.TrustedDeviceService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

/**
 * 신뢰 디바이스(30일 MFA 면제) self-service 목록/취소 엔드포인트 (FR-MF-05 Task 5 / SDD §19.7.3).
 *
 * 사용자가 직접 자신의 신뢰 디바이스를 조회([list])하고 단건([revoke])·전체([revokeAll])로 취소한다.
 * 등록(trust)과 우회 검증은 로그인 흐름(AuthController, Task 6) 소관이라 본 컨트롤러에 없다 —
 * 여기서는 [TrustedDeviceService] 의 조회/취소 오케스트레이션만 HTTP 로 노출한다.
 *
 * ## 엔드포인트 ([RequestMapping] `/api/v1/auth/mfa/trusted-devices`)
 * - [list]      GET    — 미만료 신뢰 디바이스 요약 목록.
 * - [revoke]    DELETE /{id} — 소유 검증 후 단건 취소.
 * - [revokeAll] DELETE — 전체 취소(전 기기 로그아웃 효과).
 *
 * ## JWT 전용 (PAT 차단)
 * 신뢰 디바이스 관리는 본인 세션 관리에 준하는 민감 작업이므로 대화형 로그인(JWT)만 허용한다.
 * PAT(Personal Access Token) 인증 시 principal 이 [Jwt] 타입이 아니어서 [AuthenticationPrincipal]
 * 주입이 null 이 되며, 이 경우 **403** + `session_management_requires_interactive_login` 으로 거부한다
 * ([MfaController]·[AuthController.listSessions] 의 PAT 분기와 동일 원칙 — session-management-pat-exclusion).
 * 미인증 요청은 Spring Security 필터(/api/ 하위 authenticated)가 401 을 반환해 컨트롤러에 도달하지 않는다.
 *
 * ## IDOR 차단 (OWASP)
 * [revoke] 의 타인 소유/미존재는 모두 **404 not_found** 로 일반화한다([TrustedDeviceService.revoke] 가 false 반환).
 * 타인 디바이스의 존재 여부를 응답 상태로 노출하지 않는다(존재 probe 방지).
 *
 * ## 비밀값 미노출 (DEVELOPMENT.md §1.1.1)
 * 응답 DTO([TrustedDeviceResponse])는 도메인 [TrustedDevice] 의 `tokenHash`(SHA-256)·`userId` 를
 * 절대 담지 않는다 — 표시용 식별자/라벨/시각만 노출한다. 본 컨트롤러는 로거를 두지 않는다(§1.1.2).
 *
 * ## 4xx 직접 매핑 (catch-all 변질 회귀 가드)
 * 도메인 결과(Boolean/List)를 컨트롤러에서 직접 [ResponseEntity] 상태로 매핑한다. 도메인 예외를 throw 하지
 * 않으므로 catch-all @ExceptionHandler 가 4xx 를 500 으로 변질시키는 회귀가 없다
 * (catch-all-exceptionhandler-swallows-responsestatusexception 교훈).
 */
@RestController
@RequestMapping("/api/v1/auth/mfa/trusted-devices")
class TrustedDeviceController(
    private val trustedDeviceService: TrustedDeviceService,
) {
    /**
     * GET /api/v1/auth/mfa/trusted-devices — 미만료 신뢰 디바이스 요약 목록을 반환한다(관리 화면용).
     *
     * @param jwt 인증된 JWT principal. PAT 인증 시 null → 403.
     * @return 200 `{devices:[{id,label,createdAt,lastUsedAt,expiresAt}]}` / 403 PAT / 401 미인증.
     */
    @GetMapping
    fun list(
        @AuthenticationPrincipal jwt: Jwt?,
    ): ResponseEntity<*> {
        val userId = userIdOrNull(jwt) ?: return PAT_FORBIDDEN_RESPONSE
        val devices = trustedDeviceService.list(userId).map(::toResponse)
        return ResponseEntity.ok(TrustedDevicesResponse(devices = devices))
    }

    /**
     * DELETE /api/v1/auth/mfa/trusted-devices/{id} — 소유 검증과 함께 단건 신뢰 디바이스를 취소한다.
     *
     * 미존재/타인 소유는 모두 **404 not_found** 로 응답해 타인 디바이스의 존재 여부를 노출하지 않는다(OWASP IDOR).
     *
     * @param jwt 인증된 JWT principal. PAT 인증 시 null → 403.
     * @param id 취소할 신뢰 디바이스 PK (Spring 이 UUID 바인딩 실패 시 400 자동).
     * @return 204 / 404 not_found / 403 PAT / 401 미인증.
     */
    @DeleteMapping("/{id}")
    fun revoke(
        @AuthenticationPrincipal jwt: Jwt?,
        @PathVariable id: UUID,
    ): ResponseEntity<*> {
        val userId = userIdOrNull(jwt) ?: return PAT_FORBIDDEN_RESPONSE
        return if (trustedDeviceService.revoke(userId, id)) {
            ResponseEntity.noContent().build<Unit>()
        } else {
            errorResponse(HttpStatus.NOT_FOUND, "not_found")
        }
    }

    /**
     * DELETE /api/v1/auth/mfa/trusted-devices — 사용자의 모든 신뢰 디바이스를 전량 취소한다(전 기기 로그아웃 효과).
     *
     * 0건이어도 멱등하게 204 를 반환한다(이미 신뢰 기기가 없는 상태도 성공으로 본다).
     *
     * @param jwt 인증된 JWT principal. PAT 인증 시 null → 403.
     * @return 204 / 403 PAT / 401 미인증.
     */
    @DeleteMapping
    fun revokeAll(
        @AuthenticationPrincipal jwt: Jwt?,
    ): ResponseEntity<*> {
        val userId = userIdOrNull(jwt) ?: return PAT_FORBIDDEN_RESPONSE
        trustedDeviceService.revokeAll(userId)
        return ResponseEntity.noContent().build<Unit>()
    }

    /** [TrustedDevice] 도메인을 응답 DTO 로 변환한다 — 비밀값(tokenHash)·userId 제외(§1.1.1). */
    private fun toResponse(device: TrustedDevice): TrustedDeviceResponse =
        TrustedDeviceResponse(
            id = device.id,
            label = device.label,
            createdAt = device.createdAt,
            lastUsedAt = device.lastUsedAt,
            expiresAt = device.expiresAt,
        )

    /**
     * JWT subject(UUID) 를 추출한다. PAT(=[jwt] null) 또는 subject 가 UUID 가 아니면 null.
     *
     * @param jwt nullable JWT principal ([Jwt] 타입 아니면 PAT).
     * @return 사용자 UUID 또는 null (호출 측에서 403 반환).
     */
    private fun userIdOrNull(jwt: Jwt?): UUID? = jwt?.subject?.let { runCatching { UUID.fromString(it) }.getOrNull() }

    /** `{"error": <code>}` 본문을 가진 [status] 응답을 생성한다 ([MfaController] 에러 응답 일원화 선례). */
    private fun errorResponse(
        status: HttpStatus,
        errorCode: String,
    ): ResponseEntity<Map<String, String>> = ResponseEntity.status(status).body(mapOf("error" to errorCode))

    private companion object {
        /** PAT 인증 시 신뢰 디바이스 관리 불가 응답 (session-management-pat-exclusion, [MfaController] 와 동일 코드). */
        val PAT_FORBIDDEN_RESPONSE: ResponseEntity<Map<String, String>> =
            ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(mapOf("error" to "session_management_requires_interactive_login"))
    }
}

/**
 * [TrustedDeviceController.list] 200 응답 — 신뢰 디바이스 요약 목록.
 *
 * @param devices 미만료 신뢰 디바이스 요약 목록(없으면 빈 목록). 비밀값(tokenHash)은 제외 (직렬화 키: devices).
 */
data class TrustedDevicesResponse(
    val devices: List<TrustedDeviceResponse>,
)

/**
 * 신뢰 디바이스 요약 항목 — 관리 화면 표시용.
 *
 * 도메인 [TrustedDevice] 의 `tokenHash`(SHA-256 해시)·`userId` 는 비밀/내부 식별자라 노출하지 않고
 * 표시에 필요한 식별자/라벨/시각만 담는다(§1.1.1 — 비밀값 미노출).
 *
 * @param id 신뢰 디바이스 PK (직렬화 키: id).
 * @param label User-Agent 파생 표시명. null 허용 (직렬화 키: label).
 * @param createdAt 신뢰 등록 시각 (직렬화 키: createdAt).
 * @param lastUsedAt 신뢰 우회 로그인 시 갱신되는 마지막 사용 시각. null=미사용 (직렬화 키: lastUsedAt).
 * @param expiresAt 만료 시각(등록 + 30일) (직렬화 키: expiresAt).
 */
data class TrustedDeviceResponse(
    val id: UUID,
    val label: String?,
    val createdAt: Instant,
    val lastUsedAt: Instant?,
    val expiresAt: Instant,
)
