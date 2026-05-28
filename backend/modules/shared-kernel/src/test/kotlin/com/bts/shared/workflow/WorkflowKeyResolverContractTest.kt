// WorkflowKeyResolver SPI + WorkflowStartState VO 계약 검증 — 구현 없이 RED

package com.bts.shared.workflow

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/**
 * [WorkflowKeyResolver] SPI 계약 테스트.
 *
 * 구현체 없이 인터페이스 + VO 의 계약만 검증한다.
 * Spring 컨텍스트 불필요 — 순수 단위 테스트.
 *
 * 검증 항목.
 * - [WorkflowKeyResolver.resolveStart] 메서드 시그니처 존재
 * - [Transactional] 어노테이션 + [Propagation.MANDATORY] + `readOnly = true` 선언
 * - [WorkflowStartState] non-blank 계약 (`workflowKey` + `startStateKey`)
 */
class WorkflowKeyResolverContractTest {

    @Test
    fun `WorkflowKeyResolver has resolveStart returning WorkflowStartState`() {
        // 인터페이스에 resolveStart 메서드가 선언되어 있고 반환 타입이 WorkflowStartState 인지 검증한다.
        // @JvmInline value class 는 JVM 에서 내부 타입으로 소거(erase)되므로 이름 기반 조회를 사용한다.
        val method = WorkflowKeyResolver::class.java.declaredMethods
            .firstOrNull { it.name == "resolveStart" }
            ?: error("WorkflowKeyResolver 에 resolveStart 메서드가 없습니다.")

        assertThat(method.returnType).isEqualTo(WorkflowStartState::class.java)
    }

    @Test
    fun `WorkflowKeyResolver method annotated with Transactional(propagation = MANDATORY)`() {
        // @Transactional(propagation = MANDATORY, readOnly = true) 어노테이션이 정확히 선언됐는지 검증한다.
        // 호출자가 활성 트랜잭션 없이 이 메서드를 호출하면 Spring 이 IllegalTransactionStateException 을 던진다.
        val method = WorkflowKeyResolver::class.java.declaredMethods
            .firstOrNull { it.name == "resolveStart" }
            ?: error("WorkflowKeyResolver 에 resolveStart 메서드가 없습니다.")

        val tx = method.getAnnotation(Transactional::class.java)
            ?: error("resolveStart 에 @Transactional 어노테이션이 없습니다.")

        assertThat(tx.propagation).isEqualTo(Propagation.MANDATORY)
        assertThat(tx.readOnly).isTrue()
    }

    @Test
    fun `WorkflowStartState requires non-blank workflowKey and startStateKey`() {
        // 빈 workflowKey 는 거부한다.
        assertThatThrownBy { WorkflowStartState(workflowKey = "", startStateKey = "open") }
            .isInstanceOf(IllegalArgumentException::class.java)

        // 공백만 있는 startStateKey 는 거부한다.
        assertThatThrownBy { WorkflowStartState(workflowKey = "software-default", startStateKey = "  ") }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `WorkflowStartState holds workflowKey and startStateKey when valid`() {
        // 정상 입력은 VO 를 생성한다.
        val state = WorkflowStartState(workflowKey = "software-default", startStateKey = "open")

        assertThat(state.workflowKey).isEqualTo("software-default")
        assertThat(state.startStateKey).isEqualTo("open")
    }
}
