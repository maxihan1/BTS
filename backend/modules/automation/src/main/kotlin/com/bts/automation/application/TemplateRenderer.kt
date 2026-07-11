// {{ path.to.var }} 템플릿 변수를 context 맵의 dot 경로 값으로 치환하는 순수 렌더러 (FR-AT-02 Task 4)

package com.bts.automation.application

import org.slf4j.LoggerFactory

/**
 * 자동화 액션(예: `AddComment` 본문)에 등장하는 `{{ path.to.var }}` 템플릿 변수를
 * [context] 값으로 치환하는 순수(상태 없음) 렌더러.
 *
 * ## 치환 규칙
 * - `{{ issue.key }}` 형태의 변수를 찾아 [context] 에서 dot 경로로 값을 해석한다.
 *   중괄호 안 앞뒤 공백은 허용한다(`{{issue.key}}` == `{{ issue.key }}`).
 * - dot 경로는 중첩 `Map` 을 재귀적으로 탐색한다(`issue.assignee.name` → `context["issue"]` 가
 *   `Map` 이면 그 안의 `"assignee"`, 다시 그 안의 `"name"`).
 * - 값이 `null` 이거나 경로를 해석할 수 없으면(중간 노드가 `Map` 이 아니거나 키 부재) **빈 문자열**로
 *   치환하고 경고 로그를 남긴다. 로그에는 변수 경로만 남기고 값(PII 가능성)은 남기지 않는다.
 * - 값이 있으면 [Any.toString] 결과로 치환한다.
 * - 닫히지 않은 `{{`(문법 오류)는 정규식이 매칭하지 않으므로 리터럴 그대로 유지된다(예외를 던지지 않는다).
 *
 * 조건 분기·반복·중첩 템플릿은 지원하지 않는다(단순 변수 치환만 — 조건 분기는 FR-AT-03 범위).
 */
object TemplateRenderer {
    private val log = LoggerFactory.getLogger(javaClass)

    private const val PATH_SEPARATOR = '.'

    /** `{{ path.to.var }}` 패턴. 경로는 영문/숫자/밑줄/점만 허용, 중괄호 안 앞뒤 공백은 무시한다. */
    private val TEMPLATE_VARIABLE_PATTERN = Regex("""\{\{\s*([A-Za-z0-9_.]+)\s*}}""")

    /**
     * [template] 안의 `{{ path.to.var }}` 변수를 [context] 값으로 치환한 문자열을 반환한다.
     *
     * @param template 치환 대상 원본 문자열(액션 config 의 body/value 등).
     * @param context 변수 해석에 쓰일 값. 중첩 구조는 `Map<String, Any?>` 로 표현한다.
     * @return 변수가 치환된 문자열. 미닫힘 `{{` 등 문법 오류가 있는 구간은 원본 그대로 유지된다.
     */
    fun render(
        template: String,
        context: Map<String, Any?>,
    ): String =
        TEMPLATE_VARIABLE_PATTERN.replace(template) { match ->
            val path = match.groupValues[1]
            resolvePath(path, context)?.toString() ?: run {
                log.warn("automation_template_variable_undefined path={}", path)
                ""
            }
        }

    /**
     * [path] (`.` 로 구분된 dot 경로)를 [context] 에서 재귀적으로 해석한다.
     *
     * 중간 노드가 `Map` 이 아니거나 해당 키가 없으면 `null` 을 반환한다(미정의 취급).
     * 키가 존재하되 값이 명시적으로 `null` 인 경우도 `null` 을 반환해 동일하게 미정의로 취급한다.
     */
    private fun resolvePath(
        path: String,
        context: Map<String, Any?>,
    ): Any? {
        var current: Any? = context
        for (segment in path.split(PATH_SEPARATOR)) {
            val currentMap = current as? Map<*, *>
            current = currentMap?.takeIf { it.containsKey(segment) }?.get(segment) ?: return null
        }
        return current
    }
}
