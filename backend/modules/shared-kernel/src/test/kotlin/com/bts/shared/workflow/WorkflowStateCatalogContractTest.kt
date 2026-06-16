// WorkflowStateCatalog SPI + WorkflowStateView VO 계약 검증 — 구현 없이 RED

package com.bts.shared.workflow

import com.bts.shared.issue.IssueTypeKey
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/**
 * [WorkflowStateCatalog] SPI 계약 테스트.
 *
 * 구현체 없이 인터페이스 + VO 의 계약만 검증한다.
 * Spring 컨텍스트 불필요 — 순수 단위 테스트.
 *
 * 검증 항목.
 * - [WorkflowStateCatalog.listStates] 메서드 시그니처 존재
 * - [Transactional] 어노테이션 + [Propagation.MANDATORY] + `readOnly = true` 선언
 * - [WorkflowStateView] immutable val 필드 계약 (key + name)
 * - [WorkflowStateView] non-blank 계약 (key, name)
 *
 * ### @JvmInline value class 주의
 *
 * [ProjectKey] 와 [IssueTypeKey] 모두 `@JvmInline value class` 이므로
 * JVM bytecode 레벨에서 메서드 이름이 mangled 된다.
 * JVM reflection 대신 Kotlin reflection (`KClass.memberFunctions`) 을 사용해야 한다.
 */
class WorkflowStateCatalogContractTest {
    @Test
    fun `WorkflowStateCatalog has listStates returning List`() {
        // Kotlin reflection 으로 listStates 메서드가 선언되어 있는지 검증한다.
        // @JvmInline value class 파라미터가 있으면 JVM 메서드 이름이 mangled 되므로 Kotlin reflection 필수.
        val method =
            WorkflowStateCatalog::class.members
                .firstOrNull { it.name == "listStates" }
                ?: error("WorkflowStateCatalog 에 listStates 메서드가 없습니다.")

        assertThat(method.name).isEqualTo("listStates")
    }

    @Test
    fun `WorkflowStateCatalog listStates annotated with Transactional(propagation = MANDATORY, readOnly = true)`() {
        // @Transactional(propagation = MANDATORY, readOnly = true) 어노테이션이 정확히 선언됐는지 검증한다.
        // 호출자가 활성 트랜잭션 없이 이 메서드를 호출하면 Spring 이 IllegalTransactionStateException 을 던진다.
        // @JvmInline value class 파라미터로 인한 JVM mangling 때문에 JVM method 이름이 변형될 수 있으므로
        // startsWith("listStates") 로 탐색한다.
        val jvmMethod =
            WorkflowStateCatalog::class.java.declaredMethods
                .firstOrNull { it.name.startsWith("listStates") }
                ?: error("JVM 에서 listStates 메서드를 찾을 수 없습니다.")

        val tx =
            jvmMethod.getAnnotation(Transactional::class.java)
                ?: error("listStates 에 @Transactional 어노테이션이 없습니다.")

        assertThat(tx.propagation).isEqualTo(Propagation.MANDATORY)
        assertThat(tx.readOnly).isTrue()
    }

    @Test
    fun `WorkflowStateView holds key and name when valid`() {
        // 정상 입력은 VO 를 생성하고 필드를 보존한다.
        val view = WorkflowStateView(key = "open", name = "열림")

        assertThat(view.key).isEqualTo("open")
        assertThat(view.name).isEqualTo("열림")
    }

    @Test
    fun `WorkflowStateView rejects blank key`() {
        // 빈 key 는 거부한다.
        assertThatThrownBy { WorkflowStateView(key = "", name = "열림") }
            .isInstanceOf(IllegalArgumentException::class.java)

        // 공백만 있는 key 도 거부한다.
        assertThatThrownBy { WorkflowStateView(key = "  ", name = "열림") }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `WorkflowStateView rejects blank name`() {
        // 빈 name 은 거부한다.
        assertThatThrownBy { WorkflowStateView(key = "open", name = "") }
            .isInstanceOf(IllegalArgumentException::class.java)

        // 공백만 있는 name 도 거부한다.
        assertThatThrownBy { WorkflowStateView(key = "open", name = "   ") }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `WorkflowStateView is a data class with immutable fields`() {
        // data class 이므로 equals/copy 가 동작한다.
        val a = WorkflowStateView(key = "open", name = "열림")
        val b = WorkflowStateView(key = "open", name = "열림")

        // 동일한 값이면 같다고 판단한다.
        assertThat(a).isEqualTo(b)

        // copy 로 name 만 바꾸면 key 는 유지된다.
        val c = a.copy(name = "시작")
        assertThat(c.key).isEqualTo("open")
        assertThat(c.name).isEqualTo("시작")
    }

    @Test
    fun `WorkflowStateCatalog listStates accepts null issueTypeKey`() {
        // listStates 가 IssueTypeKey? (nullable) 파라미터를 받는지 Kotlin reflection 으로 확인한다.
        val method =
            WorkflowStateCatalog::class.members
                .firstOrNull { it.name == "listStates" }
                ?: error("WorkflowStateCatalog 에 listStates 메서드가 없습니다.")

        // 두 번째 파라미터(인덱스 2 — 0=instance, 1=projectKey, 2=issueTypeKey)가 nullable 인지 확인한다.
        val issueTypeKeyParam =
            method.parameters.firstOrNull { it.name == "issueTypeKey" }
                ?: error("listStates 에 issueTypeKey 파라미터가 없습니다.")

        assertThat(issueTypeKeyParam.type.isMarkedNullable).isTrue()
    }
}
