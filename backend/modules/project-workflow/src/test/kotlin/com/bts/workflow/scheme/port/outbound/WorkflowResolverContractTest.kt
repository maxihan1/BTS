// WorkflowResolver outbound port 계약 검증 — resolveFor 시그니처 + KDoc + Propagation.MANDATORY

package com.bts.workflow.scheme.port.outbound

import com.bts.issue.type.domain.IssueTypeKey
import com.bts.workflow.domain.Workflow
import com.bts.workflow.scheme.domain.ProjectKey
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import kotlin.reflect.full.memberFunctions
import kotlin.reflect.full.findAnnotation

/**
 * WorkflowResolver outbound port 계약 검증.
 *
 * Reflection으로 컴파일 시점 구조를 검증한다.
 * 실제 구현체(WorkflowResolverImpl)는 T22에서 등록된다.
 *
 * 검증 항목.
 * 1. WorkflowResolver 가 interface 임을 확인
 * 2. resolveFor(ProjectKey, IssueTypeKey?) : Workflow 시그니처 정확성
 * 3. resolveFor 에 @Transactional(readOnly=true, propagation=MANDATORY) 부착 확인
 */
class WorkflowResolverContractTest {

    // ── 1. WorkflowResolver 는 interface 여야 한다 ──────────────────────────────

    @Test
    fun `WorkflowResolver 는 인터페이스여야 한다`() {
        val kClass = WorkflowResolver::class
        assertThat(kClass.java.isInterface).isTrue()
    }

    // ── 2. resolveFor 시그니처 검증 ─────────────────────────────────────────────

    @Test
    fun `resolveFor 메서드는 ProjectKey 와 nullable IssueTypeKey 를 받아 Workflow 를 반환해야 한다`() {
        val kClass = WorkflowResolver::class
        val method = kClass.memberFunctions.find { it.name == "resolveFor" }

        assertThat(method).isNotNull()

        val params = method!!.parameters // 0: instance, 1: projectKey, 2: issueTypeKey
        assertThat(params).hasSize(3)
        assertThat(params[1].type.classifier).isEqualTo(ProjectKey::class)
        assertThat(params[2].type.classifier).isEqualTo(IssueTypeKey::class)
        assertThat(params[2].type.isMarkedNullable).isTrue()
        assertThat(method.returnType.classifier).isEqualTo(Workflow::class)
    }

    // ── 3. @Transactional(readOnly=true, propagation=MANDATORY) 확인 ─────────────

    @Test
    fun `resolveFor 에는 readOnly=true propagation=MANDATORY Transactional 이 부착돼야 한다`() {
        val kClass = WorkflowResolver::class
        val method = kClass.memberFunctions.find { it.name == "resolveFor" }

        assertThat(method).isNotNull()

        val tx = method!!.findAnnotation<Transactional>()
        assertThat(tx).isNotNull()
        assertThat(tx!!.readOnly).isTrue()
        assertThat(tx.propagation).isEqualTo(Propagation.MANDATORY)
    }
}
