// TransitionResult sealed interface 단위 테스트 — 4 케이스 생성 + when exhaustive 컴파일 검증

package com.bts.workflow.domain.dto

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * [TransitionResult] sealed interface 단위 테스트.
 *
 * Spring 컨텍스트 없이 순수 단위 테스트로 실행한다.
 * 테스트 케이스.
 * - Success — [TransitionPlan] 을 wrap 한 Success 인스턴스 생성 및 프로퍼티 확인.
 * - ValidatorFailure — message 를 보유한 ValidatorFailure 인스턴스 생성 및 프로퍼티 확인.
 * - WorkflowNotFound — key 를 보유한 WorkflowNotFound 인스턴스 생성 및 프로퍼티 확인.
 * - ExpressionTimeout — message 를 보유한 ExpressionTimeout 인스턴스 생성 및 프로퍼티 확인.
 * - when exhaustive — when 식이 모든 sealed 케이스를 빠짐없이 커버하여 컴파일이 통과함을 검증.
 */
class TransitionResultTest {
    // ── 픽스처 ────────────────────────────────────────────────────────────────

    private val samplePlan =
        TransitionPlan(
            toStateKey = "IN_PROGRESS",
            fieldChanges = emptyList(),
            emitEvents = emptyList(),
        )

    // ── Success ───────────────────────────────────────────────────────────────

    @Test
    fun `Success — TransitionPlan 을 wrap 한 인스턴스가 정상 생성된다`() {
        val result: TransitionResult = TransitionResult.Success(plan = samplePlan)

        assertThat(result).isInstanceOf(TransitionResult.Success::class.java)
        val success = result as TransitionResult.Success
        assertThat(success.plan.toStateKey).isEqualTo("IN_PROGRESS")
        assertThat(success.plan.fieldChanges).isEmpty()
        assertThat(success.plan.emitEvents).isEmpty()
    }

    // ── ValidatorFailure ──────────────────────────────────────────────────────

    @Test
    fun `ValidatorFailure — message 를 보유한 인스턴스가 정상 생성된다`() {
        val message = "assignee 필드는 필수입니다"
        val result: TransitionResult = TransitionResult.ValidatorFailure(message = message)

        assertThat(result).isInstanceOf(TransitionResult.ValidatorFailure::class.java)
        val failure = result as TransitionResult.ValidatorFailure
        assertThat(failure.message).isEqualTo(message)
    }

    // ── WorkflowNotFound ──────────────────────────────────────────────────────

    @Test
    fun `WorkflowNotFound — key 를 보유한 인스턴스가 정상 생성된다`() {
        val key = "WF-UNKNOWN"
        val result: TransitionResult = TransitionResult.WorkflowNotFound(key = key)

        assertThat(result).isInstanceOf(TransitionResult.WorkflowNotFound::class.java)
        val notFound = result as TransitionResult.WorkflowNotFound
        assertThat(notFound.key).isEqualTo(key)
    }

    // ── ExpressionTimeout ─────────────────────────────────────────────────────

    @Test
    fun `ExpressionTimeout — message 를 보유한 인스턴스가 정상 생성된다`() {
        val message = "SpEL 표현식 평가가 500ms 를 초과했습니다"
        val result: TransitionResult = TransitionResult.ExpressionTimeout(message = message)

        assertThat(result).isInstanceOf(TransitionResult.ExpressionTimeout::class.java)
        val timeout = result as TransitionResult.ExpressionTimeout
        assertThat(timeout.message).isEqualTo(message)
    }

    // ── when exhaustive ───────────────────────────────────────────────────────

    @Test
    fun `when exhaustive — 모든 sealed 케이스를 커버하는 when 식이 컴파일 통과한다`() {
        val results: List<TransitionResult> =
            listOf(
                TransitionResult.Success(plan = samplePlan),
                TransitionResult.ValidatorFailure(message = "v-fail"),
                TransitionResult.WorkflowNotFound(key = "WF-X"),
                TransitionResult.ExpressionTimeout(message = "timeout"),
            )

        // when 식이 else 없이 컴파일되면 sealed 케이스가 exhaustive 하게 커버됨을 증명한다.
        val labels =
            results.map { result ->
                when (result) {
                    is TransitionResult.Success -> "success"
                    is TransitionResult.ValidatorFailure -> "validator-failure"
                    is TransitionResult.WorkflowNotFound -> "workflow-not-found"
                    is TransitionResult.ExpressionTimeout -> "expression-timeout"
                }
            }

        assertThat(labels).containsExactly(
            "success",
            "validator-failure",
            "workflow-not-found",
            "expression-timeout",
        )
    }
}
