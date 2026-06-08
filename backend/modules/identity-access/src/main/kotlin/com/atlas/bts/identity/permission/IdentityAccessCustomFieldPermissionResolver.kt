// 커스텀 필드 권한 prod 판정기 — 멤버 게이트 + role_permissions 매트릭스. FR-IS-10 포트 결선.

package com.atlas.bts.identity.permission

import com.atlas.bts.identity.project.ProjectMembershipRepository
import com.bts.shared.permission.CustomFieldPermission
import com.bts.shared.permission.CustomFieldPermissionResolver
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * [CustomFieldPermissionResolver] prod 구현체 (FR-IS-10).
 *
 * ## 판정 알고리즘
 * 1. 멤버 게이트 — [ProjectMembershipRepository.findByProjectAndUser] 결과가 null(비멤버)이면 false.
 * 2. 매트릭스 판정 — [PermissionSchemeRepository.roleHasPermission]로 역할이 MANAGE_CUSTOM_FIELDS를 보유하는지 확인.
 *
 * 비멤버 완전 차단(deny-by-default 하한)은 [IdentityAccessComponentPermissionResolver]와 동일한 안전 포스처다.
 *
 * ## [IdentityAccessComponentPermissionResolver]와 100% 동형 — 권한코드만 차이
 * 포트 [CustomFieldPermissionResolver.hasPermission]가 projectId를 직접 받으므로
 * IssueScope → projectId 해석 단계가 없다. 생성자는 멤버십·스킴 리포 둘뿐이며,
 * 권한코드는 [CustomFieldPermission.toPermissionCode] 결과(MANAGE_CUSTOM_FIELDS)를 사용한다.
 *
 * ## @Profile 배타성
 * `@Profile("prod")` — `AlwaysAllowCustomFieldPermissionResolver`는 `@Profile("!prod")`이므로
 * 두 Bean이 동시에 활성화되지 않는다. prod 외 환경에서는 stub이 자동 선택된다.
 *
 * @see CustomFieldPermissionResolver
 * @see IdentityAccessComponentPermissionResolver
 * @see PermissionSchemeRepository
 */
@Component
@Profile("prod")
class IdentityAccessCustomFieldPermissionResolver(
    private val membershipRepo: ProjectMembershipRepository,
    private val schemeRepo: PermissionSchemeRepository,
) : CustomFieldPermissionResolver {
    override fun hasPermission(
        actorId: UUID,
        permission: CustomFieldPermission,
        projectId: UUID,
    ): Boolean {
        val membership =
            membershipRepo.findByProjectAndUser(projectId, actorId)
                ?: return false // 비멤버 → 거부
        return schemeRepo.roleHasPermission(projectId, membership.role.name, permission.toPermissionCode())
    }
}
