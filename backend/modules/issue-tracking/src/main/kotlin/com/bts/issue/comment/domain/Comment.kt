// 댓글 도메인 엔티티 — Issue 애그리거트 자식. 이슈에 작성된 댓글 기록 (FR-IM-01 PR3).

package com.bts.issue.comment.domain

import java.time.Instant
import java.util.UUID

/**
 * 댓글 도메인 엔티티.
 *
 * Issue 애그리거트의 자식으로, 이슈에 작성된 댓글 1건을 표현한다.
 * 모든 필드는 불변(val)이다. 소프트 삭제(`deleted_at`)는 리포지토리 내부에서만 처리하며,
 * 도메인 레이어에는 노출하지 않는다 (DATA.md §1.2 #7, worklog 선례).
 *
 * @property id        댓글 UUID PK.
 * @property issueId   소속 이슈 UUID (FK: issues.id, BC 격리로 도메인 참조 없이 UUID 직접 보유).
 * @property authorId  작성자 UUID (BC 격리 — identity-access users FK 미적용).
 * @property body      댓글 본문 (raw markdown 원문).
 * @property createdAt 생성 시각.
 * @property updatedAt 마지막 수정 시각.
 */
data class Comment(
    val id: UUID,
    val issueId: UUID,
    val authorId: UUID,
    val body: String,
    /**
     * 정화된 HTML 본문 (V039). null 이면 아직 HTML 로 저장된 적 없는 옛 댓글이고,
     * [com.bts.issue.comment.application.CommentView.of] 가 [body] 를 렌더해 채운다.
     */
    val bodyHtml: String? = null,
    val createdAt: Instant,
    val updatedAt: Instant,
)
