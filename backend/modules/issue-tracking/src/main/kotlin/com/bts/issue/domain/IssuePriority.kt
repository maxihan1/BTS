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
@Suppress("MagicNumber") // enum 생성자 인자는 SDD 05 우선순위 명세의 정의 값 그 자체
enum class IssuePriority(val number: Int, val displayName: String) {
    HIGHEST(1, "Highest"),
    HIGH(2, "High"),
    MEDIUM(3, "Medium"),
    LOW(4, "Low"),
    LOWEST(5, "Lowest"),
    ;

    companion object {
        /** 정수 [number]로 [IssuePriority]를 조회한다. 1..5 범위를 벗어나면 [IllegalArgumentException]. */
        fun fromNumber(number: Int): IssuePriority =
            fromNumberOrNull(number)
                ?: throw IllegalArgumentException(
                    "priority number must be between 1 and 5, but was $number",
                )

        /**
         * 정수 [number]로 [IssuePriority]를 조회하되 범위 밖이면 null을 반환한다.
         *
         * 표시명이 없어도 화면이 돌아가야 하는 조회 경로용이다. `priority`는 SMALLINT라
         * 제약이 무너지면 6 같은 값이 들어올 수 있는데, 라벨 하나를 못 붙이는 것과 화면이
         * 500이 되는 것은 전혀 다른 사고다. try/catch로 예외를 삼키는 대신 여기서
         * "값이 없음"을 타입으로 표현해 호출자가 조용히 실패를 감추지 않게 한다.
         */
        fun fromNumberOrNull(number: Int): IssuePriority? = entries.find { it.number == number }

        /** 문자열 [displayName]으로 [IssuePriority]를 조회한다. 미정의 이름이면 [IllegalArgumentException]. */
        fun fromName(displayName: String): IssuePriority =
            entries.find { it.displayName == displayName }
                ?: throw IllegalArgumentException(
                    "unknown priority name: \"$displayName\". valid names: ${entries.map { it.displayName }}",
                )
    }
}
