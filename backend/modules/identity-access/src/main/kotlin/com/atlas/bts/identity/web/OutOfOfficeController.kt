// 부재중 조회/설정/해제 REST 컨트롤러 (FR-PR-03 Task 4)

package com.atlas.bts.identity.web

import com.atlas.bts.identity.dto.OooPatchRequest
import com.atlas.bts.identity.dto.OooResponse
import com.atlas.bts.identity.ooo.OooPatch
import com.atlas.bts.identity.ooo.OooValidationException
import com.atlas.bts.identity.ooo.OooView
import com.atlas.bts.identity.ooo.OutOfOfficeService
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/**
 * 부재중(OOO) 조회/설정/해제 REST 컨트롤러 (FR-PR-03 Task 4).
 *
 * ## 엔드포인트 ([RequestMapping] `/api/v1/users`)
 * - [getMyOoo]    GET    `/me/ooo` — 본인 OOO 조회(미설정/종료면 all-null, G3).
 * - [patchMyOoo]  PATCH  `/me/ooo` — OOO 원자적 교체(replace).
 * - [deleteMyOoo] DELETE `/me/ooo` — OOO 해제(204, 멱등).
 *
 * ## 현재 사용자 식별 (JWT subject 전용, [UserStatusController] 미러)
 * [AuthenticationPrincipal] 로 주입된 [Jwt] 의 subject(UUID)로 현재 사용자를 식별한다([currentUserId]).
 * principal 이 [Jwt] 가 아니거나(PAT 등) subject 가 UUID 형식이 아니면 401 로 거부한다 — 이 컨트롤러는
 * PAT 로부터의 사용자 식별을 지원하지 않는다(me-scope, FR-PR-01/02 선례).
 *
 * ## 예외 → HTTP 매핑 (catch-all 변질 가드)
 * 모듈에 전역 `@RestControllerAdvice` 가 없으므로([UserStatusController] 선례) [OooValidationException]
 * 을 이 컨트롤러 로컬 [ExceptionHandler] 로만 400 매핑한다.
 */
@RestController
@RequestMapping("/api/v1/users")
class OutOfOfficeController(
    private val outOfOfficeService: OutOfOfficeService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * GET `/api/v1/users/me/ooo` — 본인 OOO 를 조회한다.
     *
     * @param jwt 인증 JWT principal. PAT 등 미지원 인증이면 401.
     * @return 200 [OooResponse]. 미설정/종료(G3)면 기간·대리자·메시지 all-null, active false.
     */
    @GetMapping("/me/ooo")
    fun getMyOoo(
        @AuthenticationPrincipal jwt: Jwt?,
    ): OooResponse = toResponse(outOfOfficeService.getOoo(currentUserId(jwt)))

    /**
     * PATCH `/api/v1/users/me/ooo` — 본인 OOO 를 원자적으로 교체한다(replace).
     *
     * @param jwt 인증 JWT principal. PAT 등 미지원 인증이면 401.
     * @param req 설정 요청 바디([OooPatchRequest]).
     * @return 200 설정 후 [OooResponse].
     * @throws OooValidationException 검증 실패 시(→ 400).
     */
    @PatchMapping("/me/ooo")
    fun patchMyOoo(
        @AuthenticationPrincipal jwt: Jwt?,
        @RequestBody req: OooPatchRequest,
    ): OooResponse =
        toResponse(
            outOfOfficeService.setOoo(
                currentUserId(jwt),
                OooPatch(req.startsAt, req.endsAt, req.delegateUserId, req.message),
            ),
        )

    /**
     * DELETE `/api/v1/users/me/ooo` — 본인 OOO 를 해제한다. 멱등(없어도 204).
     *
     * @param jwt 인증 JWT principal. PAT 등 미지원 인증이면 401.
     * @return 204 No Content.
     */
    @DeleteMapping("/me/ooo")
    fun deleteMyOoo(
        @AuthenticationPrincipal jwt: Jwt?,
    ): ResponseEntity<Void> {
        outOfOfficeService.clearOoo(currentUserId(jwt))
        return ResponseEntity.noContent().build()
    }

    /** OOO 검증 실패 → 400. 메시지는 서비스가 사용자 노출용으로 미리 작성한 안전한 값이다. */
    @ExceptionHandler(OooValidationException::class)
    fun handleValidation(ex: OooValidationException): ResponseEntity<Map<String, String>> {
        log.info("부재중 검증 실패 exceptionType={}", ex.javaClass.simpleName)
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(mapOf("code" to ERROR_OOO_VALIDATION, "message" to (ex.message ?: DEFAULT_VALIDATION_MESSAGE)))
    }

    /**
     * JWT subject(UUID)로 현재 사용자를 식별한다([UserStatusController] 미러 — 리소스 조회보다 먼저
     * 인증 주체를 추출한다).
     *
     * @throws ResponseStatusException subject 가 없거나 UUID 형식이 아니면 401.
     */
    private fun currentUserId(jwt: Jwt?): UUID =
        jwt?.subject?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED)

    private fun toResponse(view: OooView): OooResponse =
        OooResponse(
            startsAt = view.startsAt,
            endsAt = view.endsAt,
            delegateUserId = view.delegateUserId,
            delegateName = view.delegateName,
            message = view.message,
            active = view.active,
        )

    private companion object {
        /** 부재중 검증 실패 에러 코드. */
        const val ERROR_OOO_VALIDATION = "OOO_VALIDATION_FAILED"

        /** 검증 예외 message 가 비어 있을 때(도달 불가) fallback. */
        const val DEFAULT_VALIDATION_MESSAGE = "잘못된 요청입니다."
    }
}
