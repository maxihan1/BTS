// NotStatusCategoryValidator 단위 테스트 — pass/fail/edge(enum 완전 검사) 3 케이스

package com.bts.workflow.validator

import com.bts.workflow.domain.StateCategory
import com.bts.workflow.domain.Workflow
import com.bts.workflow.domain.WorkflowState
import com.bts.workflow.domain.WorkflowTransition
import com.bts.workflow.domain.dto.TransitionContext
import com.bts.workflow.domain.dto.TransitionRequest
import com.bts.workflow.domain.expression.DefaultActorView
import com.bts.workflow.domain.expression.DefaultIssueView
import com.bts.workflow.domain.spi.ValidatorResult
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

/**
 * [NotStatusCategoryValidator] 단위 테스트.
 *
 * Spring 컨텍스트 없이 순수 단위 테스트로 실행한다.
 * 테스트 케이스.
 * - pass — fromState 카테고리가 forbidden 과 다르면 [com.bts.workflow.domain.spi.ValidatorResult.Pass] 반환.
 * - fail — fromState 카테고리가 forbidden 과 같으면 [com.bts.workflow.domain.spi.ValidatorResult.Fail] 반환.
 * - edge — [StateCategory] 모든 enum 값에 대해 forbidden 과 동일하면 Fail 임을 파라미터 테스트로 검증.
 */
class NotStatusCategoryValidatorTest {

    /**
     * 최소한의 TransitionContext 픽스처를 만든다.
     * fromState 의 category 만 테스트마다 다르게 주입한다.
     */
    private fun makeContext(fromCategory: StateCategory): TransitionContext {
        val fromState = WorkflowState(
            key = "S1",
            name = "State 1",
            category = fromCategory,
            displayOrder = 0,
        )
        val toState = WorkflowState(
            key = "S2",
            name = "State 2",
            category = StateCategory.IN_PROGRESS,
            displayOrder = 1,
        )
        val transition = WorkflowTransition(
            fromStateKey = fromState.key,
            toStateKey = toState.key,
            name = "Move",
        )
        val workflow = Workflow.of(
            key = "TEST-WF",
            name = "Test Workflow",
            states = listOf(fromState, toState),
            transitions = listOf(transition),
        )
        val request = TransitionRequest(
            workflowKey = "TEST-WF",
            issueKey = "BTS-1",
            fromStateKey = fromState.key,
            toStateKey = toState.key,
            transitionName = transition.name,
            actorId = "user-1",
            issueFields = emptyMap(),
            actorRoles = emptySet(),
            version = 1L,
        )
        return TransitionContext(
            request = request,
            workflow = workflow,
            fromState = fromState,
            transition = transition,
            issueView = DefaultIssueView(key = "BTS-1", priority = "MEDIUM", fields = emptyMap()),
            actorView = DefaultActorView(userId = "user-1", roles = emptySet()),
        )
    }

    @Test
    fun `pass — fromState 카테고리가 forbidden 과 다르면 Pass 를 반환한다`() {
        val validator = NotStatusCategoryValidator(forbidden = StateCategory.DONE)
        val ctx = makeContext(fromCategory = StateCategory.TODO)

        val result = validator.validate(ctx)

        assertThat(result as Any).isEqualTo(ValidatorResult.Pass)
    }

    @Test
    fun `fail — fromState 카테고리가 forbidden 과 같으면 Fail 을 반환한다`() {
        val validator = NotStatusCategoryValidator(forbidden = StateCategory.DONE)
        val ctx = makeContext(fromCategory = StateCategory.DONE)

        val result = validator.validate(ctx)

        assertThat(result as Any).isInstanceOf(ValidatorResult.Fail::class.java)
        val fail = result as ValidatorResult.Fail
        assertThat(fail.field).isNull()
        assertThat(fail.reason).isEqualTo("from state category 'DONE' is forbidden")
    }

    /**
     * edge — StateCategory 의 모든 enum 값을 한 번씩 forbidden 으로 지정해,
     * forbidden 과 동일한 카테고리일 때 항상 Fail 을 반환함을 검증한다.
     * 새 카테고리 값이 추가되어도 자동으로 커버된다.
     */
    @ParameterizedTest(name = "edge — forbidden={0} 일 때 동일 카테고리는 Fail")
    @EnumSource(StateCategory::class)
    fun `edge — 모든 StateCategory enum 값에 대해 forbidden 과 같으면 Fail 이다`(category: StateCategory) {
        val validator = NotStatusCategoryValidator(forbidden = category)
        val ctx = makeContext(fromCategory = category)

        val result = validator.validate(ctx)

        assertThat(result as Any).isInstanceOf(ValidatorResult.Fail::class.java)
    }
}
