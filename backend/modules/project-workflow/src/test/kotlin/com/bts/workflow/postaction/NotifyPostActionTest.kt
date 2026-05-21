// NotifyPostAction 단위 테스트 — valid(channel+recipients) / invalid(channel 부재)

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
 * [NotifyPostAction] 단위 테스트.
 *
 * 테스트 2건.
 * - valid — channel 과 recipients 가 주어졌을 때 DomainEvent("NotificationRequested") 를 정확한 payload 로 반환한다.
 * - invalid — channel 이 blank 일 때 IllegalArgumentException 을 던진다.
 *
 * Spring 컨텍스트 없이 순수 단위 테스트로 실행한다.
 */
class NotifyPostActionTest {

    // -------------------------------------------------------------------------
    // 헬퍼 — TransitionContext 를 최소한의 데이터로 생성한다.
    // -------------------------------------------------------------------------

    private fun buildContext(issueKey: String = "BTS-42"): TransitionContext {
        val todo = WorkflowState(
            key = "TODO",
            name = "Todo",
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
            actorId = "user-1",
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
                priority = "MEDIUM",
                fields = emptyMap(),
            ),
            actorView = DefaultActorView(
                userId = "user-1",
                roles = setOf("DEVELOPER"),
            ),
        )
    }

    // -------------------------------------------------------------------------
    // 테스트 케이스
    // -------------------------------------------------------------------------

    @Test
    fun `valid — channel 과 recipients 가 주어졌을 때 NotificationRequested 이벤트를 반환한다`() {
        val action = NotifyPostAction(channel = "slack", recipients = "team-dev")
        val ctx = buildContext(issueKey = "BTS-42")

        val plan = action.evaluate(ctx)

        assertThat(plan.fieldChanges).isEmpty()
        assertThat(plan.emitEvents).hasSize(1)

        val event = plan.emitEvents[0]
        assertThat(event.type).isEqualTo("NotificationRequested")
        assertThat(event.payload["issueKey"]).isEqualTo("BTS-42")
        assertThat(event.payload["channel"]).isEqualTo("slack")
        assertThat(event.payload["recipients"]).isEqualTo("team-dev")
    }

    @Test
    fun `invalid — channel 이 blank 이면 IllegalArgumentException 을 던진다`() {
        assertThatThrownBy { NotifyPostAction(channel = "  ", recipients = "team-dev") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("channel")
    }
}
