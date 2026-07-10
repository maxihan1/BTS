// 가짜 Slack link_shared 이벤트 풀스택 E2E — 서명검증 통과 후 chat.unfurl 호출/미호출을 실 배선으로 검증 (FR-SL-03 Task 12)
package com.bts.slack.unfurl

import com.bts.shared.crypto.SecretEncryptor
import com.bts.shared.issue.IssueUnfurlView
import com.bts.slack.SlackIntegrationTestBootApplication
import com.bts.slack.SlackTestSecurityConfig
import com.bts.slack.SlackTestcontainersConfig
import com.bts.slack.StubIssueUnfurlPort
import com.bts.slack.application.SlackInstallRepository
import com.bts.slack.application.SlackUserMappingRepository
import com.bts.slack.config.SlackAsyncConfig
import com.bts.slack.domain.SlackInstall
import com.slack.api.RequestConfigurator
import com.slack.api.methods.MethodsClient
import com.slack.api.methods.request.chat.ChatUnfurlRequest
import com.slack.api.methods.response.chat.ChatUnfurlResponse
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import java.time.Duration
import java.time.Instant
import java.util.HexFormat
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * `@TestPropertySource` 값은 컴파일 타임 상수여야 하므로 top-level const로 둔다
 * ([com.bts.slack.config.SlackAsyncConfig] 상단 주석과 동일 관례 — 클래스 내부 companion object 상수는
 * 클래스 자신에 붙는 애노테이션에서 참조할 수 없다).
 */
private const val ATLAS_BASE_URL = "https://atlas.example.com"

/** `mockk`의 `chatUnfurl` 오버로드 모호성 해소용 타입 별칭([com.bts.slack.message.SlackUnfurlClientTest] 동형 필요). */
private typealias ChatUnfurlConfigurator = RequestConfigurator<ChatUnfurlRequest.ChatUnfurlRequestBuilder>

/**
 * `POST /slack/events` 로 들어오는 가짜 Slack `link_shared` 이벤트의 풀스택 E2E (FR-SL-03 Task 12).
 *
 * [com.bts.slack.web.SlackEventsController] → 실 [com.bts.slack.security.SlackSignatureVerifier] →
 * 실 [SlackUnfurlService] → 실 [SlackUserMappingRepository]/[SlackInstallRepository](Testcontainers
 * PostgreSQL) → 실 [com.bts.slack.message.SlackUnfurlClient] → mock [MethodsClient] 까지 end-to-end.
 * cross-BC 이슈 조회만 [StubIssueUnfurlPort]로 대체한다([SlackConnectionIntegrationTest] 동형).
 *
 * ## 서명 — 실 HMAC 계산 (SlackSignatureVerifier 우회 없음)
 * [signatureFor]가 [com.bts.slack.security.SlackSignatureVerifier]와 동일한
 * `v0=HMAC-SHA256(secret, "v0:{ts}:{rawBody}")` 를 재계산해 헤더에 싣는다 — 서명 검증기를 mock으로
 * 우회하지 않고 실 구현을 통과시킨다.
 *
 * ## `@Async` 결정성 — 실 executor 완료를 폴링 (sync executor override 대신)
 * [SlackAsyncConfig.slackUnfurlExecutor]는 `SlackTestcontainersConfig`를 공유하는 다른 풀스택 통합
 * 테스트([com.bts.slack.web.SlackConnectionIntegrationTest] 등)도 같은 이름의 빈을 기대하므로, 이 빈을
 * 동기 executor로 바꿔치기하면 이름 충돌(`BeanDefinitionOverrideException`)이나 `allow-bean-definition
 * -overriding` 전역 완화가 필요해 형제 테스트에 회귀 위험이 생긴다. 대신 실 [ThreadPoolTaskExecutor]의
 * `activeCount`/`queue`가 모두 비워질 때까지 [awaitAsyncSettled]로 폴링한다 — `@Async` 제출은
 * 컨트롤러가 서비스 빈을 호출하는 시점에 이미 동기적으로 이루어지므로(3초 룰), HTTP 응답을 받은 뒤
 * 폴링을 시작해도 "제출 전 vacuous 통과" 경합이 없다.
 *
 * ## 시나리오
 * - happy — 봇 설치 + 역매핑 + 가시 이슈 시드 → `chat.unfurl` 1회 호출(unfurls에 이슈 URL 포함) + 200.
 * - fail-closed(미매핑) — 역매핑 없음 → `chat.unfurl` 미호출 + 200.
 * - fail-closed(무권한) — 역매핑은 있지만 [StubIssueUnfurlPort]에 이슈 미등록(null) → `chat.unfurl` 미호출 + 200.
 * - `url_verification` — challenge 왕복.
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
        "bts.slack-encryption.key=slack-bot-token-encryption-key-for-unfurl-e2e",
        "bts.slack-encryption.salt=deadbeefcafef00d",
        "bts.slack.signing-secret=slack-unfurl-e2e-signing-secret-0123456789",
        "bts.atlas.base-url=$ATLAS_BASE_URL",
    ],
)
class SlackUnfurlEndToEndTest {
    @Autowired
    private lateinit var webApplicationContext: WebApplicationContext

    @Autowired
    private lateinit var jdbc: NamedParameterJdbcTemplate

    @Autowired
    private lateinit var installRepository: SlackInstallRepository

    @Autowired
    private lateinit var mappingRepository: SlackUserMappingRepository

    @Autowired
    private lateinit var issueUnfurlPort: StubIssueUnfurlPort

    @Autowired
    private lateinit var methodsClient: MethodsClient

    @Autowired
    @Qualifier("slackSecretEncryptor")
    private lateinit var encryptor: SecretEncryptor

    @Autowired
    @Qualifier(SlackAsyncConfig.SLACK_UNFURL_EXECUTOR_BEAN_NAME)
    private lateinit var unfurlExecutor: ThreadPoolTaskExecutor

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
        issueUnfurlPort.visibleIssues.clear()
        clearMocks(methodsClient)
    }

    @AfterEach
    fun tearDown() {
        jdbc.update("DELETE FROM user_slack_mapping", emptyMap<String, Any>())
        jdbc.update("DELETE FROM slack_installs", emptyMap<String, Any>())
        issueUnfurlPort.visibleIssues.clear()
    }

    // ── happy path ────────────────────────────────────────────────────────────

    @Test
    fun `link_shared - 가시 이슈면 chat_unfurl을 1회 호출하고 unfurls에 이슈 URL을 담는다`() {
        seedInstallation()
        mappingRepository.upsert(USER_ID, SLACK_USER_ID, TEAM_ID)
        issueUnfurlPort.visibleIssues[ISSUE_KEY] =
            IssueUnfurlView(
                issueKey = ISSUE_KEY,
                summary = "unfurl 카드 확인용 이슈",
                statusLabel = "진행중",
                priorityLabel = "높음",
                assigneeDisplayName = null,
            )
        val requestSlot = slot<ChatUnfurlConfigurator>()
        every { methodsClient.chatUnfurl(capture(requestSlot)) } returns ChatUnfurlResponse().apply { isOk = true }

        val body = linkSharedEventJson(slackUserId = SLACK_USER_ID, links = listOf(ISSUE_URL))
        mockMvc.perform(postSignedEvents(body)).andExpect(status().isOk)

        awaitAsyncSettled()
        verify(exactly = 1) { methodsClient.chatUnfurl(any<ChatUnfurlConfigurator>()) }
        val built = ChatUnfurlRequest.builder().also { requestSlot.captured.configure(it) }.build()
        assertThat(built.channel).isEqualTo(CHANNEL)
        assertThat(built.ts).isEqualTo(MESSAGE_TS)
        assertThat(built.rawUnfurls).contains(ISSUE_URL)
        assertThat(built.rawUnfurls).contains(ISSUE_KEY)
    }

    // ── fail-closed — 미매핑 ──────────────────────────────────────────────────────

    @Test
    fun `link_shared - Slack 사용자가 역매핑되지 않으면 chat_unfurl을 호출하지 않는다`() {
        seedInstallation()
        // mappingRepository.upsert 를 호출하지 않는다 — event.user 가 어떤 BTS 계정으로도 역매핑되지 않는다.

        val body = linkSharedEventJson(slackUserId = SLACK_USER_ID, links = listOf(ISSUE_URL))
        mockMvc.perform(postSignedEvents(body)).andExpect(status().isOk)

        awaitAsyncSettled()
        verify(exactly = 0) { methodsClient.chatUnfurl(any<ChatUnfurlConfigurator>()) }
    }

    // ── fail-closed — 무권한/없는 키 ───────────────────────────────────────────────

    @Test
    fun `link_shared - 매핑은 있어도 이슈를 볼 수 없으면 chat_unfurl을 호출하지 않는다`() {
        seedInstallation()
        mappingRepository.upsert(USER_ID, SLACK_USER_ID, TEAM_ID)
        // issueUnfurlPort.visibleIssues 를 비운 채로 둔다 — StubIssueUnfurlPort 는 미등록 issueKey 를 null(fail-closed)로 되돌린다.

        val body = linkSharedEventJson(slackUserId = SLACK_USER_ID, links = listOf(ISSUE_URL))
        mockMvc.perform(postSignedEvents(body)).andExpect(status().isOk)

        awaitAsyncSettled()
        verify(exactly = 0) { methodsClient.chatUnfurl(any<ChatUnfurlConfigurator>()) }
    }

    // ── url_verification 왕복 ────────────────────────────────────────────────────

    @Test
    fun `url_verification - 서명 검증 통과 후 challenge 를 그대로 반환한다`() {
        val body = urlVerificationJson(CHALLENGE)

        mockMvc.perform(postSignedEvents(body))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.challenge").value(CHALLENGE))
    }

    // ── 서명 헬퍼 — SlackSignatureVerifier와 동일 v0 계산을 재현 ──────────────────────

    private fun postSignedEvents(body: String): MockHttpServletRequestBuilder {
        val timestamp = Instant.now().epochSecond.toString()
        return post("/slack/events")
            .contentType(MediaType.APPLICATION_JSON)
            .content(body)
            .header(TIMESTAMP_HEADER, timestamp)
            .header(SIGNATURE_HEADER, signatureFor(timestamp, body))
    }

    private fun signatureFor(
        timestamp: String,
        rawBody: String,
    ): String {
        val baseString = "v0:$timestamp:$rawBody"
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(SecretKeySpec(SIGNING_SECRET.toByteArray(Charsets.UTF_8), HMAC_ALGORITHM))
        return "v0=" + HexFormat.of().formatHex(mac.doFinal(baseString.toByteArray(Charsets.UTF_8)))
    }

    // ── payload 헬퍼 ──────────────────────────────────────────────────────────────

    private fun linkSharedEventJson(
        slackUserId: String?,
        links: List<String>,
    ): String {
        val linksJson = links.joinToString(",") { url -> """{"url":"$url"}""" }
        val userField = slackUserId?.let { """"user": "$it",""" } ?: ""
        return """
            {
              "type": "event_callback",
              "team_id": "$TEAM_ID",
              "event": {
                "type": "link_shared",
                $userField
                "channel": "$CHANNEL",
                "message_ts": "$MESSAGE_TS",
                "links": [$linksJson]
              }
            }
            """.trimIndent()
    }

    private fun urlVerificationJson(challenge: String): String {
        return """{"type":"url_verification","challenge":"$challenge"}"""
    }

    // ── DB 시드 헬퍼 ─────────────────────────────────────────────────────────────

    private fun seedInstallation() {
        installRepository.upsert(
            SlackInstall(
                teamId = TEAM_ID,
                teamName = TEAM_NAME,
                botUserId = "U0UNFURLBOT",
                appId = "A0UNFURLAPP",
                botTokenEncrypted = encryptor.encrypt("xoxb-plaintext-bot-token-for-unfurl-e2e"),
                scopes = "chat:write,links:read,links:write",
                isEnterpriseInstall = false,
                installedBy = UUID.randomUUID(),
            ),
        )
    }

    // ── @Async 결정성 헬퍼 ───────────────────────────────────────────────────────

    /**
     * `slackUnfurlExecutor`의 활성 스레드·대기열이 모두 비워질 때까지 폴링한다. `@Async` 제출은 컨트롤러가
     * [SlackUnfurlService.handleLinkShared] 빈을 호출하는 시점에 이미 이루어지므로(HTTP 응답 이전),
     * 이 호출 시점엔 제출이 끝나 있어 "제출 전 vacuous 통과" 경합이 없다.
     */
    private fun awaitAsyncSettled() {
        await().atMost(Duration.ofSeconds(ASYNC_AWAIT_SECONDS)).untilAsserted {
            assertThat(unfurlExecutor.threadPoolExecutor.activeCount).isZero()
            assertThat(unfurlExecutor.threadPoolExecutor.queue).isEmpty()
        }
    }

    private companion object {
        const val TIMESTAMP_HEADER = "X-Slack-Request-Timestamp"
        const val SIGNATURE_HEADER = "X-Slack-Signature"
        const val HMAC_ALGORITHM = "HmacSHA256"
        const val SIGNING_SECRET = "slack-unfurl-e2e-signing-secret-0123456789"
        const val ASYNC_AWAIT_SECONDS = 5L

        const val USER_ID_STRING = "44444444-4444-4444-8444-444444444444"
        val USER_ID: UUID = UUID.fromString(USER_ID_STRING)
        const val TEAM_ID = "T0UNFURLTEAM1"
        const val TEAM_NAME = "Unfurl Workspace"
        const val SLACK_USER_ID = "U0UNFURLUSER1"
        const val CHANNEL = "C0UNFURLCHAN1"
        const val MESSAGE_TS = "1700000000.000100"
        const val ISSUE_KEY = "PROJ-1"
        const val ISSUE_URL = "$ATLAS_BASE_URL/issues/$ISSUE_KEY"
        const val CHALLENGE = "unfurl-e2e-challenge-abc123"
    }
}
