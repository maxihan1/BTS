// Slack 계정 연결 웹 레이어 풀스택 통합 테스트 — happy/해제/멱등/예외 5종/PAT 401/negative-probe (FR-SL-02 D6 Task 5)

package com.bts.slack.web

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.bts.shared.crypto.SecretEncryptor
import com.bts.slack.SlackIntegrationTestBootApplication
import com.bts.slack.SlackTestSecurityConfig
import com.bts.slack.SlackTestcontainersConfig
import com.bts.slack.StubUserLookupPort
import com.bts.slack.message.SlackUserLookupClient
import com.bts.slack.message.SlackUserLookupResult
import io.mockk.clearMocks
import io.mockk.every
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.RequestPostProcessor
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import java.time.Instant
import java.util.UUID

/**
 * [SlackConnectionController] 웹 레이어 풀스택 통합 테스트 (FR-SL-02 D6 Task 5).
 *
 * `SlackConnectionController` → `SlackUserConnectionService` → 실 `SlackInstallRepository`/
 * `SlackUserMappingRepository`(JdbcTemplate) → **실 PostgreSQL(Testcontainers)** end-to-end.
 * [SlackConnectionControllerTest](`@WebMvcTest` 슬라이스, service mock)와 달리 이 테스트는 서비스·
 * repository·봇 토큰 복호화까지 실제 구현을 태운다. 외부 경계인 Slack `users.lookupByEmail`만
 * mock [SlackUserLookupClient]([SlackTestcontainersConfig] 등록)로 대체한다.
 *
 * ## 커버 (D6 spec §API `/api/v1/slack/me/connection` GET/POST/DELETE)
 * - 연결 happy — POST 200 + DB `user_slack_mapping` 행 생성.
 * - 해제 — DELETE 200 + 행 제거.
 * - 재연결 멱등 — POST 두 번 → 행 1개(upsert).
 * - 미설치 409 / 스코프부족 409 / Slack 사용자 미발견 404 / 이메일 미설정 422.
 * - 미인증 401 / **PAT 401**(JWT-only 게이트 — `SlackActorExtractor` 재사용 시 발생하는 PAT 통과
 *   회귀를 실 필터 체인으로 확인, [SlackConnectionController] KDoc 참고).
 * - **negative-probe** — 응답 바디·로그 어디에도 봇 토큰(`xoxb`)·이메일·slack 사용자 id(`U1`)가
 *   실리지 않는다(§1.1.2).
 */
@SpringBootTest(
    classes = [SlackIntegrationTestBootApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.MOCK,
)
@Import(
    SlackTestcontainersConfig::class,
    SlackTestSecurityConfig::class,
)
@TestPropertySource(
    properties = [
        "bts.slack-encryption.key=slack-bot-token-encryption-key-for-integration-tests",
        "bts.slack-encryption.salt=deadbeefcafef00d",
    ],
)
class SlackConnectionIntegrationTest {
    @Autowired
    private lateinit var webApplicationContext: WebApplicationContext

    @Autowired
    private lateinit var jdbc: NamedParameterJdbcTemplate

    @Autowired
    private lateinit var userLookupPort: StubUserLookupPort

    @Autowired
    private lateinit var userLookupClient: SlackUserLookupClient

    @Autowired
    @Qualifier("slackSecretEncryptor")
    private lateinit var encryptor: SecretEncryptor

    private lateinit var mockMvc: MockMvc

    @BeforeEach
    fun setUp() {
        mockMvc =
            MockMvcBuilders
                .webAppContextSetup(webApplicationContext)
                .apply<org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder>(springSecurity())
                .build()
        jdbc.update("DELETE FROM user_slack_mapping", emptyMap<String, Any>())
        jdbc.update("DELETE FROM slack_installs", emptyMap<String, Any>())
        userLookupPort.displayNames.clear()
        userLookupPort.emails.clear()
        userLookupPort.emails[USER_ID] = EMAIL
        clearMocks(userLookupClient)
    }

    @AfterEach
    fun tearDown() {
        jdbc.update("DELETE FROM user_slack_mapping", emptyMap<String, Any>())
        jdbc.update("DELETE FROM slack_installs", emptyMap<String, Any>())
        userLookupPort.emails.clear()
    }

    // ── POST /api/v1/slack/me/connection — happy path ───────────────────────────

    @Test
    fun `POST connection - happy path면 200 connected true와 DB 매핑 행 생성`() {
        seedInstallation()
        every { userLookupClient.lookupByEmail(any(), EMAIL) } returns
            SlackUserLookupResult.Found(SLACK_USER_ID, TEAM_ID)

        mockMvc
            .perform(post("/api/v1/slack/me/connection").with(jwtAuth(USER_ID)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.connected").value(true))
            .andExpect(jsonPath("$.workspaceName").value(TEAM_NAME))
            .andExpect(jsonPath("$.linkedAt").isNotEmpty)

        assertThat(mappingRowCount(USER_ID)).isEqualTo(1)
    }

    // ── DELETE /api/v1/slack/me/connection — 해제 ────────────────────────────────

    @Test
    fun `DELETE connection - 연결 해제 후 200 connected false와 행 제거`() {
        seedInstallation()
        every { userLookupClient.lookupByEmail(any(), EMAIL) } returns
            SlackUserLookupResult.Found(SLACK_USER_ID, TEAM_ID)
        mockMvc.perform(post("/api/v1/slack/me/connection").with(jwtAuth(USER_ID))).andExpect(status().isOk)
        assertThat(mappingRowCount(USER_ID)).isEqualTo(1)

        mockMvc
            .perform(delete("/api/v1/slack/me/connection").with(jwtAuth(USER_ID)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.connected").value(false))

        assertThat(mappingRowCount(USER_ID)).isEqualTo(0)
    }

    // ── POST 재호출 — 멱등 upsert ─────────────────────────────────────────────────

    @Test
    fun `POST connection - 재연결해도 upsert로 행 1개를 유지한다`() {
        seedInstallation()
        every { userLookupClient.lookupByEmail(any(), EMAIL) } returns
            SlackUserLookupResult.Found(SLACK_USER_ID, TEAM_ID)

        mockMvc.perform(post("/api/v1/slack/me/connection").with(jwtAuth(USER_ID))).andExpect(status().isOk)
        mockMvc.perform(post("/api/v1/slack/me/connection").with(jwtAuth(USER_ID))).andExpect(status().isOk)

        assertThat(mappingRowCount(USER_ID)).isEqualTo(1)
    }

    // ── 예외 4종 ──────────────────────────────────────────────────────────────────

    @Test
    fun `POST connection - 워크스페이스 미설치면 409 WORKSPACE_NOT_INSTALLED`() {
        // slack_installs 를 시드하지 않는다(setUp 에서 이미 비어 있음).
        mockMvc
            .perform(post("/api/v1/slack/me/connection").with(jwtAuth(USER_ID)))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("WORKSPACE_NOT_INSTALLED"))

        assertThat(mappingRowCount(USER_ID)).isEqualTo(0)
    }

    @Test
    fun `POST connection - 봇 토큰에 스코프가 없으면 409 SLACK_SCOPE_MISSING`() {
        seedInstallation()
        every { userLookupClient.lookupByEmail(any(), EMAIL) } returns SlackUserLookupResult.MissingScope

        mockMvc
            .perform(post("/api/v1/slack/me/connection").with(jwtAuth(USER_ID)))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("SLACK_SCOPE_MISSING"))

        assertThat(mappingRowCount(USER_ID)).isEqualTo(0)
    }

    @Test
    fun `POST connection - 이메일에 매칭되는 Slack 사용자가 없으면 404 SLACK_USER_NOT_FOUND`() {
        seedInstallation()
        every { userLookupClient.lookupByEmail(any(), EMAIL) } returns SlackUserLookupResult.NotFound

        mockMvc
            .perform(post("/api/v1/slack/me/connection").with(jwtAuth(USER_ID)))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value("SLACK_USER_NOT_FOUND"))

        assertThat(mappingRowCount(USER_ID)).isEqualTo(0)
    }

    @Test
    fun `POST connection - BTS 계정에 이메일이 없으면 422 EMAIL_UNAVAILABLE`() {
        seedInstallation()
        userLookupPort.emails.remove(USER_ID)

        mockMvc
            .perform(post("/api/v1/slack/me/connection").with(jwtAuth(USER_ID)))
            .andExpect(status().isUnprocessableEntity)
            .andExpect(jsonPath("$.code").value("EMAIL_UNAVAILABLE"))

        assertThat(mappingRowCount(USER_ID)).isEqualTo(0)
    }

    // ── 인증 게이트 — 미인증 401 / PAT 401 ────────────────────────────────────────

    @Test
    fun `GET connection - 미인증이면 401`() {
        mockMvc
            .perform(get("/api/v1/slack/me/connection"))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `POST connection - PAT principal이면 401(false-green 방어)`() {
        // principal 이 Jwt 가 아니라 UUID 문자열(PatAuthenticationFilter 흉내)이면 컨트롤러가 401을 던진다.
        mockMvc
            .perform(post("/api/v1/slack/me/connection").with(patAuth(USER_ID)))
            .andExpect(status().isUnauthorized)

        assertThat(mappingRowCount(USER_ID)).isEqualTo(0)
    }

    // ── negative-probe — 비밀값 미노출 (§1.1.2) ───────────────────────────────────

    @Test
    fun `연결-조회-해제 전체 흐름의 응답과 로그에 봇 토큰·이메일·slack 사용자 id가 노출되지 않는다`() {
        seedInstallation()
        every { userLookupClient.lookupByEmail(any(), EMAIL) } returns
            SlackUserLookupResult.Found(SLACK_USER_ID, TEAM_ID)
        val appender = attachRootAppender()
        try {
            val connectBody =
                mockMvc
                    .perform(post("/api/v1/slack/me/connection").with(jwtAuth(USER_ID)))
                    .andExpect(status().isOk)
                    .andReturn()
                    .response
                    .contentAsString
            val statusBody =
                mockMvc
                    .perform(get("/api/v1/slack/me/connection").with(jwtAuth(USER_ID)))
                    .andExpect(status().isOk)
                    .andReturn()
                    .response
                    .contentAsString
            val disconnectBody =
                mockMvc
                    .perform(delete("/api/v1/slack/me/connection").with(jwtAuth(USER_ID)))
                    .andExpect(status().isOk)
                    .andReturn()
                    .response
                    .contentAsString

            listOf(connectBody, statusBody, disconnectBody).forEach { body ->
                assertThat(body).doesNotContain("xoxb")
                assertThat(body).doesNotContain(EMAIL)
                assertThat(body).doesNotContain(SLACK_USER_ID)
            }

            val logText = appender.list.joinToString("\n") { it.formattedMessage }
            assertThat(logText).doesNotContain("xoxb")
            assertThat(logText).doesNotContain(EMAIL)
            assertThat(logText).doesNotContain(SLACK_USER_ID)
        } finally {
            detachRootAppender(appender)
        }
    }

    // ── DB 헬퍼 ──────────────────────────────────────────────────────────────────

    /** 상태=설치됨을 재현할 `slack_installs` 1행을 직접 삽입한다(봇 토큰은 실 [encryptor]로 암호화). */
    private fun seedInstallation() {
        jdbc.update(
            """
            INSERT INTO slack_installs
                (team_id, team_name, bot_user_id, app_id, bot_token_encrypted, scopes,
                 is_enterprise_install, installed_by, installed_at, updated_at)
            VALUES
                (:teamId, :teamName, :botUserId, :appId, :botTokenEncrypted, :scopes,
                 false, :installedBy, now(), now())
            """,
            mapOf(
                "teamId" to TEAM_ID,
                "teamName" to TEAM_NAME,
                "botUserId" to "U0BOT",
                "appId" to "A123APP",
                "botTokenEncrypted" to encryptor.encrypt(PLAINTEXT_BOT_TOKEN),
                "scopes" to "chat:write,users:read.email",
                "installedBy" to UUID.randomUUID(),
            ),
        )
    }

    private fun mappingRowCount(userId: UUID): Int =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM user_slack_mapping WHERE user_id = :userId",
            mapOf("userId" to userId),
            Int::class.java,
        ) ?: 0

    // ── 인증 postprocessor 헬퍼 ([SlackConnectionControllerTest] 동형) ────────────

    private fun jwtFor(uid: UUID): Jwt =
        Jwt
            .withTokenValue("test-token")
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

    // ── 로그 캡처 헬퍼 ([SlackInstallIntegrationTest] 동형) ────────────────────────

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

    private companion object {
        val USER_ID: UUID = UUID.fromString("33333333-3333-4333-8333-333333333333")
        const val EMAIL = "connection-it@example.com"
        const val TEAM_ID = "T1"
        const val TEAM_NAME = "Acme Workspace"
        const val SLACK_USER_ID = "U1"
        const val PLAINTEXT_BOT_TOKEN = "xoxb-plaintext-bot-token-for-connection-it"
    }
}
