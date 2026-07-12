// POST /slack/interactions 풀스택 E2E — permitAll 필터·크기상한·서명검증·결과 직렬화 (FR-SL-05 PR1 Task 10)
package com.bts.slack.web

import com.bts.shared.board.BoardTransitionResult
import com.bts.shared.board.IssueTransitionPermissionDeniedException
import com.bts.shared.issue.DoneTransition
import com.bts.shared.issue.IssueCompletionOptions
import com.bts.shared.issue.ResolutionOption
import com.bts.slack.SlackIntegrationTestBootApplication
import com.bts.slack.SlackTestSecurityConfig
import com.bts.slack.SlackTestcontainersConfig
import com.bts.slack.StubIssueCompletionOptionsPort
import com.bts.slack.StubIssueTransitionPort
import com.bts.slack.application.SlackUserMappingRepository
import com.bts.slack.message.SlackResponseUrlClient
import com.bts.slack.worker.SlackBotTokenResolver
import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.HexFormat
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * `@TestPropertySource` 값은 컴파일 타임 상수여야 하므로 top-level const 로 둔다
 * ([SlackSlashCommandEndToEndTest][com.bts.slack.command.SlackSlashCommandEndToEndTest] 동일 관례).
 */
private const val INTERACTIONS_SIGNING_SECRET = "slack-interactions-e2e-signing-secret-0123456789"

/**
 * `POST /slack/interactions` 로 들어오는 실제 Slack 인터랙티브(완료 버튼/완료 모달 제출)의 풀스택 E2E
 * (FR-SL-05 PR1 Task 10).
 *
 * [SlackInteractionsController] → 실 [com.bts.slack.security.SlackSignatureVerifier] →
 * 실 [com.bts.slack.interaction.SlackInteractionPayloadParser] →
 * 실 [com.bts.slack.interaction.SlackInteractionService] →
 * 실 [SlackUserMappingRepository](Testcontainers PostgreSQL) + seed 가능한 cross-BC stub 까지 end-to-end 로,
 * 필터 체인 permitAll 배선·크기상한·서명검증·[com.bts.slack.interaction.InteractionResult] HTTP 직렬화를 검증한다.
 *
 * ## 실 필터 체인 위에서 검증한다 (`springSecurity()` webAppContextSetup)
 * slack-integration 모듈은 임베디드 서블릿 컨테이너(Tomcat 등)를 test 클래스패스에 두지 않으므로
 * `RANDOM_PORT` 로 실 소켓 서버를 띄울 수 없다. 대신 [SlackSlashCommandEndToEndTest]
 * [com.bts.slack.command.SlackSlashCommandEndToEndTest] 동형으로 `springSecurity()` MockMvc 구성기로 **실
 * [SlackTestSecurityConfig] 필터 체인**을 얹어, permitAll 경계·서명검증·본문 크기 상한을 실 배선으로 통과시킨다
 * (MockMvc 슬라이스가 아니라 full 컨텍스트 + 실 필터 체인). 본문 크기 상한은 이 컨트롤러에서 핸들러 레벨로
 * 검사하므로 이 경로로 실제 413 이 검증된다.
 *
 * ## permitAll 배선이 load-bearing (이 테스트가 실증하는 보안 경계)
 * [SlackTestSecurityConfig] 가 `POST /slack/interactions` 를 permitAll 하지 않으면, 유효 서명 요청도 필터
 * 체인의 `authenticated()` 에 걸려 컨트롤러에 닿기 전에 401 로 막힌다(JWT 가 없는 서버-투-서버 호출이므로).
 * permitAll 은 인증을 **없애는** 것이 아니라 인증 수단을 필터의 JWT 에서 컨트롤러의 서명검증으로 교체한다.
 *
 * ## outbound 협력자만 test-double (실 네트워크 회피, 결정론)
 * cross-BC 완료 옵션/전이는 seed 가능한 stub([StubIssueCompletionOptionsPort]/[StubIssueTransitionPort],
 * [SlackTestcontainersConfig] `@Bean`)으로, Slack SDK 호출은 [SlackTestcontainersConfig] 의 `@Primary` mock
 * `MethodsClient` 로 대체된다. 여기에 더해 이 테스트는 [CaptureCollaboratorsConfig] 로 (1) 봇 토큰 해석기를
 * `@Primary` mock 으로 오버라이드해 slack_installs 시드 없이 완료 모달 경로를 태우고, (2) `response_url`
 * ephemeral 클라이언트를 `@Primary` mock 으로 오버라이드해 실 HTTP 없이 fail-closed 안내 여부를 검증한다.
 * 실제 `views.open`/`chat.update` 오케스트레이션과 타입 예외 분류는 단위 테스트
 * [com.bts.slack.interaction.SlackInteractionServiceTest] 가 상세 검증한다.
 */
@SpringBootTest(
    classes = [SlackIntegrationTestBootApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.MOCK,
)
@Import(
    SlackTestcontainersConfig::class,
    SlackTestSecurityConfig::class,
    SlackInteractionsControllerTest.CaptureCollaboratorsConfig::class,
)
@TestPropertySource(
    properties = [
        "bts.slack-encryption.key=slack-bot-token-encryption-key-for-inter-e2e",
        "bts.slack-encryption.salt=deadbeefcafef00d",
        "bts.slack.signing-secret=$INTERACTIONS_SIGNING_SECRET",
        "bts.atlas.base-url=https://atlas.example.com",
    ],
)
@Suppress("TooManyFunctions") // 6개 시나리오 + 서명/폼/시드 헬퍼로 임계값(11)을 넘는다 — 책임은 단일(interactions E2E).
class SlackInteractionsControllerTest {
    @Autowired
    private lateinit var webApplicationContext: WebApplicationContext

    @Autowired
    private lateinit var mappingRepository: SlackUserMappingRepository

    @Autowired
    private lateinit var completionOptionsPort: StubIssueCompletionOptionsPort

    @Autowired
    private lateinit var transitionPort: StubIssueTransitionPort

    @Autowired
    private lateinit var jdbc: JdbcTemplate

    @Autowired
    private lateinit var botTokenResolver: SlackBotTokenResolver

    @Autowired
    private lateinit var responseUrlClient: SlackResponseUrlClient

    private lateinit var mockMvc: MockMvc

    private val objectMapper = ObjectMapper()

    @BeforeEach
    fun setUp() {
        mockMvc =
            MockMvcBuilders
                .webAppContextSetup(webApplicationContext)
                .apply<DefaultMockMvcBuilder>(springSecurity())
                .build()
        clearState()
        clearMocks(botTokenResolver, responseUrlClient)
        every { botTokenResolver.resolve(any()) } returns BOT_TOKEN
        every { responseUrlClient.post(any(), any()) } returns true
    }

    @AfterEach
    fun tearDown() {
        clearState()
    }

    // ── (1) block_actions atlas_complete — 완료옵션 시드 시 모달 경로 → 빈 200 (ephemeral 없음) ─────

    @Test
    fun `block_actions atlas_complete - 매핑 완료옵션 봇토큰이 있으면 모달 경로로 빈 200 을 반환하고 ephemeral 안내는 없다`() {
        seedMapping()
        completionOptionsPort.completionOptionsByIssueKey[ISSUE_KEY] = completionOptions()

        val body =
            mockMvc.perform(postInteraction(blockActionsCompletePayload()))
                .andExpect(status().isOk)
                .andReturn().response.contentAsString
        assertThat(body).isEmpty()

        // 모달 경로(봇토큰 존재)면 완료옵션/미연결 fail-closed ephemeral 이 나가지 않는다.
        verify(exactly = 0) { responseUrlClient.post(any(), any()) }
        assertThat(transitionPort.lastCommand).isNull()
    }

    // ── (2) view_submission 성공 — 전이 실행 + 빈 200 (모달 닫기) + actor 정합 ─────────────────────

    @Test
    fun `view_submission atlas_complete_modal - 전이 성공은 빈 200 이고 매핑된 사용자로 전이가 실행된다`() {
        seedMapping()
        transitionPort.succeedWith(BoardTransitionResult(ISSUE_KEY, TO_STATE_KEY, 4))

        val body =
            mockMvc.perform(postInteraction(viewSubmissionPayload()))
                .andExpect(status().isOk)
                .andReturn().response.contentAsString
        assertThat(body).isEmpty()

        val command = requireNotNull(transitionPort.lastCommand)
        assertThat(command.actorUserId).isEqualTo(BTS_USER_ID)
        assertThat(command.issueKey).isEqualTo(ISSUE_KEY)
        assertThat(command.toStateKey).isEqualTo(TO_STATE_KEY)
        assertThat(command.expectedVersion).isEqualTo(EXPECTED_VERSION)
        assertThat(command.resolutionId).isEqualTo(RESOLUTION_ID)
    }

    // ── (3) view_submission 권한 거부 — 200 + response_action errors 본문 (모달 유지) ──────────────

    @Test
    fun `view_submission - 전이 권한 거부는 200 이고 response_action errors 본문을 반환한다`() {
        seedMapping()
        transitionPort.failWith(IssueTransitionPermissionDeniedException("denied"))

        val body =
            mockMvc.perform(postInteraction(viewSubmissionPayload()))
                .andExpect(status().isOk)
                .andReturn().response.contentAsString
        assertThat(body).contains("response_action").contains("errors")
    }

    // ── (4) 잘못된 서명 → 빈 401, 어떤 전이도 실행되지 않는다 (fail-closed) ─────────────────────────

    @Test
    fun `잘못된 서명 - 빈 401 이고 전이는 실행되지 않는다`() {
        seedMapping()
        transitionPort.succeedWith(BoardTransitionResult(ISSUE_KEY, TO_STATE_KEY, 4))

        mockMvc.perform(postInteraction(viewSubmissionPayload(), signature = "v0=deadbeefdeadbeefdeadbeefdeadbeef"))
            .andExpect(status().isUnauthorized)

        assertThat(transitionPort.lastCommand).isNull()
    }

    // ── (5) 서명 헤더 누락 → 401 ─────────────────────────────────────────────────────────────────

    @Test
    fun `서명 헤더 누락 - 빈 401 이고 전이는 실행되지 않는다`() {
        seedMapping()
        transitionPort.succeedWith(BoardTransitionResult(ISSUE_KEY, TO_STATE_KEY, 4))

        mockMvc.perform(postInteraction(viewSubmissionPayload(), includeSignature = false))
            .andExpect(status().isUnauthorized)

        assertThat(transitionPort.lastCommand).isNull()
    }

    // ── (6) 본문 크기 상한 초과 → 빈 413 (서명검증·서비스 모두 미도달) ─────────────────────────────

    @Test
    fun `본문 크기 상한 초과 - 빈 413 이고 전이는 실행되지 않는다`() {
        seedMapping()
        transitionPort.succeedWith(BoardTransitionResult(ISSUE_KEY, TO_STATE_KEY, 4))
        val huge = "payload=" + "a".repeat(OVERSIZE_BODY_LENGTH)

        mockMvc.perform(postRaw(huge))
            .andExpect(status().isPayloadTooLarge)

        assertThat(transitionPort.lastCommand).isNull()
    }

    // ── 요청 조립 헬퍼 ───────────────────────────────────────────────────────────────────────────

    /** `payload=<URL-encoded JSON>` form 바디를 만들어 유효/지정 서명으로 전송하는 요청 빌더를 만든다. */
    private fun postInteraction(
        payloadJson: String,
        signature: String? = null,
        includeSignature: Boolean = true,
    ) = postRaw(
        body = "payload=" + URLEncoder.encode(payloadJson, StandardCharsets.UTF_8),
        signature = signature,
        includeSignature = includeSignature,
    )

    /** 원문 [body] 를 form 미디어 타입 + Slack 서명 헤더로 실어 보내는 요청 빌더(서명은 body 원문 기준 계산). */
    private fun postRaw(
        body: String,
        signature: String? = null,
        includeSignature: Boolean = true,
    ): MockHttpServletRequestBuilder {
        val timestamp = Instant.now().truncatedTo(ChronoUnit.SECONDS).epochSecond.toString()
        val builder =
            post("/slack/interactions")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .content(body)
                .header(TIMESTAMP_HEADER, timestamp)
        if (includeSignature) {
            builder.header(SIGNATURE_HEADER, signature ?: signatureFor(timestamp, body))
        }
        return builder
    }

    // ── payload JSON 조립 (Slack 원문 최상위 구조 재현) ──────────────────────────────────────────

    private fun blockActionsCompletePayload(): String {
        val root = objectMapper.createObjectNode()
        root.put("type", "block_actions")
        root.putObject("user").put("id", SLACK_USER_ID)
        root.putObject("team").put("id", TEAM_ID)
        root.put("trigger_id", TRIGGER_ID)
        root.put("response_url", RESPONSE_URL)
        root.putObject("channel").put("id", CHANNEL_ID)
        root.putObject("message").put("ts", MESSAGE_TS)
        root.putArray("actions").addObject().put("action_id", "atlas_complete").put("value", ISSUE_KEY)
        return objectMapper.writeValueAsString(root)
    }

    private fun viewSubmissionPayload(callbackId: String = "atlas_complete_modal"): String {
        val root = objectMapper.createObjectNode()
        root.put("type", "view_submission")
        root.putObject("user").put("id", SLACK_USER_ID)
        root.putObject("team").put("id", TEAM_ID)
        val view = root.putObject("view")
        view.put("callback_id", callbackId)
        view.put("private_metadata", privateMetadata())
        val values = view.putObject("state").putObject("values")
        values.putObject("done_transition_block").putObject("done_transition_select")
            .putObject("selected_option").put("value", TO_STATE_KEY)
        values.putObject("resolution_block").putObject("resolution_select")
            .putObject("selected_option").put("value", RESOLUTION_ID.toString())
        return objectMapper.writeValueAsString(root)
    }

    private fun privateMetadata(): String {
        val meta = objectMapper.createObjectNode()
        meta.put("issueKey", ISSUE_KEY)
        meta.put("expectedVersion", EXPECTED_VERSION)
        meta.put("channel", CHANNEL_ID)
        meta.put("ts", MESSAGE_TS)
        return objectMapper.writeValueAsString(meta)
    }

    // ── 서명 헬퍼 — SlackSignatureVerifier 와 동일한 v0 계산 재현 ─────────────────────────────────

    private fun signatureFor(
        timestamp: String,
        rawBody: String,
    ): String {
        val baseString = "v0:$timestamp:$rawBody"
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(SecretKeySpec(INTERACTIONS_SIGNING_SECRET.toByteArray(Charsets.UTF_8), HMAC_ALGORITHM))
        return "v0=" + HexFormat.of().formatHex(mac.doFinal(baseString.toByteArray(Charsets.UTF_8)))
    }

    // ── 시드/정리 헬퍼 ───────────────────────────────────────────────────────────────────────────

    private fun completionOptions() =
        IssueCompletionOptions(
            version = EXPECTED_VERSION,
            doneTransitions = listOf(DoneTransition(TO_STATE_KEY, "In Review")),
            resolutions = listOf(ResolutionOption(RESOLUTION_ID, "Fixed")),
        )

    private fun seedMapping() = mappingRepository.upsert(BTS_USER_ID, SLACK_USER_ID, TEAM_ID)

    private fun clearState() {
        jdbc.update("DELETE FROM user_slack_mapping")
        jdbc.update("DELETE FROM slack_interaction_log")
        completionOptionsPort.completionOptionsByIssueKey.clear()
        transitionPort.reset()
    }

    /**
     * outbound 협력자 오버라이드 — 봇 토큰 해석기와 `response_url` 클라이언트를 `@Primary` mock 으로 대체한다.
     *
     * 봇 토큰 해석기는 실제로는 `slack_installs` 를 조회/복호화하지만, 이 E2E 는 설치 시드 없이 완료 모달
     * 경로를 태우기 위해 mock 으로 임의 토큰을 반환하게 한다([SlackSlashCommandEndToEndTest] 의 response_url
     * 캡처 오버라이드 동형). `response_url` 클라이언트는 실 HTTP(SSRF 가드 포함)를 회피하고 fail-closed
     * ephemeral 안내 호출 여부만 검증하기 위해 mock 으로 둔다.
     */
    @TestConfiguration
    class CaptureCollaboratorsConfig {
        /** 완료 모달 경로용 임의 봇 토큰을 반환하는 `@Primary` mock [SlackBotTokenResolver] 빈. */
        @Bean
        @Primary
        fun captureBotTokenResolver(): SlackBotTokenResolver = mockk()

        /** ephemeral 실 HTTP 를 회피하는 `@Primary` mock [SlackResponseUrlClient] 빈. */
        @Bean
        @Primary
        fun captureResponseUrlClient(): SlackResponseUrlClient = mockk()
    }

    private companion object {
        const val TIMESTAMP_HEADER = "X-Slack-Request-Timestamp"
        const val SIGNATURE_HEADER = "X-Slack-Signature"
        const val HMAC_ALGORITHM = "HmacSHA256"

        const val BTS_USER_ID_STRING = "66666666-6666-4666-8666-666666666666"
        val BTS_USER_ID: UUID = UUID.fromString(BTS_USER_ID_STRING)
        const val RESOLUTION_ID_STRING = "77777777-7777-4777-8777-777777777777"
        val RESOLUTION_ID: UUID = UUID.fromString(RESOLUTION_ID_STRING)

        const val TEAM_ID = "T0INTERTEAM01"
        const val SLACK_USER_ID = "U0INTERUSER01"
        const val ISSUE_KEY = "PROJ-1"
        const val CHANNEL_ID = "C0INTERCHAN01"
        const val MESSAGE_TS = "1700000000.000100"
        const val TRIGGER_ID = "trig-inter-1"
        const val TO_STATE_KEY = "in-review"
        const val EXPECTED_VERSION = 3L
        const val BOT_TOKEN = "xoxb-inter-fake"
        const val RESPONSE_URL = "https://hooks.slack.com/actions/T0INTERTEAM01/1111/abcdefg"

        /** 상한(64KB)을 확실히 넘기는 본문 길이. */
        const val OVERSIZE_BODY_LENGTH = 70_000
    }
}
