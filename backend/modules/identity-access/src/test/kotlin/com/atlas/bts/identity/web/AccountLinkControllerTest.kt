// AccountLinkController 슬라이스 테스트 — 계정 연결 목록/재인증/연결/해제 엔드포인트 (FR-AU-08 Task 6)

package com.atlas.bts.identity.web

import com.atlas.bts.identity.account.AccountLinkAuthException
import com.atlas.bts.identity.account.AccountLinkConflictException
import com.atlas.bts.identity.account.AccountLinkLastMethodException
import com.atlas.bts.identity.account.AccountLinkNotFoundException
import com.atlas.bts.identity.account.AccountLinkService
import com.atlas.bts.identity.account.AccountLinkView
import com.atlas.bts.identity.account.AccountLinks
import com.atlas.bts.identity.account.ReauthChallengeFailedException
import com.atlas.bts.identity.account.ReauthService
import com.atlas.bts.identity.account.StepUpService
import com.atlas.bts.identity.config.CorsConfig
import com.atlas.bts.identity.config.SecurityConfig
import com.atlas.bts.identity.jwt.SidRevokeJwtConverter
import com.atlas.bts.identity.pat.PersonalAccessTokenService
import com.atlas.bts.identity.provider.ldap.ProviderUnavailableException
import com.atlas.bts.identity.session.SessionService
import com.atlas.bts.identity.spi.ProviderType
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.security.oauth2.client.servlet.OAuth2ClientAutoConfiguration
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.web.cors.CorsConfigurationSource
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * AccountLinkController WebMvcTest 슬라이스 테스트 (FR-AU-08 Task 6).
 *
 * ## 검증 시나리오
 * - GET /links 200 — 목록 + externalSubject 마스킹(앞 6자 + ***) + hasLocalPassword
 * - GET /links PAT 403 — Jwt 아님(=null)
 * - POST /reauth(LOCAL) 성공 200 — stepUpExpiresAt 만 노출, sid/토큰 미노출
 * - POST /reauth(LDAP) 성공 200 — reauthenticateLdap 위임 검증
 * - POST /reauth 실패 401 — ReauthChallengeFailedException
 * - POST /reauth 바디에 위조 sid 주입 → 무시(JWT sid 만 사용) 검증
 * - POST /reauth PAT 403
 * - POST /links step-up 유효 201 — link 위임
 * - POST /links step-up 없음 403 step_up_required — link 미호출 검증
 * - POST /links 충돌 409 — AccountLinkConflictException
 * - POST /links bind 실패 401 — AccountLinkAuthException
 * - POST /links LDAP 불가 503 — ProviderUnavailableException 직접 처리(catch-all 변질 방지)
 * - POST /links PAT 403
 * - DELETE /links/{id} step-up 유효 204 — unlink 위임
 * - DELETE /links/{id} step-up 없음 403 step_up_required — unlink 미호출 검증
 * - DELETE /links/{id} 마지막 수단 409 — AccountLinkLastMethodException
 * - DELETE /links/{id} 미소유/미존재 404 — AccountLinkNotFoundException
 * - DELETE /links/{id} PAT 403
 *
 * ## 의존성 모킹 전략 (AuthControllerTest 선례와 일관)
 * - SecurityConfig 필수 Bean(JwtDecoder/Clock/SidRevokeJwtConverter/CorsConfigurationSource/PAT):
 *   @TestConfiguration + MockK.
 * - 컨트롤러 의존 서비스(AccountLinkService/ReauthService/StepUpService): @MockBean(Mockito) —
 *   companion object 포함 클래스를 Spring @Bean 으로 MockK 등록 시 ByteBuddy $Companion 로드 실패 회피.
 */
@WebMvcTest(
    controllers = [AccountLinkController::class],
    excludeAutoConfiguration = [OAuth2ClientAutoConfiguration::class],
)
@Import(SecurityConfig::class, AccountLinkControllerTest.SecurityBeans::class)
class AccountLinkControllerTest {
    @TestConfiguration
    class SecurityBeans {
        @Bean
        fun jwtDecoder(): JwtDecoder = mockk(relaxed = true)

        /** step-up 만료 계산 검증을 위해 고정 Clock 사용 — reauth 응답의 stepUpExpiresAt 결정성 보장. */
        @Bean
        fun clock(): Clock = Clock.fixed(Instant.parse("2026-06-09T10:00:00Z"), ZoneOffset.UTC)

        @Bean
        fun sidRevokeJwtConverter(clock: Clock): SidRevokeJwtConverter {
            val sessionService: SessionService = mockk(relaxed = true)
            return SidRevokeJwtConverter(sessionService, clock)
        }

        @Bean
        fun corsConfigurationSource(): CorsConfigurationSource = CorsConfig().corsConfigurationSource(listOf(ORIGIN))

        @Bean
        fun personalAccessTokenService(): PersonalAccessTokenService = mockk(relaxed = true)

        private companion object {
            /** CORS 허용 origin — SPA dev 서버. */
            const val ORIGIN = "http://localhost:5173"
        }
    }

    @Autowired
    lateinit var mockMvc: MockMvc

    @MockBean
    lateinit var accountLinkService: AccountLinkService

    @MockBean
    lateinit var reauthService: ReauthService

    @MockBean
    lateinit var stepUpService: StepUpService

    private val userId = UUID.fromString("11111111-1111-1111-1111-111111111111")
    private val currentSid = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
    private val providerId = UUID.fromString("33333333-3333-3333-3333-333333333333")
    private val linkId = UUID.fromString("44444444-4444-4444-4444-444444444444")

    /** userId(subject) + sid 클레임을 가진 JWT principal 포스트 프로세서. */
    private fun jwtPrincipal() =
        jwt().jwt { builder ->
            builder
                .subject(userId.toString())
                .claim("sid", currentSid.toString())
        }

    // ── GET /links 200 — 목록 + 마스킹 ─────────────────────────────────────────

    @Test
    fun `GET links returns 200 with masked externalSubject and hasLocalPassword`() {
        val rawSubject = "uid=alice,ou=people,dc=corp,dc=com"
        val linkedAt = Instant.parse("2026-01-01T00:00:00Z")
        val lastLoginAt = Instant.parse("2026-05-20T08:30:00Z")
        `when`(accountLinkService.listLinks(userId)).thenReturn(
            AccountLinks(
                links =
                    listOf(
                        AccountLinkView(
                            id = linkId,
                            providerId = providerId,
                            providerName = "Corp LDAP",
                            providerType = ProviderType.LDAP,
                            providerEnabled = true,
                            externalSubject = rawSubject,
                            linkedAt = linkedAt,
                            lastLoginAt = lastLoginAt,
                        ),
                    ),
                hasLocalPassword = true,
            ),
        )

        mockMvc.perform(get("/api/v1/auth/account/links").with(jwtPrincipal()))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.hasLocalPassword").value(true))
            .andExpect(jsonPath("$.links[0].id").value(linkId.toString()))
            .andExpect(jsonPath("$.links[0].providerId").value(providerId.toString()))
            .andExpect(jsonPath("$.links[0].providerName").value("Corp LDAP"))
            .andExpect(jsonPath("$.links[0].providerType").value("LDAP"))
            .andExpect(jsonPath("$.links[0].providerEnabled").value(true))
            // 앞 6자(uid=al) + *** — 원본 DN 비노출(PII 마스킹)
            .andExpect(jsonPath("$.links[0].externalSubjectMasked").value("uid=al***"))
            // 원본 externalSubject 필드는 응답에 절대 노출되지 않아야 한다
            .andExpect(jsonPath("$.links[0].externalSubject").doesNotExist())
            // 연결 시각(linkedAt) + 마지막 로그인 시각(lastLoginAt) surface — S1
            .andExpect(jsonPath("$.links[0].linkedAt").value("2026-01-01T00:00:00Z"))
            .andExpect(jsonPath("$.links[0].lastLoginAt").value("2026-05-20T08:30:00Z"))
    }

    @Test
    fun `GET links with PAT returns 403`() {
        mockMvc.perform(get("/api/v1/auth/account/links").with(user("pat-user-id")))
            .andExpect(status().isForbidden)

        verify(accountLinkService, never()).listLinks(anyUuid())
    }

    // ── POST /reauth ───────────────────────────────────────────────────────────

    @Test
    fun `POST reauth LOCAL success returns 200 with stepUpExpiresAt and no sid exposed`() {
        mockMvc.perform(
            post("/api/v1/auth/account/reauth")
                .with(csrf())
                .with(jwtPrincipal())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"method":"LOCAL","password":"secret"}"""),
        )
            .andExpect(status().isOk)
            // step-up TTL 5분 후 — 고정 Clock(2026-06-09T10:00:00Z) + 5m
            .andExpect(jsonPath("$.stepUpExpiresAt").value("2026-06-09T10:05:00Z"))
            // sid / step-up 토큰은 응답에 절대 노출 금지(FR9 / 리뷰 B1)
            .andExpect(jsonPath("$.sid").doesNotExist())
            .andExpect(jsonPath("$.stepUpToken").doesNotExist())

        verify(reauthService).reauthenticateLocal(eqUuid(userId), eqUuid(currentSid), anyCharArray())
    }

    @Test
    fun `POST reauth LDAP success delegates to reauthenticateLdap`() {
        mockMvc.perform(
            post("/api/v1/auth/account/reauth")
                .with(csrf())
                .with(jwtPrincipal())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """{"method":"LDAP","providerId":"$providerId","username":"alice","password":"secret"}""",
                ),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.stepUpExpiresAt").exists())

        verify(reauthService).reauthenticateLdap(
            eqUuid(userId),
            eqUuid(currentSid),
            eqUuid(providerId),
            eqStr("alice"),
            anyCharArray(),
        )
    }

    @Test
    fun `POST reauth failure returns 401`() {
        `when`(reauthService.reauthenticateLocal(anyUuid(), anyUuid(), anyCharArray()))
            .thenThrow(ReauthChallengeFailedException())

        mockMvc.perform(
            post("/api/v1/auth/account/reauth")
                .with(csrf())
                .with(jwtPrincipal())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"method":"LOCAL","password":"wrong"}"""),
        )
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.error").value("reauth_failed"))
    }

    /**
     * 바디에 위조된 sid 를 주입해도 무시하고 JWT 의 sid 클레임만 사용해야 한다 (FR9 / 리뷰 B1).
     * reauthenticateLocal 호출 인자의 sid 가 위조값이 아니라 JWT currentSid 임을 검증한다.
     */
    @Test
    fun `POST reauth ignores forged sid in body and uses JWT sid only`() {
        val forgedSid = UUID.fromString("dead0000-dead-dead-dead-deaddeaddead")

        mockMvc.perform(
            post("/api/v1/auth/account/reauth")
                .with(csrf())
                .with(jwtPrincipal())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"method":"LOCAL","password":"secret","sid":"$forgedSid"}"""),
        )
            .andExpect(status().isOk)

        // 위조 sid 가 아니라 JWT 의 currentSid 로 호출돼야 한다.
        verify(reauthService).reauthenticateLocal(eqUuid(userId), eqUuid(currentSid), anyCharArray())
        verify(reauthService, never()).reauthenticateLocal(anyUuid(), eqUuid(forgedSid), anyCharArray())
    }

    @Test
    fun `POST reauth with PAT returns 403`() {
        mockMvc.perform(
            post("/api/v1/auth/account/reauth")
                .with(csrf())
                .with(user("pat-user-id"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"method":"LOCAL","password":"secret"}"""),
        )
            .andExpect(status().isForbidden)

        verify(reauthService, never()).reauthenticateLocal(anyUuid(), anyUuid(), anyCharArray())
    }

    // ── POST /links ──────────────────────────────────────────────────────────

    @Test
    fun `POST links with valid step-up returns 201 and delegates to link`() {
        `when`(stepUpService.isValid(currentSid)).thenReturn(true)
        `when`(accountLinkService.link(anyUuid(), anyUuid(), eqStr("alice"), anyCharArray()))
            .thenReturn(
                AccountLinkView(
                    id = linkId,
                    providerId = providerId,
                    providerName = "Corp LDAP",
                    providerType = ProviderType.LDAP,
                    providerEnabled = true,
                    externalSubject = "uid=alice,ou=people,dc=corp,dc=com",
                    linkedAt = Instant.parse("2026-06-09T10:00:00Z"),
                    lastLoginAt = null,
                ),
            )

        mockMvc.perform(
            post("/api/v1/auth/account/links")
                .with(csrf())
                .with(jwtPrincipal())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"providerId":"$providerId","username":"alice","password":"secret"}"""),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.id").value(linkId.toString()))
            .andExpect(jsonPath("$.externalSubjectMasked").value("uid=al***"))
            .andExpect(jsonPath("$.externalSubject").doesNotExist())
            // 갓 연결한 계정 — linkedAt 노출, lastLoginAt 은 null(직렬화 관례상 null 로 노출)
            .andExpect(jsonPath("$.linkedAt").value("2026-06-09T10:00:00Z"))
            .andExpect(jsonPath("$.lastLoginAt").value(org.hamcrest.Matchers.nullValue()))

        verify(accountLinkService).link(eqUuid(userId), eqUuid(providerId), eqStr("alice"), anyCharArray())
    }

    @Test
    fun `POST links without valid step-up returns 403 step_up_required and does not call link`() {
        `when`(stepUpService.isValid(currentSid)).thenReturn(false)

        mockMvc.perform(
            post("/api/v1/auth/account/links")
                .with(csrf())
                .with(jwtPrincipal())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"providerId":"$providerId","username":"alice","password":"secret"}"""),
        )
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.error").value("step_up_required"))

        verify(accountLinkService, never()).link(anyUuid(), anyUuid(), eqStr("alice"), anyCharArray())
    }

    @Test
    fun `POST links conflict returns 409`() {
        `when`(stepUpService.isValid(currentSid)).thenReturn(true)
        `when`(accountLinkService.link(anyUuid(), anyUuid(), eqStr("alice"), anyCharArray()))
            .thenThrow(AccountLinkConflictException())

        mockMvc.perform(
            post("/api/v1/auth/account/links")
                .with(csrf())
                .with(jwtPrincipal())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"providerId":"$providerId","username":"alice","password":"secret"}"""),
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.error").value("account_already_linked"))
    }

    @Test
    fun `POST links bind failure returns 401`() {
        `when`(stepUpService.isValid(currentSid)).thenReturn(true)
        `when`(accountLinkService.link(anyUuid(), anyUuid(), eqStr("alice"), anyCharArray()))
            .thenThrow(AccountLinkAuthException())

        mockMvc.perform(
            post("/api/v1/auth/account/links")
                .with(csrf())
                .with(jwtPrincipal())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"providerId":"$providerId","username":"alice","password":"secret"}"""),
        )
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.error").value("link_authentication_failed"))
    }

    /**
     * link 경로에서 [ProviderUnavailableException] 이 전파되면 503 으로 직접 응답한다.
     * catch-all @ExceptionHandler 가 500 으로 변질시키지 않도록 메서드 안에서 직접 처리한다.
     */
    @Test
    fun `POST links returns 503 when provider unavailable`() {
        `when`(stepUpService.isValid(currentSid)).thenReturn(true)
        `when`(accountLinkService.link(anyUuid(), anyUuid(), eqStr("alice"), anyCharArray()))
            .thenThrow(ProviderUnavailableException("LDAP down", providerType = "LDAP"))

        mockMvc.perform(
            post("/api/v1/auth/account/links")
                .with(csrf())
                .with(jwtPrincipal())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"providerId":"$providerId","username":"alice","password":"secret"}"""),
        )
            .andExpect(status().isServiceUnavailable)
            .andExpect(jsonPath("$.error").value("provider_unavailable"))
    }

    @Test
    fun `POST links with PAT returns 403`() {
        mockMvc.perform(
            post("/api/v1/auth/account/links")
                .with(csrf())
                .with(user("pat-user-id"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"providerId":"$providerId","username":"alice","password":"secret"}"""),
        )
            .andExpect(status().isForbidden)

        verify(stepUpService, never()).isValid(anyUuid())
        verify(accountLinkService, never()).link(anyUuid(), anyUuid(), eqStr("alice"), anyCharArray())
    }

    // ── DELETE /links/{id} ────────────────────────────────────────────────────

    @Test
    fun `DELETE links with valid step-up returns 204 and delegates to unlink`() {
        `when`(stepUpService.isValid(currentSid)).thenReturn(true)

        mockMvc.perform(
            delete("/api/v1/auth/account/links/$linkId")
                .with(csrf())
                .with(jwtPrincipal()),
        )
            .andExpect(status().isNoContent)

        verify(accountLinkService).unlink(userId, linkId)
    }

    @Test
    fun `DELETE links without valid step-up returns 403 step_up_required and does not call unlink`() {
        `when`(stepUpService.isValid(currentSid)).thenReturn(false)

        mockMvc.perform(
            delete("/api/v1/auth/account/links/$linkId")
                .with(csrf())
                .with(jwtPrincipal()),
        )
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.error").value("step_up_required"))

        verify(accountLinkService, never()).unlink(anyUuid(), anyUuid())
    }

    @Test
    fun `DELETE links last method returns 409`() {
        `when`(stepUpService.isValid(currentSid)).thenReturn(true)
        org.mockito.Mockito.doThrow(AccountLinkLastMethodException())
            .`when`(accountLinkService).unlink(userId, linkId)

        mockMvc.perform(
            delete("/api/v1/auth/account/links/$linkId")
                .with(csrf())
                .with(jwtPrincipal()),
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.error").value("last_login_method"))
    }

    @Test
    fun `DELETE links not found or not owned returns 404`() {
        `when`(stepUpService.isValid(currentSid)).thenReturn(true)
        org.mockito.Mockito.doThrow(AccountLinkNotFoundException())
            .`when`(accountLinkService).unlink(userId, linkId)

        mockMvc.perform(
            delete("/api/v1/auth/account/links/$linkId")
                .with(csrf())
                .with(jwtPrincipal()),
        )
            .andExpect(status().isNotFound)
    }

    @Test
    fun `DELETE links with PAT returns 403`() {
        mockMvc.perform(
            delete("/api/v1/auth/account/links/$linkId")
                .with(csrf())
                .with(user("pat-user-id")),
        )
            .andExpect(status().isForbidden)

        verify(stepUpService, never()).isValid(anyUuid())
        verify(accountLinkService, never()).unlink(anyUuid(), anyUuid())
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /** CharArray 파라미터의 Mockito any() 매처 — Kotlin non-null CharArray 에 null 전달 방지. */
    private fun anyCharArray(): CharArray = org.mockito.ArgumentMatchers.any(CharArray::class.java) ?: charArrayOf()

    /** UUID 파라미터의 Mockito any() 매처. */
    private fun anyUuid(): UUID = org.mockito.ArgumentMatchers.any(UUID::class.java) ?: UUID.randomUUID()

    /** UUID 파라미터의 Mockito eq() 매처 — eq() 가 null 반환 시 NPE 방지. */
    private fun eqUuid(value: UUID): UUID = org.mockito.ArgumentMatchers.eq(value) ?: value

    /** String 파라미터의 Mockito eq() 매처. */
    private fun eqStr(value: String): String = org.mockito.ArgumentMatchers.eq(value) ?: value
}
