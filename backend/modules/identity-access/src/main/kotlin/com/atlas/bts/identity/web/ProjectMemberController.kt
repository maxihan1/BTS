// 프로젝트 멤버 관리 엔드포인트 — 추가/목록/역할변경/제거 (FR-PM-01 Task 6)

package com.atlas.bts.identity.web

import com.atlas.bts.identity.project.ProjectMembershipService
import com.atlas.bts.identity.project.ProjectRole
import com.atlas.bts.identity.web.dto.AddMemberRequest
import com.atlas.bts.identity.web.dto.ChangeRoleRequest
import com.atlas.bts.identity.web.dto.ProjectMemberResponse
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * 프로젝트 멤버 관리 컨트롤러 (FR-PM-01 Task 6).
 *
 * ## 엔드포인트
 * - [addMember]: POST /api/v1/projects/{projectId}/members → 201
 * - [listMembers]: GET /api/v1/projects/{projectId}/members → 200
 * - [changeRole]: PATCH /api/v1/projects/{projectId}/members/{userId} → 200
 * - [removeMember]: DELETE /api/v1/projects/{projectId}/members/{userId} → 204
 *
 * ## Actor 추출 ([resolveActor])
 * PAT 요청에서 `@AuthenticationPrincipal jwt: Jwt?`는 null이다.
 * - jwt != null → userId=UUID.fromString(jwt.subject), isPat=false
 * - jwt == null → SecurityContext.authentication.principal as String → UUID, isPat=true
 *   ([PatAuthenticationFilter]가 principal에 userId.toString()을 설정한 선례)
 * - 둘 다 실패 → 401
 *
 * ## 에러 매핑 ([mapServiceException])
 * service 예외를 snake_case 에러코드 + HTTP 상태로 인라인 매핑한다.
 * RestControllerAdvice 없음 (PasswordController 선례와 동일).
 *
 * ## role 파싱
 * [ProjectRole.from]으로 파싱하며 IllegalArgumentException → 422 invalid_role.
 *
 * ## 보안
 * SecurityConfig.authorizeHttpRequests 에서 /api 하위 전체 인증 요구.
 * 이 컨트롤러의 경로(/api/v1/projects) 는 기존 필터 체인이 인증을 강제한다.
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/members")
class ProjectMemberController(
    private val membershipService: ProjectMembershipService,
) {

    /**
     * POST /api/v1/projects/{projectId}/members — 프로젝트에 멤버 추가.
     *
     * @return 201 [ProjectMemberResponse] 또는 에러 응답
     */
    // ReturnCount 억제 — HTTP 상태별 guard clause early return이 중첩 try-catch보다 가독성 우수.
    @Suppress("ReturnCount")
    @PostMapping
    fun addMember(
        @AuthenticationPrincipal jwt: Jwt?,
        @PathVariable projectId: UUID,
        @RequestBody body: AddMemberRequest,
    ): ResponseEntity<*> {
        val actor = resolveActor(jwt) ?: return UNAUTHORIZED_RESPONSE

        val role = parseRole(body.role) ?: return INVALID_ROLE_RESPONSE

        return try {
            val membership = membershipService.addMember(
                actorId = actor.userId,
                isPat = actor.isPat,
                projectId = projectId,
                targetUserId = body.userId,
                requestedRole = role,
            )
            ResponseEntity.status(HttpStatus.CREATED).body(ProjectMemberResponse.from(membership))
        } catch (ex: ProjectMembershipService.ProjectMembershipException) {
            mapServiceException(ex)
        }
    }

    /**
     * GET /api/v1/projects/{projectId}/members — 프로젝트 멤버 목록 조회.
     *
     * @return 200 `{ "members": [...] }` 또는 에러 응답
     */
    @Suppress("ReturnCount")
    @GetMapping
    fun listMembers(
        @AuthenticationPrincipal jwt: Jwt?,
        @PathVariable projectId: UUID,
    ): ResponseEntity<*> {
        val actor = resolveActor(jwt) ?: return UNAUTHORIZED_RESPONSE

        return try {
            val members = membershipService.listMembers(
                actorId = actor.userId,
                projectId = projectId,
            )
            ResponseEntity.ok(mapOf("members" to members.map { ProjectMemberResponse.from(it) }))
        } catch (ex: ProjectMembershipService.ProjectMembershipException) {
            mapServiceException(ex)
        }
    }

    /**
     * PATCH /api/v1/projects/{projectId}/members/{userId} — 프로젝트 멤버 역할 변경.
     *
     * @return 200 [ProjectMemberResponse] 또는 에러 응답
     */
    @Suppress("ReturnCount")
    @PatchMapping("/{userId}")
    fun changeRole(
        @AuthenticationPrincipal jwt: Jwt?,
        @PathVariable projectId: UUID,
        @PathVariable userId: UUID,
        @RequestBody body: ChangeRoleRequest,
    ): ResponseEntity<*> {
        val actor = resolveActor(jwt) ?: return UNAUTHORIZED_RESPONSE

        val role = parseRole(body.role) ?: return INVALID_ROLE_RESPONSE

        return try {
            val membership = membershipService.changeRole(
                actorId = actor.userId,
                projectId = projectId,
                targetUserId = userId,
                newRole = role,
            )
            ResponseEntity.ok(ProjectMemberResponse.from(membership))
        } catch (ex: ProjectMembershipService.ProjectMembershipException) {
            mapServiceException(ex)
        }
    }

    /**
     * DELETE /api/v1/projects/{projectId}/members/{userId} — 프로젝트 멤버 제거.
     *
     * @return 204 No Content 또는 에러 응답
     */
    @Suppress("ReturnCount")
    @DeleteMapping("/{userId}")
    fun removeMember(
        @AuthenticationPrincipal jwt: Jwt?,
        @PathVariable projectId: UUID,
        @PathVariable userId: UUID,
    ): ResponseEntity<*> {
        val actor = resolveActor(jwt) ?: return UNAUTHORIZED_RESPONSE

        return try {
            membershipService.removeMember(
                actorId = actor.userId,
                projectId = projectId,
                targetUserId = userId,
            )
            ResponseEntity.noContent().build<Void>()
        } catch (ex: ProjectMembershipService.ProjectMembershipException) {
            mapServiceException(ex)
        }
    }

    // ── 내부 헬퍼 ────────────────────────────────────────────────────────────

    /**
     * JWT 또는 PAT SecurityContext에서 actor를 추출한다.
     *
     * - jwt != null → JWT subject를 UUID로 파싱, isPat=false
     * - jwt == null → SecurityContextHolder principal(String)을 UUID로 파싱, isPat=true
     * - 파싱 실패 → null (호출 측에서 401 반환)
     */
    private fun resolveActor(jwt: Jwt?): ActorContext? {
        if (jwt != null) {
            val userId = runCatching { UUID.fromString(jwt.subject) }.getOrNull() ?: return null
            return ActorContext(userId = userId, isPat = false)
        }

        // PAT 경로: PatAuthenticationFilter가 principal에 userId.toString()을 설정한다.
        val rawPrincipal = SecurityContextHolder.getContext().authentication?.principal as? String
            ?: return null
        val userId = runCatching { UUID.fromString(rawPrincipal) }.getOrNull() ?: return null
        return ActorContext(userId = userId, isPat = true)
    }

    /**
     * 역할 문자열을 [ProjectRole]로 파싱한다.
     *
     * [ProjectRole.from] 실패(IllegalArgumentException) → null 반환.
     * 호출 측에서 422 invalid_role로 매핑한다.
     */
    private fun parseRole(raw: String): ProjectRole? =
        runCatching { ProjectRole.from(raw) }.getOrNull()

    /**
     * [ProjectMembershipService] 예외를 HTTP ResponseEntity로 매핑한다.
     *
     * 명세에 없는 예외는 500으로 재발생시킨다 (silently swallow 금지).
     */
    private fun mapServiceException(
        ex: ProjectMembershipService.ProjectMembershipException,
    ): ResponseEntity<Map<String, String>> =
        when (ex) {
            is ProjectMembershipService.ProjectNotFound ->
                errorResponse(HttpStatus.NOT_FOUND, "project_not_found")

            is ProjectMembershipService.UserNotFound ->
                errorResponse(HttpStatus.NOT_FOUND, "user_not_found")

            is ProjectMembershipService.MemberNotFound ->
                errorResponse(HttpStatus.NOT_FOUND, "member_not_found")

            is ProjectMembershipService.AlreadyMember ->
                errorResponse(HttpStatus.CONFLICT, "membership_already_exists")

            is ProjectMembershipService.NotProjectAdmin ->
                errorResponse(HttpStatus.FORBIDDEN, "not_project_admin")

            is ProjectMembershipService.LastAdminProtected ->
                errorResponse(HttpStatus.CONFLICT, "last_admin_protected")

            is ProjectMembershipService.BootstrapRequiresJwt ->
                errorResponse(HttpStatus.FORBIDDEN, "bootstrap_requires_jwt")
        }

    private companion object {
        /** actor 추출 실패(JWT/PAT 파싱 오류) 공용 401 응답. */
        val UNAUTHORIZED_RESPONSE: ResponseEntity<Map<String, String>> =
            ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(mapOf("error" to "unauthorized"))

        /** invalid_role 공용 422 응답. */
        val INVALID_ROLE_RESPONSE: ResponseEntity<Map<String, String>> =
            ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(mapOf("error" to "invalid_role"))

        fun errorResponse(status: HttpStatus, code: String): ResponseEntity<Map<String, String>> =
            ResponseEntity.status(status).body(mapOf("error" to code))
    }
}

/**
 * actor 추출 결과 (resolveActor 내부용).
 *
 * @param userId 인증된 사용자 UUID
 * @param isPat PAT 경유 여부
 */
private data class ActorContext(val userId: UUID, val isPat: Boolean)
