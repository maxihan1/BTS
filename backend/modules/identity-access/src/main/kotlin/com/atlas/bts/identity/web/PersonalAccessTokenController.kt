// PAT 셀프서비스 엔드포인트 — 발급/목록/취소 (JWT 전용, PAT 차단·IDOR 404·검증 400) (FR-API-04 Task 5)

package com.atlas.bts.identity.web

import com.atlas.bts.identity.pat.BlankPatNameException
import com.atlas.bts.identity.pat.EmptyScopeException
import com.atlas.bts.identity.pat.InvalidPatExpiryException
import com.atlas.bts.identity.pat.PatQuotaExceededException
import com.atlas.bts.identity.pat.PersonalAccessTokenNotFoundException
import com.atlas.bts.identity.pat.PersonalAccessTokenService
import com.atlas.bts.identity.pat.UnknownScopeException
import com.atlas.bts.identity.web.dto.CreatePatRequest
import com.atlas.bts.identity.web.dto.IssuedPatResponse
import com.atlas.bts.identity.web.dto.PatListResponse
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * 사용자 본인의 Personal Access Token(PAT) 을 관리하는 셀프서비스 컨트롤러 (FR-API-04 / SDD §19.5).
 *
 * ## 엔드포인트 ([RequestMapping] `/api/v1/users/me/pats`)
 * - [issue]  POST        — PAT 발급. 응답에 raw token 을 1회 노출.
 * - [list]   GET         — 본인 PAT 요약 목록(만료 포함, token/hash 미노출).
 * - [revoke] DELETE /{id} — 본인 PAT 폐기(멱등). 타인/미존재는 404.
 *
 * ## JWT 전용 (PAT 차단)
 * PAT 관리 자체를 PAT 로 수행하면 권한 상승 통로가 되므로 대화형 로그인(JWT)만 허용한다. PAT 인증 시
 * principal 이 [Jwt] 가 아니어서 [AuthenticationPrincipal] 주입이 null 이 되며, 이 경우 **403** +
 * `session_management_requires_interactive_login` 으로 거부한다([TrustedDeviceController] 와 동일 원칙 —
 * session-management-pat-exclusion). 미인증 요청은 Spring Security 필터(/api/ 하위 authenticated)가 401 을
 * 반환해 컨트롤러에 도달하지 않는다.
 *
 * ## IDOR 차단 (OWASP)
 * [revoke] 의 타인 소유/미존재는 [PersonalAccessTokenService.revoke] 가 [PersonalAccessTokenNotFoundException]
 * 으로 수렴시키고, 본 컨트롤러 로컬 핸들러가 **404 `not_found`** 로 일반화한다(존재 probe 방지).
 *
 * ## 검증 → 400 (입력 반사 금지)
 * name 공백·빈/미지 scope·무기한/범위밖 만료·개수 상한은 [PersonalAccessTokenService.issue] 가 던지는 도메인
 * 예외를 로컬 [@ExceptionHandler] 가 고정 에러코드로 매핑한다. 미지 scope 원문 등 입력을 응답/로그에 반사하지
 * 않는다(fr-pm-04-guard-exception-message-http-leak 교훈 — 예외 message 를 HTTP 로 흘리지 않고 코드만 노출).
 *
 * ## 예외 → HTTP 매핑 (catch-all 변질 가드)
 * 모듈에 전역 @RestControllerAdvice 가 없으므로(선례 [PasswordController]·[AccountLinkController]) 도메인 예외를
 * **이 컨트롤러 로컬 [@ExceptionHandler]** 로만 상태 매핑한다. 타 컨트롤러에 영향을 주지 않고 4xx 를 500 으로
 * 변질시키지 않는다.
 *
 * ## 비밀값 미노출·감사 (DEVELOPMENT.md §1.1)
 * 응답 DTO 는 raw token(발급 1회 제외)·`token_hash`·`userId` 를 담지 않는다. 감사(PAT_ISSUED/PAT_REVOKED)는
 * [PersonalAccessTokenService] 가 트랜잭션 안에서 기록하므로 컨트롤러엔 감사/로거를 두지 않는다.
 */
@RestController
@RequestMapping("/api/v1/users/me/pats")
class PersonalAccessTokenController(
    private val patService: PersonalAccessTokenService,
) {
    /**
     * POST /api/v1/users/me/pats — 새 PAT 를 발급하고 raw token 을 1회 노출한다 (FR-API-04).
     *
     * @param jwt 인증 JWT principal. PAT 인증 시 null → 403.
     * @param req name/scopes/expiresInDays 발급 요청. 검증은 [PersonalAccessTokenService.issue] 담당.
     * @return 201 [IssuedPatResponse] / 403 PAT / 401 미인증 / 400 검증 실패.
     */
    @PostMapping
    fun issue(
        @AuthenticationPrincipal jwt: Jwt?,
        @RequestBody req: CreatePatRequest,
    ): ResponseEntity<*> {
        val userId = userIdOrNull(jwt) ?: return PAT_FORBIDDEN_RESPONSE
        val issued = patService.issue(userId, req.name, req.scopes, req.expiresInDays)
        return ResponseEntity.status(HttpStatus.CREATED).body(IssuedPatResponse.from(issued))
    }

    /**
     * GET /api/v1/users/me/pats — 본인 PAT 요약 목록(만료 포함, token/hash 미노출)을 반환한다.
     *
     * @param jwt 인증 JWT principal. PAT 인증 시 null → 403.
     * @return 200 [PatListResponse] / 403 PAT / 401 미인증.
     */
    @GetMapping
    fun list(
        @AuthenticationPrincipal jwt: Jwt?,
    ): ResponseEntity<*> {
        val userId = userIdOrNull(jwt) ?: return PAT_FORBIDDEN_RESPONSE
        return ResponseEntity.ok(PatListResponse.from(patService.listByUser(userId)))
    }

    /**
     * DELETE /api/v1/users/me/pats/{id} — 본인 PAT 를 폐기한다(멱등).
     *
     * 활성/이미취소 모두 예외 없이 완료되어 204 다. 타인 소유/미존재는 [PersonalAccessTokenService.revoke] 가
     * [PersonalAccessTokenNotFoundException] 을 던져 로컬 핸들러가 404 로 일반화한다(IDOR).
     *
     * @param jwt 인증 JWT principal. PAT 인증 시 null → 403.
     * @param id 폐기할 PAT UUID (바인딩 실패 시 Spring 이 400 자동).
     * @return 204 / 404 not_found / 403 PAT / 401 미인증.
     */
    @DeleteMapping("/{id}")
    fun revoke(
        @AuthenticationPrincipal jwt: Jwt?,
        @PathVariable id: UUID,
    ): ResponseEntity<*> {
        val userId = userIdOrNull(jwt) ?: return PAT_FORBIDDEN_RESPONSE
        patService.revoke(id, userId)
        return ResponseEntity.noContent().build<Unit>()
    }

    // ── 로컬 예외 핸들러 (PAT 도메인 예외 → HTTP) ─────────────────────────────────

    /**
     * 발급 검증 실패 → 400. 고정 에러코드만 노출하며 예외 message/입력(미지 scope 원문 등)은 반사하지 않는다.
     *
     * @param ex 발급 검증 도메인 예외.
     * @return 400 `{"error": <code>}`.
     */
    @ExceptionHandler(
        BlankPatNameException::class,
        EmptyScopeException::class,
        UnknownScopeException::class,
        InvalidPatExpiryException::class,
        PatQuotaExceededException::class,
    )
    fun handleBadRequest(ex: RuntimeException): ResponseEntity<Map<String, String>> {
        val code =
            when (ex) {
                is BlankPatNameException -> ERROR_INVALID_NAME
                is EmptyScopeException, is UnknownScopeException -> ERROR_INVALID_SCOPE
                is InvalidPatExpiryException -> ERROR_INVALID_EXPIRY
                is PatQuotaExceededException -> ERROR_QUOTA_EXCEEDED
                else -> ERROR_BAD_REQUEST
            }
        return errorResponse(HttpStatus.BAD_REQUEST, code)
    }

    /** 미소유/미존재 PAT → 404 `not_found`(존재 probe 방지). 예외 파라미터는 매핑에 불필요해 받지 않는다. */
    @ExceptionHandler(PersonalAccessTokenNotFoundException::class)
    fun handleNotFound(): ResponseEntity<Map<String, String>> = errorResponse(HttpStatus.NOT_FOUND, ERROR_NOT_FOUND)

    // ── private helpers ──────────────────────────────────────────────────────────

    /**
     * JWT subject(UUID) 를 추출한다. PAT(=[jwt] null) 또는 subject 가 UUID 가 아니면 null.
     *
     * @param jwt nullable JWT principal([Jwt] 아니면 PAT).
     * @return 사용자 UUID 또는 null(호출 측에서 403 반환).
     */
    private fun userIdOrNull(jwt: Jwt?): UUID? = jwt?.subject?.let { runCatching { UUID.fromString(it) }.getOrNull() }

    /** `{"error": <code>}` 본문을 가진 [status] 응답을 생성한다([TrustedDeviceController] 에러 응답 일원화 선례). */
    private fun errorResponse(
        status: HttpStatus,
        errorCode: String,
    ): ResponseEntity<Map<String, String>> = ResponseEntity.status(status).body(mapOf("error" to errorCode))

    private companion object {
        /** PAT 인증 시 PAT 셀프서비스 불가 응답(session-management-pat-exclusion, [TrustedDeviceController] 동일 코드). */
        val PAT_FORBIDDEN_RESPONSE: ResponseEntity<Map<String, String>> =
            ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(mapOf("error" to "session_management_requires_interactive_login"))

        /** name 공백. */
        const val ERROR_INVALID_NAME = "invalid_name"

        /** 빈/미지 scope. */
        const val ERROR_INVALID_SCOPE = "invalid_scope"

        /** 무기한/범위밖 만료. */
        const val ERROR_INVALID_EXPIRY = "invalid_expiry"

        /** 활성 PAT 개수 상한 초과. */
        const val ERROR_QUOTA_EXCEEDED = "quota_exceeded"

        /** 미소유/미존재 PAT(IDOR 일반화). */
        const val ERROR_NOT_FOUND = "not_found"

        /** 분류 불가 검증 실패 fallback(도달 불가 — when 망라). */
        const val ERROR_BAD_REQUEST = "bad_request"
    }
}
