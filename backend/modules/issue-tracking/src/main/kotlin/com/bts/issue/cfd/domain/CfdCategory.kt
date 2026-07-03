// CFD(누적 흐름 다이어그램) 상태 카테고리 3종 — 워크플로우 상태 문자열 표준화

package com.bts.issue.cfd.domain

/**
 * CFD(Cumulative Flow Diagram, 누적 흐름 다이어그램)의 상태 카테고리 3종.
 *
 * 워크플로우의 개별 상태(예: "In Review", "Done")는 이 3개 카테고리 중 하나로 매핑되어 누적 띠로
 * 집계된다.
 */
enum class CfdCategory {
    TODO,
    IN_PROGRESS,
    DONE,
    ;

    companion object {
        /**
         * 워크플로우 카테고리 문자열을 [CfdCategory]로 정규화한다.
         *
         * 대소문자를 구분하지 않는다. `null`이거나 표준 3값이 아닌 문자열은 [TODO]로 폴백한다
         * (`EpicProgress.NormalizedCategory` 폴백 정책과 동형).
         *
         * @param s 정규화할 카테고리 문자열(nullable).
         * @return 정규화된 [CfdCategory].
         */
        fun fromCategoryString(s: String?): CfdCategory =
            when (s?.uppercase()) {
                "IN_PROGRESS" -> IN_PROGRESS
                "DONE" -> DONE
                else -> TODO
            }
    }
}
