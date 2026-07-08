// 사용자 환경설정 조회/부분 수정 REST 컨트롤러 (FR-PF-01 Task 3)

package com.atlas.bts.identity.web

import com.atlas.bts.identity.dto.PreferencesPatchRequest
import com.atlas.bts.identity.dto.PreferencesResponse
import com.atlas.bts.identity.preferences.PreferencesPatch
import com.atlas.bts.identity.preferences.PreferencesValidationException
import com.atlas.bts.identity.preferences.UserPreferences
import com.atlas.bts.identity.preferences.UserPreferencesService
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
 * 사용자 환경설정(테마/로케일/날짜형식) 조회/부분 수정 REST 컨트롤러 (FR-PF-01 Task 3).
 *
 * ## 엔드포인트 ([RequestMapping] `/api/v1/users`)
 * - [getMyPreferences]   GET   `/me/preferences` — 본인 환경설정 조회(행 없으면 기본값).
 * - [patchMyPreferences] PATCH `/me/preferences` — 2-state 부분 수정.
 *
 * ## 현재 사용자 식별 (JWT subject 전용, [UserProfileController] 미러)
 * [AuthenticationPrincipal] 로 주입된 [Jwt] 의 subject(UUID)로 현재 사용자를 식별한다([currentUserId]).
 * principal 이 [Jwt] 가 아니거나(PAT 등) subject 가 UUID 형식이 아니면 401 로 거부한다 — PAT 로부터의
 * 사용자 식별은 지원하지 않는다. Spring Security 필터 체인(`/api` 하위 전체 authenticated) + 이 JWT 전용
 * 가드의 이중 방어다.
 *
 * ## 예외 → HTTP 매핑
 * 모듈에 전역 `@RestControllerAdvice` 가 없으므로([UserProfileController] 선례) 도메인 예외를 이 컨트롤러
 * 로컬 [ExceptionHandler] 로만 상태 매핑한다. [PreferencesValidationException] 의 메시지는 서비스가 사용자
 * 노출용으로 미리 작성한 일반화 값(내부정보 없음)이므로 그대로 응답에 담아 400 으로 매핑한다([handleValidation]).
 */
@RestController
@RequestMapping("/api/v1/users")
class PreferencesController(
    private val userPreferencesService: UserPreferencesService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * GET `/api/v1/users/me/preferences` — 본인 환경설정을 조회한다.
     *
     * @param jwt 인증 JWT principal. PAT 등 미지원 인증이면 401.
     * @return 200 [PreferencesResponse](저장값 또는 기본값).
     */
    @GetMapping("/me/preferences")
    fun getMyPreferences(
        @AuthenticationPrincipal jwt: Jwt?,
    ): PreferencesResponse {
        val userId = currentUserId(jwt)
        return toResponse(userPreferencesService.getPreferences(userId))
    }

    /**
     * PATCH `/api/v1/users/me/preferences` — 본인 환경설정을 2-state 로 부분 수정한다.
     *
     * @param jwt 인증 JWT principal. PAT 등 미지원 인증이면 401.
     * @param req 2-state PATCH 요청 바디([PreferencesPatchRequest]).
     * @return 200 갱신 후 [PreferencesResponse].
     * @throws PreferencesValidationException theme/locale/dateFormat 중 하나라도 허용값 밖일 때(→ 400, [handleValidation]).
     */
    @PatchMapping("/me/preferences")
    fun patchMyPreferences(
        @AuthenticationPrincipal jwt: Jwt?,
        @RequestBody req: PreferencesPatchRequest,
    ): PreferencesResponse {
        val userId = currentUserId(jwt)
        return toResponse(userPreferencesService.patchPreferences(userId, toPatch(req)))
    }

    // ── 로컬 예외 핸들러 (환경설정 도메인 예외 → HTTP) ─────────────────────────────

    /** theme/locale/dateFormat 허용값 위반 → 400. 메시지는 서비스가 사용자 노출용으로 미리 작성한 일반화 값이다. */
    @ExceptionHandler(PreferencesValidationException::class)
    fun handleValidation(ex: PreferencesValidationException): ResponseEntity<Map<String, String>> {
        log.info("환경설정 검증 실패 exceptionType={}", ex.javaClass.simpleName)
        return errorResponse(
            HttpStatus.BAD_REQUEST,
            ERROR_PREFERENCES_VALIDATION,
            ex.message ?: DEFAULT_VALIDATION_MESSAGE,
        )
    }

    // ── private helpers ─────────────────────────────────────────────────────────

    /**
     * JWT subject(UUID) 로 현재 사용자를 식별한다([UserProfileController] 와 동일 원칙 — 리소스 조회보다
     * 먼저 인증 주체를 추출한다).
     *
     * @param jwt [AuthenticationPrincipal] 로 주입된 JWT. PAT 등 미지원 인증이면 null.
     * @return 현재 사용자 UUID.
     * @throws ResponseStatusException subject 가 없거나 UUID 형식이 아니면 401.
     */
    private fun currentUserId(jwt: Jwt?): UUID =
        jwt?.subject?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED)

    /** [UserPreferences] → [PreferencesResponse]. */
    private fun toResponse(prefs: UserPreferences): PreferencesResponse =
        PreferencesResponse(
            theme = prefs.theme,
            locale = prefs.locale,
            dateFormat = prefs.dateFormat,
            startPage = prefs.startPage,
        )

    /** [PreferencesPatchRequest] 의 nullable 2-state 를 도메인 [PreferencesPatch] 로 변환한다. */
    private fun toPatch(req: PreferencesPatchRequest): PreferencesPatch =
        PreferencesPatch(
            theme = req.theme,
            locale = req.locale,
            dateFormat = req.dateFormat,
            startPage = req.startPage,
        )

    /** `{"code":..., "message":...}` 본문을 가진 [status] 응답을 생성한다([UserProfileController] 에러 응답 형식 일관). */
    private fun errorResponse(
        status: HttpStatus,
        code: String,
        message: String,
    ): ResponseEntity<Map<String, String>> {
        return ResponseEntity.status(status).body(mapOf("code" to code, "message" to message))
    }

    private companion object {
        /** theme/locale/dateFormat 검증 실패 에러 코드([UserProfileController] `PROFILE_VALIDATION_FAILED` 대응). */
        const val ERROR_PREFERENCES_VALIDATION = "PREFERENCES_VALIDATION_FAILED"

        /** 검증 예외 message 가 비어 있을 때(도달 불가— 예외는 항상 message 를 채운다) fallback. */
        const val DEFAULT_VALIDATION_MESSAGE = "잘못된 요청입니다."
    }
}
