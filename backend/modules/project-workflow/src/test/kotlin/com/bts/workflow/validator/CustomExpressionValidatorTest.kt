// CustomExpressionValidator 3 case — pass / fail / timeout (mockk SpelEvaluator)

package com.bts.workflow.validator

import com.bts.shared.workflow.TransitionRequest
import com.bts.workflow.domain.StateCategory
import com.bts.workflow.domain.Workflow
import com.bts.workflow.domain.WorkflowState
import com.bts.workflow.domain.WorkflowTransition
import com.bts.workflow.domain.dto.TransitionContext
import com.bts.workflow.domain.exception.WorkflowExpressionTimeoutException
import com.bts.workflow.domain.expression.DefaultActorView
import com.bts.workflow.domain.expression.DefaultIssueView
import com.bts.workflow.domain.spi.ValidatorResult
import com.bts.workflow.expression.SpelEvaluator
import com.bts.workflow.expression.SpelRoot
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * [CustomExpressionValidator] 단위 테스트.
 *
 * SpelEvaluator 를 mockk 으로 교체해 SpEL 평가 결과를 결정적으로 제어한다.
 * Spring 컨텍스트 없이 순수 단위 테스트로 실행한다.
 *
 * 테스트 3건.
 * - pass — SpEL 평가 결과 true 이면 ValidatorResult.Pass 반환.
 * - fail — SpEL 평가 결과 false 이면 ValidatorResult.Fail(field=null, reason="expression evaluated to false") 반환.
 * - edge — SpelEvaluator 가 WorkflowExpressionTimeoutException 을 던지면
 *   ValidatorResult.Fail(field=null, reason="expression_timeout") 으로 변환하고 예외를 전파하지 않는다.
 */
class CustomExpressionValidatorTest {
    private val evaluator: SpelEvaluator = mockk()
    private val expression = "issue.priority == 'HIGH'"

    private val validator =
        CustomExpressionValidator(
            evaluator = evaluator,
            expression = expression,
        )

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
                issueKey = "BTS-1",
                fromStateKey = "IN_PROGRESS",
                toStateKey = "DONE",
                transitionName = "resolve",
                actorId = "user-1",
                issueFields = mapOf("priority" to "HIGH"),
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
                    key = "BTS-1",
                    priority = "HIGH",
                    fields = mapOf("priority" to "HIGH"),
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
    fun `pass — SpEL 평가 결과 true 이면 Pass 를 반환한다`() {
        every { evaluator.evaluate(expression, any<SpelRoot>()) } returns true

        val result = validator.validate(buildContext())

        assertThat(result as Any).isEqualTo(ValidatorResult.Pass)
    }

    @Test
    fun `fail — SpEL 평가 결과 false 이면 Fail(field=null, reason=expression evaluated to false) 를 반환한다`() {
        every { evaluator.evaluate(expression, any<SpelRoot>()) } returns false

        val result = validator.validate(buildContext())

        assertThat(result as Any).isInstanceOf(ValidatorResult.Fail::class.java)
        val fail = result as ValidatorResult.Fail
        assertThat(fail.field).isNull()
        assertThat(fail.reason).isEqualTo("expression evaluated to false")
    }

    @Test
    fun `edge — SpelEvaluator 가 WorkflowExpressionTimeoutException 을 던지면 Fail(reason=expression_timeout) 으로 변환한다`() {
        val timeoutEx =
            WorkflowExpressionTimeoutException(
                expression = expression,
                timeoutMillis = 50L,
            )
        every { evaluator.evaluate(expression, any<SpelRoot>()) } throws timeoutEx

        val result = validator.validate(buildContext())

        assertThat(result as Any).isInstanceOf(ValidatorResult.Fail::class.java)
        val fail = result as ValidatorResult.Fail
        assertThat(fail.field).isNull()
        assertThat(fail.reason).isEqualTo("expression_timeout")
    }
}
