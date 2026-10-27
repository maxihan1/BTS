// PatUserResolver 단위 테스트 — BTS PAT 먼저, 없으면 Auth_platform PAT (FR-API-04)

package com.atlas.bts.identity.pat

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class PatUserResolverTest {
    private val patService: PersonalAccessTokenService = mockk()
    private val authVerifier: AuthPlatformPatVerifier = mockk()
    private val resolver = PatUserResolver(patService, authVerifier)

    @Test
    fun `BTS PAT 가 맞으면 Auth 를 묻지 않는다`() {
        every { patService.verify(RAW) } returns Result.success(btsPat())

        assertThat(resolver.resolvePatUserId(RAW)).isEqualTo(BTS_PAT_USER)
        verify(exactly = 0) { authVerifier.resolveUserId(any()) }
    }

    @Test
    fun `BTS 가 모르는 PAT 는 Auth 로 확인한다`() {
        every { patService.verify(RAW) } returns Result.failure(PatVerificationException("PAT not found"))
        every { authVerifier.resolveUserId(RAW) } returns AUTH_PAT_USER

        assertThat(resolver.resolvePatUserId(RAW)).isEqualTo(AUTH_PAT_USER)
    }

    @Test
    fun `Auth 확인기가 없으면 BTS DB 만 본다`() {
        every { patService.verify(RAW) } returns Result.failure(PatVerificationException("PAT not found"))

        assertThat(PatUserResolver(patService, null).resolvePatUserId(RAW)).isNull()
    }

    private fun btsPat() =
        PersonalAccessToken(
            id = UUID.randomUUID(),
            userId = BTS_PAT_USER,
            name = "ci",
            tokenHash = sha256Hex(RAW),
            scopes = listOf("*"),
            expiresAt = null,
            lastUsedAt = null,
            revokedAt = null,
            createdAt = Instant.EPOCH,
        )

    private companion object {
        const val RAW = "pat_resolverToken0000000000000000000000000000000"
        val BTS_PAT_USER: UUID = UUID.fromString("33333333-3333-4333-8333-333333333333")
        val AUTH_PAT_USER: UUID = UUID.fromString("44444444-4444-4444-8444-444444444444")
    }
}
