// trusted_devices 테이블 행 매핑 도메인 엔티티 — 30일 MFA 면제 신뢰 디바이스 + 만료 판정 (FR-MF-05)

package com.atlas.bts.identity.mfa

import java.time.Instant
import java.util.UUID

data class TrustedDevice(
    val id: UUID,
    val userId: UUID,
    val tokenHash: String,
    val label: String?,
    val createdAt: Instant,
    val expiresAt: Instant,
    val lastUsedAt: Instant?,
) {
    fun isExpired(now: Instant): Boolean = !now.isBefore(expiresAt)
}
