// 사용자 상태 메시지 조회/설정 REST 컨트롤러 (FR-PR-02 Task 4)

package com.atlas.bts.identity.web

import com.atlas.bts.identity.dto.StatusPatchRequest
import com.atlas.bts.identity.dto.StatusResponse
import com.atlas.bts.identity.status.StatusPatch
import com.atlas.bts.identity.status.StatusValidationException
import com.atlas.bts.identity.status.StatusView
import com.atlas.bts.identity.status.UserStatusService
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/**
 * 사용자 상태 메시지 조회/설정 REST 컨트롤러 (FR-PR-02 Task 4).
 *
 * ## 엔드포인트 ([RequestMapping] `/api/v1/users`)
 * - [getMyStatus]   GET   `/me/status` — 본인 활성 상태 조회(미설정/만료면 all-null).
 * - [patchMyStatus] PATCH `/me/status` — 상태 원자적 교체(replace). 둘 다 빈값이면 해제.
 *
 * ## 현재 사용자 식별 (JWT subject 전용, [UserProfileController] 미러)
 * [AuthenticationPrincipal] 로 주입된 [Jwt] 의 subject(UUID)로 현재 사용자를 식별한다([currentUserId]).
 * principal 이 [Jwt] 가 아니거나(PAT 등) subject 가 UUID 형식이 아니면 401 로 거부한다 — 이 컨트롤러는
 * PAT 로부터의 사용자 식별을 지원하지 않는다(me-scope, FR-PR-01 선례).
 *
 * ## 예외 → HTTP 매핑 (catch-all 변질 가드)
 * 모듈에 전역 `@RestControllerAdvice` 가 없으므로([UserProfileController] 선례) [StatusValidationException]
 * 을 이 컨트롤러 로컬 [ExceptionHandler] 로만 400 매핑한다. 서비스가 사용자 노출용으로 미리 작성한
 * 안전한 메시지를 그대로 응답에 담는다.
 */
@RestController
@RequestMapping("/api/v1/users")
class UserStatusController(
    private val userStatusService: UserStatusService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * GET `/api/v1/users/me/status` — 본인 활성 상태를 조회한다.
     *
     * @param jwt 인증 JWT principal. PAT 등 미지원 인증이면 401.
     * @return 200 [StatusResponse]. 미설정/만료면 세 필드 all-null.
     */
    @GetMapping("/me/status")
    fun getMyStatus(
        @AuthenticationPrincipal jwt: Jwt?,
    ): StatusResponse = toResponse(userStatusService.getActiveStatus(currentUserId(jwt)))

    /**
     * PATCH `/api/v1/users/me/status` — 본인 상태를 원자적으로 교체한다(replace).
     *
     * emoji/text 가 둘 다 빈값이면 해제(all-null 응답). 검증 실패 시 400([handleValidation]).
     *
     * @param jwt 인증 JWT principal. PAT 등 미지원 인증이면 401.
     * @param req 설정 요청 바디([StatusPatchRequest]).
     * @return 200 설정 후 [StatusResponse].
     * @throws StatusValidationException 검증 실패 시(→ 400).
     */
    @PatchMapping("/me/status")
    fun patchMyStatus(
        @AuthenticationPrincipal jwt: Jwt?,
        @RequestBody req: StatusPatchRequest,
    ): StatusResponse =
        toResponse(
            userStatusService.setStatus(currentUserId(jwt), StatusPatch(req.emoji, req.text, req.expiresAt)),
        )

    /** 상태 검증 실패 → 400. 메시지는 서비스가 사용자 노출용으로 미리 작성한 안전한 값이다. */
    @ExceptionHandler(StatusValidationException::class)
    fun handleValidation(ex: StatusValidationException): ResponseEntity<Map<String, String>> {
        log.info("상태 검증 실패 exceptionType={}", ex.javaClass.simpleName)
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(mapOf("code" to ERROR_STATUS_VALIDATION, "message" to (ex.message ?: DEFAULT_VALIDATION_MESSAGE)))
    }

    /**
     * JWT subject(UUID)로 현재 사용자를 식별한다([UserProfileController] 미러 — 리소스 조회보다 먼저
     * 인증 주체를 추출한다).
     *
     * @throws ResponseStatusException subject 가 없거나 UUID 형식이 아니면 401.
     */
    private fun currentUserId(jwt: Jwt?): UUID =
        jwt?.subject?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED)

    private fun toResponse(view: StatusView): StatusResponse =
        StatusResponse(emoji = view.emoji, text = view.text, expiresAt = view.expiresAt)

    private companion object {
        /** 상태 검증 실패 에러 코드. */
        const val ERROR_STATUS_VALIDATION = "STATUS_VALIDATION_FAILED"

        /** 검증 예외 message 가 비어 있을 때(도달 불가) fallback. */
        const val DEFAULT_VALIDATION_MESSAGE = "잘못된 요청입니다."
    }
}
