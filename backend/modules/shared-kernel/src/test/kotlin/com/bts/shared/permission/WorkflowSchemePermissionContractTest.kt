// 워크플로우 스킴 권한 계약(포트/enum/scope/예외)이 shared-kernel에 actorId:UUID 시그니처로 존재함을 단언

package com.bts.shared.permission

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * [WorkflowSchemePermissionResolver] · [WorkflowSchemePermission] · [WorkflowScope] ·
 * [WorkflowSchemeAccessDeniedException] 의 계약 단위 테스트.
 *
 * FR-PM-04 D2 — 포트 계약을 project-workflow → shared-kernel(`com.bts.shared.permission`)로
 * 이동하고 시그니처를 `actor: ActorId` → `actorId: UUID` 로 단순화한 결과를 고정한다.
 * Spring 컨텍스트 없이 순수 단위 테스트로 실행한다.
 *
 * 검증 항목.
 * - port_signature_uuid — `requirePermission(actorId: UUID, …)` 시그니처로 구현 가능하다.
 * - permission_entries — [WorkflowSchemePermission] 항목 2종(MANAGE_SCHEME, ASSIGN_SCHEME).
 * - scope_global_singleton — [WorkflowScope.Global] 은 참조 동일성이 보장된다.
 * - scope_project_equality — 같은 key 의 [WorkflowScope.Project] 는 equals true.
 * - exception_is_runtime_with_errorcode — 예외는 RuntimeException 이며 errorCode 상수를 보유한다.
 */
class WorkflowSchemePermissionContractTest {
    private val actorId: UUID = UUID.fromString("11111111-1111-4111-8111-111111111111")

    @Test
    fun `port_signature_uuid — requirePermission 은 actorId UUID 시그니처를 가진다`() {
        var capturedActor: UUID? = null
        var capturedPermission: WorkflowSchemePermission? = null
        var capturedScope: WorkflowScope? = null

        val resolver =
            object : WorkflowSchemePermissionResolver {
                override fun requirePermission(
                    actorId: UUID,
                    permission: WorkflowSchemePermission,
                    scope: WorkflowScope,
                ) {
                    capturedActor = actorId
                    capturedPermission = permission
                    capturedScope = scope
                }
            }

        resolver.requirePermission(actorId, WorkflowSchemePermission.MANAGE_SCHEME, WorkflowScope.Global)

        assertThat(capturedActor).isEqualTo(actorId)
        assertThat(capturedPermission).isEqualTo(WorkflowSchemePermission.MANAGE_SCHEME)
        assertThat(capturedScope).isEqualTo(WorkflowScope.Global)
    }

    @Test
    fun `permission_entries — WorkflowSchemePermission 항목은 MANAGE_SCHEME, ASSIGN_SCHEME 2종이다`() {
        assertThat(WorkflowSchemePermission.entries)
            .containsExactlyInAnyOrder(
                WorkflowSchemePermission.MANAGE_SCHEME,
                WorkflowSchemePermission.ASSIGN_SCHEME,
            )
    }

    @Test
    fun `scope_global_singleton — WorkflowScope Global 은 같은 인스턴스다`() {
        assertThat(WorkflowScope.Global).isSameAs(WorkflowScope.Global)
        assertThat(WorkflowScope.Global).isEqualTo(WorkflowScope.Global)
    }

    @Test
    fun `scope_project_equality — 같은 key 의 Project 는 equals true 다`() {
        val a = WorkflowScope.Project("ATLAS")
        val b = WorkflowScope.Project("ATLAS")

        assertThat(a).isEqualTo(b)
        assertThat(a.hashCode()).isEqualTo(b.hashCode())
        assertThat(a.key).isEqualTo("ATLAS")
    }

    @Test
    fun `exception_is_runtime_with_errorcode — 예외는 RuntimeException 이며 errorCode 상수를 보유한다`() {
        assertThat(WorkflowSchemeAccessDeniedException.WORKFLOW_SCHEME_ACCESS_DENIED)
            .isEqualTo("WORKFLOW_SCHEME_ACCESS_DENIED")

        val ex =
            WorkflowSchemeAccessDeniedException(
                actorId = actorId,
                permission = WorkflowSchemePermission.MANAGE_SCHEME,
                scope = WorkflowScope.Global,
            )

        assertThat(ex).isInstanceOf(RuntimeException::class.java)
        assertThat(ex.errorCode).isEqualTo(WorkflowSchemeAccessDeniedException.WORKFLOW_SCHEME_ACCESS_DENIED)
        assertThatThrownBy { throw ex }.isInstanceOf(WorkflowSchemeAccessDeniedException::class.java)
    }
}
