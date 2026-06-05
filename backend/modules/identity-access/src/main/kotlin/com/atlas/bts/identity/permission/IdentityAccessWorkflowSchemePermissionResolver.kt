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
 * Guard 패턴 — 권한이 없으면 [WorkflowSchemeAccessDeniedException] 을 던진다(deny-by-default).
 * 모든 분기는 "허용 조건을 명시적으로 만족"할 때만 통과하고, 그 외(미해석 키·비멤버·매트릭스
 * 미보유·전역 비관리자)는 전부 거부로 귀결된다.
 *
 * ## 판정 알고리즘 (scope 기준)
 * - [WorkflowSchemeScope.Global] — 스킴 CRUD(시스템 전역 자원). [SystemPermissionResolver.isSystemAdmin]
 *   (FR-PM-08)가 `true` 일 때만 통과. `system_role_assignments` 직접 조회가 아니라 포트 경유다(BC 격리).
 * - [WorkflowSchemeScope.Project] — 프로젝트 스킴 배정. [hasProjectPermission] 으로
 *   (1) [ProjectDirectory.resolveKeyToId] 해석 → (2) 멤버 게이트 → (3) `role_permissions` 매트릭스
 *   순으로 평가하며, 셋 모두 만족해야 통과(FR-PM-03 [IdentityAccessComponentPermissionResolver] 동형).
 *
 * ## @Profile 배타성
 * `@Profile("prod")` — non-prod(dev/test/staging)는 project-workflow 의
 * `AlwaysAllowWorkflowSchemePermissionResolver`(`@Profile("!prod")`)가 통과시키므로 두 Bean 이
 * 동시에 활성화되지 않는다. prod 외 환경에서는 stub 이 자동 선택된다.
 *
 * @see WorkflowSchemePermissionResolver
 * @see SystemPermissionResolver
 * @see PermissionSchemeRepository
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

    /**
     * 프로젝트 범위 권한 판정 — 미해석 키/비멤버/매트릭스 미보유는 모두 `false`(거부).
     *
     * Suppress ReturnCount — guard-clause early return 3개(미해석 키·비멤버·매트릭스 단계별 거부).
     * DEVELOPMENT.md §2.3 Early return 권장 정책에 부합 — 전역 임계 완화 대신 국소 Suppress(FR-PM-02 선례).
     *
     * @return key 해석·멤버십·매트릭스를 모두 만족하면 `true`, 그 외 `false`.
     */
    @Suppress("ReturnCount")
    private fun hasProjectPermission(
        actorId: UUID,
        permission: WorkflowSchemePermission,
        key: String,
    ): Boolean {
        val projectId = projectDirectory.resolveKeyToId(key) ?: return false // 미해석 키 → 거부
        val membership = membershipRepo.findByProjectAndUser(projectId, actorId) ?: return false // 비멤버 → 거부
        return permissionSchemeRepo.roleHasPermission(projectId, membership.role.name, permission.toPermissionCode())
    }
}

/**
 * [WorkflowSchemePermission] → permission_code 매핑 (SDD 12.3, Jira 식 도메인당 단일 관리 권한).
 *
 * | WorkflowSchemePermission | permission_code  |
 * |--------------------------|------------------|
 * | MANAGE_SCHEME            | MANAGE_WORKFLOW   |
 * | ASSIGN_SCHEME           | MANAGE_WORKFLOW   |
 *
 * 두 권한이 같은 단일 워크플로우 관리 권한으로 묶이므로 non-null 반환. `when` else 없이 2종 전부
 * 명시 — enum 값 추가/리네임 시 컴파일 에러로 drift 를 차단한다.
 *
 * 주의: 이 매핑은 [WorkflowSchemeScope.Project] 분기에서만 소비된다. [WorkflowSchemeScope.Global]
 * (MANAGE_SCHEME)은 매트릭스를 거치지 않고 [SystemPermissionResolver.isSystemAdmin] 로 판정한다.
 */
private fun WorkflowSchemePermission.toPermissionCode(): String =
    when (this) {
        WorkflowSchemePermission.MANAGE_SCHEME,
        WorkflowSchemePermission.ASSIGN_SCHEME,
        -> "MANAGE_WORKFLOW"
    }
