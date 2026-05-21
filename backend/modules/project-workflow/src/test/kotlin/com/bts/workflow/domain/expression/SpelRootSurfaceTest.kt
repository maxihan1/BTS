// IssueView/ActorView surface — reflection 으로 action 메서드 0건 강제

package com.bts.workflow.domain.expression

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import kotlin.reflect.KFunction
import kotlin.reflect.full.declaredMemberFunctions
import kotlin.reflect.full.declaredMemberProperties

/**
 * SpEL(Spring Expression Language) 평가 컨텍스트에서 사용되는 루트 객체의 surface 검증.
 *
 * SpEL: 런타임에 문자열로 작성된 표현식을 평가하는 Spring 내장 언어.
 * SimpleEvaluationContext: 메서드 호출을 기본적으로 차단하는 제한된 SpEL 컨텍스트 — getter 만 허용.
 *
 * 검증 목표.
 * 1. IssueView sealed interface 존재
 * 2. ActorView sealed interface 존재
 * 3. IssueView 에 사용자 정의 action 메서드 0건 (val property getter 만 노출)
 * 4. ActorView 에 사용자 정의 action 메서드 0건
 * 5. DefaultIssueView 구현체도 동일 surface (data class 합성 메서드 제외)
 * 6. DefaultActorView 구현체도 동일 surface (data class 합성 메서드 제외)
 * 7. IssueView 의 val property 목록 — key, priority, fields
 * 8. ActorView 의 val property 목록 — userId, roles
 */
class SpelRootSurfaceTest {

    // ── data class 합성 메서드 필터 ──────────────────────────────────────────────
    // Kotlin data class 는 컴파일 시 copy/equals/hashCode/toString/componentN 을 자동 생성한다.
    // 이 합성 메서드들은 사용자가 추가한 비즈니스 메서드가 아니므로 검증 대상에서 제외한다.
    private val dataSyntheticFunctionNames =
        setOf("copy", "equals", "hashCode", "toString") +
            (1..10).map { "component$it" }

    private fun userDefinedFunctions(kClass: kotlin.reflect.KClass<*>): List<KFunction<*>> =
        kClass.declaredMemberFunctions.filter { it.name !in dataSyntheticFunctionNames }

    // ── IssueView sealed interface ────────────────────────────────────────────

    @Test
    fun `IssueView 는 sealed interface 이다`() {
        assertThat(IssueView::class.isSealed).isTrue()
    }

    @Test
    fun `IssueView 에 사용자 정의 action 메서드가 없다`() {
        val functions = userDefinedFunctions(IssueView::class)
        assertThat(functions)
            .withFailMessage("IssueView 에 비즈니스 메서드가 존재해서는 안 된다. 발견된 메서드: $functions")
            .isEmpty()
    }

    @Test
    fun `IssueView 는 key, priority, fields val property 를 선언한다`() {
        val propertyNames = IssueView::class.declaredMemberProperties.map { it.name }.toSet()
        assertThat(propertyNames).containsExactlyInAnyOrder("key", "priority", "fields")
    }

    // ── ActorView sealed interface ────────────────────────────────────────────

    @Test
    fun `ActorView 는 sealed interface 이다`() {
        assertThat(ActorView::class.isSealed).isTrue()
    }

    @Test
    fun `ActorView 에 사용자 정의 action 메서드가 없다`() {
        val functions = userDefinedFunctions(ActorView::class)
        assertThat(functions)
            .withFailMessage("ActorView 에 비즈니스 메서드가 존재해서는 안 된다. 발견된 메서드: $functions")
            .isEmpty()
    }

    @Test
    fun `ActorView 는 userId, roles val property 를 선언한다`() {
        val propertyNames = ActorView::class.declaredMemberProperties.map { it.name }.toSet()
        assertThat(propertyNames).containsExactlyInAnyOrder("userId", "roles")
    }

    // ── DefaultIssueView 구현체 ───────────────────────────────────────────────

    @Test
    fun `DefaultIssueView 에 사용자 정의 action 메서드가 없다`() {
        val functions = userDefinedFunctions(DefaultIssueView::class)
        assertThat(functions)
            .withFailMessage("DefaultIssueView 에 비즈니스 메서드가 존재해서는 안 된다. 발견된 메서드: $functions")
            .isEmpty()
    }

    @Test
    fun `DefaultIssueView 는 IssueView 를 구현한다`() {
        assertThat(IssueView::class.isInstance(DefaultIssueView(key = "PROJ-1", priority = "HIGH", fields = emptyMap()))).isTrue()
    }

    // ── DefaultActorView 구현체 ───────────────────────────────────────────────

    @Test
    fun `DefaultActorView 에 사용자 정의 action 메서드가 없다`() {
        val functions = userDefinedFunctions(DefaultActorView::class)
        assertThat(functions)
            .withFailMessage("DefaultActorView 에 비즈니스 메서드가 존재해서는 안 된다. 발견된 메서드: $functions")
            .isEmpty()
    }

    @Test
    fun `DefaultActorView 는 ActorView 를 구현한다`() {
        assertThat(ActorView::class.isInstance(DefaultActorView(userId = "user-1", roles = setOf("MEMBER")))).isTrue()
    }
}
