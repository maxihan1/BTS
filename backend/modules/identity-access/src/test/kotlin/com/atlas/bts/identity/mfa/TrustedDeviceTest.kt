// TrustedDevice 도메인 단위 테스트 — isExpired 만료 경계(직전/정각/직후) 검증 (FR-MF-05 Task 2)

package com.atlas.bts.identity.mfa

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * [TrustedDevice] 도메인 단위 테스트.
 *
 * 신뢰 디바이스(30일 MFA 면제)의 만료 판정 경계를 검증한다. EC10 경계 규약상
 * `expires_at == now` 는 만료로 본다 — `now.isBefore(expiresAt)` 일 때만(즉 `expiresAt > now`) 유효하다.
 *
 * ## 검증 시나리오
 * - 만료 직전(now < expiresAt) → 유효(미만료)
 * - 만료 정각(now == expiresAt) → 만료 (경계 포함)
 * - 만료 직후(now > expiresAt) → 만료
 */
class TrustedDeviceTest {
    // SHA-256 hex 64자(소문자) — token_hash 포맷.
    private val tokenHash = "a".repeat(64)
    private val createdAt = Instant.parse("2026-06-13T00:00:00Z")
    private val expiresAt = Instant.parse("2026-07-13T00:00:00Z")

    private fun device(expiresAt: Instant): TrustedDevice =
        TrustedDevice(
            id = UUID.randomUUID(),
            userId = UUID.randomUUID(),
            tokenHash = tokenHash,
            label = "Chrome on macOS",
            createdAt = createdAt,
            expiresAt = expiresAt,
            lastUsedAt = null,
        )

    @Test
    fun `만료 직전(now가 expiresAt보다 1초 이전)이면 미만료다`() {
        val device = device(expiresAt)

        val now = expiresAt.minusSeconds(1)

        assertThat(device.isExpired(now)).isFalse()
    }

    @Test
    fun `만료 정각(now가 expiresAt와 동일)이면 만료다`() {
        val device = device(expiresAt)

        val now = expiresAt

        assertThat(device.isExpired(now)).isTrue()
    }

    @Test
    fun `만료 직후(now가 expiresAt보다 1초 이후)이면 만료다`() {
        val device = device(expiresAt)

        val now = expiresAt.plusSeconds(1)

        assertThat(device.isExpired(now)).isTrue()
    }
}
