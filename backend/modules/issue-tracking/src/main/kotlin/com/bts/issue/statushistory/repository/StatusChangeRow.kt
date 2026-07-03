// 상태 전이 이력 조회 결과 1행 — issue_change_group/item JOIN 결과 매핑 VO (CFD·cycletime 공유)

package com.bts.issue.statushistory.repository

import java.time.Instant
import java.util.UUID

/**
 * [StatusHistoryRepository.fetchStatusChanges] 조회 결과 1행.
 *
 * `issue_change_group`(변경 그룹)과 `issue_change_item`(개별 필드 변경) 을 조인해
 * `field = 'status'` 인 항목만 추출한 것으로, 이슈 하나의 상태 전이 1건을 나타낸다.
 *
 * @property issueId 상태가 전이된 이슈의 UUID.
 * @property changedAt 전이가 발생한 시각(`issue_change_group.created_at`).
 * @property groupId 소속 변경 그룹 id(`issue_change_group.id`) — 동일 시각 전이의 tie-break 정렬용.
 * @property fromValue 전이 전 상태 키. null 이면 이전 값 없음(이론상 status 필드는 항상 값을 가짐).
 * @property toValue 전이 후 상태 키. null 이면 이후 값 없음.
 */
data class StatusChangeRow(
    val issueId: UUID,
    val changedAt: Instant,
    val groupId: Long,
    val fromValue: String?,
    val toValue: String?,
)
