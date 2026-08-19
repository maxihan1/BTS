// 워크플로우 정의 권한 prod 판정기 검증 — 전역 자원이라 isSystemAdmin 한 축으로만 갈린다

package com.atlas.bts.identity.permission

import com.bts.shared.permission.SystemPermissionResolver
import com.bts.shared.permission.WorkflowDefinitionAccessDeniedException
import com.bts.shared.permission.WorkflowDefinitionPermission
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * [IdentityAccessWorkflowDefinitionPermissionResolver] 의 판정 계약.
 *
 * ### 왜 통합 테스트가 아닌가
 * 이 판정기의 로직은 「[SystemPermissionResolver.isSystemAdmin] 이 참이면 통과, 아니면 예외」한 줄이다.
 * DB 를 띄워 확인할 것이 없다 — 시스템 관리자 판정 자체는
 * `IdentityAccessSystemPermissionResolverGlobalPermissionTest` 가 이미 통합으로 덮는다.
 * 컨테이너를 하나 더 띄우면 러너 부하만 늘고 얻는 것이 없다.
 *
 * ### 왜 이 테스트가 필요한가
 * 비-prod 에서는 `AlwaysAllowWorkflowDefinitionPermissionResolver` 가 **항상 허용**하므로
 * 거부 경로가 로컬 눈확인으로는 원리적으로 보이지 않는다
 * (MEMORY `permission-assert-before-existence-makes-403-lie` 의 환경 판본).
 * 그래서 prod 판정기를 **직접 세워** 거부를 확인한다.
 */
class IdentityAccessWorkflowDefinitionPermissionResolverTest {
    private val admin = UUID.randomUUID()
    private val member = UUID.randomUUID()

    private val resolver =
        IdentityAccessWorkflowDefinitionPermissionResolver(
            object : SystemPermissionResolver {
                override fun isSystemAdmin(actorId: UUID): Boolean = actorId == admin
            },
        )

    @Test
    fun `시스템 관리자는 4종 권한 전부를 통과한다`() {
        WorkflowDefinitionPermission.entries.forEach { permission ->
            assertThatCode { resolver.requirePermission(admin, permission) }.doesNotThrowAnyException()
        }
    }

    @Test
    fun `시스템 관리자가 아니면 4종 권한 전부가 거부된다`() {
        WorkflowDefinitionPermission.entries.forEach { permission ->
            assertThatThrownBy { resolver.requirePermission(member, permission) }
                .describedAs("권한 %s 가 비-관리자에게 통과됐다", permission)
                .isInstanceOf(WorkflowDefinitionAccessDeniedException::class.java)
        }
    }

    @Test
    fun `거부 예외는 어떤 권한이 거부됐는지 남긴다`() {
        assertThatThrownBy { resolver.requirePermission(member, WorkflowDefinitionPermission.DELETE) }
            .hasMessageContaining("DELETE")
            .hasMessageContaining(member.toString())
    }
}
