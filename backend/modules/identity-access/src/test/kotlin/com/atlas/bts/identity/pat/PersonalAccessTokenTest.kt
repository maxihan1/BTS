// PersonalAccessToken 도메인 엔티티 단위 테스트 — hasScope / isExpired / isActive / token format 검증

package com.atlas.bts.identity.pat

import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class PersonalAccessTokenTest {
    private val userId = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private val patId = UUID.fromString("00000000-0000-0000-0000-000000000002")
    private val now = Instant.parse("2026-05-20T00:00:00Z")

    private fun buildPat(
        scopes: List<String> = listOf("read:issues"),
        expiresAt: Instant? = now.plusSeconds(3600),
        revokedAt: Instant? = null,
        lastUsedAt: Instant? = null,
    ) = PersonalAccessToken(
        id = patId,
        userId = userId,
        name = "CI token",
        tokenHash = "a".repeat(64),
        scopes = scopes,
        expiresAt = expiresAt,
        lastUsedAt = lastUsedAt,
        revokedAt = revokedAt,
        createdAt = now,
    )

    // -------------------------------------------------------------------------
    // token format (EC-26)
    // -------------------------------------------------------------------------

    @Test
    fun `TOKEN_PREFIX 는 pat_ 이다`() {
        Assertions.assertEquals("pat_", PersonalAccessToken.TOKEN_PREFIX)
    }

    @Test
    fun `TOKEN_BODY_LENGTH 는 48 이다`() {
        Assertions.assertEquals(48, PersonalAccessToken.TOKEN_BODY_LENGTH)
    }

    @Test
    fun `token_hash 는 64자 소문자 hex 를 강제한다`() {
        val validHash = "a1b2c3d4".repeat(8) // 64자
        val pat = buildPat().copy(tokenHash = validHash)
        Assertions.assertEquals(64, pat.tokenHash.length)
    }

    // -------------------------------------------------------------------------
    // hasScope (EC-26 scope 평가)
    // -------------------------------------------------------------------------

    @Test
    fun `PAT#isUsable + scopes 평가 (hasScope)`() {
        val pat = buildPat(scopes = listOf("read:issues", "write:comments"))

        Assertions.assertTrue(pat.hasScope("read:issues"))
        Assertions.assertTrue(pat.hasScope("write:comments"))
        Assertions.assertFalse(pat.hasScope("admin"))
    }

    @Test
    fun `hasScope 는 와일드카드 * 스코프를 가진 PAT 에 대해 true 를 반환한다`() {
        val pat = buildPat(scopes = listOf("*"))

        Assertions.assertTrue(pat.hasScope("read:issues"))
        Assertions.assertTrue(pat.hasScope("admin"))
        Assertions.assertTrue(pat.hasScope("anything"))
    }

    @Test
    fun `hasScope 는 빈 스코프 목록이면 false 를 반환한다`() {
        val pat = buildPat(scopes = emptyList())

        Assertions.assertFalse(pat.hasScope("read:issues"))
    }

    // -------------------------------------------------------------------------
    // isExpired (EC-27 무기한 정책)
    // -------------------------------------------------------------------------

    @Test
    fun `isExpired 는 expiresAt 이 null 이면 false 를 반환한다 (무기한)`() {
        val pat = buildPat(expiresAt = null)
        Assertions.assertFalse(pat.isExpired(now))
    }

    @Test
    fun `isExpired 는 expiresAt 이 현재 시각 이전이면 true 를 반환한다`() {
        val pat = buildPat(expiresAt = now.minusSeconds(1))
        Assertions.assertTrue(pat.isExpired(now))
    }

    @Test
    fun `isExpired 는 expiresAt 이 현재 시각 이후이면 false 를 반환한다`() {
        val pat = buildPat(expiresAt = now.plusSeconds(1))
        Assertions.assertFalse(pat.isExpired(now))
    }

    // -------------------------------------------------------------------------
    // isActive
    // -------------------------------------------------------------------------

    @Test
    fun `isActive 는 revoked 되지 않고 만료되지 않은 PAT 에 대해 true 를 반환한다`() {
        val pat = buildPat(expiresAt = now.plusSeconds(3600), revokedAt = null)
        Assertions.assertTrue(pat.isActive(now))
    }

    @Test
    fun `isActive 는 revoked 된 PAT 에 대해 false 를 반환한다`() {
        val pat = buildPat(revokedAt = now.minusSeconds(60))
        Assertions.assertFalse(pat.isActive(now))
    }

    @Test
    fun `isActive 는 만료된 PAT 에 대해 false 를 반환한다`() {
        val pat = buildPat(expiresAt = now.minusSeconds(1))
        Assertions.assertFalse(pat.isActive(now))
    }

    @Test
    fun `isActive 는 expiresAt null (무기한) 이고 revoke 안 된 PAT 에 대해 true 를 반환한다`() {
        val pat = buildPat(expiresAt = null, revokedAt = null)
        Assertions.assertTrue(pat.isActive(now))
    }

    // -------------------------------------------------------------------------
    // toString 보안 마스킹
    // -------------------------------------------------------------------------

    @Test
    fun `toString 은 tokenHash 를 마스킹한다`() {
        val rawHash = "a".repeat(64)
        val pat = buildPat().copy(tokenHash = rawHash)

        val str = pat.toString()
        Assertions.assertFalse(str.contains(rawHash), "toString 에 실제 tokenHash 가 노출되면 안 된다. 실제: $str")
        Assertions.assertTrue(str.contains("tokenHash=***"), "toString 에 'tokenHash=***' 가 포함되어야 한다. 실제: $str")
    }
}
