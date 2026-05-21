// 이슈 키 (PROJECT_KEY-NUMBER) VO — DATA.md §1.1 영구 보존 원칙
package com.bts.issue.domain

/**
 * 이슈 키. `<PROJECT_KEY>-<NUMBER>` 형식. 영구 보존 (DATA.md §1.1).
 *
 * ADR 2026-05-22-issue-key-prefix-policy 의 prefix 검증과 별개 —
 * 이 VO 는 형식 검증만, prefix 예약어 차단은 `IssueKeyPrefixReservedWords` 가 담당.
 *
 * 유효 정규식: `^[A-Z][A-Z0-9]{1,9}-[1-9][0-9]*$`
 * - prefix: 대문자 알파벳으로 시작, 대문자 알파벳/숫자 조합, 총 2~10자
 * - number: 1 이상의 양의 정수 (0 불가)
 */
@JvmInline
value class IssueKey(val value: String) {
    init {
        require(REGEX.matches(value)) { "Invalid issue key format: $value" }
    }

    val projectPrefix: String get() = value.substringBefore('-')

    val number: Long get() = value.substringAfter('-').toLong()

    companion object {
        val REGEX = Regex("^[A-Z][A-Z0-9]{1,9}-[1-9][0-9]*$")

        fun of(prefix: String, number: Long): IssueKey = IssueKey("$prefix-$number")
    }
}
