// 이슈 변경 이력 단건 — V018 issue_change_item 테이블의 도메인 표현

package com.bts.issue.history

/**
 * 이슈 변경 이력의 단건 필드 변경 내역.
 *
 * V018 마이그레이션의 `issue_change_item` 테이블 컬럼과 1:1 대응한다.
 * 이 단계(T2)에서 [fromLabel] / [toLabel] 은 항상 null 이다 — 라벨 해석은 T3 resolver 책임.
 *
 * @property field 변경된 필드명. 예: `"priority"`, `"status"`, `"customField:my_key"`.
 * @property fromValue 변경 전 값을 문자열로 직렬화한 결과. null 이면 값 없음(이전에 미설정).
 * @property toValue 변경 후 값을 문자열로 직렬화한 결과. null 이면 값 없음(clear 됨).
 * @property fromLabel [fromValue] 에 대응하는 사람이 읽기 쉬운 레이블. T3 resolver 가 채운다.
 * @property toLabel [toValue] 에 대응하는 사람이 읽기 쉬운 레이블. T3 resolver 가 채운다.
 */
data class IssueChangeItem(
    val field: String,
    val fromValue: String?,
    val toValue: String?,
    val fromLabel: String? = null,
    val toLabel: String? = null,
)
