// 현재 인증 사용자가 특정 프로젝트에 대해 가진 권한 맵을 반환하는 엔드포인트

package com.atlas.bts.identity.web

import com.atlas.bts.identity.pat.PersonalAccessToken
import com.atlas.bts.identity.pat.PersonalAccessTokenService
import com.atlas.bts.identity.web.dto.ProjectPermissionsResponse
import com.bts.shared.permission.IssuePermission
import com.bts.shared.permission.IssuePermissionResolver
import com.bts.shared.permission.IssueScope
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
 * ## UI 권한 목록
 * [UI_PROJECT_PERMISSIONS] — 프론트엔드 이슈 생성 버튼 노출에 사용하는 권한.
 *
 * @see docs/decisions/2026-06-02-issue-permission-query-api.md
 * @see IssuePermissionResolver
 * @see MyIssuePermissionController
 */
@RestController
class MyProjectPermissionController(
    private val permissionResolver: IssuePermissionResolver,
    private val personalAccessTokenService: PersonalAccessTokenService,
) {
    companion object {
        /** UI 이슈 생성 버튼 노출에 사용하는 프로젝트 권한 목록 (FR-PM-02 CREATE 게이트). */
        val UI_PROJECT_PERMISSIONS: List<IssuePermission> = listOf(IssuePermission.CREATE)
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
        val permissions =
            UI_PROJECT_PERMISSIONS.associate { permission ->
                permission.name to permissionResolver.hasPermission(actorId, permission, scope)
            }
        return ProjectPermissionsResponse(projectKey = projectKey, permissions = permissions)
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
