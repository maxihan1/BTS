// 이슈 타입 키 VO — REGEX ^[a-z][a-z0-9-]{1,29}$ URL-safe 30자컷

package com.bts.shared.issue

/**
 * 이슈 타입 키 VO.
 *
 * URL-safe 소문자 슬러그 형식을 강제한다.
 * 첫 글자는 소문자 알파벳, 이후 소문자/숫자/하이픈으로 구성되며 총 2~30자다.
 *
 * 예시 — `bug`, `subtask`, `task-item`, `a1`.
 *
 * @param value [REGEX] 패턴을 만족하는 이슈 타입 키 문자열
 * @throws IllegalArgumentException [value]가 [REGEX] 패턴을 위반하는 경우
 */
@JvmInline
value class IssueTypeKey(val value: String) {
    init {
        require(REGEX.matches(value)) {
            "Invalid issue type key: '$value'. Must match ${REGEX.pattern}"
        }
    }

    companion object {
        /**
         * URL-safe 소문자 슬러그 패턴.
         *
         * - 첫 글자: 소문자 알파벳 `[a-z]`
         * - 후속 글자: 소문자/숫자/하이픈 `[a-z0-9-]` 1~29자
         * - 총 길이: 2~30자
         */
        val REGEX = Regex("^[a-z][a-z0-9-]{1,29}\$")
    }
}
