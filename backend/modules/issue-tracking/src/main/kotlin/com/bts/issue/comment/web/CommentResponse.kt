// 댓글 목록 조회 응답 DTO — CommentView를 HTTP 응답 형식으로 변환 (FR-IM-01 PR3)

package com.bts.issue.comment.web

import com.bts.issue.comment.application.CommentView
import java.time.Instant
import java.util.UUID

/**
 * 댓글 응답 DTO.
 *
 * [CommentView] 를 HTTP 응답 형식으로 변환한다.
 *
 * @property id        댓글 UUID.
 * @property authorId  작성자 UUID.
 * @property body      댓글 본문 원문 (raw markdown).
 * @property bodyHtml  [com.bts.issue.markdown.MarkdownRenderer.renderSafe] 로 렌더링된 안전한 HTML.
 * @property createdAt 생성 시각.
 * @property updatedAt 마지막 수정 시각.
 */
data class CommentResponse(
    val id: UUID,
    val authorId: UUID,
    val body: String,
    val bodyHtml: String,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    companion object {
        /**
         * [CommentView] 로부터 [CommentResponse] 를 생성한다.
         *
         * [CommentController.listComments] 가 이 팩토리로 조회 결과를 응답 DTO 로 변환한다
         * ([com.bts.issue.worklog.web.dto.WorklogResponse.from] 과 동일한 컨트롤러-DTO 매핑 관례).
         *
         * @param view 변환할 조회 결과 뷰.
         * @return 변환된 응답 DTO.
         */
        fun from(view: CommentView): CommentResponse =
            CommentResponse(
                id = view.id,
                authorId = view.authorId,
                body = view.body,
                bodyHtml = view.bodyHtml,
                createdAt = view.createdAt,
                updatedAt = view.updatedAt,
            )

        /**
         * [com.bts.issue.comment.domain.Comment] 로부터 응답 DTO 를 생성한다 (작성 응답용, FR-CO-01).
         *
         * `bodyHtml` 렌더링은 [CommentView.of] 에 위임한다 — 목록 조회와 **같은 단일 지점**을 쓰므로
         * 두 경로의 렌더 정책이 갈라질 수 없다.
         *
         * @param comment 변환할 댓글 엔티티.
         * @return 변환된 응답 DTO.
         */
        fun from(comment: com.bts.issue.comment.domain.Comment): CommentResponse = from(CommentView.of(comment))
    }
}
