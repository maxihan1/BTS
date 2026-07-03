// CFD(누적 흐름도) 집계 원천 이슈 메타 read-model — repository 계층 전용, cfd 패키지 비의존 (FR-RP-03 Task 2)

package com.bts.issue.repository

import java.time.Instant
import java.util.UUID

/**
 * [IssueRepository.fetchActiveVisibleIssuesForCfd] 조회 결과 1행.
 *
 * CFD(Cumulative Flow Diagram, 누적 흐름도) 집계에 필요한 이슈 최소 메타데이터만 담는다.
 * core repository(`com.bts.issue.repository`)가 feature 패키지(`cfd`)를 의존하지 않도록
 * 이 read-model 을 repository 계층에 정의한다(CONCERN-1 반영).
 *
 * @property issueId 이슈 UUID.
 * @property typeId 이슈 유형 id (issue_types.id).
 * @property currentStateKey 이슈 현재 워크플로우 상태 키.
 * @property createdAt 이슈 생성 시각.
 */
data class CfdIssueSourceRow(
    val issueId: UUID,
    val typeId: Long,
    val currentStateKey: String,
    val createdAt: Instant,
)
