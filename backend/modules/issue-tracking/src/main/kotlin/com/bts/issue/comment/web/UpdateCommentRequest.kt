// 댓글 수정 요청 DTO — PATCH /api/v1/issues/{key}/comments/{commentId} (FR-CO-02)

package com.bts.issue.comment.web

/**
 * 댓글 수정 요청 본문.
 *
 * ## ★ 저작자 필드가 없는 것이 설계다 ([AddCommentRequest] D4 계승)
 * `authorId`·`author`·`userId` 같은 저작자 지정 필드를 **의도적으로 두지 않는다.** 수정은 저작자
 * 본인만 가능하며([com.bts.issue.comment.application.CommentApplicationService.update]),
 * 저작자는 저장된 댓글의 값과 인증 주체(actor)를 대조해 판정한다. 요청이 저작자를 제안할 창구
 * 자체가 존재하지 않는다 — 필드를 두고 무시하는 것과 필드가 없는 것은 다르다.
 *
 * 미지 필드가 섞여 와도 Spring Boot 의 Jackson 기본 설정(`FAIL_ON_UNKNOWN_PROPERTIES` 비활성)이
 * 조용히 버린다.
 *
 * @property body 새 댓글 본문 (raw markdown). 필수. 공백만이거나
 *   [com.bts.issue.comment.application.CommentApplicationService.MAX_BODY_LENGTH] 초과면 400.
 *   `null` (필드 누락) 판정은 컨트롤러가, 공백·길이 판정은 서비스가 담당한다
 *   ([AddCommentRequest] 와 동일한 책임 분담 — 요청 형식 = 웹 관심사 / 본문 정책 = 도메인 관심사).
 * @property bodyHtml 리치 에디터가 보낸 HTML 본문 (선택, V039). [AddCommentRequest.bodyHtml] 과 같은 규칙.
 */
data class UpdateCommentRequest(
    val body: String?,
    val bodyHtml: String? = null,
)
