// 프로젝트 이슈 보안 스킴 적용/해제/조회 엔드포인트 — actor 선추출 + PROJECT_ADMIN 가드(서비스 위임) (FR-PM-06 PR-A Task 8)

package com.atlas.bts.identity.web

import com.atlas.bts.identity.issuesecurity.IssueSecurityLevel
import com.atlas.bts.identity.issuesecurity.ProjectNotFoundException
import com.atlas.bts.identity.issuesecurity.ProjectSchemeAccessDeniedException
import com.atlas.bts.identity.issuesecurity.ProjectSecuritySchemeService
import com.atlas.bts.identity.issuesecurity.SchemeNotFoundException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * 프로젝트 ↔ 이슈 보안 스킴 적용 컨트롤러 (FR-PM-06 PR-A Task 8).
 *
 * ## 엔드포인트 (모두 `/api/v1/projects/{key}/issue-security-scheme`)
 * - [assign] PUT → 204 (스킴 적용, 교체=덮어쓰기)
 * - [unassign] DELETE → 204 (스킴 해제, 멱등)
 * - [findScheme] GET → 200 (현재 적용 스킴 식별자, 미적용이면 schemeId=null)
 * - [listLevels] GET `/levels` → 200 (드롭다운 옵션용 등급 목록, 미적용이면 빈 배열)
 *
 * ## 인증/권한 (이중 가드 + actor 선추출)
 * 1. 클래스 레벨 [PreAuthorize]("isAuthenticated()") — 미인증 요청을 필터 체인에서 차단.
 * 2. **actor 추출을 메서드 맨 앞**(서비스 위임 = 프로젝트 조회보다 먼저)에서 수행한다. 추출 실패
 *    (미인증/비-UUID subject)면 즉시 401 로 끊어, 미인증자가 404(없는 프로젝트) vs 401 로 리소스
 *    존재 여부를 probe 하지 못하게 한다(auth-extraction-before-resource-lookup 교훈).
 * 3. **PROJECT_ADMIN 판정은 서비스([ProjectSecuritySchemeService]) 책임**이다(행정 작업
 *    assign/unassign/findScheme). 컨트롤러는 actor 를 전달만 하고, 거부는 서비스의
 *    [ProjectSchemeAccessDeniedException] → 403 으로 매핑한다. `@PreAuthorize hasRole` 을 쓰지 않는
 *    이유 — 프로젝트별 역할은 멤버십 DB 진실원천에서 판정해야 하고, JWT claim/PAT 경로에 프로젝트
 *    역할 정보가 없기 때문이다.
 *    **예외**: [listLevels](드롭다운 옵션 조회)는 인증만 요구한다(PROJECT_ADMIN 불요). 실제 등급
 *    지정이 `SET_ISSUE_SECURITY` 로 가드되므로, 편집 권한자가 옵션을 보는 것은 Jira 와 동일하게
 *    허용한다. 멤버 데이터는 노출하지 않는다.
 *
 * ## Actor 추출 ([resolveActorId], UserGroupController/IssueSecuritySchemeController 동형)
 * - jwt != null → JWT subject 를 UUID 로 파싱.
 * - jwt == null → SecurityContext principal(String, PatAuthenticationFilter 설정)을 UUID 로 파싱.
 * - 둘 다 실패 → null → 401.
 *
 * ## 에러 매핑 ([mapServiceException])
 * 서비스 예외를 snake_case `error` 코드 + HTTP 상태로 인라인 매핑한다(RestControllerAdvice 없음,
 * IssueSecuritySchemeController 선례). 응답 바디는 error 키 단일 맵이다.
 * - [ProjectNotFoundException] → 404 `project_not_found`
 * - [SchemeNotFoundException] → 404 `scheme_not_found`
 * - [ProjectSchemeAccessDeniedException] → 403 `forbidden`
 *
 * ## 보안 (SecurityConfig 무변경)
 * /api 하위 전체에 대한 기존 authenticated 규칙이 라우트를 커버하므로 신규 라우트 등록은 하지 않는다.
 */
@RestController
@RequestMapping("/api/v1/projects/{key}/issue-security-scheme")
@PreAuthorize("isAuthenticated()")
class ProjectSecuritySchemeController(
    private val service: ProjectSecuritySchemeService,
) {
    /**
     * PUT — 프로젝트에 보안 스킴을 적용한다(이미 적용된 스킴이 있으면 교체).
     *
     * @return 204 No Content 또는 401/403/404 에러 응답.
     */
    @PutMapping
    fun assign(
        @AuthenticationPrincipal jwt: Jwt?,
        @PathVariable key: String,
        @RequestBody body: AssignSchemeRequest,
    ): ResponseEntity<*> {
        val actorId = resolveActorId(jwt) ?: return UNAUTHORIZED_RESPONSE
        return runHandler {
            service.assign(key, body.schemeId, actorId)
            ResponseEntity.noContent().build<Void>()
        }
    }

    /**
     * DELETE — 프로젝트의 보안 스킴 적용을 해제한다(미적용이어도 멱등).
     *
     * @return 204 No Content 또는 401/403/404 에러 응답.
     */
    @DeleteMapping
    fun unassign(
        @AuthenticationPrincipal jwt: Jwt?,
        @PathVariable key: String,
    ): ResponseEntity<*> {
        val actorId = resolveActorId(jwt) ?: return UNAUTHORIZED_RESPONSE
        return runHandler {
            service.unassign(key, actorId)
            ResponseEntity.noContent().build<Void>()
        }
    }

    /**
     * GET — 프로젝트에 적용된 보안 스킴 식별자를 조회한다.
     *
     * @return 200 [ProjectSchemeResponse](미적용이면 schemeId=null) 또는 401/403/404 에러 응답.
     */
    @GetMapping
    fun findScheme(
        @AuthenticationPrincipal jwt: Jwt?,
        @PathVariable key: String,
    ): ResponseEntity<*> {
        val actorId = resolveActorId(jwt) ?: return UNAUTHORIZED_RESPONSE
        return runHandler {
            ResponseEntity.ok(ProjectSchemeResponse(schemeId = service.findByProject(key, actorId)))
        }
    }

    /**
     * GET `/levels` — 프로젝트에 적용된 스킴의 보안 등급 목록을 조회한다 (FR-PM-06 PR-B BE-2).
     *
     * 이슈 편집/생성 화면의 보안 등급 드롭다운 옵션을 채운다. 인증만 요구하며(PROJECT_ADMIN 불요,
     * 실제 지정은 SET_ISSUE_SECURITY 로 가드), 미적용 프로젝트는 200 + 빈 배열로 응답한다(404 아님).
     * 등급 멤버 데이터는 노출하지 않고 스킴 구조(id/name/description/isDefault)만 반환한다.
     *
     * @return 200 [ProjectLevelsResponse](미적용이면 levels=[]) 또는 401/404 에러 응답.
     */
    @GetMapping("/levels")
    fun listLevels(
        @AuthenticationPrincipal jwt: Jwt?,
        @PathVariable key: String,
    ): ResponseEntity<*> {
        val actorId = resolveActorId(jwt) ?: return UNAUTHORIZED_RESPONSE
        return runHandler {
            val levels = service.listLevelsByProject(key, actorId).map { ProjectLevelResponse.from(it) }
            ResponseEntity.ok(ProjectLevelsResponse(levels = levels))
        }
    }

    // ── 내부 헬퍼 (IssueSecuritySchemeController 동형) ───────────────────────────

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
     * 핸들러 본문을 실행하고 서비스 예외만 HTTP 응답으로 매핑한다.
     *
     * 잡는 예외를 세 종류로 한정한다(광범위 catch 금지, silently swallow 금지).
     * - [ProjectNotFoundException] → 404 `project_not_found`
     * - [SchemeNotFoundException] → 404 `scheme_not_found`
     * - [ProjectSchemeAccessDeniedException] → 403 `forbidden`
     * 그 외 예외는 잡지 않고 전파시켜 전역 처리에 위임한다.
     */
    private inline fun runHandler(block: () -> ResponseEntity<*>): ResponseEntity<*> =
        try {
            block()
        } catch (ignoredProjectMissing: ProjectNotFoundException) {
            errorResponse(HttpStatus.NOT_FOUND, "project_not_found")
        } catch (ignoredSchemeMissing: SchemeNotFoundException) {
            errorResponse(HttpStatus.NOT_FOUND, "scheme_not_found")
        } catch (ignoredAccessDenied: ProjectSchemeAccessDeniedException) {
            errorResponse(HttpStatus.FORBIDDEN, "forbidden")
        }

    private companion object {
        /** actor 추출 실패(JWT/PAT 파싱 오류) 공용 401 응답. */
        val UNAUTHORIZED_RESPONSE: ResponseEntity<Map<String, String>> =
            ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(mapOf("error" to "unauthorized"))

        /** 주어진 상태/코드로 error 키 단일 맵 응답을 만든다. */
        fun errorResponse(
            status: HttpStatus,
            code: String,
        ): ResponseEntity<Map<String, String>> = ResponseEntity.status(status).body(mapOf("error" to code))
    }
}

/**
 * 프로젝트 스킴 적용 요청 바디 (FR-PM-06 PR-A Task 8).
 *
 * @param schemeId 적용할 이슈 보안 스킴 식별자.
 */
data class AssignSchemeRequest(
    val schemeId: UUID,
)

/**
 * 프로젝트 적용 스킴 조회 응답 DTO (FR-PM-06 PR-A Task 8).
 *
 * @param schemeId 적용된 스킴 식별자. 미적용이면 null.
 */
data class ProjectSchemeResponse(
    val schemeId: UUID?,
)

/**
 * 프로젝트 적용 스킴의 보안 등급 목록 응답 DTO (FR-PM-06 PR-B BE-2).
 *
 * 이슈 편집/생성 드롭다운 옵션 전용 — 미적용 프로젝트는 빈 목록이다.
 *
 * @param levels 적용 스킴의 등급 목록(없으면 빈 목록).
 */
data class ProjectLevelsResponse(
    val levels: List<ProjectLevelResponse>,
)

/**
 * 드롭다운 옵션용 보안 등급 응답 DTO (FR-PM-06 PR-B BE-2).
 *
 * 멤버 데이터(누가 볼 수 있나)는 노출하지 않고 스킴 구조만 담는다.
 *
 * @param id 등급 식별자.
 * @param name 등급 이름.
 * @param description 등급 설명(nullable).
 * @param isDefault 스킴 기본 등급 여부.
 */
data class ProjectLevelResponse(
    val id: UUID,
    val name: String,
    val description: String?,
    val isDefault: Boolean,
) {
    companion object {
        /** 영속 [IssueSecurityLevel] 로부터 응답을 만든다(멤버/schemeId 제외). */
        fun from(level: IssueSecurityLevel): ProjectLevelResponse =
            ProjectLevelResponse(
                id = requireNotNull(level.id) { "영속 등급은 id 를 가져야 한다." },
                name = level.name,
                description = level.description,
                isDefault = level.isDefault,
            )
    }
}
