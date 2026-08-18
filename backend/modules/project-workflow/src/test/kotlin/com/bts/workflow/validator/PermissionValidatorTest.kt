// PermissionValidator — mockk(PermissionResolver) 3 case (true / false / Scope 분기)

package com.bts.workflow.validator

import com.bts.shared.workflow.TransitionRequest
import com.bts.workflow.domain.Workflow
import com.bts.workflow.domain.WorkflowState
import com.bts.workflow.domain.WorkflowTransition
import com.bts.workflow.domain.dto.TransitionContext
import com.bts.workflow.domain.expression.ActorView
import com.bts.workflow.domain.expression.IssueView
import com.bts.workflow.domain.spi.ValidatorResult
import com.bts.workflow.port.outbound.ActorId
import com.bts.workflow.port.outbound.PermissionResolver
import com.bts.workflow.port.outbound.Scope
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * PermissionValidator 단위 테스트.
 *
 * PermissionResolver 를 mockk 로 대체해 PermissionValidator 의 판정 로직만 격리 검증한다.
 * 테스트 케이스 3건.
 * 1. pass — resolver 가 true 반환 시 ValidatorResult.Pass
 * 2. fail — resolver 가 false 반환 시 ValidatorResult.Fail (permission denied 메시지 포함)
 * 3. scope 분기 — Scope.Issue(issueKey) / Scope.Project(workflowKey) 둘 다 호출 가능
 */
class PermissionValidatorTest {
    private val resolver: PermissionResolver = mockk()

    companion object {
        /** 테스트 픽스처 actor UUID sentinel. ActorId VO UUID 형식 강제 (CONCERN-2). */
        const val ACTOR_UUID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
    }

    // ── 공통 픽스처 ──────────────────────────────────────────────────────────────

    private fun buildContext(
        actorId: String = ACTOR_UUID,
        issueKey: String = "BTS-1",
        workflowKey: String = "DEFAULT",
    ): TransitionContext {
        val request =
            TransitionRequest(
                workflowKey = workflowKey,
                issueKey = issueKey,
                fromStateKey = "TODO",
                toStateKey = "IN_PROGRESS",
                actorId = actorId,
                issueFields = emptyMap(),
                actorRoles = emptySet(),
                version = 1L,
            )
        val workflow = mockk<Workflow>(relaxed = true)
        val fromState = mockk<WorkflowState>(relaxed = true)
        val transition = mockk<WorkflowTransition>(relaxed = true)
        val issueView = mockk<IssueView>(relaxed = true)
        val actorView = mockk<ActorView>(relaxed = true)
        return TransitionContext(request, workflow, fromState, transition, issueView, actorView)
    }

    // ── 케이스 1. pass ──────────────────────────────────────────────────────────

    @Test
    fun `resolver 가 true 반환 시 Pass 를 반환한다`() {
        val permission = "TRANSITION_ISSUE"
        val validator = PermissionValidator(resolver, permission)
        val ctx = buildContext()

        every {
            resolver.hasPermission(ActorId(ACTOR_UUID), permission, Scope.Issue("BTS-1"))
        } returns true

        val result = validator.validate(ctx)

        assertThat(result).isEqualTo(ValidatorResult.Pass)
    }

    // ── 케이스 2. fail ──────────────────────────────────────────────────────────

    @Test
    fun `resolver 가 false 반환 시 Fail 을 반환하고 permission 이름을 reason 에 포함한다`() {
        val permission = "TRANSITION_ISSUE"
        val validator = PermissionValidator(resolver, permission)
        val ctx = buildContext()

        every {
            resolver.hasPermission(ActorId(ACTOR_UUID), permission, Scope.Issue("BTS-1"))
        } returns false

        val result = validator.validate(ctx)

        assertThat(result).isInstanceOf(ValidatorResult.Fail::class.java)
        val fail = result as ValidatorResult.Fail
        assertThat(fail.field).isNull()
        assertThat(fail.reason).contains("permission denied")
        assertThat(fail.reason).contains(permission)
    }

    // ── 케이스 3. Scope 분기 ────────────────────────────────────────────────────

    @Test
    fun `scope issue 기본값은 Scope Issue(issueKey) 로 resolver 를 호출한다`() {
        val permission = "TRANSITION_ISSUE"
        val validator = PermissionValidator(resolver, permission) // scope 미지정 → 기본 Issue
        val ctx = buildContext(issueKey = "BTS-42")

        every {
            resolver.hasPermission(ActorId(ACTOR_UUID), permission, Scope.Issue("BTS-42"))
        } returns true

        validator.validate(ctx)

        verify(exactly = 1) {
            resolver.hasPermission(ActorId(ACTOR_UUID), permission, Scope.Issue("BTS-42"))
        }
    }

    @Test
    fun `scope project 지정 시 Scope Project(workflowKey) 로 resolver 를 호출한다`() {
        val permission = "ADMIN_WORKFLOW"
        val validator = PermissionValidator(resolver, permission, scope = ValidatorScope.PROJECT)
        val ctx = buildContext(workflowKey = "CUSTOM")

        every {
            resolver.hasPermission(ActorId(ACTOR_UUID), permission, Scope.Project("CUSTOM"))
        } returns true

        validator.validate(ctx)

        verify(exactly = 1) {
            resolver.hasPermission(ActorId(ACTOR_UUID), permission, Scope.Project("CUSTOM"))
        }
    }

    // ── 케이스 4. resolver 예외 → 안전 Fail ────────────────────────────────────

    @Test
    fun `resolver 에서 예외 발생 시 Fail 을 반환하고 전환을 차단한다`() {
        val permission = "TRANSITION_ISSUE"
        val validator = PermissionValidator(resolver, permission)
        val ctx = buildContext()

        every {
            // ActorId 는 UUID 강제 value class — MockK 가 any() 로 매칭하면 임의값으로 생성하다 검증 실패한다.
            // 구체 ActorId(ACTOR_UUID) 로 매칭 (ctx 가 buildContext 기본 actor 이므로 실제 호출과 일치).
            resolver.hasPermission(ActorId(ACTOR_UUID), any(), any())
        } throws RuntimeException("downstream unavailable")

        val result = validator.validate(ctx)

        assertThat(result).isInstanceOf(ValidatorResult.Fail::class.java)
        val fail = result as ValidatorResult.Fail
        assertThat(fail.reason).contains("permission resolver error")
    }
}
