// 이슈 우선순위 SMALLINT↔이름 매핑 — SDD 05 priority 1..5, SDD 08/09/10 문자열 이름 재사용 대비

package com.bts.issue.domain

/**
 * 이슈 우선순위 매핑.
 *
 * DB에는 SMALLINT(1~5)로 저장되고, API/자동화(SDD 08/09/10)에서는 문자열 이름("Highest" 등)으로
 * 참조된다. 이 enum 이 두 표현의 단일 출처(single source of truth)이다.
 *
 * 매핑 근거 (SDD 05 §priority 정의).
 * ```
 * 1 = Highest
 * 2 = High
 * 3 = Medium  (기본값)
 * 4 = Low
 * 5 = Lowest
 * ```
 *
 * @property number      DB에 저장되는 SMALLINT 값 (1..5).
 * @property displayName API/자동화에서 사용하는 문자열 표현.
 */
enum class IssuePriority(val number: Int, val displayName: String) {
    HIGHEST(1, "Highest"),
    HIGH(2, "High"),
    MEDIUM(3, "Medium"),
    LOW(4, "Low"),
    LOWEST(5, "Lowest");

    companion object {
        /** 정수 [number]로 [IssuePriority]를 조회한다. 1..5 범위를 벗어나면 [IllegalArgumentException]. */
        fun fromNumber(number: Int): IssuePriority =
            entries.find { it.number == number }
                ?: throw IllegalArgumentException(
                    "priority number must be between 1 and 5, but was $number"
                )

        /** 문자열 [displayName]으로 [IssuePriority]를 조회한다. 미정의 이름이면 [IllegalArgumentException]. */
        fun fromName(displayName: String): IssuePriority =
            entries.find { it.displayName == displayName }
                ?: throw IllegalArgumentException(
                    "unknown priority name: \"$displayName\". valid names: ${entries.map { it.displayName }}"
                )
    }
}
