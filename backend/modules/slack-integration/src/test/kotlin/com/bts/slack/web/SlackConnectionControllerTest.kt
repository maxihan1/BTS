// SlackConnectionController 슬라이스 테스트 — me-scope 연결 상태 조회/연결/해제 + PAT 401 (FR-SL-02 D6 Task 4)

package com.bts.slack.web

import com.bts.slack.SlackTestSecurityConfig
import com.bts.slack.application.ConnectionStatus
import com.bts.slack.application.EmailUnavailableException
import com.bts.slack.application.SlackScopeMissingException
import com.bts.slack.application.SlackTemporarilyUnavailableException
import com.bts.slack.application.SlackUserConnectionService
import com.bts.slack.application.SlackUserNotFoundException
import com.bts.slack.application.WorkspaceNotInstalledException
import io.mockk.Runs
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.RequestPostProcessor
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant
import java.util.UUID

/**
 * [SlackConnectionController] `@WebMvcTest` 슬라이스 테스트 (FR-SL-02 D6 Task 4).
 *
 * ## 검증 시나리오
 * - GET/POST/DELETE `/api/v1/slack/me/connection` — 정상 200 + `SlackConnectionResponse`.
 * - 예외별 상태매핑 — [EmailUnavailableException] 422, [WorkspaceNotInstalledException]/[SlackScopeMissingException]
 *   409, [SlackUserNotFoundException] 404, [SlackTemporarilyUnavailableException] 503.
 * - 미인증 401([SlackTestSecurityConfig] 필터 체인이 거부).
 * - **★PAT 401** — principal이 [Jwt] 가 아닌 [UsernamePasswordAuthenticationToken](uuid 문자열, `ROLE_PAT`)로
 *   인증돼도 컨트롤러가 401을 던진다(`SlackActorExtractor` 재사용 시 발생하는 PAT 통과 회귀 가드).
 * - 응답 바디에 slack_user_id·이메일 미포함(§1.1.2).
 *
 * ## 인증 postprocessor — [Jwt] 는 직접 조립, `.jwt()` 미사용
 * `SecurityMockMvcRequestPostProcessors.jwt()`는 내부적으로 `JwtAuthenticationToken`(spring-security-oauth2
 * -resource-server 소속)을 생성해 이 모듈이 갖지 않은 추가 의존성을 요구한다. 대신 [Jwt] 를 직접 빌드해
 * [UsernamePasswordAuthenticationToken] principal 에 담아 [authentication] postprocessor로 주입한다 —
 * `@AuthenticationPrincipal jwt: Jwt?` 는 Authentication 토큰 클래스가 아니라 principal 의 런타임 타입만
 * 검사하므로 결과는 동일하다(PAT 케이스도 같은 postprocessor로 principal 타입만 바꿔 재현).
 */
@WebMvcTest(controllers = [SlackConnectionController::class])
@Import(SlackTestSecurityConfig::class, SlackConnectionControllerTest.SecurityBeans::class)
class SlackConnectionControllerTest {
    @TestConfiguration
    class SecurityBeans {
        @Bean
        fun slackUserConnectionService(): SlackUserConnectionService = mockk()
    }

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var service: SlackUserConnectionService

    private val userId = UUID.fromString("11111111-1111-4111-8111-111111111111")

    @BeforeEach
    fun resetMock() {
        clearMocks(service)
    }

    private fun jwtFor(uid: UUID): Jwt =
        Jwt.withTokenValue("test-token")
            .header("alg", "none")
            .claim("sub", uid.toString())
            .subject(uid.toString())
            .issuedAt(Instant.parse("2026-07-10T00:00:00Z"))
            .expiresAt(Instant.parse("2026-07-10T01:00:00Z"))
            .build()

    private fun jwtAuth(uid: UUID): RequestPostProcessor =
        authentication(
            UsernamePasswordAuthenticationToken(jwtFor(uid), null, listOf(SimpleGrantedAuthority("ROLE_USER"))),
        )

    /** PAT(개인 액세스 토큰) 흉내 — principal이 UUID 문자열일 뿐 [Jwt] 타입이 아니다. */
    private fun patAuth(uid: UUID): RequestPostProcessor =
        authentication(
            UsernamePasswordAuthenticationToken(uid.toString(), null, listOf(SimpleGrantedAuthority("ROLE_PAT"))),
        )

    // ── GET /api/v1/slack/me/connection ────────────────────────────────────────

    @Test
    fun `GET connection returns 200 with connected status`() {
        every { service.getStatus(userId) } returns
            ConnectionStatus(connected = true, workspaceName = "Acme Corp", linkedAt = FIXED_LINKED_AT)

        mockMvc.perform(get("/api/v1/slack/me/connection").with(jwtAuth(userId)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.connected").value(true))
            .andExpect(jsonPath("$.workspaceName").value("Acme Corp"))
            .andExpect(jsonPath("$.linkedAt").value(FIXED_LINKED_AT.toString()))
    }

    @Test
    fun `GET connection returns 200 with connected false when not linked`() {
        every { service.getStatus(userId) } returns ConnectionStatus(connected = false, workspaceName = null, linkedAt = null)

        mockMvc.perform(get("/api/v1/slack/me/connection").with(jwtAuth(userId)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.connected").value(false))
            .andExpect(jsonPath("$.workspaceName", nullValue()))
            .andExpect(jsonPath("$.linkedAt", nullValue()))
    }

    @Test
    fun `GET connection returns 401 without authentication`() {
        mockMvc.perform(get("/api/v1/slack/me/connection"))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `GET connection with PAT-style non-Jwt principal returns 401`() {
        mockMvc.perform(get("/api/v1/slack/me/connection").with(patAuth(userId)))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `GET connection response body does not expose slack_user_id or email`() {
        every { service.getStatus(userId) } returns
            ConnectionStatus(connected = true, workspaceName = "Acme Corp", linkedAt = FIXED_LINKED_AT)

        val body =
            mockMvc.perform(get("/api/v1/slack/me/connection").with(jwtAuth(userId)))
                .andExpect(status().isOk)
                .andReturn()
                .response
                .contentAsString

        assertThat(body).doesNotContain("slack_user_id")
        assertThat(body).doesNotContain("slackUserId")
        assertThat(body).doesNotContain("email")
        assertThat(body).doesNotContain("@")
    }

    // ── POST /api/v1/slack/me/connection ───────────────────────────────────────

    @Test
    fun `POST connection returns 200 connected status`() {
        every { service.connect(userId) } returns
            ConnectionStatus(connected = true, workspaceName = "Acme Corp", linkedAt = FIXED_LINKED_AT)

        mockMvc.perform(post("/api/v1/slack/me/connection").with(jwtAuth(userId)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.connected").value(true))
            .andExpect(jsonPath("$.workspaceName").value("Acme Corp"))
    }

    @Test
    fun `POST connection with PAT-style non-Jwt principal returns 401`() {
        mockMvc.perform(post("/api/v1/slack/me/connection").with(patAuth(userId)))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `POST connection returns 422 when email unavailable`() {
        every { service.connect(userId) } throws EmailUnavailableException()

        mockMvc.perform(post("/api/v1/slack/me/connection").with(jwtAuth(userId)))
            .andExpect(status().isUnprocessableEntity)
            .andExpect(jsonPath("$.code").value("EMAIL_UNAVAILABLE"))
    }

    @Test
    fun `POST connection returns 409 when workspace not installed`() {
        every { service.connect(userId) } throws WorkspaceNotInstalledException()

        mockMvc.perform(post("/api/v1/slack/me/connection").with(jwtAuth(userId)))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("WORKSPACE_NOT_INSTALLED"))
    }

    @Test
    fun `POST connection returns 409 when bot scope missing`() {
        every { service.connect(userId) } throws SlackScopeMissingException()

        mockMvc.perform(post("/api/v1/slack/me/connection").with(jwtAuth(userId)))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("SLACK_SCOPE_MISSING"))
    }

    @Test
    fun `POST connection returns 404 when slack user not found`() {
        every { service.connect(userId) } throws SlackUserNotFoundException()

        mockMvc.perform(post("/api/v1/slack/me/connection").with(jwtAuth(userId)))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value("SLACK_USER_NOT_FOUND"))
    }

    @Test
    fun `POST connection returns 503 when slack temporarily unavailable`() {
        every { service.connect(userId) } throws SlackTemporarilyUnavailableException()

        mockMvc.perform(post("/api/v1/slack/me/connection").with(jwtAuth(userId)))
            .andExpect(status().isServiceUnavailable)
            .andExpect(jsonPath("$.code").value("SLACK_TEMPORARILY_UNAVAILABLE"))
    }

    // ── DELETE /api/v1/slack/me/connection ─────────────────────────────────────

    @Test
    fun `DELETE connection returns 200 connected false`() {
        every { service.disconnect(userId) } just Runs

        mockMvc.perform(delete("/api/v1/slack/me/connection").with(jwtAuth(userId)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.connected").value(false))
            .andExpect(jsonPath("$.workspaceName", nullValue()))
            .andExpect(jsonPath("$.linkedAt", nullValue()))
    }

    @Test
    fun `DELETE connection with PAT-style non-Jwt principal returns 401`() {
        mockMvc.perform(delete("/api/v1/slack/me/connection").with(patAuth(userId)))
            .andExpect(status().isUnauthorized)
    }

    private companion object {
        /** 결정적 ISO-8601 직렬화 검증용 고정 연결 시각. */
        val FIXED_LINKED_AT: Instant = Instant.parse("2026-07-10T00:00:00Z")
    }
}
