// 사용자 프로필 조회/수정 + 아바타 업로드/다운로드/삭제 REST 컨트롤러 (FR-PR-01 Task 5)

package com.atlas.bts.identity.web

import com.atlas.bts.identity.dto.AvatarUploadResponse
import com.atlas.bts.identity.dto.ProfilePatchRequest
import com.atlas.bts.identity.dto.ProfileResponse
import com.atlas.bts.identity.profile.ProfilePatch
import com.atlas.bts.identity.profile.ProfilePatchField
import com.atlas.bts.identity.profile.ProfileUserNotFoundException
import com.atlas.bts.identity.profile.ProfileValidationException
import com.atlas.bts.identity.profile.ProfileView
import com.atlas.bts.identity.profile.UserProfileService
import com.atlas.bts.identity.profile.avatar.AvatarObjectNotFoundException
import com.atlas.bts.identity.profile.avatar.AvatarValidationException
import com.fasterxml.jackson.databind.JsonNode
import org.slf4j.LoggerFactory
import org.springframework.core.io.InputStreamResource
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/**
 * 사용자 프로필 조회/수정 + 아바타 업로드/다운로드/삭제 REST 컨트롤러 (FR-PR-01 Task 5).
 *
 * ## 엔드포인트 ([RequestMapping] `/api/v1/users`)
 * - [getMyProfile]   GET    `/me/profile`        — 본인 프로필 조회.
 * - [patchMyProfile] PATCH  `/me/profile`        — 3-state 부분 수정.
 * - [uploadAvatar]   POST   `/me/profile/avatar` — 아바타 업로드.
 * - [getAvatar]      GET    `/{userId}/avatar`   — 아바타 다운로드(동료 것도 조회 가능).
 * - [deleteAvatar]   DELETE `/me/profile/avatar` — 아바타 삭제(멱등).
 *
 * ## 현재 사용자 식별 (JWT subject 전용, [WhoamiController] 미러)
 * `me` 스코프 4개 엔드포인트는 [AuthenticationPrincipal] 로 주입된 [Jwt] 의 subject(UUID)로 현재 사용자를
 * 식별한다([currentUserId]). principal 이 [Jwt] 가 아니거나(PAT 등) subject 가 UUID 형식이 아니면 401 로
 * 거부한다 — 이 컨트롤러는 PAT 로부터의 사용자 식별을 지원하지 않는다(PersonalAccessTokenService 미의존,
 * 이번 Task 범위 밖). [getAvatar] 는 특정 "본인" 개념이 없어 이 식별을 요구하지 않고, Spring Security
 * 필터 체인(`/api` 하위 전체 authenticated) + `@PreAuthorize("isAuthenticated()")` 이중 가드로 임의 인증 사용자의
 * 조회만 허용한다(동료 아바타 표시).
 *
 * ## 3-state PATCH (C4 — [ProfilePatchRequest] 참고)
 * [ProfilePatchRequest] 의 [JsonNode] 부재/명시-null/명시-값 3-state 를 [ProfilePatchField] 로 변환해
 * [UserProfileService.patchProfile] 에 위임한다(변환은 [toPatch]/[toRequiredField]/[toNullableField]).
 *
 * ## avatarUrl 파생
 * [UserProfileService] 는 `avatarObjectKey` 원본만 노출하고, 다운로드 경로 조립은 이 컨트롤러 책임이다
 * — `avatarObjectKey` 가 null 이 아니면 `/api/v1/users/{userId}/avatar`, null 이면 응답의 avatarUrl 도 null.
 *
 * ## 아바타 응답 보안 헤더
 * [getAvatar] 응답은 항상 `X-Content-Type-Options: nosniff` + `Content-Disposition: inline` 을 포함해
 * 브라우저 MIME 스니핑/강제 다운로드를 방지한다(issue-tracking `IssueAttachmentController` 의 nosniff
 * 선례와 동일 원칙). [InputStreamResource] 는 Spring MVC 가 응답 바디 복사 완료 후 자동 close 한다.
 *
 * ## 예외 → HTTP 매핑 (catch-all 변질 가드)
 * 모듈에 전역 `@RestControllerAdvice` 가 없으므로([PersonalAccessTokenController] 선례) 도메인 예외를
 * **이 컨트롤러 로컬 [ExceptionHandler]** 로만 상태 매핑한다. [ProfileUserNotFoundException] 의 원본
 * 메시지는 userId 를 포함하므로 노출하지 않고 고정 일반 메시지로 치환한다([handleProfileNotFound]).
 * [ProfileValidationException]/[AvatarValidationException] 의 메시지는 서비스가 사용자 노출용으로 미리
 * 작성한 안전한 값이므로 그대로 응답에 담는다([handleValidation]).
 */
@RestController
@RequestMapping("/api/v1/users")
// TooManyFunctions 억제 — 5개 엔드포인트 + 로컬 예외 핸들러 3개 + DTO 변환/식별자 추출 helper 들이 응집돼야
// 하는 단일 컨트롤러다([AccountLinkController] 선례와 동일 원칙 — 분리하면 SecurityBeans/errorResponse 중복).
@Suppress("TooManyFunctions")
class UserProfileController(
    private val userProfileService: UserProfileService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * GET `/api/v1/users/me/profile` — 본인 프로필을 조회한다.
     *
     * @param jwt 인증 JWT principal. PAT 등 미지원 인증이면 401.
     * @return 200 [ProfileResponse].
     * @throws ProfileUserNotFoundException 사용자가 DB 에 없을 때(→ 404, [handleProfileNotFound]).
     */
    @GetMapping("/me/profile")
    fun getMyProfile(
        @AuthenticationPrincipal jwt: Jwt?,
    ): ProfileResponse {
        val userId = currentUserId(jwt)
        return toResponse(userProfileService.getProfile(userId))
    }

    /**
     * PATCH `/api/v1/users/me/profile` — 본인 프로필을 3-state 로 부분 수정한다.
     *
     * @param jwt 인증 JWT principal. PAT 등 미지원 인증이면 401.
     * @param req 3-state PATCH 요청 바디([ProfilePatchRequest]).
     * @return 200 갱신 후 [ProfileResponse].
     * @throws ProfileValidationException displayName/timezone 검증 실패 시(→ 400, [handleValidation]).
     */
    @PatchMapping("/me/profile")
    fun patchMyProfile(
        @AuthenticationPrincipal jwt: Jwt?,
        @RequestBody req: ProfilePatchRequest,
    ): ProfileResponse {
        val userId = currentUserId(jwt)
        return toResponse(userProfileService.patchProfile(userId, toPatch(req)))
    }

    /**
     * POST `/api/v1/users/me/profile/avatar` — 아바타를 업로드한다(multipart/form-data, part 이름 `file`).
     *
     * `file` part 가 없으면 Spring 이 `MissingServletRequestPartException` 을 던져 기본적으로 400 으로
     * 응답한다(별도 핸들러 불필요).
     *
     * @param jwt 인증 JWT principal. PAT 등 미지원 인증이면 401.
     * @param file 업로드 이미지 파일.
     * @return 200 [AvatarUploadResponse].
     * @throws AvatarValidationException MIME/크기 정책 위반 시(→ 400, [handleValidation]).
     */
    @PostMapping("/me/profile/avatar")
    fun uploadAvatar(
        @AuthenticationPrincipal jwt: Jwt?,
        @RequestParam("file") file: MultipartFile,
    ): AvatarUploadResponse {
        val userId = currentUserId(jwt)
        val contentType = file.contentType ?: DEFAULT_CONTENT_TYPE
        userProfileService.uploadAvatar(userId, file.bytes, contentType)
        return AvatarUploadResponse(avatarUrl = avatarUrlFor(userId))
    }

    /**
     * GET `/api/v1/users/{userId}/avatar` — 아바타 바이너리를 다운로드한다(임의 인증 사용자가 동료 것도 조회 가능).
     *
     * @param userId 조회 대상 사용자 id.
     * @return 200 이미지 바이트 스트림 + Content-Type/nosniff/inline 헤더.
     * @throws AvatarObjectNotFoundException 아바타 미설정 시(→ 404, [handleAvatarNotFound]).
     */
    @GetMapping("/{userId}/avatar")
    @PreAuthorize("isAuthenticated()")
    fun getAvatar(
        @PathVariable userId: UUID,
    ): ResponseEntity<InputStreamResource> {
        val avatar = userProfileService.getAvatar(userId)
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_TYPE, avatar.contentType)
            .header(HEADER_CONTENT_TYPE_OPTIONS, NOSNIFF)
            .header(HttpHeaders.CONTENT_DISPOSITION, CONTENT_DISPOSITION_INLINE)
            .body(InputStreamResource(avatar.content))
    }

    /**
     * DELETE `/api/v1/users/me/profile/avatar` — 본인 아바타를 삭제한다(멱등 — 없어도 204).
     *
     * @param jwt 인증 JWT principal. PAT 등 미지원 인증이면 401.
     * @return 204 No Content.
     */
    @DeleteMapping("/me/profile/avatar")
    fun deleteAvatar(
        @AuthenticationPrincipal jwt: Jwt?,
    ): ResponseEntity<Unit> {
        val userId = currentUserId(jwt)
        userProfileService.deleteAvatar(userId)
        return ResponseEntity.noContent().build()
    }

    // ── 로컬 예외 핸들러 (프로필/아바타 도메인 예외 → HTTP) ────────────────────────

    /** displayName/timezone/MIME/크기 검증 실패 → 400. 메시지는 서비스가 사용자 노출용으로 미리 작성한 값이다. */
    @ExceptionHandler(ProfileValidationException::class, AvatarValidationException::class)
    fun handleValidation(ex: RuntimeException): ResponseEntity<Map<String, String>> {
        log.info("프로필/아바타 검증 실패 exceptionType={}", ex.javaClass.simpleName)
        val code = if (ex is ProfileValidationException) ERROR_PROFILE_VALIDATION else ERROR_AVATAR_VALIDATION
        return errorResponse(HttpStatus.BAD_REQUEST, code, ex.message ?: DEFAULT_VALIDATION_MESSAGE)
    }

    /** 대상 사용자가 BTS 에 없음 → 404. 원본 메시지(userId 포함)는 노출하지 않고 고정 메시지로 치환. */
    @ExceptionHandler(ProfileUserNotFoundException::class)
    fun handleProfileNotFound(): ResponseEntity<Map<String, String>> =
        errorResponse(HttpStatus.NOT_FOUND, ERROR_PROFILE_NOT_FOUND, "사용자를 찾을 수 없습니다.")

    /** 아바타 미설정 → 404. */
    @ExceptionHandler(AvatarObjectNotFoundException::class)
    fun handleAvatarNotFound(): ResponseEntity<Map<String, String>> =
        errorResponse(HttpStatus.NOT_FOUND, ERROR_AVATAR_NOT_FOUND, "아바타를 찾을 수 없습니다.")

    // ── private helpers ───────────────────────────────────────────────────────

    /**
     * JWT subject(UUID) 로 현재 사용자를 식별한다([WhoamiController] 의 JWT 분기와 동일 원칙 — 리소스
     * 조회보다 먼저 인증 주체를 추출한다, auth-extraction-before-resource-lookup).
     *
     * @param jwt [AuthenticationPrincipal] 로 주입된 JWT. PAT 등 미지원 인증이면 null.
     * @return 현재 사용자 UUID.
     * @throws ResponseStatusException subject 가 없거나 UUID 형식이 아니면 401.
     */
    private fun currentUserId(jwt: Jwt?): UUID =
        jwt?.subject?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED)

    /** [ProfileView] → [ProfileResponse]. avatarObjectKey 존재 시에만 avatarUrl 을 파생한다. */
    private fun toResponse(view: ProfileView): ProfileResponse =
        ProfileResponse(
            userId = view.userId,
            username = view.username,
            email = view.email,
            displayName = view.displayName,
            avatarUrl = view.avatarObjectKey?.let { avatarUrlFor(view.userId) },
            timezone = view.timezone,
            department = view.department,
        )

    /** [ProfilePatchRequest] 의 [JsonNode] 3-state 를 [ProfilePatch] 로 변환한다. */
    private fun toPatch(req: ProfilePatchRequest): ProfilePatch =
        ProfilePatch(
            displayName = toRequiredField(req.displayName),
            timezone = toRequiredField(req.timezone),
            department = toNullableField(req.department),
        )

    /**
     * 부재/명시-값만 표현 가능한 필드(displayName/timezone) 변환.
     *
     * 명시 null 이 오면([JsonNode.isNull]) [JsonNode.asText] 가 빈 문자열을 반환하므로, 서비스의
     * blank/형식 검증이 자연스럽게 400 으로 거부한다(별도 분기 불필요).
     */
    private fun toRequiredField(node: JsonNode?): ProfilePatchField<String> =
        if (node == null) ProfilePatchField.Absent else ProfilePatchField.Present(node.asText())

    /** 부재/명시-null(삭제)/명시-값 모두 표현 가능한 필드(department) 변환. */
    private fun toNullableField(node: JsonNode?): ProfilePatchField<String?> =
        when {
            node == null -> ProfilePatchField.Absent
            node.isNull -> ProfilePatchField.Present(null)
            else -> ProfilePatchField.Present(node.asText())
        }

    /** 아바타 다운로드 경로를 조립한다. */
    private fun avatarUrlFor(userId: UUID): String = "/api/v1/users/$userId/avatar"

    /** `{"code":..., "message":...}` 본문을 가진 [status] 응답을 생성한다([UsersController] 에러 응답 형식 일관). */
    private fun errorResponse(
        status: HttpStatus,
        code: String,
        message: String,
    ): ResponseEntity<Map<String, String>> {
        return ResponseEntity.status(status).body(mapOf("code" to code, "message" to message))
    }

    private companion object {
        /** MIME 스니핑 차단 헤더명. */
        const val HEADER_CONTENT_TYPE_OPTIONS = "X-Content-Type-Options"

        /** [HEADER_CONTENT_TYPE_OPTIONS] 값 — 저장된 contentType 을 신뢰하도록 강제. */
        const val NOSNIFF = "nosniff"

        /** 아바타는 항상 인라인 렌더링(강제 다운로드 방지). */
        const val CONTENT_DISPOSITION_INLINE = "inline"

        /** 클라이언트가 Content-Type 을 보내지 않았을 때 폴백([AvatarTypePolicy] 가 최종 화이트리스트 판정). */
        const val DEFAULT_CONTENT_TYPE = "application/octet-stream"

        /** displayName/timezone 검증 실패 에러 코드. */
        const val ERROR_PROFILE_VALIDATION = "PROFILE_VALIDATION_FAILED"

        /** 아바타 MIME/크기 검증 실패 에러 코드. */
        const val ERROR_AVATAR_VALIDATION = "AVATAR_VALIDATION_FAILED"

        /** 프로필 대상 사용자 미존재 에러 코드. */
        const val ERROR_PROFILE_NOT_FOUND = "PROFILE_NOT_FOUND"

        /** 아바타 미설정 에러 코드. */
        const val ERROR_AVATAR_NOT_FOUND = "AVATAR_NOT_FOUND"

        /** 검증 예외 message 가 비어 있을 때(도달 불가— 두 예외 모두 항상 message 를 채운다) fallback. */
        const val DEFAULT_VALIDATION_MESSAGE = "잘못된 요청입니다."
    }
}
