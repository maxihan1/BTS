// 3 도메인 예외 구조화 메시지 + 필드 검증

package com.bts.workflow.domain.exception

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * 도메인 예외 3종의 구조화 필드 및 메시지 포맷 검증.
 *
 * 검증 대상.
 * 1. [WorkflowValidatorFailureException] — validatorType / field(nullable) / reason 필드 + 메시지 포맷
 * 2. [WorkflowNotFoundException] — workflowKey 필드 + 메시지 포맷
 * 3. [WorkflowExpressionTimeoutException] — expression / timeoutMillis 필드 + cause 전파 + 메시지 포맷
 */
class WorkflowExceptionsTest {

    // ── WorkflowValidatorFailureException ────────────────────────────────────

    @Test
    fun `WorkflowValidatorFailureException 은 RuntimeException 상속`() {
        val ex = WorkflowValidatorFailureException(
            validatorType = "RequiredFieldValidator",
            field = "assignee",
            reason = "must not be null",
        )
        assertThat(ex is RuntimeException).isTrue()
    }

    @Test
    fun `WorkflowValidatorFailureException field 있을 때 메시지에 validatorType, field, reason 모두 포함`() {
        val ex = WorkflowValidatorFailureException(
            validatorType = "RequiredFieldValidator",
            field = "assignee",
            reason = "must not be null",
        )

        assertThat(ex.validatorType).isEqualTo("RequiredFieldValidator")
        assertThat(ex.field).isEqualTo("assignee")
        assertThat(ex.reason).isEqualTo("must not be null")
        // plan 명세 메시지 포맷: "Validator '...' failed on field '...': ..."
        assertThat(ex.message).isEqualTo(
            "Validator 'RequiredFieldValidator' failed on field 'assignee': must not be null",
        )
    }

    @Test
    fun `WorkflowValidatorFailureException field null 일 때 메시지에 field 구문 없음`() {
        val ex = WorkflowValidatorFailureException(
            validatorType = "PermissionValidator",
            field = null,
            reason = "user lacks TRANSITION permission",
        )

        assertThat(ex.field).isNull()
        // plan 명세 메시지 포맷: "Validator '...' failed: ..." (field 구문 없음)
        assertThat(ex.message).isEqualTo(
            "Validator 'PermissionValidator' failed: user lacks TRANSITION permission",
        )
    }

    // ── WorkflowNotFoundException ─────────────────────────────────────────────

    @Test
    fun `WorkflowNotFoundException 은 RuntimeException 상속`() {
        val ex = WorkflowNotFoundException(workflowKey = "WF-MISSING")
        assertThat(ex is RuntimeException).isTrue()
    }

    @Test
    fun `WorkflowNotFoundException workflowKey 필드 + 메시지에 key 포함`() {
        val ex = WorkflowNotFoundException(workflowKey = "WF-MISSING")

        assertThat(ex.workflowKey).isEqualTo("WF-MISSING")
        assertThat(ex.message).contains("WF-MISSING")
    }

    // ── WorkflowExpressionTimeoutException ────────────────────────────────────

    @Test
    fun `WorkflowExpressionTimeoutException 은 RuntimeException 상속`() {
        val ex = WorkflowExpressionTimeoutException(
            expression = "#issue.priority == 'HIGH'",
            timeoutMillis = 500L,
        )
        assertThat(ex is RuntimeException).isTrue()
    }

    @Test
    fun `WorkflowExpressionTimeoutException expression, timeoutMillis 필드 + 메시지에 포함`() {
        val ex = WorkflowExpressionTimeoutException(
            expression = "#issue.priority == 'HIGH'",
            timeoutMillis = 500L,
        )

        assertThat(ex.expression).isEqualTo("#issue.priority == 'HIGH'")
        assertThat(ex.timeoutMillis).isEqualTo(500L)
        assertThat(ex.cause).isNull()
        // plan 명세 메시지 포맷: "SpEL expression evaluation exceeded ...ms: '...'"
        assertThat(ex.message).isEqualTo(
            "SpEL expression evaluation exceeded 500ms: '#issue.priority == 'HIGH''",
        )
    }

    @Test
    fun `WorkflowExpressionTimeoutException cause 전파됨`() {
        val root = RuntimeException("interrupted")
        val ex = WorkflowExpressionTimeoutException(
            expression = "someExpr",
            timeoutMillis = 200L,
            cause = root,
        )

        assertThat(ex.cause).isSameAs(root)
    }
}
