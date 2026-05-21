// CallWebhookPostAction 2 case — valid / invalid url

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
 * [CallWebhookPostAction] 단위 테스트.
 *
 * 테스트 2건.
 * - valid — url + method 설정 시 DomainEvent("WebhookRequested") 1건 반환, HTTP 실제 호출 없음.
 * - invalid — url 이 빈 문자열이면 IllegalArgumentException 발생.
 *
 * Spring 컨텍스트 없이 순수 단위 테스트로 실행한다.
 */
class CallWebhookPostActionTest {
    // -------------------------------------------------------------------------
    // 헬퍼 — TransitionContext 를 최소한의 데이터로 생성한다.
    // -------------------------------------------------------------------------

    private fun buildContext(): TransitionContext {
        val inProgress =
            WorkflowState(
                key = "IN_PROGRESS",
                name = "In Progress",
                category = StateCategory.IN_PROGRESS,
                displayOrder = 1,
            )
        val done =
            WorkflowState(
                key = "DONE",
                name = "Done",
                category = StateCategory.DONE,
                displayOrder = 2,
            )
        val transition =
            WorkflowTransition(
                fromStateKey = "IN_PROGRESS",
                toStateKey = "DONE",
                name = "resolve",
            )
        val workflow =
            Workflow.of(
                key = "DEFAULT",
                name = "Default Workflow",
                states = listOf(inProgress, done),
                transitions = listOf(transition),
            )
        val request =
            TransitionRequest(
                workflowKey = "DEFAULT",
                issueKey = "BTS-42",
                fromStateKey = "IN_PROGRESS",
                toStateKey = "DONE",
                transitionName = "resolve",
                actorId = "user-1",
                issueFields = emptyMap(),
                actorRoles = setOf("DEVELOPER"),
                version = 1L,
            )
        return TransitionContext(
            request = request,
            workflow = workflow,
            fromState = inProgress,
            transition = transition,
            issueView =
                DefaultIssueView(
                    key = "BTS-42",
                    priority = "MEDIUM",
                    fields = emptyMap(),
                ),
            actorView =
                DefaultActorView(
                    userId = "user-1",
                    roles = setOf("DEVELOPER"),
                ),
        )
    }

    // -------------------------------------------------------------------------
    // 테스트 케이스
    // -------------------------------------------------------------------------

    @Test
    fun `valid — url + method 설정 시 WebhookRequested 이벤트 1건을 반환한다`() {
        val action =
            CallWebhookPostAction(
                url = "https://example.com/hook",
                method = "POST",
            )
        val ctx = buildContext()

        val plan = action.evaluate(ctx)

        assertThat(plan.fieldChanges).isEmpty()
        assertThat(plan.emitEvents).hasSize(1)

        val event = plan.emitEvents[0]
        assertThat(event.type).isEqualTo("WebhookRequested")
        assertThat(event.payload["issueKey"]).isEqualTo("BTS-42")
        assertThat(event.payload["url"]).isEqualTo("https://example.com/hook")
        assertThat(event.payload["method"]).isEqualTo("POST")
    }

    @Test
    fun `invalid — url 이 빈 문자열이면 IllegalArgumentException 이 발생한다`() {
        assertThatThrownBy {
            CallWebhookPostAction(url = "", method = "POST")
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("url")
    }
}
