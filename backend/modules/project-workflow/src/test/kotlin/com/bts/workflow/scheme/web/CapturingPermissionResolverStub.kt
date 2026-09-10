// 스킴 권한 포트 테스트 스텁 — 마지막 호출의 (actorId, permission, scope)를 캡처하고 선택적으로 거부한다

package com.bts.workflow.scheme.web

import com.bts.shared.permission.WorkflowSchemePermission
import com.bts.shared.permission.WorkflowSchemePermissionResolver
import com.bts.shared.permission.WorkflowScope
import java.util.UUID

/**
 * [WorkflowSchemePermissionResolver] 테스트 스텁 — 호출 인자를 캡처한다.
 *
 * ### 왜 MockK 가 아니라 손수 만든 스텁인가
 * MockK 1.13.x 는 `@JvmInline` value class 파라미터를 가진 함수의 서명 값을 생성할 때 init 검증에
 * 실패한다([com.bts.workflow.port.outbound.ActorId] 가 그 경우다). 그래서 컨트롤러가 소비하는
 * 권한 포트는 mock 대신 이 스텁으로 교체한다.
 *
 * ### 최상위 클래스인 이유
 * 원래 [ProjectWorkflowSchemeControllerTest] 의 중첩 클래스였으나,
 * [com.bts.workflow.archunit.SchemeHandlerPermissionRuntimeMatrixTest] 가 스킴 컨트롤러 전수의
 * 권한 인자를 런타임에 캡처하는 데 같은 스텁이 필요해 최상위로 추출했다.
 */
class CapturingPermissionResolverStub : WorkflowSchemePermissionResolver {
    var capturedActorId: UUID? = null
    var capturedPermission: WorkflowSchemePermission? = null
    var capturedScope: WorkflowScope? = null
    var callCount = 0

    /** 설정 시 [requirePermission] 이 캡처를 마친 뒤 이 예외를 던진다(403 거부 시나리오 재현용). */
    var denyWith: RuntimeException? = null

    override fun requirePermission(
        actorId: UUID,
        permission: WorkflowSchemePermission,
        scope: WorkflowScope,
    ) {
        capturedActorId = actorId
        capturedPermission = permission
        capturedScope = scope
        callCount++
        denyWith?.let { throw it }
    }

    fun reset() {
        capturedActorId = null
        capturedPermission = null
        capturedScope = null
        callCount = 0
        denyWith = null
    }
}
