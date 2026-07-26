// 댓글 본문 검증 도메인 예외 — 공백/길이 위반 (FR-CO-01)

package com.bts.issue.comment.domain

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
