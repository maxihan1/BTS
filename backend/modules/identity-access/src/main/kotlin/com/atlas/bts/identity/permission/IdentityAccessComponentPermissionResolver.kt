// 컴포넌트 권한 prod 판정기 — 멤버 게이트 + role_permissions 매트릭스. FR-PM-03 stub 교체.

package com.atlas.bts.identity.permission

import com.atlas.bts.identity.project.ProjectMembershipRepository
import com.bts.shared.permission.ComponentPermission
import com.bts.shared.permission.ComponentPermissionResolver
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * [ComponentPermissionResolver] prod 구현체 (FR-PM-03).
 *
 * ## 판정 알고리즘
 * 1. 멤버 게이트 — [ProjectMembershipRepository.findByProjectAndUser] 결과가 null(비멤버)이면 false.
 * 2. 매트릭스 판정 — [PermissionSchemeRepository.roleHasPermission]로 역할이 MANAGE_COMPONENTS를 보유하는지 확인.
 *
 * 비멤버 완전 차단(deny-by-default 하한)은 FR-PM-02 [IdentityAccessIssuePermissionResolver]와 동일한 안전 포스처다.
 *
 * ## [IdentityAccessIssuePermissionResolver]와의 차이 — scope 해석 없음
 * 동형이나 포트 [ComponentPermissionResolver.hasPermission]가 projectId를 직접 받으므로
 * IssueScope → projectId 해석 단계(ProjectDirectory 주입)가 없다. 생성자는 멤버십·스킴 리포 둘뿐.
 *
 * ## @Profile 배타성
 * `@Profile("prod")` — `AlwaysAllowComponentPermissionResolver`는 `@Profile("!prod")`이므로
 * 두 Bean이 동시에 활성화되지 않는다. prod 외 환경에서는 stub이 자동 선택된다.
 *
 * @see ComponentPermissionResolver
 * @see PermissionSchemeRepository
 */
@Component
@Profile("prod")
class IdentityAccessComponentPermissionResolver(
    private val membershipRepo: ProjectMembershipRepository,
    private val schemeRepo: PermissionSchemeRepository,
) : ComponentPermissionResolver {
    override fun hasPermission(
        actorId: UUID,
        permission: ComponentPermission,
        projectId: UUID,
    ): Boolean {
        val membership =
            membershipRepo.findByProjectAndUser(projectId, actorId)
                ?: return false // 비멤버 → 거부
        return schemeRepo.roleHasPermission(projectId, membership.role.name, permission.toPermissionCode())
    }
}

/**
 * [ComponentPermission] → permission_code 매핑 (SDD 12.3, Jira식 단일 관리 권한).
 *
 * | ComponentPermission       | permission_code     |
 * |---------------------------|---------------------|
 * | CREATE / UPDATE / DELETE  | MANAGE_COMPONENTS    |
 *
 * 세 값이 같은 단일 관리 권한으로 묶이므로 non-null 반환. `when` else 없이 3종 전부 명시 —
 * enum 값 추가/리네임 시 컴파일 에러로 drift 차단.
 */
private fun ComponentPermission.toPermissionCode(): String =
    when (this) {
        ComponentPermission.CREATE,
        ComponentPermission.UPDATE,
        ComponentPermission.DELETE,
        -> "MANAGE_COMPONENTS"
    }
