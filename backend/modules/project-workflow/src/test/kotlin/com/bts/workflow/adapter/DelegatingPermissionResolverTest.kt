// DelegatingPermissionResolver 단위 테스트 — shared-kernel 위임·권한 매핑·fail-closed 검증(Spring 부팅 없음)

package com.bts.workflow.adapter

import com.bts.shared.permission.IssuePermission
import com.bts.shared.permission.IssuePermissionResolver
import com.bts.shared.permission.IssueScope
import com.bts.shared.permission.SystemPermissionResolver
import com.bts.workflow.port.outbound.ActorId
import com.bts.workflow.port.outbound.Scope
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * [DelegatingPermissionResolver] 단위 테스트.
 *
 * shared-kernel 위임 포트([IssuePermissionResolver], [SystemPermissionResolver])만 mockk 로 목킹하고
 * Spring 컨텍스트 없이 순수 단위 수준에서 검증한다.
 *
 * [ActorId] 는 value class 이므로 mockk 인자 매칭 함정을 피하기 위해 실제 인스턴스(유효 UUID)를 사용하고
 * 목킹하지 않는다. 위임 포트 시그니처는 [UUID] 를 받으므로 mock 경계에 value class 가 노출되지 않는다.
 */
class DelegatingPermissionResolverTest {
    private val issuePermissionResolver = mockk<IssuePermissionResolver>()
    private val systemPermissionResolver = mockk<SystemPermissionResolver>()
    private val resolver = DelegatingPermissionResolver(issuePermissionResolver, systemPermissionResolver)

    private val actorUuid: UUID = UUID.fromString("550e8400-e29b-41d4-a716-446655440000")
    private val actor = ActorId(actorUuid.toString())

    @Test
    fun `Global scope는 isSystemAdmin이 true면 true를 반환한다`() {
        every { systemPermissionResolver.isSystemAdmin(actorUuid) } returns true

        val result = resolver.hasPermission(actor, "ANY_PERMISSION", Scope.Global)

        assertThat(result).isTrue()
        verify(exactly = 1) { systemPermissionResolver.isSystemAdmin(actorUuid) }
        verify(exactly = 0) { issuePermissionResolver.hasPermission(any(), any(), any()) }
    }

    @Test
    fun `Global scope는 isSystemAdmin이 false면 false를 반환한다`() {
        every { systemPermissionResolver.isSystemAdmin(actorUuid) } returns false

        val result = resolver.hasPermission(actor, "ANY_PERMISSION", Scope.Global)

        assertThat(result).isFalse()
        verify(exactly = 1) { systemPermissionResolver.isSystemAdmin(actorUuid) }
    }

    @Test
    fun `Project scope의 CREATE_ISSUE는 IssuePermission_CREATE와 IssueScope_Project로 위임한다`() {
        every {
            issuePermissionResolver.hasPermission(actorUuid, IssuePermission.CREATE, IssueScope.Project("ATLAS"))
        } returns true

        val result = resolver.hasPermission(actor, "CREATE_ISSUE", Scope.Project("ATLAS"))

        assertThat(result).isTrue()
        verify(exactly = 1) {
            issuePermissionResolver.hasPermission(actorUuid, IssuePermission.CREATE, IssueScope.Project("ATLAS"))
        }
    }

    @Test
    fun `Project scope 위임 결과가 false면 false를 그대로 전달한다`() {
        every {
            issuePermissionResolver.hasPermission(actorUuid, IssuePermission.CREATE, IssueScope.Project("ATLAS"))
        } returns false

        val result = resolver.hasPermission(actor, "CREATE_ISSUE", Scope.Project("ATLAS"))

        assertThat(result).isFalse()
    }

    @Test
    fun `Issue scope의 TRANSITION_ISSUE는 IssuePermission_TRANSITION과 IssueScope_Issue로 위임한다`() {
        every {
            issuePermissionResolver.hasPermission(actorUuid, IssuePermission.TRANSITION, IssueScope.Issue("ATLAS-1"))
        } returns true

        val result = resolver.hasPermission(actor, "TRANSITION_ISSUE", Scope.Issue("ATLAS-1"))

        assertThat(result).isTrue()
        verify(exactly = 1) {
            issuePermissionResolver.hasPermission(actorUuid, IssuePermission.TRANSITION, IssueScope.Issue("ATLAS-1"))
        }
    }

    @Test
    fun `미등록 권한 문자열은 Project scope에서 위임 없이 false를 반환한다(fail-closed)`() {
        val result = resolver.hasPermission(actor, "ADMIN_WORKFLOW", Scope.Project("ATLAS"))

        assertThat(result).isFalse()
        verify(exactly = 0) { issuePermissionResolver.hasPermission(any(), any(), any()) }
    }

    @Test
    fun `미등록 권한 문자열은 Issue scope에서 위임 없이 false를 반환한다(fail-closed)`() {
        val result = resolver.hasPermission(actor, "FOO", Scope.Issue("ATLAS-1"))

        assertThat(result).isFalse()
        verify(exactly = 0) { issuePermissionResolver.hasPermission(any(), any(), any()) }
    }

    @Test
    fun `알려진 7종 권한 문자열은 각각 올바른 IssuePermission enum으로 위임된다`() {
        every { issuePermissionResolver.hasPermission(any(), any(), any()) } returns true

        val scope = IssueScope.Project("ATLAS")
        val mappings =
            mapOf(
                "BROWSE_PROJECT" to IssuePermission.BROWSE,
                "VIEW_ISSUE" to IssuePermission.VIEW,
                "CREATE_ISSUE" to IssuePermission.CREATE,
                "EDIT_ISSUE" to IssuePermission.UPDATE,
                "TRANSITION_ISSUE" to IssuePermission.TRANSITION,
                "DELETE_ISSUE" to IssuePermission.SOFT_DELETE,
                "SET_ISSUE_SECURITY" to IssuePermission.SET_SECURITY,
            )

        mappings.forEach { (permissionString, expectedEnum) ->
            resolver.hasPermission(actor, permissionString, Scope.Project("ATLAS"))
            verify(exactly = 1) { issuePermissionResolver.hasPermission(actorUuid, expectedEnum, scope) }
        }
    }
}
