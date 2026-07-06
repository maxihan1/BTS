// 사용자 상태 설정(replace) 요청 DTO — 평이 nullable(부재=미설정, replace 시맨틱) (FR-PR-02)

package com.atlas.bts.identity.dto

import java.time.Instant

/**
 * 상태 메시지 설정 요청 DTO (FR-PR-02).
 *
 * FR-PR-01 프로필의 JsonNode 3-state 와 달리, 상태는 통짜 값이라 부재 필드는 "미설정"(null)으로
 * 해석된다(replace 시맨틱, 보존 아님). emoji/text 정규화·해제 판정은 서비스([StatusPatch]) 책임.
 * expiresAt 은 ISO-8601 Instant 문자열로 역직렬화된다.
 */
data class StatusPatchRequest(
    val emoji: String?,
    val text: String?,
    val expiresAt: Instant?,
)
