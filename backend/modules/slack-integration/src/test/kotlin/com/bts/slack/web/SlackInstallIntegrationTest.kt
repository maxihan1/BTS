// Slack App 설치 웹 레이어 풀스택 통합 테스트 — 관리자 가드·콜백 302·upsert·평문 봇 토큰 미노출 (FR-SL-01 Task 9)

package com.bts.slack.web

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.bts.shared.crypto.SecretEncryptor
import com.bts.slack.SlackIntegrationTestBootApplication
import com.bts.slack.SlackTestSecurityConfig
import com.bts.slack.SlackTestcontainersConfig
import com.bts.slack.StubSystemPermissionResolver
import com.bts.slack.oauth.SlackOAuthClient
import com.bts.slack.oauth.SlackOAuthStateSigner
import com.bts.slack.oauth.SlackOAuthTokenResponse
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.startsWith
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import java.util.UUID

/** `@WithMockUser` username 은 컴파일 상수여야 하므로 top-level const 로 둔다(actor 는 principal name=UUID). */
private const val ADMIN_UUID = "11111111-1111-1111-1111-111111111111"
private const val NON_ADMIN_UUID = "22222222-2222-2222-2222-222222222222"

/**
 * Slack App 설치 웹 레이어 풀스택 통합 테스트 (FR-SL-01 Task 9).
 *
 * `SlackInstallController` → `SlackInstallService` → 실제 state 서명/봇 토큰 암호화 → JdbcTemplate →
 * **실 PostgreSQL(Testcontainers)** end-to-end. Slack 아웃바운드([SlackOAuthClient])만 [FakeSlackOAuthClient]
 * 로 오버라이드해 실 Slack 호출을 회피한다(state 서명·암호화·영속화는 모두 실제 구현).
 *
 * ## 커버 (plan Task 9 / spec S1·S2·S4·S5·EC1~EC7)
 * - **관리자 가드** — 관리자 `GET /slack/install` → 302 authorize URL, 비관리자 → 403, 미인증 → 401(필터).
 * - **콜백 permitAll + 설치 완료** — 유효 서명 state → `slack_installs` 저장, 302 `?installed=<teamName>`.
 * - **봇 토큰 위생(§1.1.2)** — 저장된 `bot_token_encrypted` 가 평문(`xoxb-…`)이 아니고 복호화 시 원문 복원,
 *   302 응답·로그 어디에도 평문 토큰 미노출.
 * - **재설치 upsert** — 같은 team_id 재콜백 시 행 1개 유지.
 * - **state 위조 / ok:false / 사용자 취소** — 저장 없이 302 `?error=<code>`(내부 사정 미노출).
 */
@SpringBootTest(
    classes = [SlackIntegrationTestBootApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.MOCK,
)
@Import(
    SlackTestcontainersConfig::class,
    SlackTestSecurityConfig::class,
    SlackInstallIntegrationTest.FakeOAuthConfig::class,
)
@TestPropertySource(
    properties = [
        "bts.slack.state-key=slack-oauth-state-hmac-key-for-integration-tests-0123456789",
        "bts.slack-encryption.key=slack-bot-token-encryption-key-for-integration-tests",
        "bts.slack-encryption.salt=deadbeefcafef00d",
    ],
)
class SlackInstallIntegrationTest {
    @Autowired
    private lateinit var webApplicationContext: WebApplicationContext

    @Autowired
    private lateinit var jdbc: NamedParameterJdbcTemplate

    @Autowired
    private lateinit var stateSigner: SlackOAuthStateSigner

    @Autowired
    @Qualifier("slackSecretEncryptor")
    private lateinit var encryptor: SecretEncryptor

    @Autowired
    private lateinit var permissionResolver: StubSystemPermissionResolver

    @Autowired
    private lateinit var fakeOAuth: FakeSlackOAuthClient

    private lateinit var mockMvc: MockMvc

    @BeforeEach
    fun setUp() {
        mockMvc =
            MockMvcBuilders
                .webAppContextSetup(webApplicationContext)
                .apply<org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder>(springSecurity())
                .build()
        jdbc.update("DELETE FROM slack_installs", emptyMap<String, Any>())
        permissionResolver.admins.clear()
        permissionResolver.admins.add(UUID.fromString(ADMIN_UUID))
        fakeOAuth.responder = { workspaceResponse(PLAINTEXT_TOKEN) }
    }

    @AfterEach
    fun tearDown() {
        jdbc.update("DELETE FROM slack_installs", emptyMap<String, Any>())
    }

    // ── 관리자 가드 (GET /slack/install) ─────────────────────────────────────────

    @Test
    @WithMockUser(username = ADMIN_UUID)
    fun `GET slack install - 관리자면 302 Location authorize URL`() {
        mockMvc
            .perform(get("/slack/install"))
            .andExpect(status().isFound)
            .andExpect(header().string("Location", startsWith("https://slack.com/oauth/v2/authorize")))
    }

    @Test
    @WithMockUser(username = NON_ADMIN_UUID)
    fun `GET slack install - 비관리자면 403`() {
        mockMvc
            .perform(get("/slack/install"))
            .andExpect(status().isForbidden)
    }

    @Test
    fun `GET slack install - 미인증이면 401`() {
        mockMvc
            .perform(get("/slack/install"))
            .andExpect(status().isUnauthorized)
    }

    // ── 콜백 성공 (GET /slack/install/callback, permitAll) ────────────────────────

    @Test
    fun `GET callback - 유효 state면 설치 완료 후 302 installed 리다이렉트`() {
        val state = stateSigner.issue(UUID.fromString(ADMIN_UUID))

        mockMvc
            .perform(get("/slack/install/callback").param("code", "valid-code").param("state", state))
            .andExpect(status().isFound)
            .andExpect(header().string("Location", "/settings/slack?installed=Acme+Workspace"))

        // 설치 1행이 저장되고 installedBy 는 state 에서 복원된 개시자다.
        assertThat(rowCount(TEAM_ID)).isEqualTo(1)
        assertThat(installedByOf(TEAM_ID)).isEqualTo(ADMIN_UUID)
    }

    @Test
    fun `GET callback - 저장된 봇 토큰은 평문이 아니라 암호문이다`() {
        val state = stateSigner.issue(UUID.fromString(ADMIN_UUID))

        mockMvc
            .perform(get("/slack/install/callback").param("code", "valid-code").param("state", state))
            .andExpect(status().isFound)

        val stored = storedToken(TEAM_ID)
        assertThat(stored).isNotNull()
        // 평문 봇 토큰(xoxb-…)이 그대로 저장되지 않는다(§1.1.1 평문 저장 금지).
        assertThat(stored).isNotEqualTo(PLAINTEXT_TOKEN)
        assertThat(stored!!).doesNotContain("xoxb-")
        // 그러나 복호화하면 원문이 복원된다(round-trip — 실제 암호화 경로 확인).
        assertThat(encryptor.decrypt(stored)).isEqualTo(PLAINTEXT_TOKEN)
    }

    @Test
    fun `GET callback - 응답과 로그에 평문 봇 토큰이 노출되지 않는다`() {
        val appender = attachRootAppender()
        try {
            val state = stateSigner.issue(UUID.fromString(ADMIN_UUID))

            val response =
                mockMvc
                    .perform(get("/slack/install/callback").param("code", "valid-code").param("state", state))
                    .andExpect(status().isFound)
                    .andReturn()
                    .response

            // 302 응답(Location 헤더 + 본문) 어디에도 평문 토큰이 없다.
            assertThat(response.getHeader("Location") ?: "").doesNotContain("xoxb-")
            assertThat(response.contentAsString).doesNotContain("xoxb-")
            // 콜백 처리 중 남긴 로그에도 평문 토큰이 없다(§1.1.2 비밀값 로깅 금지).
            val logText = appender.list.joinToString("\n") { it.formattedMessage }
            assertThat(logText).doesNotContain("xoxb-")
        } finally {
            detachRootAppender(appender)
        }
    }

    // ── 재설치 upsert (EC4) ──────────────────────────────────────────────────────

    @Test
    fun `GET callback - 같은 team_id 재설치는 upsert로 행 1개 유지`() {
        val state1 = stateSigner.issue(UUID.fromString(ADMIN_UUID))
        mockMvc
            .perform(get("/slack/install/callback").param("code", "c1").param("state", state1))
            .andExpect(status().isFound)

        // 두 번째 설치는 team_name 을 바꿔 upsert 갱신을 유도한다.
        fakeOAuth.responder = { workspaceResponse(PLAINTEXT_TOKEN, teamName = "Acme Renamed") }
        val state2 = stateSigner.issue(UUID.fromString(ADMIN_UUID))
        mockMvc
            .perform(get("/slack/install/callback").param("code", "c2").param("state", state2))
            .andExpect(status().isFound)

        assertThat(rowCount(TEAM_ID)).isEqualTo(1)
    }

    // ── 실패 경로 (EC1 state 위조 / EC3 ok:false / EC2 사용자 취소) — 저장 없이 302 error ──

    @Test
    fun `GET callback - state 위조면 302 error invalid_state 이고 저장하지 않는다`() {
        mockMvc
            .perform(get("/slack/install/callback").param("code", "code").param("state", "tampered.state"))
            .andExpect(status().isFound)
            .andExpect(header().string("Location", "/settings/slack?error=invalid_state"))

        assertThat(rowCount(TEAM_ID)).isEqualTo(0)
    }

    @Test
    fun `GET callback - ok false면 302 error 슬랙코드이고 저장하지 않는다`() {
        fakeOAuth.responder = { failedResponse("invalid_code") }
        val state = stateSigner.issue(UUID.fromString(ADMIN_UUID))

        mockMvc
            .perform(get("/slack/install/callback").param("code", "bad").param("state", state))
            .andExpect(status().isFound)
            .andExpect(header().string("Location", "/settings/slack?error=invalid_code"))

        assertThat(rowCount(TEAM_ID)).isEqualTo(0)
    }

    @Test
    fun `GET callback - Slack이 준 errorCode도 안전 문자셋으로 정화된다 (NIT-1)`() {
        // Slack 이 비정상/악의적 error 코드(공백·특수문자·CRLF 주입 시도)를 돌려줘도, 방어심층으로 안전
        // 문자셋([A-Za-z0-9_-])만 리다이렉트 쿼리에 실린다(URLEncoder 만이 아니라 컨트롤러 정화도 적용).
        fakeOAuth.responder = { failedResponse("invalid code!\r\nSet-Cookie: evil") }
        val state = stateSigner.issue(UUID.fromString(ADMIN_UUID))

        val location =
            mockMvc
                .perform(get("/slack/install/callback").param("code", "bad").param("state", state))
                .andExpect(status().isFound)
                .andReturn()
                .response
                .getHeader("Location")

        // 정화 결과 — 공백·`!`·CRLF·`:` 는 제거되고 퍼센트 인코딩 잔재(%)도 남지 않는다.
        assertThat(location).isEqualTo("/settings/slack?error=invalidcodeSet-Cookieevil")
        assertThat(rowCount(TEAM_ID)).isEqualTo(0)
    }

    @Test
    fun `GET callback - 사용자 취소 error 파라미터면 302 error 리다이렉트이고 저장하지 않는다`() {
        mockMvc
            .perform(get("/slack/install/callback").param("error", "access_denied"))
            .andExpect(status().isFound)
            .andExpect(header().string("Location", "/settings/slack?error=access_denied"))

        assertThat(rowCount(TEAM_ID)).isEqualTo(0)
    }

    // ── DB 헬퍼 ──────────────────────────────────────────────────────────────────

    private fun rowCount(teamId: String): Int =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM slack_installs WHERE team_id = :teamId",
            mapOf("teamId" to teamId),
            Int::class.java,
        ) ?: 0

    private fun storedToken(teamId: String): String? =
        jdbc
            .queryForList(
                "SELECT bot_token_encrypted FROM slack_installs WHERE team_id = :teamId",
                mapOf("teamId" to teamId),
                String::class.java,
            ).firstOrNull()

    private fun installedByOf(teamId: String): String? =
        jdbc
            .queryForList(
                "SELECT installed_by FROM slack_installs WHERE team_id = :teamId",
                mapOf("teamId" to teamId),
                UUID::class.java,
            ).firstOrNull()
            ?.toString()

    // ── 로그 캡처 헬퍼 ────────────────────────────────────────────────────────────

    private fun attachRootAppender(): ListAppender<ILoggingEvent> {
        val root = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        root.addAppender(appender)
        return appender
    }

    private fun detachRootAppender(appender: ListAppender<ILoggingEvent>) {
        val root = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
        root.detachAppender(appender)
    }

    // ── fixtures ─────────────────────────────────────────────────────────────────

    /** 정상 워크스페이스 설치 응답(ok:true, team 존재). */
    private fun workspaceResponse(
        accessToken: String,
        teamName: String = TEAM_NAME,
    ): SlackOAuthTokenResponse =
        SlackOAuthTokenResponse(
            ok = true,
            error = null,
            accessToken = accessToken,
            tokenType = "bot",
            scope = "chat:write,commands",
            botUserId = "U123BOT",
            appId = "A123APP",
            teamId = TEAM_ID,
            teamName = teamName,
            isEnterpriseInstall = false,
        )

    /** Slack 이 정상 응답으로 반환한 실패(ok:false, error 코드 존재). */
    private fun failedResponse(error: String): SlackOAuthTokenResponse =
        SlackOAuthTokenResponse(
            ok = false,
            error = error,
            accessToken = null,
            tokenType = null,
            scope = null,
            botUserId = null,
            appId = null,
            teamId = null,
            teamName = null,
            isEnterpriseInstall = false,
        )

    /**
     * 통합 테스트 전용 fake [SlackOAuthClient] 빈 등록(@Primary — 스캔된 `DefaultSlackOAuthClient` 대신 주입).
     */
    @TestConfiguration
    class FakeOAuthConfig {
        @Bean
        @Primary
        fun fakeSlackOAuthClient(): FakeSlackOAuthClient = FakeSlackOAuthClient()
    }

    /**
     * 실 Slack 호출을 회피하는 fake [SlackOAuthClient]. 테스트가 [responder] 로 교환 결과를 제어한다.
     * authorize URL 은 state 를 실은 결정적 문자열을 반환한다.
     */
    class FakeSlackOAuthClient : SlackOAuthClient {
        @Volatile
        var responder: (String) -> SlackOAuthTokenResponse = { _ ->
            error("responder not configured")
        }

        override fun exchangeCode(code: String): SlackOAuthTokenResponse = responder(code)

        override fun buildAuthorizeUrl(state: String): String =
            "https://slack.com/oauth/v2/authorize?client_id=test-client&scope=chat:write&state=$state"
    }

    private companion object {
        const val TEAM_ID = "T123WS"
        const val TEAM_NAME = "Acme Workspace"
        const val PLAINTEXT_TOKEN = "xoxb-plaintext-bot-token-should-never-persist"
    }
}
