// 프로젝트 활동 피드 항목 도메인 모델 — 변경 그룹 하나를 사람이 읽는 한 줄로

package com.bts.issue.summary.domain

import com.bts.issue.history.IssueChangeItem
import java.time.Instant
import java.util.UUID

/**
 * 프로젝트 활동 피드의 항목 하나 — 변경 그룹(한 트랜잭션) 하나에 대응한다.
 *
 * [com.bts.issue.application.ChangelogGroupView] 와 모양이 같지만 **이슈 키가 추가**된다.
 * 이슈 단건 changelog 는 어느 이슈인지 이미 알지만, 프로젝트 피드는 줄마다 이슈를 밝혀야 한다.
 *
 * @property issueKey 기록 시점 이슈 키(예: `BTS-1`). 이슈가 옮겨져도 당시 키가 보존된다.
 * @property actorId 변경 주체 UUID. null 이면 시스템 자동 변경.
 * @property actorName 표시명. [actorId] 가 null 이거나 identity-access 조회가 실패하면 null.
 * @property createdAt 변경 발생 시각.
 * @property items 이 그룹에 속한 필드 변경 항목들. 라벨은 기록 시점 박제값이다.
 */
data class ProjectActivityEntry(
    val issueKey: String,
    val actorId: UUID?,
    val actorName: String?,
    val createdAt: Instant,
    val items: List<IssueChangeItem>,
)
