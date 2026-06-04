// 워크플로우 스킴 권한 거부 예외(공용) — BC를 가로지르므로 shared-kernel 배치(FR-PM-04 EC7).

package com.bts.shared.permission

import java.util.UUID

/**
 * 행위자가 워크플로우 스킴 작업에 필요한 권한을 보유하지 않을 때 던지는 Guard 예외.
 *
 * RuntimeException 을 상속하므로 Spring `@Transactional` 롤백 트리거 대상이다.
 *
 * ## 배치 (BC 가로지름)
 * FR-PM-04 EC7 — prod adapter(identity-access)가 이 예외를 **던지고**,
 * project-workflow 의 `WorkflowSchemeExceptionHandler` 가 **catch** 하여 403 +
 * [WORKFLOW_SCHEME_ACCESS_DENIED] 로 매핑한다. 양 BC 가 참조하므로 shared-kernel 에 둔다.
 * (FR-PM-03 은 Boolean 반환이라 예외가 소비 BC 내부였으나, 본 포트는 Guard 패턴이라
 * 예외가 BC 를 가로지른다 — 이 차이가 핵심.)
 *
 * @param actorId 권한 검사 대상 행위자 UUID.
 * @param permission 요청한 스킴 권한.
 * @param scope 권한 평가 범위.
 * @property errorCode REST 응답 매핑용 에러 코드. 항상 [WORKFLOW_SCHEME_ACCESS_DENIED].
 */
class WorkflowSchemeAccessDeniedException(
    actorId: UUID,
    permission: WorkflowSchemePermission,
    scope: WorkflowSchemeScope,
) : RuntimeException(
        "Access denied: actor=$actorId, permission=${permission.name}, scope=$scope",
    ) {
    /** REST 핸들러가 403 응답 바디에 싣는 에러 코드. */
    val errorCode: String = WORKFLOW_SCHEME_ACCESS_DENIED

    companion object {
        /** 워크플로우 스킴 권한 거부 에러 코드 상수. project-workflow 핸들러와 공유한다. */
        const val WORKFLOW_SCHEME_ACCESS_DENIED: String = "WORKFLOW_SCHEME_ACCESS_DENIED"
    }
}
