// PatAuthenticationFilter 단위 테스트 — Auth_platform PAT 도 BTS 사용자 UUID principal + ROLE_PAT 로 세운다 (FR-API-04)

package com.atlas.bts.identity.web

import com.atlas.bts.identity.pat.AuthPlatformPatVerifier
import com.atlas.bts.identity.pat.PatUserResolver
import com.atlas.bts.identity.pat.PatVerificationException
import com.atlas.bts.identity.pat.PersonalAccessTokenService
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.core.Authentication
import org.springframework.security.core.context.SecurityContextHolder
import java.util.UUID

class PatAuthenticationFilterTest {
    private val patService: PersonalAccessTokenService = mockk()
    private val authVerifier: AuthPlatformPatVerifier = mockk()
    private val filter = PatAuthenticationFilter(PatUserResolver(patService, authVerifier))

    @AfterEach
    fun clearContext() {
        SecurityContextHolder.clearContext()
    }

    @Test
    fun `Auth PAT 면 authentication name 이 연결된 BTS 사용자 UUID 다`() {
        every { patService.verify(RAW) } returns Result.failure(PatVerificationException("PAT not found"))
        every { authVerifier.resolveUserId(RAW) } returns BTS_USER_ID

        val auth = runFilter("Bearer $RAW")

        assertThat(auth?.name).isEqualTo(BTS_USER_ID.toString())
        assertThat(auth?.authorities?.map { it.authority }).containsExactly("ROLE_PAT")
    }

    @Test
    fun `어디서도 확인되지 않으면 인증을 세우지 않는다`() {
        every { patService.verify(RAW) } returns Result.failure(PatVerificationException("PAT not found"))
        every { authVerifier.resolveUserId(RAW) } returns null

        assertThat(runFilter("Bearer $RAW")).isNull()
    }

    /** 필터를 돌리고, 다음 필터가 받는 시점의 인증을 돌려준다. */
    private fun runFilter(authorization: String): Authentication? {
        var seen: Authentication? = null
        val request = MockHttpServletRequest().apply { addHeader("Authorization", authorization) }
        filter.doFilter(request, MockHttpServletResponse()) { _, _ ->
            seen = SecurityContextHolder.getContext().authentication
        }
        return seen
    }

    private companion object {
        const val RAW = "pat_filterToken00000000000000000000000000000000"
        val BTS_USER_ID: UUID = UUID.fromString("55555555-5555-4555-8555-555555555555")
    }
}
