// RunAutomationPostAction 2 case — valid automationKey / invalid 빈 문자열

package com.bts.workflow.postaction

import com.bts.workflow.domain.StateCategory
import com.bts.workflow.domain.Workflow
import com.bts.workflow.domain.WorkflowState
import com.bts.workflow.domain.WorkflowTransition
import com.bts.workflow.domain.dto.TransitionContext
import com.bts.workflow.domain.dto.TransitionRequest
import com.bts.workflow.domain.expression.DefaultActorView
import com.bts.workflow.domain.expression.DefaultIssueView
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * [RunAutomationPostAction] 단위 테스트.
 *
 * 테스트 2건.
 * - valid — automationKey 가 지정되면 emitEvents 1건(type = "AutomationRequested"),
 *   payload 에 issueKey + automationKey 포함, fieldChanges 0건.
 * - invalid — automationKey 가 빈 문자열이면 생성 시점에 IllegalArgumentException 발생.
 *
 * Spring 컨텍스트 없이 순수 단위 테스트로 실행한다.
 */
class RunAutomationPostActionTest {

    // -------------------------------------------------------------------------
    // 헬퍼 — TransitionContext 를 최소한의 데이터로 생성한다.
    // -------------------------------------------------------------------------

    private fun buildContext(issueKey: String = "BTS-1"): TransitionContext {
        val todo = WorkflowState(
            key = "TODO",
            name = "To Do",
            category = StateCategory.TODO,
            displayOrder = 1,
        )
        val inProgress = WorkflowState(
            key = "IN_PROGRESS",
            name = "In Progress",
            category = StateCategory.IN_PROGRESS,
            displayOrder = 2,
        )
        val transition = WorkflowTransition(
            fromStateKey = "TODO",
            toStateKey = "IN_PROGRESS",
            name = "start",
        )
        val workflow = Workflow.of(
            key = "DEFAULT",
            name = "Default Workflow",
            states = listOf(todo, inProgress),
            transitions = listOf(transition),
        )
        val request = TransitionRequest(
            workflowKey = "DEFAULT",
            issueKey = issueKey,
            fromStateKey = "TODO",
            toStateKey = "IN_PROGRESS",
            transitionName = "start",
            actorId = "user-42",
            issueFields = emptyMap(),
            actorRoles = setOf("DEVELOPER"),
            version = 1L,
        )
        return TransitionContext(
            request = request,
            workflow = workflow,
            fromState = todo,
            transition = transition,
            issueView = DefaultIssueView(
                key = issueKey,
                priority = "HIGH",
                fields = emptyMap(),
            ),
            actorView = DefaultActorView(
                userId = "user-42",
                roles = setOf("DEVELOPER"),
            ),
        )
    }

    // -------------------------------------------------------------------------
    // 케이스 1. valid
    // -------------------------------------------------------------------------

    @Test
    fun `valid — automationKey 지정 시 emitEvents 1건 fieldChanges 0건을 반환한다`() {
        val action = RunAutomationPostAction(automationKey = "auto-close-stale")
        val ctx = buildContext(issueKey = "BTS-99")

        val plan = action.evaluate(ctx)

        assertThat(plan.fieldChanges).isEmpty()
        assertThat(plan.emitEvents).hasSize(1)

        val event = plan.emitEvents[0]
        assertThat(event.type).isEqualTo("AutomationRequested")
        assertThat(event.payload["issueKey"]).isEqualTo("BTS-99")
        assertThat(event.payload["automationKey"]).isEqualTo("auto-close-stale")
    }

    // -------------------------------------------------------------------------
    // 케이스 2. invalid
    // -------------------------------------------------------------------------

    @Test
    fun `invalid — automationKey 가 빈 문자열이면 IllegalArgumentException 이 발생한다`() {
        assertThatThrownBy {
            RunAutomationPostAction(automationKey = "")
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("automationKey")
    }
}
