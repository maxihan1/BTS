// GraphNeighborRow — 그래프 시각화용 이슈 노드 읽기 모델 (parent/children 조회 결과)

package com.bts.issue.link.repository

import java.util.UUID

/**
 * 이슈 그래프 시각화에서 부모 또는 자식 노드를 표현하는 읽기 전용 결과 행.
 *
 * [IssueGraphRepository.findParent] 및 [IssueGraphRepository.findChildren] 이
 * 이 타입으로 반환한다.
 *
 * ## 용도
 * - 부모 조회(`findParent`): 단건 또는 null.
 * - 자식 조회(`findChildren`): key ASC 정렬 목록.
 *
 * ## 소프트 삭제
 * 소프트삭제(`deleted_at IS NOT NULL`)된 이슈는 쿼리 단에서 제외되므로
 * 이 결과 행은 항상 살아있는 이슈만 나타낸다.
 *
 * @property id 이슈의 내부 UUID.
 * @property key 이슈 키 문자열 (예: "BTS-2").
 * @property summary 이슈 제목.
 * @property statusKey 현재 워크플로우 상태 키.
 */
data class GraphNeighborRow(
    val id: UUID,
    val key: String,
    val summary: String,
    val statusKey: String,
)
