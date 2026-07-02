// 댓글 조회 결과 VO — 원문 body + renderSafe 렌더링 결과 bodyHtml 동시 보유 (FR-IM-01 PR3)

package com.bts.issue.comment.application

import java.time.Instant
import java.util.UUID

/**
 * 댓글 조회 결과 VO.
 *
 * [CommentApplicationService.list] 가 반환하는 댓글 1건 표현.
 * [com.bts.issue.comment.domain.Comment] 도메인 엔티티에 렌더링된 [bodyHtml] 을 더한 뷰 모델이다.
 *
 * @property id        댓글 UUID.
 * @property authorId  작성자 UUID.
 * @property body      댓글 본문 원문 (raw markdown).
 * @property bodyHtml  [com.bts.issue.markdown.MarkdownRenderer.renderSafe] 로 렌더링된 안전한 HTML.
 * @property createdAt 생성 시각.
 * @property updatedAt 마지막 수정 시각.
 */
data class CommentView(
    val id: UUID,
    val authorId: UUID,
    val body: String,
    val bodyHtml: String,
    val createdAt: Instant,
    val updatedAt: Instant,
)
