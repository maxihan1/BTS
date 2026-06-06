// 이슈 보안 스킴/등급/멤버 관리 엔드포인트 — SYSTEM_ADMIN 가드 + CRUD 9종 (FR-PM-06 PR-A Task 7)

package com.atlas.bts.identity.web

import com.atlas.bts.identity.issuesecurity.IssueSecurityException
import com.atlas.bts.identity.issuesecurity.IssueSecurityGroupNotFoundException
import com.atlas.bts.identity.issuesecurity.IssueSecurityLevel
import com.atlas.bts.identity.issuesecurity.IssueSecurityScheme
import com.atlas.bts.identity.issuesecurity.IssueSecuritySchemeDetail
import com.atlas.bts.identity.issuesecurity.IssueSecuritySchemeService
import com.atlas.bts.identity.issuesecurity.IssueSecurityUserNotFoundException
import com.atlas.bts.identity.issuesecurity.LevelNameConflictException
import com.atlas.bts.identity.issuesecurity.LevelNotFoundException
import com.atlas.bts.identity.issuesecurity.MemberType
import com.atlas.bts.identity.issuesecurity.SchemeNameConflictException
import com.atlas.bts.identity.issuesecurity.SchemeNotFoundException
import com.atlas.bts.identity.issuesecurity.SecurityLevelMember
import com.bts.shared.permission.SystemPermissionResolver
import org.springframework.dao.DataIntegrityViolationException
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
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * 이슈 보안 스킴/등급/멤버 관리 컨트롤러 (FR-PM-06 PR-A Task 7).
 *
 * ## 엔드포인트 (모두 SYSTEM_ADMIN 전용)
 * - 스킴 `/api/v1/issue-security-schemes`
 *   - [createScheme] POST → 201, [listSchemes] GET → 200(+levels), [getScheme] GET `/{id}` → 200,
 *     [updateScheme] PATCH `/{id}` → 200, [deleteScheme] DELETE `/{id}` → 204
 * - 등급 `/api/v1/issue-security-schemes/{schemeId}/levels`·`/api/v1/issue-security-levels`
 *   - [addLevel] POST → 201, [updateLevel] PATCH `/{levelId}` → 200, [deleteLevel] DELETE `/{levelId}` → 204
 * - 멤버 `/api/v1/issue-security-levels/{levelId}/members`·`/api/v1/issue-security-level-members`
 *   - [addMember] POST → 201, [listMembers] GET → 200, [removeMember] DELETE `/{memberId}` → 204(멱등)
 *
 * ## 이중 가드 (DEVELOPMENT.md §1.1 #4, UserGroupController 동형)
 * 1. 클래스 레벨 [PreAuthorize]("isAuthenticated()") — 미인증 요청을 필터 체인에서 차단.
 * 2. 각 핸들러가 [requireSystemAdmin] 으로 DB 기반 [SystemPermissionResolver.isSystemAdmin] 을
 *    수동 평가한다. `@PreAuthorize hasRole` 을 쓰지 않는 이유 — JWT claim 이 stale 일 수 있고
 *    PAT 경로에는 role claim 이 없어, 두 인증 경로에서 일관된 전역 관리자 판정을 보장하기 위해
 *    DB 진실원천을 직접 조회한다 (FR-PM-09 동형).
 *
 * ## Actor 추출 ([resolveActorId], UserGroupController 동형)
 * - jwt != null → JWT subject 를 UUID 로 파싱.
 * - jwt == null → SecurityContext principal(String, PatAuthenticationFilter 설정)을 UUID 로 파싱.
 * - 둘 다 실패 → null → 401.
 *
 * ## 에러 매핑 ([mapServiceException])
 * 도메인 예외를 snake_case `error` 코드 + HTTP 상태로 인라인 매핑한다(RestControllerAdvice 없음,
 * UserGroupController 선례). 응답 바디는 error 키 단일 맵이다.
 *
 * ## 보안 (SecurityConfig 무변경)
 * /api 하위 전체에 대한 기존 authenticated 규칙이 라우트를 커버하므로 신규 라우트 등록은 하지 않는다.
 */
@RestController
@PreAuthorize("isAuthenticated()")
@Suppress("TooManyFunctions") // 9 엔드포인트(명세 요구) + 가드/매핑 헬퍼 4종
class IssueSecuritySchemeController(
    private val service: IssueSecuritySchemeService,
    private val systemPermissionResolver: SystemPermissionResolver,
) {
    // ── 스킴 ──────────────────────────────────────────────────────────────────

    /**
     * POST /api/v1/issue-security-schemes — 새 보안 스킴 생성.
     *
     * @return 201 [SchemeResponse](levels=[]) 또는 에러 응답.
     */
    @PostMapping("/api/v1/issue-security-schemes")
    fun createScheme(
        @AuthenticationPrincipal jwt: Jwt?,
        @RequestBody body: CreateSchemeRequest,
    ): ResponseEntity<*> {
        requireSystemAdmin(jwt)?.let { return it }
        return runHandler {
            val created = service.createScheme(body.name, body.description)
            ResponseEntity.status(HttpStatus.CREATED).body(SchemeResponse.from(created, emptyList()))
        }
    }

    /**
     * GET /api/v1/issue-security-schemes — 전체 스킴(등급 동봉) 목록.
     *
     * @return 200 `[SchemeResponse]` 또는 에러 응답.
     */
    @GetMapping("/api/v1/issue-security-schemes")
    fun listSchemes(
        @AuthenticationPrincipal jwt: Jwt?,
    ): ResponseEntity<*> {
        requireSystemAdmin(jwt)?.let { return it }
        return ResponseEntity.ok(service.listSchemes().map { SchemeResponse.from(it) })
    }

    /**
     * GET /api/v1/issue-security-schemes/{schemeId} — 단건 스킴(등급 동봉) 조회.
     *
     * @return 200 [SchemeResponse] 또는 404 에러 응답.
     */
    @GetMapping("/api/v1/issue-security-schemes/{schemeId}")
    fun getScheme(
        @AuthenticationPrincipal jwt: Jwt?,
        @PathVariable schemeId: UUID,
    ): ResponseEntity<*> {
        requireSystemAdmin(jwt)?.let { return it }
        return runHandler { ResponseEntity.ok(SchemeResponse.from(service.getScheme(schemeId))) }
    }

    /**
     * PATCH /api/v1/issue-security-schemes/{schemeId} — 스킴 이름/설명 갱신.
     *
     * @return 200 [SchemeResponse](levels=[]) 또는 에러 응답.
     */
    @PatchMapping("/api/v1/issue-security-schemes/{schemeId}")
    fun updateScheme(
        @AuthenticationPrincipal jwt: Jwt?,
        @PathVariable schemeId: UUID,
        @RequestBody body: UpdateSchemeRequest,
    ): ResponseEntity<*> {
        requireSystemAdmin(jwt)?.let { return it }
        return runHandler {
            val updated = service.updateScheme(schemeId, body.name, body.description)
            ResponseEntity.ok(SchemeResponse.from(updated, emptyList()))
        }
    }

    /**
     * DELETE /api/v1/issue-security-schemes/{schemeId} — 스킴 삭제(등급/멤버 CASCADE).
     *
     * 프로젝트에 적용 중인 스킴은 FK ON DELETE RESTRICT 로 409 `scheme_in_use` 가 된다.
     *
     * @return 204 No Content 또는 404/409 에러 응답.
     */
    @DeleteMapping("/api/v1/issue-security-schemes/{schemeId}")
    fun deleteScheme(
        @AuthenticationPrincipal jwt: Jwt?,
        @PathVariable schemeId: UUID,
    ): ResponseEntity<*> {
        requireSystemAdmin(jwt)?.let { return it }
        return runHandler {
            service.deleteScheme(schemeId)
            ResponseEntity.noContent().build<Void>()
        }
    }

    // ── 등급 ──────────────────────────────────────────────────────────────────

    /**
     * POST /api/v1/issue-security-schemes/{schemeId}/levels — 스킴에 등급 추가.
     *
     * @return 201 [LevelResponse] 또는 404(스킴)/409(이름)/400 에러 응답.
     */
    @PostMapping("/api/v1/issue-security-schemes/{schemeId}/levels")
    fun addLevel(
        @AuthenticationPrincipal jwt: Jwt?,
        @PathVariable schemeId: UUID,
        @RequestBody body: CreateLevelRequest,
    ): ResponseEntity<*> {
        requireSystemAdmin(jwt)?.let { return it }
        return runHandler {
            val created = service.addLevel(schemeId, body.name, body.description, body.isDefault)
            ResponseEntity.status(HttpStatus.CREATED).body(LevelResponse.from(created))
        }
    }

    /**
     * PATCH /api/v1/issue-security-levels/{levelId} — 등급 이름/설명/기본 여부 갱신.
     *
     * @return 200 [LevelResponse] 또는 404/409/400 에러 응답.
     */
    @PatchMapping("/api/v1/issue-security-levels/{levelId}")
    fun updateLevel(
        @AuthenticationPrincipal jwt: Jwt?,
        @PathVariable levelId: UUID,
        @RequestBody body: UpdateLevelRequest,
    ): ResponseEntity<*> {
        requireSystemAdmin(jwt)?.let { return it }
        return runHandler {
            val updated = service.updateLevel(levelId, body.name, body.description, body.isDefault)
            ResponseEntity.ok(LevelResponse.from(updated))
        }
    }

    /**
     * DELETE /api/v1/issue-security-levels/{levelId} — 등급 삭제(멤버 CASCADE).
     *
     * @return 204 No Content 또는 404 에러 응답.
     */
    @DeleteMapping("/api/v1/issue-security-levels/{levelId}")
    fun deleteLevel(
        @AuthenticationPrincipal jwt: Jwt?,
        @PathVariable levelId: UUID,
    ): ResponseEntity<*> {
        requireSystemAdmin(jwt)?.let { return it }
        return runHandler {
            service.deleteLevel(levelId)
            ResponseEntity.noContent().build<Void>()
        }
    }

    // ── 멤버 ──────────────────────────────────────────────────────────────────

    /**
     * POST /api/v1/issue-security-levels/{levelId}/members — 등급에 멤버 추가(멱등).
     *
     * @return 201 [MemberResponse] 또는 404(등급/사용자/그룹)/400(타입·값) 에러 응답.
     */
    @PostMapping("/api/v1/issue-security-levels/{levelId}/members")
    fun addMember(
        @AuthenticationPrincipal jwt: Jwt?,
        @PathVariable levelId: UUID,
        @RequestBody body: AddMemberRequest,
    ): ResponseEntity<*> {
        requireSystemAdmin(jwt)?.let { return it }
        return runHandler {
            val created = service.addMember(levelId, body.memberType, body.memberValue)
            ResponseEntity.status(HttpStatus.CREATED).body(MemberResponse.from(created))
        }
    }

    /**
     * GET /api/v1/issue-security-levels/{levelId}/members — 등급 멤버 목록.
     *
     * 없는 등급은 404(빈 등급 200 과 구분, plan Task 5 갭 보강)로 응답한다.
     *
     * @return 200 `[MemberResponse]` 또는 404 에러 응답.
     */
    @GetMapping("/api/v1/issue-security-levels/{levelId}/members")
    fun listMembers(
        @AuthenticationPrincipal jwt: Jwt?,
        @PathVariable levelId: UUID,
    ): ResponseEntity<*> {
        requireSystemAdmin(jwt)?.let { return it }
        return runHandler {
            ResponseEntity.ok(service.listMembers(levelId).map { MemberResponse.from(it) })
        }
    }

    /**
     * DELETE /api/v1/issue-security-level-members/{memberId} — 멤버 제거(멱등).
     *
     * @return 204 No Content (없는 멤버여도 멱등).
     */
    @DeleteMapping("/api/v1/issue-security-level-members/{memberId}")
    fun removeMember(
        @AuthenticationPrincipal jwt: Jwt?,
        @PathVariable memberId: UUID,
    ): ResponseEntity<*> {
        requireSystemAdmin(jwt)?.let { return it }
        return runHandler {
            service.removeMember(memberId)
            ResponseEntity.noContent().build<Void>()
        }
    }

    // ── 내부 헬퍼 (UserGroupController 동형) ────────────────────────────────────

    /**
     * SYSTEM_ADMIN 가드 — 통과 시 `null`, 차단 시 에러 [ResponseEntity] 를 반환한다.
     *
     * - actor 추출 실패(미인증/비-UUID subject) → 401 `unauthorized`.
     * - [SystemPermissionResolver.isSystemAdmin] = false → 403 `forbidden`.
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
     * 핸들러 본문을 실행하고 도메인/검증/무결성 예외만 HTTP 응답으로 매핑한다.
     *
     * 잡는 예외를 세 종류로 한정한다(광범위 catch 금지, silently swallow 금지).
     * - [IssueSecurityException]: 서비스 도메인 예외 sealed 계층([mapServiceException]).
     * - [IllegalArgumentException]: 도메인 팩토리(이름/멤버 값) 불변식 위반 → 400.
     * - [DataIntegrityViolationException]: 프로젝트 적용 중 스킴 삭제(FK RESTRICT) → 409 `scheme_in_use`.
     * 그 외 예외는 잡지 않고 전파시켜 전역 처리에 위임한다.
     */
    private inline fun runHandler(block: () -> ResponseEntity<*>): ResponseEntity<*> =
        try {
            block()
        } catch (ex: IssueSecurityException) {
            mapServiceException(ex)
        } catch (ignoredValidation: IllegalArgumentException) {
            errorResponse(HttpStatus.BAD_REQUEST, "validation_error")
        } catch (ignoredIntegrity: DataIntegrityViolationException) {
            errorResponse(HttpStatus.CONFLICT, "scheme_in_use")
        }

    /**
     * 도메인 예외([IssueSecurityException])를 snake_case `error` 코드 + HTTP 상태로 매핑한다.
     */
    private fun mapServiceException(ex: IssueSecurityException): ResponseEntity<Map<String, String>> =
        when (ex) {
            is SchemeNotFoundException -> errorResponse(HttpStatus.NOT_FOUND, "scheme_not_found")
            is SchemeNameConflictException -> errorResponse(HttpStatus.CONFLICT, "scheme_name_conflict")
            is LevelNotFoundException -> errorResponse(HttpStatus.NOT_FOUND, "level_not_found")
            is LevelNameConflictException -> errorResponse(HttpStatus.CONFLICT, "level_name_conflict")
            is IssueSecurityUserNotFoundException -> errorResponse(HttpStatus.NOT_FOUND, "user_not_found")
            is IssueSecurityGroupNotFoundException -> errorResponse(HttpStatus.NOT_FOUND, "group_not_found")
        }

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
 * 스킴 생성 요청 바디 (FR-PM-06 PR-A Task 7).
 *
 * @param name 스킴 이름. 정규화/검증은 [IssueSecurityScheme.create] 가 소유한다.
 * @param description 스킴 설명(nullable).
 */
data class CreateSchemeRequest(
    val name: String,
    val description: String? = null,
)

/**
 * 스킴 수정 요청 바디 (FR-PM-06 PR-A Task 7).
 *
 * @param name 새 스킴 이름.
 * @param description 새 스킴 설명(nullable).
 */
data class UpdateSchemeRequest(
    val name: String,
    val description: String? = null,
)

/**
 * 등급 생성 요청 바디 (FR-PM-06 PR-A Task 7).
 *
 * @param name 등급 이름. 정규화/검증은 [IssueSecurityLevel.create] 가 소유한다.
 * @param description 등급 설명(nullable).
 * @param isDefault 스킴 기본 등급 여부(기본 false).
 */
data class CreateLevelRequest(
    val name: String,
    val description: String? = null,
    val isDefault: Boolean = false,
)

/**
 * 등급 수정 요청 바디 (FR-PM-06 PR-A Task 7).
 *
 * @param name 새 등급 이름.
 * @param description 새 등급 설명(nullable).
 * @param isDefault 스킴 기본 등급 여부(기본 false).
 */
data class UpdateLevelRequest(
    val name: String,
    val description: String? = null,
    val isDefault: Boolean = false,
)

/**
 * 멤버 추가 요청 바디 (FR-PM-06 PR-A Task 7).
 *
 * @param memberType 멤버 타입(REPORTER/ASSIGNEE/USER/PROJECT_ROLE/GROUP).
 * @param memberValue 타입별 값(USER/GROUP=UUID, PROJECT_ROLE=역할, REPORTER/ASSIGNEE=null).
 *   타입별 규칙 검증은 [SecurityLevelMember.create] 가 소유한다.
 */
data class AddMemberRequest(
    val memberType: MemberType,
    val memberValue: String? = null,
)

/**
 * 스킴 응답 DTO — 소속 등급 목록 동봉 (FR-PM-06 PR-A Task 7).
 *
 * @param id 스킴 식별자.
 * @param name 스킴 이름.
 * @param description 스킴 설명(nullable).
 * @param levels 소속 등급 목록(없으면 빈 목록).
 */
data class SchemeResponse(
    val id: UUID,
    val name: String,
    val description: String?,
    val levels: List<LevelResponse>,
) {
    companion object {
        /** 스킴+등급 상세([IssueSecuritySchemeDetail])로부터 응답을 만든다. */
        fun from(detail: IssueSecuritySchemeDetail): SchemeResponse =
            from(detail.scheme, detail.levels.map { LevelResponse.from(it) })

        /** 영속 [IssueSecurityScheme] 과 등급 응답 목록으로부터 응답을 만든다. */
        fun from(
            scheme: IssueSecurityScheme,
            levels: List<LevelResponse>,
        ): SchemeResponse =
            SchemeResponse(
                id = requireNotNull(scheme.id) { "영속 스킴은 id 를 가져야 한다." },
                name = scheme.name,
                description = scheme.description,
                levels = levels,
            )
    }
}

/**
 * 등급 응답 DTO (FR-PM-06 PR-A Task 7).
 *
 * @param id 등급 식별자.
 * @param schemeId 소속 스킴 식별자.
 * @param name 등급 이름.
 * @param description 등급 설명(nullable).
 * @param isDefault 스킴 기본 등급 여부.
 */
data class LevelResponse(
    val id: UUID,
    val schemeId: UUID,
    val name: String,
    val description: String?,
    val isDefault: Boolean,
) {
    companion object {
        /** 영속 [IssueSecurityLevel] 로부터 응답을 만든다. */
        fun from(level: IssueSecurityLevel): LevelResponse =
            LevelResponse(
                id = requireNotNull(level.id) { "영속 등급은 id 를 가져야 한다." },
                schemeId = level.schemeId,
                name = level.name,
                description = level.description,
                isDefault = level.isDefault,
            )
    }
}

/**
 * 멤버 응답 DTO (FR-PM-06 PR-A Task 7).
 *
 * @param id 멤버 식별자.
 * @param levelId 소속 등급 식별자.
 * @param memberType 멤버 타입.
 * @param memberValue 타입별 값(nullable).
 */
data class MemberResponse(
    val id: UUID,
    val levelId: UUID,
    val memberType: MemberType,
    val memberValue: String?,
) {
    companion object {
        /** 영속 [SecurityLevelMember] 로부터 응답을 만든다. */
        fun from(member: SecurityLevelMember): MemberResponse =
            MemberResponse(
                id = requireNotNull(member.id) { "영속 멤버는 id 를 가져야 한다." },
                levelId = member.levelId,
                memberType = member.memberType,
                memberValue = member.memberValue,
            )
    }
}
