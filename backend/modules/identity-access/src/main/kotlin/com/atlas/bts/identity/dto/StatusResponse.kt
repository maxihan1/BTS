// 사용자 상태 조회/설정 응답 DTO — 이모지/텍스트/만료시각 (FR-PR-02)

package com.atlas.bts.identity.dto

import java.time.Instant

/**
 * 상태 메시지 응답 DTO (FR-PR-02).
 *
 * 미설정/만료/해제 상태는 세 필드 모두 null 이다. expiresAt 은 ISO-8601 Instant 로 직렬화된다.
 */
data class StatusResponse(
    val emoji: String?,
    val text: String?,
    val expiresAt: Instant?,
)
