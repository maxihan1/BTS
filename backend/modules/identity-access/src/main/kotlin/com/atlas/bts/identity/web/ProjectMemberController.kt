// 프로젝트 멤버 관리 엔드포인트 — projectIdOrKey 수용·displayName 동봉 (FR-PM-01 Task B3)

package com.atlas.bts.identity.web

import com.atlas.bts.identity.project.AlreadyMember
import com.atlas.bts.identity.project.BootstrapRequiresJwt
import com.atlas.bts.identity.project.LastAdminProtected
import com.atlas.bts.identity.project.MemberNotFound
import com.atlas.bts.identity.project.NotProjectAdmin
import com.atlas.bts.identity.project.ProjectDirectory
import com.atlas.bts.identity.project.ProjectMembershipException
import com.atlas.bts.identity.project.ProjectMembershipService
import com.atlas.bts.identity.project.ProjectNotFound
import com.atlas.bts.identity.project.ProjectRole
import com.atlas.bts.identity.project.UserNotFound
import com.atlas.bts.identity.web.dto.AddMemberRequest
import com.atlas.bts.identity.web.dto.ChangeRoleRequest
import com.atlas.bts.identity.web.dto.ProjectMemberResponse
import com.atlas.bts.identity.web.support.UNAUTHORIZED_RESPONSE
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
 * 프로젝트 멤버 관리 컨트롤러 (FR-PM-01 Task B3).
 *
 * ## 엔드포인트
 * - [addMember]: POST /api/v1/projects/{projectIdOrKey}/members → 201
 * - [listMembers]: GET /api/v1/projects/{projectIdOrKey}/members → 200
 * - [changeRole]: PATCH /api/v1/projects/{projectIdOrKey}/members/{userId} → 200
 * - [removeMember]: DELETE /api/v1/projects/{projectIdOrKey}/members/{userId} → 204
 *
 * ## projectIdOrKey 해석 ([resolveProjectId])
 * path variable을 `String`으로 받아 두 단계로 UUID를 확정한다.
 * 1. UUID 파싱 시도 → 성공하면 [ProjectDirectory.exists] 확인 → false면 null → 404.
 * 2. UUID 파싱 실패 → key로 간주 → [ProjectDirectory.resolveKeyToId] 호출.
 * 3. 결과 null → 404.
 * 정규식 사전거부 없음. 빈문자열·특수문자·초장문도 key 경로로 흘러가 DB에서 null 반환 후 404 수렴 (B-1).
 *
 * ## displayName / username 동봉 (B3 C-2)
 * GET 목록: [ProjectMembershipService.listMemberViewsByProject] — users LEFT JOIN 단일 쿼리.
 * POST / PATCH: service 호출 후 [ProjectMembershipService.findMemberView] 단건 조회.
 * DELETE: 응답 바디 없음(204) — view 조회 불필요.
 *
 * ## Actor 추출 ([resolveActor])
 * PAT 요청에서 `@AuthenticationPrincipal jwt: Jwt?`는 null이다.
 * - jwt != null → userId=UUID.fromString(jwt.subject), isPat=false
 * - jwt == null → SecurityContext.authentication.principal as String → UUID, isPat=true
 * - 둘 다 실패 → 401
 *
 * ## 에러 매핑 ([mapServiceException])
 * service 예외를 snake_case 에러코드 + HTTP 상태로 인라인 매핑한다.
 * RestControllerAdvice 없음 (PasswordController 선례와 동일).
 *
 * ## 보안
 * SecurityConfig.authorizeHttpRequests 에서 /api 하위 전체 인증 요구.
 * 이 컨트롤러의 경로(/api/v1/projects)는 기존 필터 체인이 인증을 강제한다.
 */
@RestController
@RequestMapping("/api/v1/projects/{projectIdOrKey}/members")
class ProjectMemberController(
    private val membershipService: ProjectMembershipService,
    private val projectDirectory: ProjectDirectory,
) {

    /**
     * POST /api/v1/projects/{projectIdOrKey}/members — 프로젝트에 멤버 추가.
     *
     * @return 201 [ProjectMemberResponse](displayName/username 포함) 또는 에러 응답
     */
    @Suppress("ReturnCount")
    @PostMapping
    fun addMember(
        @AuthenticationPrincipal jwt: Jwt?,
        @PathVariable projectIdOrKey: String,
        @RequestBody body: AddMemberRequest,
    ): ResponseEntity<*> {
        val actor = resolveActor(jwt) ?: return UNAUTHORIZED_RESPONSE

        val projectId = resolveProjectId(projectIdOrKey)
            ?: return errorResponse(HttpStatus.NOT_FOUND, "project_not_found")

        val role = parseRole(body.role) ?: return INVALID_ROLE_RESPONSE

        return try {
            membershipService.addMember(
                actorId = actor.userId,
                isPat = actor.isPat,
                projectId = projectId,
                targetUserId = body.userId,
                requestedRole = role,
            )
            val view = membershipService.findMemberView(projectId, body.userId)
                ?: return errorResponse(HttpStatus.INTERNAL_SERVER_ERROR, "member_view_missing")
            ResponseEntity.status(HttpStatus.CREATED).body(ProjectMemberResponse.from(view))
        } catch (ex: ProjectMembershipException) {
            mapServiceException(ex)
        }
    }

    /**
     * GET /api/v1/projects/{projectIdOrKey}/members — 프로젝트 멤버 목록 조회.
     *
     * users LEFT JOIN 단일 쿼리로 N+1 없이 displayName / username 동봉.
     *
     * @return 200 `{ "members": [...] }` 또는 에러 응답
     */
    @Suppress("ReturnCount")
    @GetMapping
    fun listMembers(
        @AuthenticationPrincipal jwt: Jwt?,
        @PathVariable projectIdOrKey: String,
    ): ResponseEntity<*> {
        val actor = resolveActor(jwt) ?: return UNAUTHORIZED_RESPONSE

        val projectId = resolveProjectId(projectIdOrKey)
            ?: return errorResponse(HttpStatus.NOT_FOUND, "project_not_found")

        return try {
            val views = membershipService.listMemberViewsByProject(projectId, actor.userId)
            ResponseEntity.ok(mapOf("members" to views.map { ProjectMemberResponse.from(it) }))
        } catch (ex: ProjectMembershipException) {
            mapServiceException(ex)
        }
    }

    /**
     * PATCH /api/v1/projects/{projectIdOrKey}/members/{userId} — 프로젝트 멤버 역할 변경.
     *
     * @return 200 [ProjectMemberResponse](displayName/username 포함) 또는 에러 응답
     */
    @Suppress("ReturnCount")
    @PatchMapping("/{userId}")
    fun changeRole(
        @AuthenticationPrincipal jwt: Jwt?,
        @PathVariable projectIdOrKey: String,
        @PathVariable userId: UUID,
        @RequestBody body: ChangeRoleRequest,
    ): ResponseEntity<*> {
        val actor = resolveActor(jwt) ?: return UNAUTHORIZED_RESPONSE

        val projectId = resolveProjectId(projectIdOrKey)
            ?: return errorResponse(HttpStatus.NOT_FOUND, "project_not_found")

        val role = parseRole(body.role) ?: return INVALID_ROLE_RESPONSE

        return try {
            membershipService.changeRole(
                actorId = actor.userId,
                projectId = projectId,
                targetUserId = userId,
                newRole = role,
            )
            val view = membershipService.findMemberView(projectId, userId)
                ?: return errorResponse(HttpStatus.INTERNAL_SERVER_ERROR, "member_view_missing")
            ResponseEntity.ok(ProjectMemberResponse.from(view))
        } catch (ex: ProjectMembershipException) {
            mapServiceException(ex)
        }
    }

    /**
     * DELETE /api/v1/projects/{projectIdOrKey}/members/{userId} — 프로젝트 멤버 제거.
     *
     * @return 204 No Content 또는 에러 응답
     */
    @Suppress("ReturnCount")
    @DeleteMapping("/{userId}")
    fun removeMember(
        @AuthenticationPrincipal jwt: Jwt?,
        @PathVariable projectIdOrKey: String,
        @PathVariable userId: UUID,
    ): ResponseEntity<*> {
        val actor = resolveActor(jwt) ?: return UNAUTHORIZED_RESPONSE

        val projectId = resolveProjectId(projectIdOrKey)
            ?: return errorResponse(HttpStatus.NOT_FOUND, "project_not_found")

        return try {
            membershipService.removeMember(
                actorId = actor.userId,
                projectId = projectId,
                targetUserId = userId,
            )
            ResponseEntity.noContent().build<Unit>()
        } catch (ex: ProjectMembershipException) {
            mapServiceException(ex)
        }
    }

    // ── 내부 헬퍼 ────────────────────────────────────────────────────────────

    /**
     * projectIdOrKey 문자열을 활성 프로젝트 UUID로 해석한다 (B3 B-1).
     *
     * ## 해석 순서
     * 1. UUID 파싱 시도 — [UUID.fromString] 성공 시 [ProjectDirectory.exists] 로 활성 여부 확인.
     *    `exists = false` → `null` 반환 → 호출 측에서 404 단일봉투로 응답.
     * 2. UUID 파싱 실패 → key로 간주 → [ProjectDirectory.resolveKeyToId] 호출.
     *    결과 `null` → `null` 반환 → 호출 측에서 404 단일봉투로 응답.
     *
     * ## 입력 봉투 보장 (B-1)
     * 정규식 사전거부는 하지 않는다. 검증은 DB 조회 결과(`exists`/`resolveKeyToId`)로만 수행한다.
     * 빈문자열·특수문자·초장문·소문자 등 UUID 파싱이 실패하는 모든 입력은 key 경로로 흘러가
     * DB에서 `null`을 반환하며, 호출 측이 `404 {error:"project_not_found"}` 단일봉투로 응답한다.
     *
     * @param raw path variable 원본 문자열 — 신뢰할 수 없는 외부 입력
     * @return 활성 프로젝트 UUID, 미존재·soft-deleted·키 미존재이면 `null`
     */
    private fun resolveProjectId(raw: String): UUID? {
        val asUuid = runCatching { UUID.fromString(raw) }.getOrNull()
        if (asUuid != null) {
            return if (projectDirectory.exists(asUuid)) asUuid else null
        }
        return projectDirectory.resolveKeyToId(raw)
    }

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
        ex: ProjectMembershipException,
    ): ResponseEntity<Map<String, String>> =
        when (ex) {
            is ProjectNotFound ->
                errorResponse(HttpStatus.NOT_FOUND, "project_not_found")

            is UserNotFound ->
                errorResponse(HttpStatus.NOT_FOUND, "user_not_found")

            is MemberNotFound ->
                errorResponse(HttpStatus.NOT_FOUND, "member_not_found")

            is AlreadyMember ->
                errorResponse(HttpStatus.CONFLICT, "membership_already_exists")

            is NotProjectAdmin ->
                errorResponse(HttpStatus.FORBIDDEN, "not_project_admin")

            is LastAdminProtected ->
                errorResponse(HttpStatus.CONFLICT, "last_admin_protected")

            is BootstrapRequiresJwt ->
                errorResponse(HttpStatus.FORBIDDEN, "bootstrap_requires_jwt")
        }

    private companion object {
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
