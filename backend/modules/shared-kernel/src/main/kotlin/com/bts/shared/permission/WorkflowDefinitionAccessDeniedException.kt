// 워크플로우 정의 권한 거부 예외(공용) — BC를 가로지르므로 shared-kernel 배치.

package com.bts.shared.permission

import java.util.UUID

/**
 * 워크플로우 정의 편집 권한이 없을 때 [WorkflowDefinitionPermissionResolver] 가 던진다.
 *
 * 에러 코드를 [WorkflowSchemeAccessDeniedException] 과 **다르게** 둔다 — 같으면 프론트가
 * 「스킴 권한이 없다」와 「워크플로우 편집 권한이 없다」를 같은 문구로 안내하게 된다.
 *
 * @param actorId 거부된 행위자
 * @param permission 요청했던 권한
 */
class WorkflowDefinitionAccessDeniedException(
    actorId: UUID,
    permission: WorkflowDefinitionPermission,
) : RuntimeException(
        "Access denied: actor=$actorId, permission=${permission.name}, resource=workflow-definition",
    ) {
    val errorCode: String = WORKFLOW_DEFINITION_ACCESS_DENIED

    companion object {
        const val WORKFLOW_DEFINITION_ACCESS_DENIED: String = "WORKFLOW_DEFINITION_ACCESS_DENIED"
    }
}
