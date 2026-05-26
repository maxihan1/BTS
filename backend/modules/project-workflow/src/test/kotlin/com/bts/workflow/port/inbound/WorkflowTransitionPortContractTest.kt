// WorkflowTransitionPort 계약 검증 — plan 시그니처 + @Transactional MANDATORY

package com.bts.workflow.port.inbound

import com.bts.workflow.domain.dto.TransitionRequest
import com.bts.workflow.domain.dto.TransitionResult
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import kotlin.reflect.KClass
import kotlin.reflect.full.memberFunctions

/**
 * WorkflowTransitionPort inbound port 계약을 reflection으로 검증한다.
 *
 * Spring 컨텍스트 없이 순수 Kotlin reflection만 사용한다.
 * 검증 항목.
 * 1. WorkflowTransitionPort 인터페이스가 존재한다.
 * 2. `plan(req: TransitionRequest): TransitionResult` 메서드를 가진다.
 * 3. plan 메서드에 @Transactional 이 붙어 있다.
 * 4. @Transactional propagation 이 Propagation.MANDATORY 다.
 *
 * ### 시그니처 진화 이력
 * 2026-05-26 — plan() 반환 타입이 TransitionPlan → TransitionResult (sealed interface) 로 변경.
 * BC 격리 강화 목적 (호출자 BC 가 workflow domain exception import 0건).
 * 결정 근거: docs/adr/2026-05-26-workflow-transition-port-result-sealed.md
 */
class WorkflowTransitionPortContractTest {
    private val portClass: KClass<WorkflowTransitionPort> = WorkflowTransitionPort::class

    // ── 1. interface 존재 ──────────────────────────────────────────────────────

    @Test
    fun `WorkflowTransitionPort 는 interface 다`() {
        assertThat(portClass.java.isInterface).isTrue()
    }

    // ── 2. plan 시그니처 ────────────────────────────────────────────────────────

    @Test
    fun `plan 메서드가 존재하며 TransitionRequest 파라미터를 받는다`() {
        val planFn = portClass.memberFunctions.find { it.name == "plan" }
        assertThat(planFn).isNotNull()

        // 파라미터: this(receiver) + req(TransitionRequest)
        val params = planFn!!.parameters
        val reqParam = params.find { it.type.classifier == TransitionRequest::class }
        assertThat(reqParam).isNotNull()
    }

    @Test
    fun `plan 반환 타입은 TransitionResult 이다`() {
        val planFn = portClass.memberFunctions.find { it.name == "plan" }
        assertThat(planFn).isNotNull()
        assertThat(planFn!!.returnType.classifier).isEqualTo(TransitionResult::class)
    }

    // ── 3. @Transactional 존재 ──────────────────────────────────────────────────

    @Test
    fun `plan 메서드에 @Transactional 이 부착돼 있다`() {
        val javaMethod = portClass.java.getDeclaredMethod("plan", TransitionRequest::class.java)
        val annotation = javaMethod.getAnnotation(Transactional::class.java)
        assertThat(annotation).isNotNull()
    }

    // ── 4. Propagation.MANDATORY ─────────────────────────────────────────────────

    @Test
    fun `plan @Transactional 의 propagation 은 MANDATORY 다`() {
        val javaMethod = portClass.java.getDeclaredMethod("plan", TransitionRequest::class.java)
        val annotation = javaMethod.getAnnotation(Transactional::class.java)
        assertThat(annotation).isNotNull()
        assertThat(annotation.propagation).isEqualTo(Propagation.MANDATORY)
    }
}
