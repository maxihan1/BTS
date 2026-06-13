// 이슈 템플릿 본문에서 변수 토큰을 단일 패스로 치환하는 순수 함수 유틸

package com.bts.issue.template.domain

/**
 * 이슈 템플릿 본문의 `{{변수명}}` 토큰을 바인딩 값으로 치환하는 무상태 유틸.
 *
 * MentionParser와 동형으로 `object`(싱글턴)로 설계되어 빈 주입 없이 직접 호출 가능하다.
 *
 * ## 설계 원칙
 * - **단일 패스**: `Regex.replace`의 람다 방식을 사용하여 치환 결과를 재스캔하지 않는다.
 *   바인딩 값이 다른 토큰 문자열(`"{{date}}"` 등)이어도 재치환이 발생하지 않는다.
 * - **fail-safe**: 정의되지 않은 토큰이거나 바인딩에 해당 변수가 없으면 원문 토큰을 그대로 유지한다.
 *   치환 실패가 이슈 생성을 막아서는 안 된다.
 * - **대소문자 구분**: `{{Author}}`나 `{{ author }}`(내부 공백)는 치환 대상이 아니다.
 * - **순수 함수**: 포트·Clock·DB 의존 없음. 바인딩 값 수집은 앱 계층 책임이다.
 */
object TemplateVariableSubstitutor {
    /**
     * 치환 대상 토큰 정규식.
     *
     * `(author|date|project)` — [TemplateVariable] closed set과 정확히 일치하는 변수명만 매칭한다.
     * 내부 공백(`{{ author }}`)이나 대문자(`{{Author}}`)는 의도적으로 제외한다.
     *
     * 정규식이 캡처하는 변수명은 반드시 [TemplateVariable]의 소문자 이름과 일치하므로,
     * substitute 내부에서 `entries.first`로 안전하게 조회할 수 있다.
     */
    private val TOKEN_PATTERN = Regex("""\{\{(author|date|project)\}\}""")

    /**
     * [content] 내의 `{{변수명}}` 토큰을 [bindings] 값으로 단일 패스 치환하여 반환한다.
     *
     * - 매칭된 변수명이 [bindings]에 있으면 해당 값으로 치환한다.
     * - [bindings]에 없으면 원문 토큰을 그대로 유지한다 (fail-safe).
     * - 정의되지 않은 토큰(`{{foo}}` 등)은 정규식에 매칭되지 않으므로 자동으로 리터럴 유지된다.
     *
     * @param content 원본 템플릿 본문
     * @param bindings 변수 치환값 맵
     * @return 치환이 적용된 결과 문자열
     */
    fun substitute(
        content: String,
        bindings: Map<TemplateVariable, String>,
    ): String {
        return TOKEN_PATTERN.replace(content) { matchResult ->
            // 정규식이 closed set(author|date|project)만 캡처하므로 entries.first는 항상 성공한다.
            val variableName = matchResult.groupValues[1]
            val variable = TemplateVariable.entries.first { it.name.lowercase() == variableName }
            bindings[variable] ?: matchResult.value
        }
    }
}
