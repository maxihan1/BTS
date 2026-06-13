// TemplateVariableSubstitutor 단위 테스트 — 치환 동작·fail-safe·단일 패스 회귀 가드

package com.bts.issue.template.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TemplateVariableSubstitutorTest {
    // ── 기본 치환 ──────────────────────────────────────────────────────────────

    @Test
    fun `author 단독 토큰을 바인딩 값으로 치환한다`() {
        val result =
            TemplateVariableSubstitutor.substitute(
                content = "{{author}}님이 작성",
                bindings = mapOf(TemplateVariable.AUTHOR to "alice"),
            )
        assertEquals("alice님이 작성", result)
    }

    @Test
    fun `author date project 세 토큰을 동시에 각각 치환한다`() {
        val result =
            TemplateVariableSubstitutor.substitute(
                content = "작성자: {{author}}, 날짜: {{date}}, 프로젝트: {{project}}",
                bindings =
                    mapOf(
                        TemplateVariable.AUTHOR to "alice",
                        TemplateVariable.DATE to "2026-06-13",
                        TemplateVariable.PROJECT to "ATLAS",
                    ),
            )
        assertEquals("작성자: alice, 날짜: 2026-06-13, 프로젝트: ATLAS", result)
    }

    @Test
    fun `같은 토큰이 여러 번 등장하면 전부 치환한다`() {
        val result =
            TemplateVariableSubstitutor.substitute(
                content = "{{author}} created by {{author}}",
                bindings = mapOf(TemplateVariable.AUTHOR to "bob"),
            )
        assertEquals("bob created by bob", result)
    }

    // ── fail-safe ──────────────────────────────────────────────────────────────

    @Test
    fun `정의되지 않은 토큰 foo 는 리터럴 그대로 유지한다`() {
        val result =
            TemplateVariableSubstitutor.substitute(
                content = "{{foo}} bar",
                bindings = mapOf(TemplateVariable.AUTHOR to "alice"),
            )
        assertEquals("{{foo}} bar", result)
    }

    @Test
    fun `바인딩에 없는 변수는 해당 토큰을 리터럴로 유지한다`() {
        // author 바인딩 없음 — {{author}} 토큰이 리터럴 유지되어야 한다
        val result =
            TemplateVariableSubstitutor.substitute(
                content = "{{author}}님 안녕하세요",
                bindings = emptyMap(),
            )
        assertEquals("{{author}}님 안녕하세요", result)
    }

    // ── 단일 패스 회귀 가드 ────────────────────────────────────────────────────

    @Test
    fun `바인딩 값이 다른 토큰 문자열이어도 재치환되지 않는다`() {
        // author 를 "{{date}}" 로 치환했을 때 {{date}} 가 date 바인딩으로 재치환되면 안 된다
        // 단일 패스이므로 결과는 "{{date}}" 리터럴이어야 한다
        val result =
            TemplateVariableSubstitutor.substitute(
                content = "{{author}}",
                bindings =
                    mapOf(
                        TemplateVariable.AUTHOR to "{{date}}",
                        TemplateVariable.DATE to "2026-06-13",
                    ),
            )
        assertEquals("{{date}}", result)
    }

    // ── no-op ─────────────────────────────────────────────────────────────────

    @Test
    fun `토큰이 없는 content 는 원문 그대로 반환한다`() {
        val result =
            TemplateVariableSubstitutor.substitute(
                content = "아무 변수도 없는 텍스트",
                bindings = mapOf(TemplateVariable.AUTHOR to "alice"),
            )
        assertEquals("아무 변수도 없는 텍스트", result)
    }

    // ── 대소문자·공백 엄격성 ──────────────────────────────────────────────────

    @Test
    fun `내부에 공백이 있는 토큰 은 치환하지 않고 리터럴 유지한다`() {
        val result =
            TemplateVariableSubstitutor.substitute(
                content = "{{ author }}",
                bindings = mapOf(TemplateVariable.AUTHOR to "alice"),
            )
        assertEquals("{{ author }}", result)
    }

    @Test
    fun `대문자 Author 토큰은 치환하지 않고 리터럴 유지한다`() {
        val result =
            TemplateVariableSubstitutor.substitute(
                content = "{{Author}}",
                bindings = mapOf(TemplateVariable.AUTHOR to "alice"),
            )
        assertEquals("{{Author}}", result)
    }

    // ── enum ↔ 정규식 정합 가드 (C1: 이중 정의 drift 차단) ──────────────────────

    @Test
    fun `모든 TemplateVariable 토큰을 substitute 가 인식하고 치환한다`() {
        // TOKEN_PATTERN 정규식(author|date|project)과 enum closed set이 이중 정의되어 있다.
        // enum에 항목을 추가하고 정규식을 갱신하지 않으면 새 토큰이 조용히 치환 안 됨 — 이 가드가 즉시 적발한다.
        // 메모리 enum-add-breaks-crossmodule-count-guard 와 같은 결의 drift 트랩 차단.
        TemplateVariable.entries.forEach { variable ->
            val result =
                TemplateVariableSubstitutor.substitute(
                    content = variable.token,
                    bindings = mapOf(variable to "REPLACED"),
                )
            assertEquals("REPLACED", result, "${variable.name} 토큰(${variable.token}) 미치환 — 정규식↔enum drift")
        }
    }
}
