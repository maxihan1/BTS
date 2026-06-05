// 전역 사용자 그룹 관리 엔드포인트 — SYSTEM_ADMIN 가드 + CRUD/멤버십 8종 (FR-PM-09 Task 5)

package com.atlas.bts.identity.web

import com.atlas.bts.identity.group.UserGroup
import com.atlas.bts.identity.group.UserGroupException
import com.atlas.bts.identity.group.UserGroupNameConflictException
import com.atlas.bts.identity.group.UserGroupNotFoundException
import com.atlas.bts.identity.group.UserGroupService
import com.atlas.bts.identity.group.UserGroupWithCount
import com.atlas.bts.identity.group.UserNotFoundException
import com.atlas.bts.identity.user.User
import com.atlas.bts.identity.user.UserRepository
import com.atlas.bts.identity.web.dto.UserSummaryResponse
import com.bts.shared.permission.SystemPermissionResolver
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

/**
 * 전역 사용자 그룹 관리 컨트롤러 (FR-PM-09 Task 5).
 *
 * ## 엔드포인트 (모두 /api/v1/groups 하위, SYSTEM_ADMIN 전용)
 * - [createGroup]: POST 그룹 생성 → 201
 * - [listGroups]: GET 그룹 목록 → 200 (memberCount 포함)
 * - [getGroup]: GET 단건 그룹 → 200
 * - [updateGroup]: PATCH 그룹 갱신 → 200
 * - [deleteGroup]: DELETE 그룹 삭제 → 204
 * - [listMembers]: GET 그룹 멤버 목록 → 200
 * - [addMember]: PUT 멤버 추가 → 204 (멱등)
 * - [removeMember]: DELETE 멤버 제거 → 204 (멱등)
 *
 * ## 이중 가드 (DEVELOPMENT.md §1.1 #4)
 * 1. 클래스 레벨 [PreAuthorize]("isAuthenticated()") — 미인증 요청을 필터 체인에서 차단.
 * 2. 각 핸들러가 [requireSystemAdmin] 으로 DB 기반 [SystemPermissionResolver.isSystemAdmin]
 *    을 수동 평가한다. `@PreAuthorize hasRole` 을 쓰지 않는 이유 — JWT claim 이 stale 일 수
 *    있고 PAT 경로에는 role claim 이 없어, 두 인증 경로에서 일관된 전역 관리자 판정을 보장하기
 *    위해 DB 진실원천을 직접 조회한다 (FR-PM-04/리뷰 C2 결정).
 *
 * ## Actor 추출 ([resolveActorId], ProjectMemberController 동형)
 * - jwt != null → JWT subject 를 UUID 로 파싱.
 * - jwt == null → SecurityContext principal(String, PatAuthenticationFilter 설정)을 UUID 로 파싱.
 * - 둘 다 실패 → null → 401.
 *
 * ## 에러 매핑 ([mapServiceException])
 * 도메인 예외를 snake_case `error` 코드 + HTTP 상태로 인라인 매핑한다(RestControllerAdvice 없음,
 * ProjectMemberController 선례). 응답 바디는 error 키 단일 맵이다.
 *
 * ## 보안 (SecurityConfig 무변경)
 * /api 하위 전체에 대한 기존 authenticated 규칙이 라우트를 커버하므로 신규 라우트 등록은 하지 않는다.
 */
@RestController
@RequestMapping("/api/v1/groups")
@PreAuthorize("isAuthenticated()")
@Suppress("TooManyFunctions") // 8 엔드포인트(명세 요구) + 가드/매핑 헬퍼 5종
class UserGroupController(
    private val userGroupService: UserGroupService,
    private val userRepository: UserRepository,
    private val systemPermissionResolver: SystemPermissionResolver,
) {
    /**
     * POST /api/v1/groups — 새 그룹 생성.
     *
     * @return 201 [GroupResponse] (memberCount=0) 또는 에러 응답
     */
    @PostMapping
    fun createGroup(
        @AuthenticationPrincipal jwt: Jwt?,
        @RequestBody body: CreateGroupRequest,
    ): ResponseEntity<*> {
        requireSystemAdmin(jwt)?.let { return it }

        return runHandler {
            val created = userGroupService.createGroup(body.name, body.description)
            ResponseEntity.status(HttpStatus.CREATED).body(GroupResponse.from(created, memberCount = 0))
        }
    }

    /**
     * GET /api/v1/groups — 전체 그룹 목록(멤버 수 포함) 조회.
     *
     * @return 200 `[GroupResponse]` 또는 에러 응답
     */
    @GetMapping
    fun listGroups(
        @AuthenticationPrincipal jwt: Jwt?,
    ): ResponseEntity<*> {
        requireSystemAdmin(jwt)?.let { return it }

        val groups = userGroupService.listGroups().map { GroupResponse.from(it) }
        return ResponseEntity.ok(groups)
    }

    /**
     * GET /api/v1/groups/{groupId} — 단건 그룹 조회.
     *
     * @return 200 [GroupResponse] 또는 404 에러 응답
     */
    @GetMapping("/{groupId}")
    fun getGroup(
        @AuthenticationPrincipal jwt: Jwt?,
        @PathVariable groupId: UUID,
    ): ResponseEntity<*> {
        requireSystemAdmin(jwt)?.let { return it }

        return runHandler {
            ResponseEntity.ok(GroupResponse.from(userGroupService.getGroup(groupId)))
        }
    }

    /**
     * PATCH /api/v1/groups/{groupId} — 그룹 이름/설명 갱신.
     *
     * @return 200 [GroupResponse] 또는 에러 응답
     */
    @PatchMapping("/{groupId}")
    fun updateGroup(
        @AuthenticationPrincipal jwt: Jwt?,
        @PathVariable groupId: UUID,
        @RequestBody body: UpdateGroupRequest,
    ): ResponseEntity<*> {
        requireSystemAdmin(jwt)?.let { return it }

        return runHandler {
            val updated = userGroupService.updateGroup(groupId, body.name, body.description)
            ResponseEntity.ok(GroupResponse.from(updated))
        }
    }

    /**
     * DELETE /api/v1/groups/{groupId} — 그룹 삭제(멤버십 CASCADE).
     *
     * @return 204 No Content 또는 404 에러 응답
     */
    @DeleteMapping("/{groupId}")
    fun deleteGroup(
        @AuthenticationPrincipal jwt: Jwt?,
        @PathVariable groupId: UUID,
    ): ResponseEntity<*> {
        requireSystemAdmin(jwt)?.let { return it }

        return runHandler {
            userGroupService.deleteGroup(groupId)
            ResponseEntity.noContent().build<Void>()
        }
    }

    /**
     * GET /api/v1/groups/{groupId}/members — 그룹 멤버 사용자 요약 목록.
     *
     * @return 200 `[UserSummaryResponse]` 또는 404 에러 응답
     */
    @GetMapping("/{groupId}/members")
    fun listMembers(
        @AuthenticationPrincipal jwt: Jwt?,
        @PathVariable groupId: UUID,
    ): ResponseEntity<*> {
        requireSystemAdmin(jwt)?.let { return it }

        return runHandler {
            val memberIds = userGroupService.listMembers(groupId)
            val summaries = userRepository.findByIds(memberIds).map { it.toSummary() }
            ResponseEntity.ok(summaries)
        }
    }

    /**
     * PUT /api/v1/groups/{groupId}/members/{userId} — 멤버 추가(멱등).
     *
     * @return 204 No Content 또는 404 에러 응답
     */
    @PutMapping("/{groupId}/members/{userId}")
    fun addMember(
        @AuthenticationPrincipal jwt: Jwt?,
        @PathVariable groupId: UUID,
        @PathVariable userId: UUID,
    ): ResponseEntity<*> {
        requireSystemAdmin(jwt)?.let { return it }

        return runHandler {
            userGroupService.addMember(groupId, userId)
            ResponseEntity.noContent().build<Void>()
        }
    }

    /**
     * DELETE /api/v1/groups/{groupId}/members/{userId} — 멤버 제거(멱등).
     *
     * @return 204 No Content 또는 404 에러 응답
     */
    @DeleteMapping("/{groupId}/members/{userId}")
    fun removeMember(
        @AuthenticationPrincipal jwt: Jwt?,
        @PathVariable groupId: UUID,
        @PathVariable userId: UUID,
    ): ResponseEntity<*> {
        requireSystemAdmin(jwt)?.let { return it }

        return runHandler {
            userGroupService.removeMember(groupId, userId)
            ResponseEntity.noContent().build<Void>()
        }
    }

    // ── 내부 헬퍼 ────────────────────────────────────────────────────────────

    /**
     * SYSTEM_ADMIN 가드 — 통과 시 `null`, 차단 시 에러 [ResponseEntity] 를 반환한다.
     *
     * - actor 추출 실패(미인증/비-UUID subject) → 401 `unauthorized`.
     * - [SystemPermissionResolver.isSystemAdmin] = false → 403 `forbidden`.
     *
     * @return 가드 통과면 `null`, 아니면 즉시 반환할 에러 응답.
     */
    @Suppress("ReturnCount")
    private fun requireSystemAdmin(jwt: Jwt?): ResponseEntity<Map<String, String>>? {
        val actorId = resolveActorId(jwt) ?: return UNAUTHORIZED_RESPONSE
        if (!systemPermissionResolver.isSystemAdmin(actorId)) return FORBIDDEN_RESPONSE
        return null
    }

    /**
     * JWT 또는 PAT SecurityContext 에서 actor UUID 를 추출한다.
     *
     * - jwt != null → JWT subject 를 UUID 로 파싱.
     * - jwt == null → SecurityContext principal(String)을 UUID 로 파싱(PAT 경로).
     * - 파싱 실패 → null (호출 측 401).
     */
    @Suppress("ReturnCount")
    private fun resolveActorId(jwt: Jwt?): UUID? {
        if (jwt != null) {
            return runCatching { UUID.fromString(jwt.subject) }.getOrNull()
        }
        val authentication = SecurityContextHolder.getContext().authentication
        val rawPrincipal = authentication?.principal as? String ?: return null
        return runCatching { UUID.fromString(rawPrincipal) }.getOrNull()
    }

    /**
     * 핸들러 본문을 실행하고 도메인/검증 예외만 HTTP 응답으로 매핑한다.
     *
     * 잡는 예외는 두 종류로 한정한다(광범위 catch 금지, silently swallow 금지).
     * - [UserGroupException]: 서비스 도메인 예외 sealed 계층([mapDomainException]).
     * - [IllegalArgumentException]: [UserGroup.create] 의 이름/설명 불변식 위반 → 400.
     * 그 외 예외는 잡지 않고 전파시켜 전역 처리에 위임한다.
     */
    private inline fun runHandler(block: () -> ResponseEntity<*>): ResponseEntity<*> =
        try {
            block()
        } catch (ex: UserGroupException) {
            mapDomainException(ex)
        } catch (ignoredValidation: IllegalArgumentException) {
            errorResponse(HttpStatus.BAD_REQUEST, "group_name_invalid")
        }

    /**
     * 도메인 예외([UserGroupException])를 snake_case `error` 코드 + HTTP 상태로 매핑한다.
     */
    private fun mapDomainException(ex: UserGroupException): ResponseEntity<Map<String, String>> =
        when (ex) {
            is UserGroupNameConflictException -> errorResponse(HttpStatus.CONFLICT, "group_name_conflict")
            is UserGroupNotFoundException -> errorResponse(HttpStatus.NOT_FOUND, "group_not_found")
            is UserNotFoundException -> errorResponse(HttpStatus.NOT_FOUND, "user_not_found")
        }

    private fun User.toSummary(): UserSummaryResponse =
        UserSummaryResponse(id = id, username = username, displayName = displayName, email = email)

    private companion object {
        /** actor 추출 실패(JWT/PAT 파싱 오류) 공용 401 응답. */
        val UNAUTHORIZED_RESPONSE: ResponseEntity<Map<String, String>> =
            ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(mapOf("error" to "unauthorized"))

        /** 전역 관리자가 아닌 행위자에 대한 공용 403 응답. */
        val FORBIDDEN_RESPONSE: ResponseEntity<Map<String, String>> =
            ResponseEntity.status(HttpStatus.FORBIDDEN).body(mapOf("error" to "forbidden"))

        /** 주어진 상태/코드로 error 키 단일 맵 응답을 만든다. */
        fun errorResponse(
            status: HttpStatus,
            code: String,
        ): ResponseEntity<Map<String, String>> = ResponseEntity.status(status).body(mapOf("error" to code))
    }
}

/**
 * 그룹 생성 요청 바디 (FR-PM-09 Task 5).
 *
 * @param name 그룹 이름. 정규화/검증은 [UserGroup.create] 가 소유한다.
 * @param description 그룹 설명(nullable).
 */
data class CreateGroupRequest(
    val name: String,
    val description: String? = null,
)

/**
 * 그룹 수정 요청 바디 (FR-PM-09 Task 5).
 *
 * @param name 새 그룹 이름.
 * @param description 새 그룹 설명(nullable).
 */
data class UpdateGroupRequest(
    val name: String,
    val description: String? = null,
)

/**
 * 그룹 응답 DTO (FR-PM-09 Task 5).
 *
 * @param id 그룹 식별자.
 * @param name 그룹 이름.
 * @param description 그룹 설명(nullable).
 * @param memberCount 멤버 수(0 이상). 단건/생성 응답은 별도 카운트가 없으면 0.
 * @param createdAt 생성 시각.
 * @param updatedAt 갱신 시각.
 */
data class GroupResponse(
    val id: UUID,
    val name: String,
    val description: String?,
    val memberCount: Int,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    companion object {
        /**
         * 멤버 수를 함께 담은 [UserGroupWithCount] 로부터 응답을 만든다.
         */
        fun from(view: UserGroupWithCount): GroupResponse = from(view.group, view.memberCount)

        /**
         * 영속 [UserGroup] 으로부터 응답을 만든다.
         *
         * @param group id/타임스탬프가 채워진 영속 그룹.
         * @param memberCount 멤버 수. 단건 조회/생성 경로처럼 카운트가 없으면 0.
         */
        fun from(
            group: UserGroup,
            memberCount: Int = 0,
        ): GroupResponse =
            GroupResponse(
                id = requireNotNull(group.id) { "영속 그룹은 id 를 가져야 한다." },
                name = group.name,
                description = group.description,
                memberCount = memberCount,
                createdAt = requireNotNull(group.createdAt) { "영속 그룹은 createdAt 을 가져야 한다." },
                updatedAt = requireNotNull(group.updatedAt) { "영속 그룹은 updatedAt 을 가져야 한다." },
            )
    }
}
