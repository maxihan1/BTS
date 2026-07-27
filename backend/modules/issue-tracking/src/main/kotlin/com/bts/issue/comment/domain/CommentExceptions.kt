// 댓글 도메인 예외 — 본문 공백/길이 위반 (FR-CO-01) + 대상 부재 (FR-CO-02)

package com.bts.issue.comment.domain

import java.util.UUID

/**
 * 댓글 본문이 비어 있거나 공백·개행만으로 이루어졌을 때.
 *
 * 웹 계층이 `400 Bad Request` 로 번역한다
 * ([com.bts.issue.comment.web.CommentExceptionHandler]).
 */
class CommentBodyBlankException :
    RuntimeException("댓글 본문은 비어 있을 수 없습니다.")

/**
 * 댓글 본문이 허용 길이를 초과했을 때.
 *
 * ## 왜 `ResponseStatusException` 이 아닌가
 * 이 예외는 **서비스 계층**([com.bts.issue.comment.application.CommentApplicationService.create])
 * 에서 던진다. 그 서비스의 호출자는 REST 컨트롤러뿐 아니라 automation 액션
 * ([com.bts.issue.adapter.outbound.automation.AutomationIssueMutationAdapter.addComment])
 * 도 있고, automation 경로는 HTTP 를 모른다. 웹 예외를 서비스에서 던지면 자동화 실행 기록에
 * HTTP 관심사가 새고, 실패 분류가 타입이 아닌 이름 기반으로 흐른다.
 *
 * 따라서 도메인 예외로 던지고 상태코드는 웹 계층이 결정한다.
 *
 * @param actual 실제 본문 길이 (문자 수).
 * @param max 허용 최대 길이 (문자 수).
 */
class CommentBodyTooLongException(
    val actual: Int,
    val max: Int,
) : RuntimeException("댓글 본문은 ${max}자를 넘을 수 없습니다. (현재 ${actual}자)")

/**
 * 수정·삭제 대상 댓글을 찾을 수 없을 때.
 *
 * 다음 세 경우를 **하나의 예외로 합친다** — 미존재 / 이미 소프트 삭제됨 /
 * 경로의 이슈에 속하지 않음. 셋을 구분해 알리면 "그 댓글 id 는 존재하지만 다른 이슈 소속"
 * 이라는 사실이 새어 나가므로, 리포지토리의 `deleted_at IS NULL` + `issue_id` 대조 결과가
 * 비었다는 사실만 표현한다
 * ([com.bts.issue.comment.repository.CommentRepository.findActive] 반환 null,
 * [com.bts.issue.comment.repository.CommentRepository.updateBody] /
 * [com.bts.issue.comment.repository.CommentRepository.softDelete] 반환 0).
 *
 * ## 왜 `ResponseStatusException` 이 아닌가
 * [CommentBodyTooLongException] 과 같은 이유다 — 서비스 계층의 호출자에 HTTP 를 모르는
 * automation 경로가 포함되므로 도메인 예외로 던지고 상태코드는 웹 계층이 결정한다.
 *
 * @param commentId 찾지 못한 댓글 UUID.
 */
class CommentNotFoundException(
    val commentId: UUID,
) : RuntimeException("댓글을 찾을 수 없습니다. (id=$commentId)")
