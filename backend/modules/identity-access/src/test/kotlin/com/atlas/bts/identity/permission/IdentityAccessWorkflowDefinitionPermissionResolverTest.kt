// 워크플로우 정의 권한 prod 판정기 검증 — 전역/프로젝트 두 축과 스펙 §4 D6 표 전수

package com.atlas.bts.identity.permission

import com.bts.shared.permission.SystemPermissionResolver
import com.bts.shared.permission.WorkflowDefinitionAccessDeniedException
import com.bts.shared.permission.WorkflowDefinitionPermission
import com.bts.shared.permission.WorkflowScope
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * [IdentityAccessWorkflowDefinitionPermissionResolver] 의 판정 계약.
 *
 * ### 왜 통합 테스트가 아닌가
 * 이 판정기가 스스로 정하는 것은 **어느 축으로 갈지**뿐이다 — 전역이면 [SystemPermissionResolver],
 * 프로젝트면 [ProjectWorkflowPermissionGate]. 그 두 부품의 실 DB 판정은 각각
 * `IdentityAccessSystemPermissionResolverGlobalPermissionTest` 와
 * [IdentityAccessWorkflowSchemePermissionResolverIntegrationTest] 가 이미 덮는다.
 * 여기서 컨테이너를 하나 더 띄우면 러너 부하만 늘고 얻는 것이 없다.
 *
 * 단, **소유가 갈린 뒤의 실 DB 거부**는 mock 으로 대신할 수 없으므로
 * [IdentityAccessWorkflowSchemePermissionResolverIntegrationTest] 에 D6 행을 함께 심었다
 * (MEMORY `issue-scope-global-prod-hard-deny` — 단위 mock 은 prod 거부의 진실이 아니다).
 *
 * ### 왜 이 테스트가 필요한가
 * 비-prod 에서는 `AlwaysAllowWorkflowDefinitionPermissionResolver` 가 **항상 허용**하므로
 * 거부 경로가 로컬 눈확인으로는 원리적으로 보이지 않는다
 * (MEMORY `permission-assert-before-existence-makes-403-lie` 의 환경 판본).
 * 그래서 prod 판정기를 **직접 세워** 거부를 확인한다.
 *
 * ### 스펙 §4 D6 표
 * | 권한 | PROJECT_ADMIN (자기 프로젝트 소유분) |
 * |---|---|
 * | CREATE | 허용 |
 * | UPDATE | 허용 |
 * | PUBLISH | 허용 |
 * | DELETE | 불허 (SYSTEM_ADMIN 유지) |
 *
 * 이 표의 네 행을 [D6_PROJECT_DELEGATED] · [D6_PROJECT_DENIED] 로 옮겨 적고 전수로 잰다.
 * 한 행이라도 표와 코드가 갈리면 여기가 red 다.
 */
class IdentityAccessWorkflowDefinitionPermissionResolverTest {
    private val admin = UUID.randomUUID()
    private val projectAdmin = UUID.randomUUID()
    private val outsider = UUID.randomUUID()

    private val projectKey = "ATLAS"

    private val projectGate: ProjectWorkflowPermissionGate =
        mockk<ProjectWorkflowPermissionGate>().also {
            every { it.has(any(), any(), any()) } returns false
            every { it.has(projectAdmin, projectKey, MANAGE_WORKFLOW) } returns true
        }

    private val resolver =
        IdentityAccessWorkflowDefinitionPermissionResolver(
            object : SystemPermissionResolver {
                override fun isSystemAdmin(actorId: UUID): Boolean = actorId == admin
            },
            projectGate,
        )

    // ── 전역 축 — 종전 계약 그대로 ────────────────────────────────────────────

    @Test
    fun `시스템 관리자는 전역 스코프에서 4종 권한 전부를 통과한다`() {
        WorkflowDefinitionPermission.entries.forEach { permission ->
            assertThatCode { resolver.requirePermission(admin, permission, WorkflowScope.Global) }
                .describedAs("권한 %s 가 시스템 관리자에게 거부됐다", permission)
                .doesNotThrowAnyException()
        }
    }

    @Test
    fun `시스템 관리자가 아니면 전역 스코프의 4종 권한 전부가 거부된다`() {
        WorkflowDefinitionPermission.entries.forEach { permission ->
            assertThatThrownBy { resolver.requirePermission(projectAdmin, permission, WorkflowScope.Global) }
                .describedAs("권한 %s 가 비-관리자에게 통과됐다", permission)
                .isInstanceOf(WorkflowDefinitionAccessDeniedException::class.java)
        }
    }

    // ── D6 표 — 프로젝트 축 ──────────────────────────────────────────────────

    @Test
    fun `D6 — 프로젝트 관리자는 자기 프로젝트 워크플로우의 CREATE·UPDATE·PUBLISH 를 통과한다`() {
        D6_PROJECT_DELEGATED.forEach { permission ->
            assertThatCode {
                resolver.requirePermission(projectAdmin, permission, WorkflowScope.Project(projectKey))
            }.describedAs("D6 표는 %s 를 허용이라 적었는데 거부됐다", permission)
                .doesNotThrowAnyException()
        }
    }

    @Test
    fun `D6 — 프로젝트 관리자도 DELETE 는 거부된다 (SYSTEM_ADMIN 유지)`() {
        D6_PROJECT_DENIED.forEach { permission ->
            assertThatThrownBy {
                resolver.requirePermission(projectAdmin, permission, WorkflowScope.Project(projectKey))
            }.describedAs("D6 표는 %s 를 불허라 적었는데 통과됐다", permission)
                .isInstanceOf(WorkflowDefinitionAccessDeniedException::class.java)
        }
    }

    @Test
    fun `D6 표는 4종 권한을 빠짐없이 가른다`() {
        // ★표를 옮겨 적은 두 목록이 enum 전량을 덮는지 본다. 한 값이 어느 쪽에도 없으면
        //  그 권한은 위 두 테스트를 **한 번도 타지 않고** 조용히 통과한다.
        assertThat(D6_PROJECT_DELEGATED + D6_PROJECT_DENIED)
            .describedAs("D6 표를 옮겨 적은 두 목록이 enum 전량을 덮지 않는다")
            .containsExactlyInAnyOrderElementsOf(WorkflowDefinitionPermission.entries)
    }

    // ── 프로젝트 축의 거부 경로 ──────────────────────────────────────────────

    @Test
    fun `그 프로젝트에서 권한이 없으면 위임 대상 권한도 거부된다`() {
        D6_PROJECT_DELEGATED.forEach { permission ->
            assertThatThrownBy {
                resolver.requirePermission(outsider, permission, WorkflowScope.Project(projectKey))
            }.describedAs("매트릭스 미보유 행위자에게 %s 가 통과됐다", permission)
                .isInstanceOf(WorkflowDefinitionAccessDeniedException::class.java)
        }
    }

    @Test
    fun `시스템 관리자는 프로젝트 소유 워크플로우도 지울 수 있다`() {
        // DELETE 는 대상 소유와 무관하게 전역 축으로 판정된다 — 아니면 프로젝트 소유 워크플로우를
        // **아무도** 못 지운다(프로젝트 관리자는 D6 로 막히고 시스템 관리자는 비멤버라 막힌다).
        assertThatCode {
            resolver.requirePermission(admin, WorkflowDefinitionPermission.DELETE, WorkflowScope.Project(projectKey))
        }.doesNotThrowAnyException()
    }

    @Test
    fun `거부 예외는 어떤 권한과 어느 스코프가 거부됐는지 남긴다`() {
        assertThatThrownBy {
            resolver.requirePermission(outsider, WorkflowDefinitionPermission.DELETE, WorkflowScope.Project(projectKey))
        }.hasMessageContaining("DELETE")
            .hasMessageContaining(outsider.toString())
            .hasMessageContaining(projectKey)
    }

    private companion object {
        /** `role_permissions` 의 워크플로우 관리 권한 코드. 스킴 판정기와 같은 코드를 쓴다. */
        const val MANAGE_WORKFLOW = "MANAGE_WORKFLOW"

        /** D6 표에서 「허용」인 행. */
        val D6_PROJECT_DELEGATED =
            listOf(
                WorkflowDefinitionPermission.CREATE,
                WorkflowDefinitionPermission.UPDATE,
                WorkflowDefinitionPermission.PUBLISH,
            )

        /** D6 표에서 「불허(SYSTEM_ADMIN 유지)」인 행. */
        val D6_PROJECT_DENIED = listOf(WorkflowDefinitionPermission.DELETE)
    }
}
