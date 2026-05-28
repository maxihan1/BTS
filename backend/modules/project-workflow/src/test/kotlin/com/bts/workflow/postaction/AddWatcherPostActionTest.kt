// AddWatcherPostAction 단위 테스트 — valid config / invalid config 2 case

package com.bts.workflow.postaction

import com.bts.shared.workflow.DomainEvent
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

/**
 * [AddWatcherPostAction] 단위 테스트.
 *
 * 테스트 2건.
 * - valid — 유효한 watcher 설정 시 DomainEvent("WatcherAdded") 1건을 emitEvents 에 반환한다.
 * - invalid — 빈 watcher 값으로 생성 시 IllegalArgumentException 을 던진다.
 *
 * Spring 컨텍스트 없이 순수 단위 테스트로 실행한다.
 */
class AddWatcherPostActionTest {
    // -------------------------------------------------------------------------
    // 헬퍼 — TransitionContext 를 최소 데이터로 생성한다.
    // -------------------------------------------------------------------------

    private fun buildContext(actorId: String = "user-42"): TransitionContext {
        val todo =
            WorkflowState(
                key = "TODO",
                name = "To Do",
                category = StateCategory.TODO,
                displayOrder = 1,
            )
        val inProgress =
            WorkflowState(
                key = "IN_PROGRESS",
                name = "In Progress",
                category = StateCategory.IN_PROGRESS,
                displayOrder = 2,
            )
        val transition =
            WorkflowTransition(
                fromStateKey = "TODO",
                toStateKey = "IN_PROGRESS",
                name = "start",
            )
        val workflow =
            Workflow.of(
                key = "DEFAULT",
                name = "Default Workflow",
                states = listOf(todo, inProgress),
                transitions = listOf(transition),
            )
        val request =
            TransitionRequest(
                workflowKey = "DEFAULT",
                issueKey = "BTS-99",
                fromStateKey = "TODO",
                toStateKey = "IN_PROGRESS",
                actorId = actorId,
                issueFields = emptyMap(),
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
                    key = "BTS-99",
                    priority = "HIGH",
                    fields = emptyMap(),
                ),
            actorView =
                DefaultActorView(
                    userId = actorId,
                    roles = setOf("DEVELOPER"),
                ),
        )
    }

    // -------------------------------------------------------------------------
    // 테스트 케이스
    // -------------------------------------------------------------------------

    @Test
    fun `valid — watcher 가 고정 사용자 ID 일 때 WatcherAdded 이벤트 1건을 반환한다`() {
        val action = AddWatcherPostAction(watcher = "user-007")
        val ctx = buildContext()

        val plan = action.evaluate(ctx)

        assertThat(plan.fieldChanges).isEmpty()
        assertThat(plan.emitEvents).hasSize(1)
        val event: DomainEvent = plan.emitEvents[0]
        assertThat(event.type).isEqualTo("WatcherAdded")
        assertThat(event.payload["issueKey"]).isEqualTo("BTS-99")
        assertThat(event.payload["watcher"]).isEqualTo("user-007")
    }

    @Test
    fun `valid — watcher 가 actor 플레이스홀더일 때 actorId 로 치환된 이벤트를 반환한다`() {
        val action = AddWatcherPostAction(watcher = "\${actor}")
        val ctx = buildContext(actorId = "user-42")

        val plan = action.evaluate(ctx)

        assertThat(plan.emitEvents).hasSize(1)
        val event: DomainEvent = plan.emitEvents[0]
        assertThat(event.type).isEqualTo("WatcherAdded")
        assertThat(event.payload["issueKey"]).isEqualTo("BTS-99")
        assertThat(event.payload["watcher"]).isEqualTo("user-42")
    }

    @Test
    fun `invalid — 빈 watcher 로 생성 시 IllegalArgumentException 을 던진다`() {
        assertThatThrownBy { AddWatcherPostAction(watcher = "") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("watcher")
    }

    @Test
    fun `invalid — 공백만 있는 watcher 로 생성 시 IllegalArgumentException 을 던진다`() {
        assertThatThrownBy { AddWatcherPostAction(watcher = "   ") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("watcher")
    }
}
