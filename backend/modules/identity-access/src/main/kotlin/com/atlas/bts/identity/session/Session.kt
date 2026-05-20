// sessions 테이블 행 매핑 도메인 엔티티 — 세션 만료·폐기 상태 판별 포함 (SDD 19.5)

package com.atlas.bts.identity.session

import java.time.Instant
import java.util.UUID

data class Session(
    val id: UUID,
    val userId: UUID,
    val providerId: String,
    val deviceFingerprint: String?,
    val ipAddress: String?,
    val userAgent: String?,
    val createdAt: Instant,
    val expiresAt: Instant,
    val lastSeenAt: Instant,
    val revokedAt: Instant?,
    val revokeReason: String?,
) {
    fun isActive(now: Instant): Boolean = revokedAt == null && expiresAt > now

    fun isExpired(now: Instant): Boolean = expiresAt <= now

    fun isRevoked(): Boolean = revokedAt != null
}
