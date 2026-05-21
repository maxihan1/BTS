// RefreshToken 도메인 엔티티 단위 테스트 — isUsable 경계값 / tokenHash SHA-256 hex 64자 포맷 강제 / rotation chain

package com.atlas.bts.identity.session

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class RefreshTokenTest {

    private val fixedNow = Instant.parse("2026-05-20T12:00:00Z")
    private val tokenId = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000001")
    private val sessionId = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001")

    /** SHA-256 hex 64자 소문자 유효 해시 */
    private val validHash = "a".repeat(64)

    private fun token(
        expiresAt: Instant = fixedNow.plusSeconds(3600),
        usedAt: Instant? = null,
        replacedBy: UUID? = null,
    ) = RefreshToken(
        id = tokenId,
        sessionId = sessionId,
        tokenHash = validHash,
        issuedAt = fixedNow.minusSeconds(10),
        expiresAt = expiresAt,
        usedAt = usedAt,
        replacedBy = replacedBy,
    )

    // ── 필드 노출 ──────────────────────────────────────────────

    @Test
    fun `RefreshToken 생성 후 7개 필드가 정상 노출된다`() {
        val rt = token()

        assertThat(rt.id).isEqualTo(tokenId)
        assertThat(rt.sessionId).isEqualTo(sessionId)
        assertThat(rt.tokenHash).isEqualTo(validHash)
        assertThat(rt.issuedAt).isEqualTo(fixedNow.minusSeconds(10))
        assertThat(rt.expiresAt).isEqualTo(fixedNow.plusSeconds(3600))
        assertThat(rt.usedAt).isNull()
        assertThat(rt.replacedBy).isNull()
    }

    // ── tokenHash 포맷 강제 ────────────────────────────────────

    @Test
    fun `tokenHash 는 64자 SHA-256 hex 강제 — 63자이면 IllegalArgumentException`() {
        assertThatThrownBy { token().copy(tokenHash = "a".repeat(63)) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `tokenHash 는 64자 SHA-256 hex 강제 — 65자이면 IllegalArgumentException`() {
        assertThatThrownBy { token().copy(tokenHash = "a".repeat(65)) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `tokenHash 는 64자 SHA-256 hex 강제 — 대문자 포함이면 IllegalArgumentException`() {
        assertThatThrownBy { token().copy(tokenHash = "A".repeat(64)) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `tokenHash 는 64자 SHA-256 hex 강제 — 비hex 문자 포함이면 IllegalArgumentException`() {
        assertThatThrownBy { token().copy(tokenHash = "g".repeat(64)) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `tokenHash 64자 소문자 hex 는 정상 생성된다`() {
        val validHex = "0123456789abcdef".repeat(4)  // 64자
        assertThat(token().copy(tokenHash = validHex)).isNotNull
    }

    // ── isUsable ───────────────────────────────────────────────

    @Test
    fun `isUsable — usedAt null이고 expiresAt이 now 이후이면 true`() {
        assertThat(token(expiresAt = fixedNow.plusSeconds(1)).isUsable(fixedNow)).isTrue
    }

    @Test
    fun `isUsable — usedAt이 설정되면 만료 전이어도 false`() {
        assertThat(token(usedAt = fixedNow.minusSeconds(1)).isUsable(fixedNow)).isFalse
    }

    @Test
    fun `isUsable — expiresAt이 now와 같으면 false (경계값, 만료 포함)`() {
        assertThat(token(expiresAt = fixedNow).isUsable(fixedNow)).isFalse
    }

    @Test
    fun `isUsable — expiresAt이 now보다 과거이면 false`() {
        assertThat(token(expiresAt = fixedNow.minusSeconds(1)).isUsable(fixedNow)).isFalse
    }

    @Test
    fun `isUsable — usedAt 설정 + expiresAt 과거이면 false`() {
        assertThat(
            token(
                expiresAt = fixedNow.minusSeconds(10),
                usedAt = fixedNow.minusSeconds(20),
            ).isUsable(fixedNow),
        ).isFalse
    }

    // ── isExpired ──────────────────────────────────────────────

    @Test
    fun `isExpired — expiresAt이 now보다 미래이면 false`() {
        assertThat(token(expiresAt = fixedNow.plusSeconds(1)).isExpired(fixedNow)).isFalse
    }

    @Test
    fun `isExpired — expiresAt이 now와 같으면 true (경계값)`() {
        assertThat(token(expiresAt = fixedNow).isExpired(fixedNow)).isTrue
    }

    @Test
    fun `isExpired — expiresAt이 now보다 과거이면 true`() {
        assertThat(token(expiresAt = fixedNow.minusSeconds(1)).isExpired(fixedNow)).isTrue
    }

    // ── isReplaced (rotation chain) ───────────────────────────

    @Test
    fun `isReplaced — replacedBy null이면 false`() {
        assertThat(token(replacedBy = null).isReplaced()).isFalse
    }

    @Test
    fun `isReplaced — replacedBy UUID 설정 시 true (rotation chain 표현)`() {
        val nextId = UUID.fromString("cccccccc-0000-0000-0000-000000000001")
        assertThat(token(replacedBy = nextId).isReplaced()).isTrue
    }

    // ── HASH_LENGTH 상수 ───────────────────────────────────────

    @Test
    fun `HASH_LENGTH 상수는 64이다`() {
        assertThat(RefreshToken.HASH_LENGTH).isEqualTo(64)
    }
}
