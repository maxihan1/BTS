// Cycle/Lead Time 분포 집계 원천 이슈 메타 read-model — repository 계층 전용, feature 패키지 비의존 (FR-RP-04 Task 4)

package com.bts.issue.repository

import java.time.Instant
import java.util.UUID

/**
 * [IssueRepository.fetchActiveVisibleIssuesForCycleTime] 조회 결과 1행.
 *
 * Cycle/Lead Time 분포 집계에 필요한 이슈 최소 메타데이터를 담는다.
 * [CfdIssueSourceRow] 를 미러하되, 분포 응답 샘플에 이슈를 식별해 노출할 수 있도록 `issueKey` 를 추가한다.
 * core repository(`com.bts.issue.repository`)가 feature 패키지를 의존하지 않도록
 * 이 read-model 을 repository 계층에 정의한다.
 *
 * @property issueId 이슈 UUID.
 * @property issueKey 이슈 키 (`<PROJECT_KEY>-<NUMBER>`). 분포 응답 샘플 식별에 사용.
 * @property typeId 이슈 유형 id (issue_types.id).
 * @property currentStateKey 이슈 현재 워크플로우 상태 키.
 * @property createdAt 이슈 생성 시각.
 */
data class CycleTimeIssueSourceRow(
    val issueId: UUID,
    val issueKey: String,
    val typeId: Long,
    val currentStateKey: String,
    val createdAt: Instant,
)
