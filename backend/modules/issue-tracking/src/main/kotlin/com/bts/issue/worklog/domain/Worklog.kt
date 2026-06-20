// 워크로그 도메인 엔티티 — Issue 애그리거트 자식. 작업 소요 시간 기록 (FR-TT-01).

package com.bts.issue.worklog.domain

import java.time.Instant
import java.util.UUID

/**
 * 워크로그 도메인 엔티티.
 *
 * Issue 애그리거트의 자식으로, 이슈에 소요된 작업 시간을 기록한다.
 * 모든 필드는 불변(val)이다. 소프트 삭제(`deleted_at`)는 리포지토리 내부에서만 처리하며,
 * 도메인 레이어에는 노출하지 않는다 (DATA.md §1.2 #7).
 *
 * @property id              워크로그 UUID PK.
 * @property issueId         소속 이슈 UUID (FK: issues.id, BC 격리로 도메인 참조 없이 UUID 직접 보유).
 * @property authorId        작성자 UUID (BC 격리 — identity-access users FK 미적용).
 * @property timeSpentSeconds 작업 소요 시간 (초, 양수).
 * @property startedAt       작업 시작 시각.
 * @property comment         선택적 코멘트.
 * @property createdAt       생성 시각.
 * @property updatedAt       마지막 수정 시각.
 */
data class Worklog(
    val id: UUID,
    val issueId: UUID,
    val authorId: UUID,
    val timeSpentSeconds: Int,
    val startedAt: Instant,
    val comment: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
)
