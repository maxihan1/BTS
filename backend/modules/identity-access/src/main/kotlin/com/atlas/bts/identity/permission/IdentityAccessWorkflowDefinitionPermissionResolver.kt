// 워크플로우 정의 권한 prod 판정기 — 전역 자원이라 isSystemAdmin 한 축으로 판정한다.

package com.atlas.bts.identity.permission

import com.bts.shared.permission.SystemPermissionResolver
import com.bts.shared.permission.WorkflowDefinitionAccessDeniedException
import com.bts.shared.permission.WorkflowDefinitionPermission
import com.bts.shared.permission.WorkflowDefinitionPermissionResolver
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * 워크플로우 **정의** 편집 권한의 운영 판정기.
 *
 * ### 왜 권한 매트릭스를 거치지 않는가
 * 워크플로우 정의는 **사이트 전역 자원**이다. 프로젝트에 종속되지 않으므로 역할×권한 매트릭스
 * (`role_permissions`)를 볼 대상이 없다. [IdentityAccessWorkflowSchemePermissionResolver] 도
 * `WorkflowSchemeScope.Global` 에 대해 같은 판단을 하고 있고, 그 파일 주석이
 * 「Global 은 매트릭스를 거치지 않고 `isSystemAdmin` 으로 판정한다」고 명시한다.
 *
 * `MANAGE_WORKFLOW` 권한 코드는 **프로젝트 스코프** 판정에 쓰이는 것이라 여기 관여하지 않는다.
 *
 * ### 4종 권한을 한 축으로 판정하는 이유
 * CREATE·UPDATE·DELETE·PUBLISH 를 나누어 부여할 요구가 아직 없다. 나누려면 전역 권한 부여
 * (`global_permission_grants`)를 도입해야 하고 그건 별도 FR 이다. 지금은 시스템 관리자만 편집한다.
 * enum 을 4값으로 둔 것은 **감사 로그와 에러 메시지가 무엇을 시도했는지 남기기 위해서**다.
 */
@Component
@Profile("prod")
class IdentityAccessWorkflowDefinitionPermissionResolver(
    private val systemPermissionResolver: SystemPermissionResolver,
) : WorkflowDefinitionPermissionResolver {
    override fun requirePermission(
        actorId: UUID,
        permission: WorkflowDefinitionPermission,
    ) {
        if (!systemPermissionResolver.isSystemAdmin(actorId)) {
            throw WorkflowDefinitionAccessDeniedException(actorId, permission)
        }
    }
}
