// user_statuses 테이블 행 매핑 엔티티 — 이모지/텍스트/만료로 구성된 개인 상태 (FR-PR-02)

package com.atlas.bts.identity.status

import java.time.Instant
import java.util.UUID

/**
 * 사용자 상태 메시지 엔티티 (FR-PR-02, V028 user_statuses).
 *
 * users 와 1:1. 상태는 통짜 값(replace 시맨틱)이라 FR-PR-01 프로필의 필드별 3-state 병합과 달리
 * upsert(설정) 또는 delete(해제)로만 다뤄진다.
 *
 * ## 필드
 * - [userId]: users.id FK 이자 PK (1:1).
 * - [emoji]: 이모지 문자열(예: "🌴"). 미설정 시 null.
 * - [text]: 상태 텍스트. 미설정 시 null. emoji·text 중 최소 하나는 non-null(V028 CHECK).
 * - [expiresAt]: 만료 시각(절대). null 이면 만료 없음. 만료 판정은 조회 SQL 의 lazy 필터 책임.
 */
data class UserStatus(
    val userId: UUID,
    val emoji: String?,
    val text: String?,
    val expiresAt: Instant?,
)
