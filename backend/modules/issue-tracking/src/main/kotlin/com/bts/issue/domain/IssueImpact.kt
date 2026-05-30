// 이슈 영향도 SMALLINT↔이름 매핑 — SDD 05 impact 1..3, SDD 08/09/10 문자열 이름 재사용 대비

package com.bts.issue.domain

/**
 * 이슈 영향도 매핑.
 *
 * DB에는 SMALLINT(1~3)로 저장되고, API/자동화(SDD 08/09/10)에서는 문자열 이름("High" 등)으로
 * 참조된다. 이 enum 이 두 표현의 단일 출처(single source of truth)이다.
 *
 * 매핑 근거 (SDD 05 §impact 정의).
 * ```
 * 1 = High   (치명적 영향)
 * 2 = Medium (중간 영향)
 * 3 = Low    (낮은 영향)
 * ```
 *
 * @property number      DB에 저장되는 SMALLINT 값 (1..3).
 * @property displayName API/자동화에서 사용하는 문자열 표현.
 */
enum class IssueImpact(val number: Int, val displayName: String) {
    HIGH(1, "High"),
    MEDIUM(2, "Medium"),
    LOW(3, "Low");

    companion object {
        /** 정수 [number]로 [IssueImpact]를 조회한다. 1..3 범위를 벗어나면 [IllegalArgumentException]. */
        fun fromNumber(number: Int): IssueImpact =
            entries.find { it.number == number }
                ?: throw IllegalArgumentException(
                    "impact number must be between 1 and 3, but was $number"
                )

        /** 문자열 [displayName]으로 [IssueImpact]를 조회한다. 미정의 이름이면 [IllegalArgumentException]. */
        fun fromName(displayName: String): IssueImpact =
            entries.find { it.displayName == displayName }
                ?: throw IllegalArgumentException(
                    "unknown impact name: \"$displayName\". valid names: ${entries.map { it.displayName }}"
                )
    }
}
