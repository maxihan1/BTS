// Slack 채널 매핑 관리 권한 prod 판정기 — 키 해석 + PROJECT_ADMIN 역할 직접 확인 (FR-SL-06 Task 8)

package com.atlas.bts.identity.permission

import com.atlas.bts.identity.project.ProjectDirectory
import com.atlas.bts.identity.project.ProjectMembershipRepository
import com.atlas.bts.identity.project.ProjectRole
import com.bts.shared.permission.SlackChannelMappingPermissionResolver
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * [SlackChannelMappingPermissionResolver] prod 구현체 (FR-SL-06 Task 8).
 *
 * deny-by-default(fail-closed) — 허용 조건을 명시적으로 만족할 때만 `true` 를 반환하고, 그 외
 * (미해석 키·비멤버·비관리자)는 전부 `false`(거부)로 귀결된다. 모든 의존성은 non-null 주입이며,
 * nullable + `?: return true` 같은 fail-open 처리를 하지 않는다(cross-BC resolver fail-open 회귀 방지).
 *
 * ## 판정 = PROJECT_ADMIN 역할 직접 확인 (신규 권한 코드 회피)
 * [IdentityAccessAutomationPermissionResolver] 는 `role_permissions` 매트릭스(MANAGE_AUTOMATION 코드)로
 * 판정하지만, 채널 매핑 관리에는 대응하는 권한 코드가 시드되어 있지 않다. 신규 권한 코드를 추가하면
 * `SchemaMigrationTest` 의 시드 카운트 가드가 깨지는 blast radius 가 발생하므로,
 * [com.atlas.bts.identity.issuesecurity.ProjectSecuritySchemeService] `requireProjectAdmin` 선례를 따라
 * 멤버십 역할이 [ProjectRole.PROJECT_ADMIN] 인지 **직접 확인**한다(ADMIN_PROJECT 권한 코드 부재 시 확립된
 * ground-truth 정합 패턴). 이 role 직접 조회는 데이터 소유자(identity-access) 내부에서 이뤄지므로 정당하며,
 * cross-BC 소비자(slack-integration)는 이 포트 계약만 사용한다(role 직접 조회 회귀 아님).
 *
 * ## 판정 알고리즘 (프로젝트 스코프 전용)
 * 1. 키 해석 — [ProjectDirectory.resolveKeyToId] 가 `null`(미존재/소프트삭제)이면 거부.
 * 2. 멤버십 + 역할 — [ProjectMembershipRepository.findByProjectAndUser] 로 멤버십을 조회하고,
 *    역할이 [ProjectRole.PROJECT_ADMIN] 이면 허용. 비멤버(`null`)·비관리자는 모두 거부.
 *
 * ## @Profile 배타성
 * `@Profile("prod")` — non-prod(dev/test/staging)에서는 소비 모듈(slack-integration)이 자신의 컨텍스트에
 * fail-safe stub 을 등록한다(consumer-owns-stub). identity-access 는 이 포트를 소비하지 않으므로
 * [IdentityAccessAutomationPermissionResolver] 와 마찬가지로 non-prod fallback 을 두지 않는다(dead bean 회피).
 *
 * @see SlackChannelMappingPermissionResolver
 * @see com.atlas.bts.identity.issuesecurity.ProjectSecuritySchemeService
 * @see IdentityAccessAutomationPermissionResolver
 */
@Component
@Profile("prod")
class IdentityAccessSlackChannelMappingPermissionResolver(
    private val projectDirectory: ProjectDirectory,
    private val membershipRepo: ProjectMembershipRepository,
) : SlackChannelMappingPermissionResolver {
    /**
     * 미해석 키·비멤버·비관리자는 모두 `false`(거부)로 수렴한다(fail-closed).
     *
     * @return 키가 해석되고 actor 의 멤버십 역할이 [ProjectRole.PROJECT_ADMIN] 이면 `true`, 그 외 `false`.
     */
    override fun hasManageChannelMapping(
        actorId: UUID,
        projectKey: String,
    ): Boolean {
        val projectId = projectDirectory.resolveKeyToId(projectKey) ?: return false // 미해석 키 → 거부
        val membership = membershipRepo.findByProjectAndUser(projectId, actorId)
        return membership?.role == ProjectRole.PROJECT_ADMIN // 비멤버(null)·비관리자 → 거부
    }
}
