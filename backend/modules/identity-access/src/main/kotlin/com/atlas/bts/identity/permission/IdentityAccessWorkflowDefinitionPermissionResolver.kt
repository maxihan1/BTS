// 워크플로우 정의 권한 prod 판정기 — 전역 자원이라 isSystemAdmin 한 축으로 판정한다.

package com.atlas.bts.identity.permission

import com.bts.shared.permission.SystemPermissionResolver
import com.bts.shared.permission.WorkflowDefinitionAccessDeniedException
import com.bts.shared.permission.WorkflowDefinitionPermission
import com.bts.shared.permission.WorkflowDefinitionPermissionResolver
import com.bts.shared.permission.WorkflowScope
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * 워크플로우 **정의** 편집 권한의 운영 판정기. 대상의 소유(`workflows.project_id`)로 축이 갈린다.
 *
 * ### 두 축 (FR-WF-08)
 * - [WorkflowScope.Global] — 전역 공유 워크플로우와 전역 상태 카탈로그. 매트릭스를 거치지 않고
 *   [SystemPermissionResolver.isSystemAdmin] 로 판정한다.
 *   [IdentityAccessWorkflowSchemePermissionResolver] 가 같은 스코프에 쓰는 것과 같은 경로다.
 * - [WorkflowScope.Project] — 그 프로젝트 전용 워크플로우. [ProjectWorkflowPermissionGate] 로
 *   키 해석 → 멤버 게이트 → `role_permissions` 매트릭스(`MANAGE_WORKFLOW`) 순으로 평가한다.
 *   ★그 절차는 스킴 판정기와 **같은 구현**을 쓴다 — 복사본을 두면 한쪽만 강화된다.
 *
 * ### ★ DELETE 만 소유와 무관하게 전역 축이다 (스펙 §4 D6)
 * 프로젝트 소유 워크플로우라도 삭제는 시스템 관리자만 한다. 이슈가 물려 있는 워크플로우 삭제는
 * 파급이 크고, 그 위임은 요구에 없다. **판정을 여기 한 곳에 둔다** — 호출부가 DELETE 에만
 * `Global` 을 넣어 흉내 내면 그 규칙이 호출부 수만큼 생기고, 새 삭제 경로가 생길 때 조용히 빠진다.
 * 그래서 호출부는 늘 **사실대로의 소유**를 넘기고 예외 규칙은 이 판정기가 안다.
 *
 * 뒤집어 말하면 [WorkflowScope.Project] + DELETE 를 전역 축으로 보내는 것은 완화가 아니라
 * **강화**다. 그 조합이 게이트를 타면 프로젝트 관리자가 통과하게 된다.
 *
 * ### 왜 권한을 나누어 부여하지 않는가
 * 전역 축에서는 CREATE·UPDATE·DELETE·PUBLISH 를 나누어 부여할 요구가 아직 없다. 나누려면 전역
 * 권한 부여(`global_permission_grants`)를 도입해야 하고 그건 별도 FR 이다. enum 을 4값으로 둔 것은
 * **감사 로그와 에러 메시지가 무엇을 시도했는지 남기기 위해서**다.
 */
@Component
@Profile("prod")
class IdentityAccessWorkflowDefinitionPermissionResolver(
    private val systemPermissionResolver: SystemPermissionResolver,
    private val projectGate: ProjectWorkflowPermissionGate,
) : WorkflowDefinitionPermissionResolver {
    override fun requirePermission(
        actorId: UUID,
        permission: WorkflowDefinitionPermission,
        scope: WorkflowScope,
    ) {
        val granted =
            if (!permission.isProjectDelegable()) {
                // D6 — 삭제는 대상 소유와 무관하게 시스템 관리자만. 스코프 분기보다 **먼저** 본다.
                systemPermissionResolver.isSystemAdmin(actorId)
            } else {
                // ★주어 있는 when 이다. else 를 두면 스코프가 늘어도 조용히 한쪽으로 떨어져,
                //  「두 판정기가 같은 컴파일 에러로 멈춘다」는 D8 의 근거가 여기서만 무너진다.
                when (scope) {
                    is WorkflowScope.Global -> systemPermissionResolver.isSystemAdmin(actorId)
                    is WorkflowScope.Project -> projectGate.has(actorId, scope.key, MANAGE_WORKFLOW)
                }
            }
        if (!granted) {
            throw WorkflowDefinitionAccessDeniedException(actorId, permission, scope)
        }
    }

    private companion object {
        /** `role_permissions` 의 워크플로우 관리 권한 코드 (SDD 12.3 — 도메인당 단일 관리 권한). */
        const val MANAGE_WORKFLOW = "MANAGE_WORKFLOW"
    }
}

/**
 * 프로젝트 관리자에게 위임할 수 있는 권한인가 (스펙 §4 D6).
 *
 * `when` 에 else 를 두지 않는다 — enum 에 값이 추가되면 **컴파일 에러**로 결정을 강요한다.
 * else 를 두면 새 권한이 조용히 한쪽으로 떨어지고, 그 방향이 위임이면 그날 권한이 넓어진다.
 *
 * @return 프로젝트 스코프에서 매트릭스로 판정할 권한이면 `true`,
 *   소유와 무관하게 시스템 관리자만 할 수 있으면 `false`.
 */
private fun WorkflowDefinitionPermission.isProjectDelegable(): Boolean =
    when (this) {
        WorkflowDefinitionPermission.CREATE,
        WorkflowDefinitionPermission.UPDATE,
        WorkflowDefinitionPermission.PUBLISH,
        -> true
        // 이슈가 물려 있는 워크플로우 삭제는 파급이 크다. 요구에 없다(D6).
        WorkflowDefinitionPermission.DELETE -> false
    }
