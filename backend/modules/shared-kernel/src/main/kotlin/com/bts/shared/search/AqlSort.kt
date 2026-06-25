// AQL ORDER BY 정렬 기준 값 객체

package com.bts.shared.search

/**
 * AQL 쿼리의 ORDER BY 정렬 기준을 담는 불변 값 객체.
 *
 * `ORDER BY priority DESC, summary ASC` 와 같은 다중 정렬을 표현하기 위해
 * [IssueSearchQuery.sort] 는 [AqlSort] 목록을 가진다.
 *
 * @property field 정렬 대상 필드. [AqlField] 참조.
 * @property direction 정렬 방향. [SortDirection] 참조.
 * @see IssueSearchQuery
 */
data class AqlSort(
    val field: AqlField,
    val direction: SortDirection,
)

/**
 * AQL 정렬 방향.
 *
 * AQL 문법의 `ASC` / `DESC` 키워드를 나타낸다.
 * 파서는 키워드 미지정 시 기본값을 소비측(어댑터)이 결정하도록 [ASC] 를 기본으로 둔다.
 */
enum class SortDirection {
    /** 오름차순 정렬. 숫자는 작은 값이 앞, 문자열은 사전순 앞. */
    ASC,

    /** 내림차순 정렬. 숫자는 큰 값이 앞, 문자열은 사전순 뒤. */
    DESC,
}
