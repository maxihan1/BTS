// 자동화 규칙 관리 권한 prod 판정기 — 키 해석 + 멤버 게이트 + role_permissions MANAGE_AUTOMATION 매트릭스. FR-AT-01 D5.

package com.atlas.bts.identity.permission

import com.atlas.bts.identity.project.ProjectDirectory
import com.atlas.bts.identity.project.ProjectMembershipRepository
import com.bts.shared.permission.AutomationPermissionResolver
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * [AutomationPermissionResolver] prod 구현체 (FR-AT-01 D5).
 *
 * deny-by-default(fail-closed) — 허용 조건을 명시적으로 만족할 때만 `true` 를 반환하고, 그 외
 * (미해석 키·비멤버·매트릭스 미보유)는 전부 `false`(거부)로 귀결된다. 모든 의존성은 non-null 주입이며,
 * nullable + `?: return true` 같은 fail-open 처리를 하지 않는다(cross-BC resolver fail-open 회귀 방지).
 *
 * ## 판정 알고리즘 (프로젝트 스코프 전용)
 * MANAGE_AUTOMATION 은 SDD 12.3 상 프로젝트 행정 권한(grants=[ProjectAdmin])이라 전역(Global)
 * 스코프가 없다. 따라서 [WorkflowSchemePermissionResolver] 의 Project 분기만 취한 형태다.
 * 1. 키 해석 — [ProjectDirectory.resolveKeyToId] 가 `null`(미존재/소프트삭제)이면 거부.
 * 2. 멤버 게이트 — [ProjectMembershipRepository.findByProjectAndUser] 가 `null`(비멤버)이면 거부.
 * 3. 매트릭스 판정 — [PermissionSchemeRepository.roleHasPermission] 으로 역할이
 *    [MANAGE_AUTOMATION_CODE] 를 보유하는지 확인. `role_permissions` 매트릭스 창구만 사용하며
 *    role 을 직접 조회하지 않는다(cross-BC 권한은 권한코드+resolver 창구).
 *
 * ## @Profile 배타성
 * `@Profile("prod")` — non-prod(dev/test/staging)에서는 소비 모듈(automation)이 자신의 컨텍스트에
 * fail-safe stub 을 등록한다(consumer-owns-stub). identity-access 는 이 포트를 소비하지 않으므로,
 * [WorkflowSchemePermissionResolver] 와 마찬가지로 identity-access 쪽 non-prod fallback 을 두지
 * 않는다(불필요한 dead bean 회피).
 *
 * @see AutomationPermissionResolver
 * @see PermissionSchemeRepository
 * @see IdentityAccessWorkflowSchemePermissionResolver
 */
@Component
@Profile("prod")
class IdentityAccessAutomationPermissionResolver(
    private val projectDirectory: ProjectDirectory,
    private val membershipRepo: ProjectMembershipRepository,
    private val permissionSchemeRepo: PermissionSchemeRepository,
) : AutomationPermissionResolver {
    /**
     * 미해석 키·비멤버·매트릭스 미보유는 모두 `false`(거부)로 수렴한다(fail-closed).
     *
     * Suppress ReturnCount — guard-clause early return 2개(미해석 키·비멤버 단계별 거부).
     * DEVELOPMENT.md §2.3 Early return 권장 정책에 부합 — 전역 임계 완화 대신 국소 Suppress
     * ([IdentityAccessWorkflowSchemePermissionResolver] 선례).
     *
     * @return 키 해석·멤버십·매트릭스를 모두 만족하면 `true`, 그 외 `false`.
     */
    @Suppress("ReturnCount")
    override fun hasManageAutomation(
        actorId: UUID,
        projectKey: String,
    ): Boolean {
        val projectId = projectDirectory.resolveKeyToId(projectKey) ?: return false // 미해석 키 → 거부
        val membership = membershipRepo.findByProjectAndUser(projectId, actorId) ?: return false // 비멤버 → 거부
        return permissionSchemeRepo.roleHasPermission(projectId, membership.role.name, MANAGE_AUTOMATION_CODE)
    }

    private companion object {
        /**
         * 자동화 규칙 관리 권한 코드 (SDD 12.3 정본). V035 시드의 `permission_code` 와 일치한다.
         */
        const val MANAGE_AUTOMATION_CODE = "MANAGE_AUTOMATION"
    }
}
