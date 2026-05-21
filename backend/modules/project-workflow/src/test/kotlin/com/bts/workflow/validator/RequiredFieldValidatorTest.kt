// RequiredFieldValidator 3 case — pass / null / whitespace

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

/**
 * [RequiredFieldValidator] 단위 테스트.
 *
 * 테스트 3건.
 * - pass — 필드 값이 채워진 경우 ValidatorResult.Pass 반환.
 * - null — 필드 자체가 null 인 경우 ValidatorResult.Fail 반환.
 * - edge — whitespace 또는 empty string 인 경우 ValidatorResult.Fail 반환.
 *
 * Spring 컨텍스트 없이 순수 단위 테스트로 실행한다.
 */
class RequiredFieldValidatorTest {

    /** config = `{"field": "resolution"}` 에 해당하는 Validator 인스턴스. */
    private val validator = RequiredFieldValidator(field = "resolution")

    // -------------------------------------------------------------------------
    // 헬퍼 — TransitionContext 를 최소한의 데이터로 생성한다.
    // -------------------------------------------------------------------------

    private fun buildContext(issueFields: Map<String, Any?>): TransitionContext {
        val inProgress = WorkflowState(
            key = "IN_PROGRESS",
            name = "In Progress",
            category = StateCategory.IN_PROGRESS,
            displayOrder = 1,
        )
        val done = WorkflowState(
            key = "DONE",
            name = "Done",
            category = StateCategory.DONE,
            displayOrder = 2,
        )
        val transition = WorkflowTransition(
            fromStateKey = "IN_PROGRESS",
            toStateKey = "DONE",
            name = "resolve",
        )
        val workflow = Workflow.of(
            key = "DEFAULT",
            name = "Default Workflow",
            states = listOf(inProgress, done),
            transitions = listOf(transition),
        )
        val request = TransitionRequest(
            workflowKey = "DEFAULT",
            issueKey = "BTS-1",
            fromStateKey = "IN_PROGRESS",
            toStateKey = "DONE",
            transitionName = "resolve",
            actorId = "user-1",
            issueFields = issueFields,
            actorRoles = setOf("DEVELOPER"),
            version = 1L,
        )
        return TransitionContext(
            request = request,
            workflow = workflow,
            fromState = inProgress,
            transition = transition,
            issueView = DefaultIssueView(
                key = "BTS-1",
                priority = "MEDIUM",
                fields = issueFields,
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
    fun `pass — resolution 필드가 채워진 경우 Pass 를 반환한다`() {
        val ctx = buildContext(issueFields = mapOf("resolution" to "Fixed"))
        val result = validator.validate(ctx)
        assertThat(result).isEqualTo(ValidatorResult.Pass)
    }

    @Test
    fun `null — resolution 필드가 null 인 경우 Fail 을 반환한다`() {
        val ctx = buildContext(issueFields = mapOf("resolution" to null))
        val result = validator.validate(ctx)
        assertThat(result).isInstanceOf(ValidatorResult.Fail::class.java)
        val fail = result as ValidatorResult.Fail
        assertThat(fail.field).isEqualTo("resolution")
        assertThat(fail.reason).isNotBlank()
    }

    @Test
    fun `edge — resolution 필드가 whitespace 또는 empty string 인 경우 Fail 을 반환한다`() {
        listOf("", "   ", "\t", "\n").forEach { blankValue ->
            val ctx = buildContext(issueFields = mapOf("resolution" to blankValue))
            val result = validator.validate(ctx)
            assertThat(result)
                .withFailMessage("blank value '%s' 는 Fail 이어야 한다", blankValue)
                .isInstanceOf(ValidatorResult.Fail::class.java)
            val fail = result as ValidatorResult.Fail
            assertThat(fail.field).isEqualTo("resolution")
            assertThat(fail.reason).isNotBlank()
        }
    }
}
