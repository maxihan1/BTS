// AccountLinkJwtSupport 단위 테스트 — JWT subject+sid 동시추출/step-up 게이트/표준 errorResponse 공통 헬퍼 (FR-AU-08b Task 11)

package com.atlas.bts.identity.web

import com.atlas.bts.identity.account.StepUpService
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.security.oauth2.jwt.Jwt
import java.util.UUID

/**
 * [AccountLinkJwtSupport] 단위 테스트 (FR-AU-08b Task 11 / 리뷰 B2).
 *
 * 계정 연결 컨트롤러들([AccountLinkController]/SsoAccountLinkController)이 공유하는 인증 헬퍼.
 * 보안 로직(JWT 출처 sid 추출·step-up 판정·표준 errorResponse)을 컨트롤러별로 복붙하면 drift 가
 * 생기므로 단일 컴포넌트로 추출해 검증한다.
 *
 * 검증 대상.
 * - PAT(jwt=null) → resolveClaims null (호출 측 403).
 * - subject 가 유효 UUID 아니면 → resolveClaims null.
 * - sid 는 JWT 클레임에서만 추출(클레임 부재/파싱 실패 → currentSid null). 1차 불변식 계승.
 * - requireStepUp: 유효 sid → null(통과), 미발급/만료/sid null → 403 step_up_required.
 * - errorResponse: 주어진 status + {"error": code} 본문.
 */
class AccountLinkJwtSupportTest {
    private val stepUpService = mockk<StepUpService>()
    private val sut = AccountLinkJwtSupport(stepUpService)

    private val userId = UUID.fromString("11111111-1111-1111-1111-111111111111")
    private val sid = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")

    private fun jwt(
        subject: String,
        sidClaim: String?,
    ): Jwt {
        val jwt = mockk<Jwt>()
        every { jwt.subject } returns subject
        every { jwt.getClaimAsString("sid") } returns sidClaim
        return jwt
    }

    // ── resolveClaims ───────────────────────────────────────────────────────

    @Test
    fun `resolveClaims — PAT(jwt null) 이면 null`() {
        assertThat(sut.resolveClaims(null)).isNull()
    }

    @Test
    fun `resolveClaims — subject 가 유효 UUID 가 아니면 null`() {
        assertThat(sut.resolveClaims(jwt(subject = "not-a-uuid", sidClaim = sid.toString()))).isNull()
    }

    @Test
    fun `resolveClaims — subject UUID + sid 클레임 동시 추출`() {
        val claims = sut.resolveClaims(jwt(subject = userId.toString(), sidClaim = sid.toString()))

        assertThat(claims).isNotNull
        assertThat(claims!!.userId).isEqualTo(userId)
        assertThat(claims.currentSid).isEqualTo(sid)
    }

    @Test
    fun `resolveClaims — sid 클레임 부재면 currentSid null(userId 는 추출됨)`() {
        val claims = sut.resolveClaims(jwt(subject = userId.toString(), sidClaim = null))

        assertThat(claims).isNotNull
        assertThat(claims!!.userId).isEqualTo(userId)
        assertThat(claims.currentSid).isNull()
    }

    @Test
    fun `resolveClaims — sid 클레임이 UUID 형식이 아니면 currentSid null`() {
        val claims = sut.resolveClaims(jwt(subject = userId.toString(), sidClaim = "garbage"))

        assertThat(claims).isNotNull
        assertThat(claims!!.currentSid).isNull()
    }

    // ── requireStepUp ──────────────────────────────────────────────────────

    @Test
    fun `requireStepUp — 유효 step-up 이면 null(통과)`() {
        every { stepUpService.isValid(sid) } returns true

        assertThat(sut.requireStepUp(sid)).isNull()
    }

    @Test
    fun `requireStepUp — step-up 미발급 또는 만료면 403 step_up_required`() {
        every { stepUpService.isValid(sid) } returns false

        val response = sut.requireStepUp(sid)

        assertThat(response).isNotNull
        assertThat(response!!.statusCode).isEqualTo(HttpStatus.FORBIDDEN)
        assertThat(response.body).isEqualTo(mapOf("error" to "step_up_required"))
    }

    @Test
    fun `requireStepUp — sid null 이면 isValid 호출 없이 403`() {
        // sid 가 null 이면 stepUpService 를 호출하지 않고(fail-safe) 즉시 403.
        val response = sut.requireStepUp(null)

        assertThat(response).isNotNull
        assertThat(response!!.statusCode).isEqualTo(HttpStatus.FORBIDDEN)
        assertThat(response.body).isEqualTo(mapOf("error" to "step_up_required"))
    }

    // ── errorResponse ──────────────────────────────────────────────────────

    @Test
    fun `errorResponse — 주어진 status 와 error 코드 본문을 만든다`() {
        val response = sut.errorResponse(HttpStatus.BAD_REQUEST, "some_error")

        assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(response.body).isEqualTo(mapOf("error" to "some_error"))
    }
}
