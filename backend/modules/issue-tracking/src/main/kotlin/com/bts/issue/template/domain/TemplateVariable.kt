// 이슈 템플릿에서 치환 가능한 변수 목록 — closed set 3종 (author/date/project)

package com.bts.issue.template.domain

/**
 * 이슈 템플릿 본문에서 치환 가능한 변수 열거형.
 *
 * closed set 3종만 허용하며, 토큰 형식은 `{{변수명}}`이다.
 * 앱 계층은 [token]을 이용해 content 내 포함 여부를 사전 확인하고,
 * [TemplateVariableSubstitutor]에 바인딩 맵을 전달한다.
 *
 * @property token content 내에서 치환 대상이 되는 `{{변수명}}` 형태의 문자열
 */
enum class TemplateVariable(val token: String) {
    /** 이슈 작성자 username. 예: `{{author}}` */
    AUTHOR("{{author}}"),

    /** 이슈 생성 날짜. 예: `{{date}}` */
    DATE("{{date}}"),

    /** 이슈가 속한 프로젝트 키. 예: `{{project}}` */
    PROJECT("{{project}}"),
}
