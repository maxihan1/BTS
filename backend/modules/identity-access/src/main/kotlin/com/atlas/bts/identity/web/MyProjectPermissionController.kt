// 현재 인증 사용자가 특정 프로젝트에 대해 가진 권한 맵을 반환하는 엔드포인트

package com.atlas.bts.identity.web

import com.atlas.bts.identity.pat.PersonalAccessToken
import com.atlas.bts.identity.pat.PersonalAccessTokenService
import com.atlas.bts.identity.permission.PermissionSchemeRepository
import com.atlas.bts.identity.project.ProjectDirectory
import com.atlas.bts.identity.project.ProjectMembershipRepository
import com.atlas.bts.identity.web.dto.ProjectPermissionsResponse
import com.bts.shared.permission.ComponentPermission
import com.bts.shared.permission.ComponentPermissionResolver
import com.bts.shared.permission.CustomFieldPermission
import com.bts.shared.permission.CustomFieldPermissionResolver
import com.bts.shared.permission.IssuePermission
import com.bts.shared.permission.IssuePermissionResolver
import com.bts.shared.permission.IssueScope
import com.bts.shared.permission.TemplatePermission
import com.bts.shared.permission.TemplatePermissionResolver
import com.bts.shared.permission.VersionPermission
import com.bts.shared.permission.VersionPermissionResolver
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/**
 * 현재 인증 사용자가 특정 프로젝트에 대해 가진 권한 맵을 반환하는 엔드포인트 (FR-PM-02 CREATE 게이트 Task 1).
 *
 * ## 엔드포인트 책임
 * `GET /api/v1/users/me/project-permissions?projectKey={key}` 를 처리한다.
 * 인증된 사용자의 actorId를 추출하여 [IssuePermissionResolver]에 위임한다.
 * 보안 원칙: actorId는 인증 토큰에서만 추출하며, 요청 바디/파라미터로 actor를 받지 않는다.
 * 존재하지 않는 projectKey는 404가 아니라 200 + `false`로 응답한다(resolver가 미존재를 거부로 판정).
 *
 * ## 인증 방식 분기
 * - **JWT**: [Jwt] principal에서 subject(UUID)를 추출한다.
 * - **PAT**: `pat_` prefix Bearer 토큰을 [PersonalAccessTokenService.verify]로 검증한다.
 * - 미인증: 401 [ResponseStatusException]을 던진다.
 *
 * ## 상태 코드 경계
 * - 미인증 → 401, projectKey 공백/빈 문자열 → 400.
 * - 인증된 비멤버 → 200 + `false` (401 아님 — 인증은 성공, 권한만 부재).
 * - 미존재 projectKey → 200 + `false` (404 아님 — resolver가 미존재를 거부로 판정).
 *
 * ## UI 권한 목록
 * 응답 `permissions` 맵에 담기는 권한 키.
 * - [UI_PROJECT_PERMISSIONS] — 이슈 생성 버튼 노출용 `CREATE` (FR-PM-02).
 * - `MANAGE_COMPONENTS`/`MANAGE_VERSIONS` — 버전/컴포넌트 관리 버튼 게이팅용 (FR-PM-03 D6/D7).
 * - `MANAGE_CUSTOM_FIELDS` — 커스텀 필드 관리 버튼 게이팅용 (FR-IS-10 D6).
 * - `MANAGE_FIELD_PERMISSIONS` — 필드 권한 규칙 관리 버튼 게이팅용 (FR-PM-07 PR-B). 전용 리졸버 없이
 *   [ProjectMembershipRepository]+[PermissionSchemeRepository]로 직접 매트릭스 판정한다([hasManageFieldPermissions]).
 *   [ComponentPermissionResolver]/[VersionPermissionResolver]/[CustomFieldPermissionResolver]가 단일
 *   관리 코드(MANAGE_*)로 매핑하므로 임의 대표값
 *   ([ComponentPermission.CREATE]/[VersionPermission.CREATE]/[CustomFieldPermission.CREATE]) 1회 호출로
 *   판정한다. projectKey→projectId는 [ProjectDirectory.resolveKeyToId]로 해석하고, null(미존재/소프트삭제)이면
 *   MANAGE_* 모두 false(기존 "미존재 projectKey → 200 + false" 정책 일관).
 *
 * @see docs/decisions/2026-06-03-version-component-permission-query-and-gating.md
 * @see docs/decisions/2026-06-02-issue-permission-query-api.md
 * @see IssuePermissionResolver
 * @see MyIssuePermissionController
 */
@RestController
// 권한 게이팅 aggregator — 이슈/컴포넌트/버전/커스텀필드/필드권한 5종 판정 의존성을 한 응답에 모은다.
// FR-PM-07 PR-B 에서 필드권한 판정용 멤버십·스킴 리포 2종이 추가되어 7 임계값을 초과하나,
// 각 의존성은 서로 다른 권한 도메인이라 묶을 수 없다(AuthController 선례 동형).
@Suppress("LongParameterList")
class MyProjectPermissionController(
    private val permissionResolver: IssuePermissionResolver,
    private val componentPermissionResolver: ComponentPermissionResolver,
    private val versionPermissionResolver: VersionPermissionResolver,
    private val customFieldPermissionResolver: CustomFieldPermissionResolver,
    private val templatePermissionResolver: TemplatePermissionResolver,
    private val projectDirectory: ProjectDirectory,
    private val membershipRepository: ProjectMembershipRepository,
    private val permissionSchemeRepository: PermissionSchemeRepository,
    private val personalAccessTokenService: PersonalAccessTokenService,
) {
    companion object {
        /** UI 이슈 생성 버튼 노출에 사용하는 프로젝트 권한 목록 (FR-PM-02 CREATE 게이트). */
        val UI_PROJECT_PERMISSIONS: List<IssuePermission> = listOf(IssuePermission.CREATE)

        /** UI 컴포넌트 관리 버튼 게이팅 권한 키 (FR-PM-03 D6/D7). */
        const val MANAGE_COMPONENTS_KEY = "MANAGE_COMPONENTS"

        /** UI 버전 관리 버튼 게이팅 권한 키 (FR-PM-03 D6/D7). */
        const val MANAGE_VERSIONS_KEY = "MANAGE_VERSIONS"

        /** UI 커스텀 필드 관리 버튼 게이팅 권한 키 (FR-IS-10 D6). */
        const val MANAGE_CUSTOM_FIELDS_KEY = "MANAGE_CUSTOM_FIELDS"

        /** UI 이슈 템플릿 관리 버튼 게이팅 권한 키 (FR-TM-01 D6, V024 시드 PROJECT_ADMIN MANAGE_TEMPLATES). */
        const val MANAGE_TEMPLATES_KEY = "MANAGE_TEMPLATES"

        /** UI 필드 권한 규칙 관리 버튼 게이팅 권한 키 (FR-PM-07 PR-B, V018 시드 PROJECT_ADMIN 전용). */
        const val MANAGE_FIELD_PERMISSIONS_KEY = "MANAGE_FIELD_PERMISSIONS"
    }

    /**
     * `GET /api/v1/users/me/project-permissions` — 현재 인증 사용자의 프로젝트 권한 맵 반환.
     *
     * @param request HTTP 요청 (PAT Bearer 토큰 추출용)
     * @param jwt Spring Security 필터 체인이 주입한 JWT Principal (PAT 요청 시 null)
     * @param projectKey 권한 조회 대상 프로젝트 키. 예. "ATLAS".
     * @return 프로젝트 키 + 권한 이름 → 보유 여부 맵
     * @throws ResponseStatusException projectKey가 공백/빈 문자열이면 400
     */
    @GetMapping("/api/v1/users/me/project-permissions")
    fun getProjectPermissions(
        request: HttpServletRequest,
        @AuthenticationPrincipal jwt: Jwt?,
        @RequestParam projectKey: String,
    ): ProjectPermissionsResponse {
        if (projectKey.isBlank()) throw ResponseStatusException(HttpStatus.BAD_REQUEST)
        val actorId = resolveActorId(request, jwt)
        val scope = IssueScope.Project(projectKey)
        val issuePermissions =
            UI_PROJECT_PERMISSIONS.associate { permission ->
                permission.name to permissionResolver.hasPermission(actorId, permission, scope)
            }
        val managePermissions = resolveManagePermissions(actorId, projectKey)
        return ProjectPermissionsResponse(
            projectKey = projectKey,
            permissions = issuePermissions + managePermissions,
        )
    }

    /**
     * `MANAGE_COMPONENTS`/`MANAGE_VERSIONS`/`MANAGE_CUSTOM_FIELDS` 보유 여부를 판정한다.
     *
     * MANAGE_COMPONENTS/MANAGE_VERSIONS는 FR-PM-03 D6/D7, MANAGE_CUSTOM_FIELDS는 FR-IS-10 D6
     * (커스텀 필드 관리 버튼 게이팅)에서 사용한다.
     *
     * projectKey를 [ProjectDirectory.resolveKeyToId]로 projectId(UUID)로 해석한다.
     * - null(미존재/소프트삭제) → 세 키 모두 false (기존 미존재 정책 일관, 404 아님).
     * - 그 외 → Component/Version/CustomFieldPermissionResolver를 각 1회 호출한다. 세 리졸버가
     *   CREATE/UPDATE/DELETE를 단일 관리 코드(MANAGE_*)로 매핑하므로 임의 대표값 CREATE로 판정해도
     *   결과는 동일하다.
     *
     * @param actorId 인증 토큰에서 추출한 행위자 UUID
     * @param projectKey 권한 조회 대상 프로젝트 키
     * @return MANAGE_COMPONENTS/MANAGE_VERSIONS/MANAGE_CUSTOM_FIELDS → 보유 여부 맵
     */
    private fun resolveManagePermissions(
        actorId: UUID,
        projectKey: String,
    ): Map<String, Boolean> {
        val projectId =
            projectDirectory.resolveKeyToId(projectKey)
                ?: return mapOf(
                    MANAGE_COMPONENTS_KEY to false,
                    MANAGE_VERSIONS_KEY to false,
                    MANAGE_CUSTOM_FIELDS_KEY to false,
                    MANAGE_TEMPLATES_KEY to false,
                    MANAGE_FIELD_PERMISSIONS_KEY to false,
                )
        return mapOf(
            MANAGE_COMPONENTS_KEY to
                componentPermissionResolver.hasPermission(actorId, ComponentPermission.CREATE, projectId),
            MANAGE_VERSIONS_KEY to
                versionPermissionResolver.hasPermission(actorId, VersionPermission.CREATE, projectId),
            MANAGE_CUSTOM_FIELDS_KEY to
                customFieldPermissionResolver.hasPermission(actorId, CustomFieldPermission.CREATE, projectId),
            MANAGE_TEMPLATES_KEY to
                templatePermissionResolver.hasPermission(actorId, TemplatePermission.CREATE, projectId),
            MANAGE_FIELD_PERMISSIONS_KEY to
                hasManageFieldPermissions(actorId, projectId),
        )
    }

    /**
     * actor 가 [projectId] 에서 `MANAGE_FIELD_PERMISSIONS` 를 보유하는지 판정한다 (FR-PM-07 PR-B).
     *
     * [IdentityAccessCustomFieldPermissionResolver] 와 동형 알고리즘이다.
     * 1. 멤버 게이트 — 비멤버([ProjectMembershipRepository.findByProjectAndUser] = null)면 false.
     * 2. 매트릭스 판정 — [PermissionSchemeRepository.roleHasPermission] 으로 role 이
     *    `MANAGE_FIELD_PERMISSIONS`(V018 시드, 기본 스킴 PROJECT_ADMIN 전용)를 보유하는지 확인.
     *
     * 전용 cross-BC 리졸버([CustomFieldPermissionResolver] 동형) 대신 컨트롤러가 두 리포지토리를
     * 직접 조회하는 이유 — MANAGE_FIELD_PERMISSIONS 는 issue-tracking 등 타 BC 가 소비하지 않고
     * identity-access 내부 UI 게이팅 전용이므로 shared-kernel 포트로 노출할 필요가 없다.
     * [FieldPermissionApplicationService.requireManagePermission] 가 동일한 두 리포지토리로
     * 서버측 권한 게이트를 수행하므로 판정 기준이 일치한다.
     *
     * @param actorId 인증 토큰에서 추출한 행위자 UUID
     * @param projectId 권한 조회 대상 프로젝트 UUID
     * @return MANAGE_FIELD_PERMISSIONS 보유 여부
     */
    private fun hasManageFieldPermissions(
        actorId: UUID,
        projectId: UUID,
    ): Boolean {
        val membership =
            membershipRepository.findByProjectAndUser(projectId, actorId)
                ?: return false // 비멤버 → 거부
        return permissionSchemeRepository.roleHasPermission(
            projectId,
            membership.role.name,
            MANAGE_FIELD_PERMISSIONS_KEY,
        )
    }

    /**
     * 인증 토큰에서 actorId(UUID)를 추출한다.
     *
     * PAT → JWT → 미인증 순서로 판정한다.
     * MyIssuePermissionController와 동일한 분기 패턴을 따른다.
     *
     * @Suppress ThrowsCount: PAT 실패, JWT 파싱 실패, 미인증 3경로가 모두 독립적인 401 거부 이유이므로
     * 단일 throw로 합치면 분기 의도가 사라진다. 기존 컨트롤러 패턴과 일관성을 유지한다.
     *
     * @param request HTTP 요청 (Authorization 헤더 파싱용)
     * @param jwt Spring Security 필터 주입 JWT Principal (PAT 요청 시 null)
     * @return 인증된 사용자 UUID
     * @throws ResponseStatusException 미인증 시 401
     */
    @Suppress("ThrowsCount")
    private fun resolveActorId(
        request: HttpServletRequest,
        jwt: Jwt?,
    ): UUID {
        val rawToken = extractBearerToken(request)

        if (rawToken != null && rawToken.startsWith(PersonalAccessToken.TOKEN_PREFIX)) {
            val result = personalAccessTokenService.verify(rawToken)
            if (result.isFailure) throw ResponseStatusException(HttpStatus.UNAUTHORIZED)
            return result.getOrThrow().userId
        }

        if (jwt != null) {
            return runCatching { UUID.fromString(jwt.subject) }.getOrNull()
                ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED)
        }

        throw ResponseStatusException(HttpStatus.UNAUTHORIZED)
    }

    /**
     * `Authorization: Bearer <token>` 헤더에서 raw token을 추출한다.
     *
     * @Suppress ReturnCount: header null 조기 반환, prefix 불일치 조기 반환, 정상 추출 반환의
     * 3-return 구조가 기존 컨트롤러 패턴과 동일하며 early-return이 가독성을 높인다.
     *
     * MyIssuePermissionController와 동일한 헬퍼 패턴.
     */
    @Suppress("ReturnCount")
    private fun extractBearerToken(request: HttpServletRequest): String? {
        val header = request.getHeader("Authorization") ?: return null
        if (!header.startsWith("Bearer ")) return null
        return header.removePrefix("Bearer ")
    }
}
