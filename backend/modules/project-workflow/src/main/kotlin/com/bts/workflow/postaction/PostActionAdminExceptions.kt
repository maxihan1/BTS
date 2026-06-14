// post-action 관리 API 도메인 예외 — 검증 실패(400) / 미존재(404)

package com.bts.workflow.postaction

import java.util.UUID

/**
 * post-action 요청 검증 실패 예외 (HTTP 400).
 *
 * 미지원 type, 필수 config 키 누락, CALL_WEBHOOK url 비-http 스킴 시 발생한다.
 *
 * @param reason 검증 실패 사유.
 */
class PostActionValidationException(
    val reason: String,
) : RuntimeException("post-action 검증 실패: $reason")

/**
 * post-action 또는 전이가 존재하지 않을 때 던지는 예외 (HTTP 404).
 *
 * transitionKey 파싱 오류, 전이 미존재, post-action id 미존재 시 발생한다.
 *
 * @param detail 조회를 시도한 대상 상세 정보.
 */
class PostActionNotFoundException(
    val detail: String,
) : RuntimeException("post-action 또는 전이를 찾을 수 없습니다: $detail")
