// WorkflowTransitionAdapter 단위 테스트 — 4 시나리오
// (S1 Success / S2 ValidatorFailure / S3 WorkflowNotFound / S4 ExpressionTimeout)

package com.bts.workflow.adapter.inbound

import com.bts.shared.workflow.TransitionPlan
import com.bts.shared.workflow.TransitionRequest
import com.bts.shared.workflow.TransitionResult
import com.bts.workflow.domain.exception.WorkflowExpressionTimeoutException
import com.bts.workflow.domain.exception.WorkflowNotFoundException
import com.bts.workflow.domain.exception.WorkflowValidatorFailureException
import com.bts.workflow.engine.WorkflowEngine
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * [WorkflowTransitionAdapter] 단위 테스트.
 *
 * WorkflowEngine 을 mockk 로 대체하여 Spring 컨텍스트 없이 실행한다.
 * 트랜잭션 MANDATORY 계약은 [com.bts.workflow.port.inbound.WorkflowTransitionPortContractTest] 가 보장한다.
 *
 * 테스트 시나리오.
 * - S1. WorkflowEngine.plan 이 TransitionPlan 을 반환 → TransitionResult.Success(plan) 반환.
 * - S2. WorkflowEngine 이 WorkflowValidatorFailureException throw → TransitionResult.ValidatorFailure(message) 반환.
 * - S3. WorkflowEngine 이 WorkflowNotFoundException throw → TransitionResult.WorkflowNotFound(key) 반환.
 * - S4. WorkflowEngine 이 WorkflowExpressionTimeoutException throw → TransitionResult.ExpressionTimeout(message) 반환.
 */
class WorkflowTransitionAdapterTest {
    private val mockEngine: WorkflowEngine = mockk()
    private lateinit var adapter: WorkflowTransitionAdapter

    private val validReq =
        TransitionRequest(
            workflowKey = "DEFAULT",
            issueKey = "BTS-1",
            fromStateKey = "TODO",
            toStateKey = "IN_PROGRESS",
            actorId = "user-001",
            issueFields = emptyMap(),
            actorRoles = setOf("MEMBER"),
            version = 1L,
        )

    private val samplePlan =
        TransitionPlan(
            toStateKey = "IN_PROGRESS",
            fieldChanges = emptyList(),
            emitEvents = emptyList(),
        )

    @BeforeEach
    fun setUp() {
        adapter = WorkflowTransitionAdapter(mockEngine)
    }

    // ── S1. Success ──────────────────────────────────────────────────────────

    @Test
    fun `S1 — WorkflowEngine plan 이 정상 반환되면 TransitionResult Success 를 반환한다`() {
        every { mockEngine.plan(validReq) } returns samplePlan

        val result = adapter.plan(validReq)

        assertThat(result).isInstanceOf(TransitionResult.Success::class.java)
        val success = result as TransitionResult.Success
        assertThat(success.plan).isEqualTo(samplePlan)
        verify(exactly = 1) { mockEngine.plan(validReq) }
    }

    // ── S2. ValidatorFailure ─────────────────────────────────────────────────

    @Test
    fun `S2 — WorkflowEngine 이 WorkflowValidatorFailureException throw 시 TransitionResult ValidatorFailure 를 반환한다`() {
        val exception =
            WorkflowValidatorFailureException(
                validatorType = "RequiredField",
                field = "assignee",
                reason = "조건 X 위반",
            )
        every { mockEngine.plan(validReq) } throws exception

        val result = adapter.plan(validReq)

        assertThat(result).isInstanceOf(TransitionResult.ValidatorFailure::class.java)
        val failure = result as TransitionResult.ValidatorFailure
        assertThat(failure.message).isEqualTo(exception.message)
    }

    // ── S3. WorkflowNotFound ─────────────────────────────────────────────────

    @Test
    fun `S3 — WorkflowEngine 이 WorkflowNotFoundException throw 시 TransitionResult WorkflowNotFound 를 반환한다`() {
        val exception = WorkflowNotFoundException(workflowKey = "UNKNOWN")
        every { mockEngine.plan(validReq) } throws exception

        val result = adapter.plan(validReq)

        assertThat(result).isInstanceOf(TransitionResult.WorkflowNotFound::class.java)
        val notFound = result as TransitionResult.WorkflowNotFound
        assertThat(notFound.key).isEqualTo("UNKNOWN")
    }

    // ── S4. ExpressionTimeout ────────────────────────────────────────────────

    @Test
    fun `S4 — WorkflowEngine 이 WorkflowExpressionTimeoutException throw 시 TransitionResult ExpressionTimeout 를 반환한다`() {
        val exception =
            WorkflowExpressionTimeoutException(
                expression = "issue.priority == 'HIGH'",
                timeoutMillis = 500L,
            )
        every { mockEngine.plan(validReq) } throws exception

        val result = adapter.plan(validReq)

        assertThat(result).isInstanceOf(TransitionResult.ExpressionTimeout::class.java)
        val timeout = result as TransitionResult.ExpressionTimeout
        assertThat(timeout.message).isEqualTo(exception.message)
    }
}
