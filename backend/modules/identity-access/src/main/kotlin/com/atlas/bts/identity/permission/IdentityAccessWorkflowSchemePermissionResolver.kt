// 워크플로우 스킴 권한 prod 판정기 — Global=isSystemAdmin, Project=멤버 게이트+매트릭스. FR-PM-04 stub 교체.

package com.atlas.bts.identity.permission

import com.atlas.bts.identity.project.ProjectDirectory
import com.atlas.bts.identity.project.ProjectMembershipRepository
import com.bts.shared.permission.SystemPermissionResolver
import com.bts.shared.permission.WorkflowSchemeAccessDeniedException
import com.bts.shared.permission.WorkflowSchemePermission
import com.bts.shared.permission.WorkflowSchemePermissionResolver
import com.bts.shared.permission.WorkflowSchemeScope
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * [WorkflowSchemePermissionResolver] prod 구현체 (FR-PM-04).
 *
 * @Component @Profile("prod") — non-prod 는 project-workflow 의
 * AlwaysAllowWorkflowSchemePermissionResolver(@Profile "!prod") 가 통과시킨다.
 */
@Component
@Profile("prod")
class IdentityAccessWorkflowSchemePermissionResolver(
    private val systemPermissionResolver: SystemPermissionResolver,
    private val projectDirectory: ProjectDirectory,
    private val membershipRepo: ProjectMembershipRepository,
    private val permissionSchemeRepo: PermissionSchemeRepository,
) : WorkflowSchemePermissionResolver {
    override fun requirePermission(
        actorId: UUID,
        permission: WorkflowSchemePermission,
        scope: WorkflowSchemeScope,
    ) {
        val granted =
            when (scope) {
                is WorkflowSchemeScope.Global -> systemPermissionResolver.isSystemAdmin(actorId)
                is WorkflowSchemeScope.Project -> hasProjectPermission(actorId, permission, scope.key)
            }
        if (!granted) {
            throw WorkflowSchemeAccessDeniedException(actorId, permission, scope)
        }
    }

    private fun hasProjectPermission(
        actorId: UUID,
        permission: WorkflowSchemePermission,
        key: String,
    ): Boolean {
        val projectId = projectDirectory.resolveKeyToId(key) ?: return false
        val membership = membershipRepo.findByProjectAndUser(projectId, actorId) ?: return false
        val code = permission.toPermissionCode()
        return permissionSchemeRepo.roleHasPermission(projectId, membership.role.name, code)
    }
}

private fun WorkflowSchemePermission.toPermissionCode(): String =
    when (this) {
        WorkflowSchemePermission.MANAGE_SCHEME,
        WorkflowSchemePermission.ASSIGN_SCHEME,
        -> "MANAGE_WORKFLOW"
    }
