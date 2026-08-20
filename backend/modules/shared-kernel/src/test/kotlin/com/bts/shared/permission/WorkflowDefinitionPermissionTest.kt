// 워크플로우 정의 권한 계약 검증 — enum 4값과 Guard 패턴 resolver 포트

package com.bts.shared.permission

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * `WorkflowDefinitionPermission` + `WorkflowDefinitionPermissionResolver` 의 계약을 못박는다.
 *
 * ### 왜 스킴 권한에 끼워 넣지 않았나
 * `WorkflowSchemePermission` 은 이름이 **스킴**(이슈 타입별 워크플로우 매핑 묶음)을 뜻한다.
 * 거기에 워크플로우 **정의** 편집 권한을 넣으면 다음 사람이 두 개념을 같은 것으로 읽는다.
 * 도메인당 enum 1개 + resolver 1개가 이 저장소의 관례다(`VersionPermission`·`ComponentPermission`·
 * `TemplatePermission`·`CustomFieldPermission`).
 *
 * ### 왜 스코프 인자가 없나
 * 워크플로우 정의는 **사이트 전역 자원**이라 축이 하나다. `WorkflowSchemeScope` 는 Global/Project
 * 두 축이 있어 sealed 계층이 필요했지만 여기는 아니다 — `VersionPermissionResolver` 가
 * 「축이 하나면 sealed 계층 대신 단순화한다」고 남긴 판단과 같다.
 *
 * ### 왜 Boolean 이 아니라 Guard(예외) 인가
 * 전역 자원 + 같은 BC 소비라는 두 조건에서 `WorkflowSchemePermissionResolver` 와 동형이다.
 * `VersionPermissionResolver` 의 `Boolean` 규약은 프로젝트 스코프 자원의 것이다.
 */
class WorkflowDefinitionPermissionTest {
    @Test
    fun `권한은 CREATE UPDATE DELETE PUBLISH 4종이다`() {
        assertThat(WorkflowDefinitionPermission.entries.map { it.name })
            .containsExactlyInAnyOrder("CREATE", "UPDATE", "DELETE", "PUBLISH")
    }

    @Test
    fun `resolver 는 권한이 없으면 예외를 던진다 — Guard 패턴`() {
        val denying =
            object : WorkflowDefinitionPermissionResolver {
                override fun requirePermission(
                    actorId: UUID,
                    permission: WorkflowDefinitionPermission,
                ) = throw WorkflowDefinitionAccessDeniedException(actorId, permission)
            }

        assertThatThrownBy { denying.requirePermission(UUID.randomUUID(), WorkflowDefinitionPermission.UPDATE) }
            .isInstanceOf(WorkflowDefinitionAccessDeniedException::class.java)
    }

    @Test
    fun `거부 예외는 고유 에러 코드를 갖는다`() {
        val ex = WorkflowDefinitionAccessDeniedException(UUID.randomUUID(), WorkflowDefinitionPermission.DELETE)
        assertThat(ex.errorCode).isEqualTo("WORKFLOW_DEFINITION_ACCESS_DENIED")
        // 스킴 권한 거부와 코드가 겹치면 프론트가 두 상황을 같은 문구로 안내한다.
        assertThat(ex.errorCode).isNotEqualTo(WorkflowSchemeAccessDeniedException.WORKFLOW_SCHEME_ACCESS_DENIED)
    }

    @Test
    fun `거부 예외 메시지는 행위자와 권한을 담는다`() {
        val actorId = UUID.randomUUID()
        val ex = WorkflowDefinitionAccessDeniedException(actorId, WorkflowDefinitionPermission.PUBLISH)
        assertThat(ex.message).contains(actorId.toString()).contains("PUBLISH")
    }
}
