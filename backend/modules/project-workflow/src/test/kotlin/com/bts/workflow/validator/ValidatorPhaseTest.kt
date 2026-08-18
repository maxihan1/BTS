// ValidatorPhase 분류 검증 — 각 Validator 구현체의 phase 값이 명세에 맞는지 확인

package com.bts.workflow.validator

import com.bts.workflow.domain.StateCategory
import com.bts.workflow.domain.spi.ValidatorPhase
import com.bts.workflow.expression.SpelEvaluator
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * 각 [com.bts.workflow.domain.spi.WorkflowValidator] 구현체가 올바른 [ValidatorPhase] 를
 * 반환하는지 검증한다.
 *
 * - AVAILABILITY 페이즈. 전환 목록 조회 시 평가(availableTransitions 게이트).
 * - EXECUTION 페이즈. 실제 전환 실행 시 평가(transition 실행 게이트).
 */
class ValidatorPhaseTest {
    @Test
    fun `RequiredFieldValidator 는 EXECUTION phase 를 반환한다`() {
        val validator = RequiredFieldValidator(field = "resolution")
        assertThat(validator.phase).isEqualTo(ValidatorPhase.EXECUTION)
    }

    @Test
    fun `PermissionValidator 는 기본값 AVAILABILITY phase 를 반환한다`() {
        val resolver = mockk<com.bts.workflow.port.outbound.PermissionResolver>()
        val validator = PermissionValidator(resolver = resolver, permission = "TRANSITION_ISSUE")
        assertThat(validator.phase).isEqualTo(ValidatorPhase.AVAILABILITY)
    }

    @Test
    fun `NotStatusCategoryValidator 는 기본값 AVAILABILITY phase 를 반환한다`() {
        val validator = NotStatusCategoryValidator(forbidden = StateCategory.DONE)
        assertThat(validator.phase).isEqualTo(ValidatorPhase.AVAILABILITY)
    }

    @Test
    fun `CustomExpressionValidator 는 기본값 AVAILABILITY phase 를 반환한다`() {
        val evaluator = mockk<SpelEvaluator>()
        val validator =
            CustomExpressionValidator(
                evaluator = evaluator,
                expression = "issue.priority == 'HIGH'",
            )
        assertThat(validator.phase).isEqualTo(ValidatorPhase.AVAILABILITY)
    }
}
