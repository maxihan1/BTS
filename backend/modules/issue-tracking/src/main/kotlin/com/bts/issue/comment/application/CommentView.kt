// 댓글 조회 결과 VO — 원문 body + renderSafe 렌더링 결과 bodyHtml 동시 보유 (FR-IM-01 PR3)

package com.bts.issue.comment.application

import com.bts.issue.comment.domain.Comment
import com.bts.issue.markdown.MarkdownRenderer
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
) {
    companion object {
        /**
         * [Comment] 도메인 엔티티에 렌더링된 `bodyHtml` 을 더해 뷰로 변환한다.
         *
         * **Markdown 렌더링의 단일 지점이다.** 목록 조회([CommentApplicationService.list]),
         * 작성 응답([com.bts.issue.comment.web.CommentController.addComment]),
         * 수정 응답([com.bts.issue.comment.web.CommentController.updateComment]) 이 같은 함수를 쓰므로
         * 세 경로의 `bodyHtml` 정책이 갈라질 수 없다. 렌더러를 두 곳에서 직접 부르면 한쪽만
         * 정책이 바뀌어 XSS 방어가 비대칭이 되는 형태의 회귀가 가능해진다.
         *
         * @param comment 변환할 댓글 엔티티.
         * @return `bodyHtml` 이 채워진 [CommentView].
         */
        fun of(comment: Comment): CommentView =
            CommentView(
                id = comment.id,
                authorId = comment.authorId,
                body = comment.body,
                bodyHtml = MarkdownRenderer.renderSafe(comment.body),
                createdAt = comment.createdAt,
                updatedAt = comment.updatedAt,
            )
    }
}
