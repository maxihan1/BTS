// 이슈 타입 키 VO — REGEX ^[a-z][a-z0-9-]{1,29}$ + URL-safe + 30자컷
package com.bts.issue.type.domain

/**
 * 이슈 타입 식별 키 VO.
 *
 * WorkflowSchemeKey 와 동일한 REGEX 패턴을 공유한다.
 * URL-safe 소문자 슬러그 형식 — 경로 파라미터와 YAML 키로 직접 사용 가능.
 *
 * 유효 정규식: `^[a-z][a-z0-9-]{1,29}$`
 * - 첫 글자: 소문자 알파벳 (숫자/하이픈/대문자 시작 불가)
 * - 후속 문자: 소문자 알파벳, 숫자, 하이픈 조합, 1자 이상 29자 이하
 * - 총 길이: 2자 이상 30자 이하
 *
 * 유효 예: `bug`, `subtask`, `task-item`, `a1`
 * 위반 예: `Bug` (대문자 시작), `1bug` (숫자 시작), 31자 초과
 *
 * @property value 이슈 타입 키 원문. 예: `"bug"`, `"subtask"`
 */
@JvmInline
value class IssueTypeKey(val value: String) {
    init {
        require(REGEX.matches(value)) { "Invalid issue type key: '$value'. Must match ${REGEX.pattern}" }
    }

    companion object {
        /**
         * URL-safe 소문자 슬러그 정규식. WorkflowSchemeKey 와 동일 패턴.
         *
         * 규칙 요약.
         * - `^[a-z]` — 소문자 알파벳으로 시작 (대문자·숫자·특수문자 시작 불가)
         * - `[a-z0-9-]{1,29}` — 소문자·숫자·하이픈 조합 후속 1~29자
         * - 총 길이 2~30자 (URL 경로 파라미터 및 YAML 키로 안전하게 사용 가능)
         */
        val REGEX = Regex("^[a-z][a-z0-9-]{1,29}$")
    }
}
