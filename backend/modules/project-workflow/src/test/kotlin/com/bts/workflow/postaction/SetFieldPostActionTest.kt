// SetFieldPostAction 3 case — valid / placeholder ${now} / invalid

package com.bts.workflow.postaction

import com.bts.shared.workflow.TransitionRequest
import com.bts.workflow.domain.StateCategory
import com.bts.workflow.domain.Workflow
import com.bts.workflow.domain.WorkflowState
import com.bts.workflow.domain.WorkflowTransition
import com.bts.workflow.domain.dto.TransitionContext
import com.bts.workflow.domain.expression.DefaultActorView
import com.bts.workflow.domain.expression.DefaultIssueView
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * [SetFieldPostAction] 단위 테스트.
 *
 * 테스트 3건.
 * - valid — field + value 설정 시 FieldChange 1건 + emitEvents 0건 반환.
 * - placeholder — newValue 가 `${now}` 일 때 Instant 문자열로 치환.
 * - invalid — field 가 빈 문자열이면 IllegalArgumentException 발생.
 *
 * Spring 컨텍스트 없이 순수 단위 테스트로 실행한다.
 */
class SetFieldPostActionTest {
    // -------------------------------------------------------------------------
    // 헬퍼 — TransitionContext 를 최소한의 데이터로 생성한다.
    // -------------------------------------------------------------------------

    private fun buildContext(issueFields: Map<String, Any?> = emptyMap()): TransitionContext {
        val todo =
            WorkflowState(
                key = "TODO",
                name = "To Do",
                category = StateCategory.TODO,
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
                fromStateKey = "TODO",
                toStateKey = "DONE",
                name = "close",
            )
        val workflow =
            Workflow.of(
                key = "DEFAULT",
                name = "Default Workflow",
                states = listOf(todo, done),
                transitions = listOf(transition),
            )
        val request =
            TransitionRequest(
                workflowKey = "DEFAULT",
                issueKey = "BTS-1",
                fromStateKey = "TODO",
                toStateKey = "DONE",
                transitionName = "close",
                actorId = "user-42",
                issueFields = issueFields,
                actorRoles = setOf("DEVELOPER"),
                version = 1L,
            )
        return TransitionContext(
            request = request,
            workflow = workflow,
            fromState = todo,
            transition = transition,
            issueView =
                DefaultIssueView(
                    key = "BTS-1",
                    priority = "HIGH",
                    fields = issueFields,
                ),
            actorView =
                DefaultActorView(
                    userId = "user-42",
                    roles = setOf("DEVELOPER"),
                ),
        )
    }

    // -------------------------------------------------------------------------
    // 테스트 케이스
    // -------------------------------------------------------------------------

    @Test
    fun `valid — field + value 설정 시 fieldChanges 1건 emitEvents 0건을 반환한다`() {
        val action = SetFieldPostAction(field = "resolution", value = "Fixed")
        val ctx = buildContext(issueFields = mapOf("resolution" to "Open"))

        val plan = action.evaluate(ctx)

        assertThat(plan.fieldChanges).hasSize(1)
        assertThat(plan.emitEvents).isEmpty()

        val change = plan.fieldChanges[0]
        assertThat(change.field).isEqualTo("resolution")
        assertThat(change.oldValue).isEqualTo("Open")
        assertThat(change.newValue).isEqualTo("Fixed")
    }

    @Test
    fun `placeholder — newValue 가 now 이면 Instant 문자열로 치환된다`() {
        val action = SetFieldPostAction(field = "closedAt", value = "\${now}")
        val before = Instant.now()
        val ctx = buildContext(issueFields = emptyMap())

        val plan = action.evaluate(ctx)

        assertThat(plan.fieldChanges).hasSize(1)
        val newValue = plan.fieldChanges[0].newValue as String
        val parsed = Instant.parse(newValue)
        val after = Instant.now()

        assertThat(parsed).isBetween(before, after)
        assertThat(plan.fieldChanges[0].oldValue).isNull()
    }

    @Test
    fun `invalid — field 가 빈 문자열이면 IllegalArgumentException 이 발생한다`() {
        assertThatThrownBy {
            SetFieldPostAction(field = "", value = "any")
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("field")
    }
}
