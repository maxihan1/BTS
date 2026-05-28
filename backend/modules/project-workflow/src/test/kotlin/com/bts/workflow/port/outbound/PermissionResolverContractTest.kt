// PermissionResolver 계약 검증 — hasPermission 시그니처 + Scope sealed 3종

package com.bts.workflow.port.outbound

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import kotlin.reflect.full.memberFunctions

/**
 * PermissionResolver outbound port 계약 검증.
 *
 * Reflection 으로 컴파일 시점 구조를 검증한다.
 * 실제 구현(IdentityAccessPermissionResolver)은 identity-access PR #8 이후 별도 PR 에서 어댑터로 연결된다.
 *
 * 검증 항목.
 * 1. PermissionResolver 인터페이스 존재
 * 2. hasPermission(ActorId, String, Scope) : Boolean 시그니처 정확성
 * 3. Scope 가 sealed interface 임을 확인
 * 4. Scope 구현체 3종 (Global, Project, Issue) 존재
 * 5. ActorId 가 blank 이면 IllegalArgumentException 발생
 */
class PermissionResolverContractTest {
    // ── 1. PermissionResolver 인터페이스 존재 ────────────────────────────────────

    @Test
    fun `PermissionResolver 는 인터페이스여야 한다`() {
        val kClass = PermissionResolver::class
        assertThat(kClass.java.isInterface).isTrue()
    }

    // ── 2. hasPermission 시그니처 ────────────────────────────────────────────────

    @Test
    fun `hasPermission 메서드는 ActorId, String, Scope 를 받아 Boolean 을 반환해야 한다`() {
        val kClass = PermissionResolver::class
        val method = kClass.memberFunctions.find { it.name == "hasPermission" }

        assertThat(method).isNotNull()

        val params = method!!.parameters // 0: instance, 1: actorId, 2: permission, 3: scope
        assertThat(params).hasSize(4)
        assertThat(params[1].type.classifier).isEqualTo(ActorId::class)
        assertThat(params[2].type.classifier).isEqualTo(String::class)
        assertThat(params[3].type.classifier).isEqualTo(Scope::class)
        assertThat(method.returnType.classifier).isEqualTo(Boolean::class)
    }

    // ── 3. Scope 가 sealed interface 임을 확인 ────────────────────────────────────

    @Test
    fun `Scope 는 sealed interface 여야 한다`() {
        val kClass = Scope::class
        assertThat(kClass.java.isInterface).isTrue()
        assertThat(kClass.isSealed).isTrue()
    }

    // ── 4. Scope 구현체 3종 존재 ─────────────────────────────────────────────────

    @Test
    fun `Scope sealed 구현체는 Global, Project, Issue 3종이어야 한다`() {
        val subclassNames = Scope::class.sealedSubclasses.map { it.simpleName }.toSet()
        assertThat(subclassNames).containsExactlyInAnyOrder("Global", "Project", "Issue")
    }

    @Test
    fun `Scope Global 은 싱글턴 data object 여야 한다`() {
        val globalClass = Scope::class.sealedSubclasses.find { it.simpleName == "Global" }
        assertThat(globalClass).isNotNull()
        assertThat(globalClass!!.objectInstance).isNotNull()
    }

    @Test
    fun `Scope Project 는 key String 프로퍼티를 갖는 data class 여야 한다`() {
        val projectClass = Scope::class.sealedSubclasses.find { it.simpleName == "Project" }
        assertThat(projectClass).isNotNull()
        val keyParam = projectClass!!.constructors.first().parameters.find { it.name == "key" }
        assertThat(keyParam).isNotNull()
        assertThat(keyParam!!.type.classifier).isEqualTo(String::class)
    }

    @Test
    fun `Scope Issue 는 key String 프로퍼티를 갖는 data class 여야 한다`() {
        val issueClass = Scope::class.sealedSubclasses.find { it.simpleName == "Issue" }
        assertThat(issueClass).isNotNull()
        val keyParam = issueClass!!.constructors.first().parameters.find { it.name == "key" }
        assertThat(keyParam).isNotNull()
        assertThat(keyParam!!.type.classifier).isEqualTo(String::class)
    }

    // ── 5. ActorId blank 검증 ─────────────────────────────────────────────────────

    @Test
    fun `ActorId 는 blank raw 값으로 생성 시 IllegalArgumentException 을 던져야 한다`() {
        assertThatThrownBy { ActorId("") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("blank")

        assertThatThrownBy { ActorId("   ") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("blank")
    }

    @Test
    fun `ActorId 는 UUID 형식이 아닌 값으로 생성 시 IllegalArgumentException 을 던져야 한다`() {
        assertThatThrownBy { ActorId("user-abc") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("UUID")

        assertThatThrownBy { ActorId("not-a-uuid-at-all") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("UUID")
    }

    @Test
    fun `ActorId 는 유효한 UUID String 으로 정상 생성돼야 한다`() {
        val actorId = ActorId("44444444-4444-4444-4444-444444444444")
        assertThat(actorId.raw).isEqualTo("44444444-4444-4444-4444-444444444444")
    }
}
