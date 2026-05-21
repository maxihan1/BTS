// WorkflowPostAction interface 계약 검증 — evaluate 는 계산만

package com.bts.workflow.domain.spi

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import kotlin.reflect.KClass
import kotlin.reflect.full.memberFunctions
import kotlin.reflect.full.memberProperties

/**
 * WorkflowPostAction SPI interface 계약을 reflection 으로 검증한다.
 *
 * 검증 범위.
 * - interface WorkflowPostAction 존재 (com.bts.workflow.domain.spi 패키지)
 * - `val type: String` 멤버 존재
 * - `evaluate(TransitionContext): PostActionPlan` 시그니처 존재
 *
 * Task 12 (DTO group) 완료 전 wave 2 병렬 실행 시 컴파일 오류는 expected.
 * 파라미터/반환 타입은 단순 이름(String) 비교로 검증하여 Task 12 의존성 없이 RED 단계를 격리한다.
 */
class WorkflowPostActionContractTest {
    private val interfaceClass: KClass<*> by lazy {
        Class.forName("com.bts.workflow.domain.spi.WorkflowPostAction").kotlin
    }

    @Test
    fun `WorkflowPostAction 은 interface 이다`() {
        val clazz = Class.forName("com.bts.workflow.domain.spi.WorkflowPostAction")
        assertThat(clazz.isInterface).isTrue()
    }

    @Test
    fun `WorkflowPostAction 은 val type String 멤버를 가진다`() {
        val typeProperty =
            interfaceClass.memberProperties
                .find { it.name == "type" }
        assertThat(typeProperty).isNotNull()
        assertThat(typeProperty!!.returnType.toString()).contains("String")
    }

    @Test
    fun `WorkflowPostAction 은 evaluate 메서드를 가진다`() {
        val evaluateFn =
            interfaceClass.memberFunctions
                .find { it.name == "evaluate" }
        assertThat(evaluateFn).isNotNull()
    }

    @Test
    fun `evaluate 는 파라미터로 TransitionContext 를 받는다`() {
        val evaluateFn =
            interfaceClass.memberFunctions
                .find { it.name == "evaluate" }
        assertThat(evaluateFn).isNotNull()
        // 파라미터 인덱스 0은 receiver(this), 인덱스 1이 첫 번째 실제 파라미터
        val firstParam = evaluateFn!!.parameters.getOrNull(1)
        assertThat(firstParam).isNotNull()
        assertThat(firstParam!!.type.toString()).contains("TransitionContext")
    }

    @Test
    fun `evaluate 는 PostActionPlan 을 반환한다`() {
        val evaluateFn =
            interfaceClass.memberFunctions
                .find { it.name == "evaluate" }
        assertThat(evaluateFn).isNotNull()
        assertThat(evaluateFn!!.returnType.toString()).contains("PostActionPlan")
    }
}
