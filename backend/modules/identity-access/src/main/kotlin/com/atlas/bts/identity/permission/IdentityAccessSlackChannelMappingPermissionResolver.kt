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
 * deny-by-default(fail-closed) — 키 해석 후 actor 의 멤버십 역할이 [ProjectRole.PROJECT_ADMIN] 일 때만
 * `true`, 그 외(미해석 키·비멤버·비관리자)는 전부 `false`(거부)로 귀결된다. `@Profile("prod")` 전용이며
 * non-prod 는 소비 모듈(slack-integration)이 stub 을 제공한다.
 *
 * @see SlackChannelMappingPermissionResolver
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
