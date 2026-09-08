// 프로젝트 스코프 워크플로우 권한 판정의 단일 구현 — 스킴·정의 두 prod 판정기가 함께 쓴다 (FR-WF-08)

package com.atlas.bts.identity.permission

import com.atlas.bts.identity.project.ProjectDirectory
import com.atlas.bts.identity.project.ProjectMembershipRepository
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * 「이 사람이 이 프로젝트에서 이 권한을 갖는가」를 판정하는 **단일 구현**.
 *
 * 절차는 세 단계이고 셋 모두 만족해야 통과한다(deny-by-default).
 * 1. 프로젝트 키 → id 해석. 실패하면 거부 — 없는 프로젝트에 권한이 있을 수 없다.
 * 2. 멤버 게이트. 그 프로젝트의 멤버가 아니면 거부.
 * 3. `role_permissions` 매트릭스. 그 역할이 해당 권한 코드를 갖는지.
 *
 * ★ **이 절차가 판정기마다 복사되면 안 된다.** FR-WF-08 이전에는 스킴 판정기 하나만 프로젝트
 * 스코프를 봤다. 이제 워크플로우 **정의** 판정기도 같은 절차를 필요로 하는데, 각자 복사본을
 * 들면 멤버 게이트를 강화하는 날 한쪽만 고쳐진다 — 두 판정기 테스트가 각자 자기 사본만 지키므로
 * red 도 나지 않는다. 저장소가 `ManageSchemeGuard` 에 이미 같은 이유로 단일 구현을 요구했다.
 *
 * ## 왜 non-prod 구현이 없는가
 * 이 클래스는 판정기의 **부품**이지 포트가 아니다. 비-prod 는 project-workflow 의 AlwaysAllow
 * stub 이 판정기 자체를 대체하므로 이 부품에 도달하지 않는다.
 *
 * @param projectDirectory 프로젝트 키 → id 해석.
 * @param membershipRepo 프로젝트 멤버십 조회.
 * @param permissionSchemeRepo 역할×권한 매트릭스 조회.
 */
@Component
@Profile("prod")
class ProjectWorkflowPermissionGate(
    private val projectDirectory: ProjectDirectory,
    private val membershipRepo: ProjectMembershipRepository,
    private val permissionSchemeRepo: PermissionSchemeRepository,
) {
    /**
     * [actorId] 가 [projectKey] 프로젝트에서 [permissionCode] 를 갖는지 판정한다.
     *
     * Suppress ReturnCount — guard-clause early return 2개(미해석 키·비멤버).
     * DEVELOPMENT.md §2.3 Early return 권장 정책에 부합 — 전역 임계 완화 대신 국소 Suppress(FR-PM-02 선례).
     *
     * @param actorId 판정 대상 행위자.
     * @param projectKey 대상 프로젝트 키. 예. "ATLAS".
     * @param permissionCode `role_permissions` 의 권한 코드. 예. "MANAGE_WORKFLOW".
     * @return 키 해석·멤버십·매트릭스를 모두 만족하면 `true`, 그 외 `false`.
     */
    @Suppress("ReturnCount")
    fun has(
        actorId: UUID,
        projectKey: String,
        permissionCode: String,
    ): Boolean {
        val projectId = projectDirectory.resolveKeyToId(projectKey) ?: return false // 미해석 키 → 거부
        val membership = membershipRepo.findByProjectAndUser(projectId, actorId) ?: return false // 비멤버 → 거부
        return permissionSchemeRepo.roleHasPermission(projectId, membership.role.name, permissionCode)
    }
}
