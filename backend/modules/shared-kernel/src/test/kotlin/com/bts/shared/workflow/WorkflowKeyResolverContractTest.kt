// WorkflowKeyResolver SPI + WorkflowStartState VO 계약 검증 — 구현 없이 RED

package com.bts.shared.workflow

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import kotlin.reflect.full.memberFunctions

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
 *
 * ### @JvmInline value class 주의
 *
 * `ProjectKey` + `IssueTypeKey` 모두 `@JvmInline value class` 이므로
 * JVM bytecode 레벨에서 메서드 이름이 mangled 된다 (`resolveStart-XxxXxx`).
 * JVM reflection 대신 Kotlin reflection (`KClass.memberFunctions`) 을 사용해야 한다.
 */
class WorkflowKeyResolverContractTest {
    @Test
    fun `WorkflowKeyResolver has resolveStart returning WorkflowStartState`() {
        // Kotlin reflection 으로 resolveStart 메서드가 선언되어 있고 반환 타입이 WorkflowStartState 인지 검증한다.
        // @JvmInline value class 파라미터가 있으면 JVM 메서드 이름이 mangled 되므로 Kotlin reflection 필수.
        val method =
            WorkflowKeyResolver::class.memberFunctions
                .firstOrNull { it.name == "resolveStart" }
                ?: error("WorkflowKeyResolver 에 resolveStart 메서드가 없습니다.")

        assertThat(method.returnType.classifier).isEqualTo(WorkflowStartState::class)
    }

    @Test
    fun `WorkflowKeyResolver method annotated with Transactional(propagation = MANDATORY)`() {
        // @Transactional(propagation = MANDATORY, readOnly = true) 어노테이션이 정확히 선언됐는지 검증한다.
        // 호출자가 활성 트랜잭션 없이 이 메서드를 호출하면 Spring 이 IllegalTransactionStateException 을 던진다.
        // JVM mangling 때문에 Kotlin reflection 으로 메서드를 찾되, 어노테이션은 Java reflection 으로 읽는다.
        val kMethod =
            WorkflowKeyResolver::class.memberFunctions
                .firstOrNull { it.name == "resolveStart" }
                ?: error("WorkflowKeyResolver 에 resolveStart 메서드가 없습니다.")

        // Kotlin KFunction → JVM Method 변환 후 어노테이션 조회
        val jvmMethod =
            WorkflowKeyResolver::class.java.declaredMethods
                .firstOrNull { it.name.startsWith("resolveStart") }
                ?: error("JVM 에서 resolveStart 메서드를 찾을 수 없습니다.")

        val tx =
            jvmMethod.getAnnotation(Transactional::class.java)
                ?: error("resolveStart 에 @Transactional 어노테이션이 없습니다. (kMethod=$kMethod)")

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
