// 댓글 작성 요청 DTO — POST /api/v1/issues/{key}/comments (FR-CO-01)

package com.bts.issue.comment.web

/**
 * 댓글 작성 요청 본문.
 *
 * ## ★ 저작자 필드가 없는 것이 설계다
 * `authorId`·`author`·`userId` 같은 저작자 지정 필드를 **의도적으로 두지 않는다.** 저작자는 서버가
 * 인증 주체(actor)로 결정하며([com.bts.issue.comment.application.CommentApplicationService.create]),
 * 요청이 저작자를 제안할 창구 자체가 존재하지 않는다. 필드를 두고 무시하는 것과 필드가 없는 것은
 * 다르다 — 후자만 컴파일·바인딩 수준에서 위조를 불가능하게 한다.
 *
 * 미지 필드가 섞여 와도 Spring Boot 의 Jackson 기본 설정(`FAIL_ON_UNKNOWN_PROPERTIES` 비활성)이
 * 조용히 버린다.
 *
 * 원본 저작자를 보존해야 하는 Import 는 REST 를 거치지 않고
 * [com.bts.issue.comment.application.CommentApplicationService.createImported] 를 직접 호출한다.
 *
 * @property body 댓글 본문 (raw markdown). 필수. 공백만이거나
 *   [com.bts.issue.comment.application.CommentApplicationService.MAX_BODY_LENGTH] 초과면 400.
 *   `null` (필드 누락) 판정은 컨트롤러가, 공백·길이 판정은 서비스가 담당한다
 *   (요청 형식 = 웹 관심사 / 본문 정책 = 도메인 관심사).
 * @property bodyHtml 리치 에디터가 보낸 HTML 본문 (선택, V039). 주면 서버가
 *   [com.bts.issue.markdown.MarkdownRenderer.sanitizeHtml] 로 정화해 저장한다.
 *   ★[body] 를 **대체하지 않는다** — 검색·알림·이력이 원문을 쓰므로 에디터도 평문 표현을 함께 보낸다.
 *   생략하면 서버가 [body] 를 렌더해 채운다(레거시·CSV import 경로).
 */
data class AddCommentRequest(
    val body: String?,
    val bodyHtml: String? = null,
)
