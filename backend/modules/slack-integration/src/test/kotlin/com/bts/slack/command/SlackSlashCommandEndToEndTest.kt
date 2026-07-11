// `/atlas` slash 명령 풀스택 E2E — permitAll 필터 + 서명검증 + @Async 위임 + response_url 캡처 (FR-SL-04 Task 9)
package com.bts.slack.command

import com.bts.shared.issue.IssueImportResult
import com.bts.shared.issue.IssueUnfurlView
import com.bts.shared.search.IssueSearchHit
import com.bts.shared.search.IssueSearchPage
import com.bts.shared.search.SlashSearchOutcome
import com.bts.slack.SlackIntegrationTestBootApplication
import com.bts.slack.SlackTestSecurityConfig
import com.bts.slack.SlackTestcontainersConfig
import com.bts.slack.StubIssueImportPort
import com.bts.slack.StubIssueUnfurlPort
import com.bts.slack.StubSlashIssueSearchPort
import com.bts.slack.application.SlackUserMappingRepository
import com.bts.slack.config.SlackAsyncConfig
import com.bts.slack.message.SlackResponseUrlClient
import com.fasterxml.jackson.databind.node.ArrayNode
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
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
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.http.MediaType
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.HexFormat
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * `@TestPropertySource` 값은 컴파일 타임 상수여야 하므로 top-level const 로 둔다
 * ([SlackUnfurlEndToEndTest][com.bts.slack.unfurl.SlackUnfurlEndToEndTest] 동일 관례).
 */
private const val ATLAS_BASE_URL = "https://atlas.example.com"
private const val SLASH_SIGNING_SECRET = "slack-slash-e2e-signing-secret-0123456789"

/**
 * `POST /slack/commands` 로 들어오는 실제 Slack slash 명령의 풀스택 E2E (FR-SL-04 Task 9).
 *
 * [com.bts.slack.web.SlackCommandsController] → 실 [com.bts.slack.security.SlackSignatureVerifier] →
 * 실 [SlashCommandService](@Async) → 실 [SlashCommandParser]/[SlashCommandHandlers] →
 * 실 [SlackUserMappingRepository](Testcontainers PostgreSQL) → 캡처 [SlackResponseUrlClient] 까지
 * end-to-end 로, 필터 체인 permitAll 배선·서명검증·@Async 위임·fail-closed 경계를 실 배선으로 검증한다
 * ([SlackUnfurlEndToEndTest][com.bts.slack.unfurl.SlackUnfurlEndToEndTest] 동형).
 *
 * cross-BC 이슈 조회/검색/생성만 test-double 로 대체한다 — [StubIssueUnfurlPort]([SlackTestcontainersConfig]
 * `@Bean`) · [StubSlashIssueSearchPort]/[StubIssueImportPort](컴포넌트 스캔) · 캡처
 * [SlackResponseUrlClient](아래 [CaptureResponseUrlClientConfig] `@Primary`).
 *
 * ## permitAll 배선이 load-bearing (이 테스트가 실증하는 보안 경계)
 * [SlackTestSecurityConfig] 가 `POST /slack/commands` 를 permitAll 하지 않으면, 유효 서명 요청도 필터 체인의
 * `authenticated()` 에 걸려 컨트롤러에 닿기 전에 401 로 막힌다(JWT 가 없는 서버-투-서버 호출이므로). 이
 * 배선 없이 이 파일의 happy 시나리오는 모두 401 로 실패한다(Task 9 RED). permitAll 은 인증을 **없애는** 것이
 * 아니라, 인증 수단을 필터의 JWT 에서 컨트롤러의 서명검증으로 **교체**하는 것이다.
 *
 * ## 서명검증은 permitAll 과 무관하게 무조건 선행 (fail-closed)
 * permitAll 로 필터를 통과해도 [com.bts.slack.web.SlackCommandsController] 는 [SlackSignatureVerifier] 로
 * 요청 서명을 검증하고, 실패(위조·헤더 누락·재전송)는 빈 401 로만 매핑한다. `서명 실패` 시나리오가 이를
 * 실증한다 — 잘못된 서명이면 어떤 하위 포트(import/search/unfurl)도 호출되지 않고 response_url 로 아무것도
 * 나가지 않는다.
 *
 * ## slack test-boot 에는 issue/search 실 어댑터가 없다 (stub 필요, 실 파싱/위임은 다른 테스트가 담당)
 * `SlashIssueSearchPort` 의 실 파싱·issue-tracking 위임은 search-export-import `SlashIssueSearchAdapterTest`
 * 가, 이슈 생성 권한 게이트는 issue-tracking `IssueImportAdapter` 계열 테스트가 각자의 BC 에서 검증한다.
 * 이 E2E 는 slack BC 의 배선(필터·서명·비동기·렌더·전송)만 실증하므로, cross-BC 결과는 stub 으로 시드해
 * 결정론을 확보한다.
 *
 * ## response_url 캡처 방식 — @Primary mockk (실 HTTP 미발생, 결정론)
 * response_url POST 의 실 HTTP 전송은 Task 4 [com.bts.slack.message.SlackResponseUrlClientTest] 가 단위로
 * 검증했다. 이 E2E 는 [CaptureResponseUrlClientConfig] 가 `@Primary` mockk [SlackResponseUrlClient] 로
 * 오버라이드해 마지막 `post(responseUrl, blocks)` 인자를 캡처하고, 렌더된 블록 내용만 검증한다(MockWebServer
 * 대신 캡처 stub — 네트워크 비의존, 플래키 0).
 *
 * ## @Async 결정성 — 실 executor 완료를 폴링
 * [SlashCommandService.process] 는 [SlackAsyncConfig.slackCommandExecutor] 로 위임되므로, HTTP 200 ack 를
 * 받은 뒤 [awaitAsyncSettled] 로 executor 의 active/queue 가 비워질 때까지 폴링한다(sync executor 바꿔치기는
 * 형제 통합 테스트의 빈 이름 충돌을 유발 — [SlackUnfurlEndToEndTest][com.bts.slack.unfurl.SlackUnfurlEndToEndTest]
 * 동일 근거).
 */
@SpringBootTest(
    classes = [SlackIntegrationTestBootApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.MOCK,
)
@Import(
    SlackTestcontainersConfig::class,
    SlackTestSecurityConfig::class,
    SlackSlashCommandEndToEndTest.CaptureResponseUrlClientConfig::class,
)
@TestPropertySource(
    properties = [
        "bts.slack-encryption.key=slack-bot-token-encryption-key-for-slash-e2e",
        "bts.slack-encryption.salt=deadbeefcafef00d",
        "bts.slack.signing-secret=$SLASH_SIGNING_SECRET",
        "bts.atlas.base-url=$ATLAS_BASE_URL",
    ],
)
@Suppress("TooManyFunctions") // 9개 fail-closed/happy 시나리오 + 서명/폼/시드 헬퍼로 임계값(11)을 넘는다 — 책임은 단일(slash E2E).
class SlackSlashCommandEndToEndTest {
    @Autowired
    private lateinit var webApplicationContext: WebApplicationContext

    @Autowired
    private lateinit var jdbc: NamedParameterJdbcTemplate

    @Autowired
    private lateinit var mappingRepository: SlackUserMappingRepository

    @Autowired
    private lateinit var issueUnfurlPort: StubIssueUnfurlPort

    @Autowired
    private lateinit var issueSearchPort: StubSlashIssueSearchPort

    @Autowired
    private lateinit var issueImportPort: StubIssueImportPort

    @Autowired
    private lateinit var responseUrlClient: SlackResponseUrlClient

    @Autowired
    @Qualifier(SlackAsyncConfig.SLACK_COMMAND_EXECUTOR_BEAN_NAME)
    private lateinit var commandExecutor: ThreadPoolTaskExecutor

    private lateinit var mockMvc: MockMvc

    /** 마지막 response_url POST 의 응답 URL 캡처(호출되지 않으면 미캡처). */
    private val capturedUrl = slot<String>()

    /** 마지막 response_url POST 의 렌더된 블록 배열 캡처(호출되지 않으면 미캡처). */
    private val capturedBlocks = slot<ArrayNode>()

    @BeforeEach
    fun setUp() {
        mockMvc =
            MockMvcBuilders
                .webAppContextSetup(webApplicationContext)
                .apply<org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder>(springSecurity())
                .build()
        clearState()
        clearMocks(responseUrlClient)
        every { responseUrlClient.post(capture(capturedUrl), capture(capturedBlocks)) } returns true
    }

    @AfterEach
    fun tearDown() {
        clearState()
    }

    // ── (1) help — 미매핑도 사용법 안내 + 계정 연결 안내 ─────────────────────────────

    @Test
    fun `help - 매핑이 없어도 사용법 안내와 계정 연결 안내를 반환한다`() {
        val json = dispatchExpectingOk("help")

        assertThat(json).contains("Atlas 명령어").contains("/atlas view")
        assertThat(json).contains("연결")
        assertThat(capturedUrl.captured).isEqualTo(RESPONSE_URL)
        assertThat(issueImportPort.lastCommand).isNull()
        assertThat(issueSearchPort.lastQuery).isNull()
    }

    // ── (2) view happy — 매핑 + 가시 이슈 → 카드 ─────────────────────────────────────

    @Test
    fun `view - 매핑된 사용자가 볼 수 있는 이슈면 요약 카드를 반환한다`() {
        seedMapping()
        issueUnfurlPort.visibleIssues[ISSUE_KEY] =
            IssueUnfurlView(
                issueKey = ISSUE_KEY,
                summary = "slash view 카드 확인용 이슈",
                statusLabel = "진행중",
                priorityLabel = "높음",
                assigneeDisplayName = null,
            )

        val json = dispatchExpectingOk("view $ISSUE_KEY")

        assertThat(json).contains(ISSUE_KEY).contains("slash view 카드 확인용 이슈").contains("상태")
    }

    // ── (3) search happy — 매핑 + 결과 페이지 → 목록 + 쿼리 정합 ──────────────────────

    @Test
    fun `search - 매핑된 사용자의 검색은 결과 목록을 반환하고 파싱된 쿼리가 포트로 전달된다`() {
        seedMapping()
        issueSearchPort.nextOutcome = SlashSearchOutcome.Success(pageOf(searchHit()))

        val json = dispatchExpectingOk("search $PROJECT_KEY status=open")

        assertThat(json).contains(ISSUE_KEY).contains("검색 결과 이슈").contains("표시")
        val query = requireNotNull(issueSearchPort.lastQuery)
        assertThat(query.rawAql).isEqualTo("status=open")
        assertThat(query.projectKey).isEqualTo(PROJECT_KEY)
        assertThat(query.viewerUserId).isEqualTo(USER_ID)
    }

    // ── (4) create happy — 매핑 + 성공 → 생성 안내 + 커맨드 정합 ──────────────────────

    @Test
    fun `create - 매핑된 사용자의 생성 성공은 완료 안내를 반환하고 커맨드가 매핑된 사용자로 전달된다`() {
        seedMapping()
        issueImportPort.nextResult = IssueImportResult.success("$PROJECT_KEY-42")

        val json = dispatchExpectingOk("create $PROJECT_KEY 새 버그 리포트")

        assertThat(json).contains("만들었습니다").contains("$PROJECT_KEY-42")
        val command = requireNotNull(issueImportPort.lastCommand)
        assertThat(command.projectKey).isEqualTo(PROJECT_KEY)
        assertThat(command.requesterUserId).isEqualTo(USER_ID)
        assertThat(command.summary).isEqualTo("새 버그 리포트")
    }

    // ── (5) 서명 실패 → 빈 401 + 어떤 하위 포트도 미호출 (fail-closed) ─────────────────

    @Test
    fun `서명 실패 - 잘못된 서명은 빈 401 이고 하위 포트도 response_url 전송도 일어나지 않는다`() {
        seedMapping()
        issueImportPort.nextResult = IssueImportResult.success("$PROJECT_KEY-99")

        val body = commandBody("create $PROJECT_KEY 위조 요청")
        mockMvc
            .perform(postCommand(body, signature = "v0=deadbeefdeadbeefdeadbeefdeadbeef"))
            .andExpect(status().isUnauthorized)

        awaitAsyncSettled()
        verify(exactly = 0) { responseUrlClient.post(any(), any()) }
        assertThat(issueImportPort.lastCommand).isNull()
        assertThat(issueSearchPort.lastQuery).isNull()
    }

    // ── (6) 미매핑 안내 — 매핑 없는 사용자의 view 는 카드 대신 계정 연결 안내(fail-closed) ─

    @Test
    fun `view - 매핑되지 않은 사용자는 가시 이슈가 있어도 카드 대신 계정 연결 안내만 받는다`() {
        // 매핑을 시드하지 않는다. 이슈는 가시로 등록해도, 미매핑이면 handler 경로에 진입하지 못해 카드가 노출되지 않는다.
        issueUnfurlPort.visibleIssues[ISSUE_KEY] =
            IssueUnfurlView(
                issueKey = ISSUE_KEY,
                summary = "미매핑 사용자에게 노출되면 안 되는 이슈",
                statusLabel = "진행중",
                priorityLabel = "높음",
                assigneeDisplayName = null,
            )

        val json = dispatchExpectingOk("view $ISSUE_KEY")

        assertThat(json).contains("연결")
        assertThat(json).doesNotContain("미매핑 사용자에게 노출되면 안 되는 이슈")
    }

    // ── (7) AQL 문법 오류 → 검색 문법 오류 안내 ──────────────────────────────────────

    @Test
    fun `search - AQL 문법 오류는 검색 문법 오류 안내 블록을 반환한다`() {
        seedMapping()
        issueSearchPort.nextOutcome = SlashSearchOutcome.SyntaxError("괄호가 맞지 않습니다")

        val json = dispatchExpectingOk("search $PROJECT_KEY ((")

        assertThat(json).contains("검색 문법 오류")
    }

    // ── (8) create 무권한 → 권한 없음 안내(원시 reasonCode 미노출) ──────────────────────

    @Test
    fun `create - 무권한 실패는 권한 없음 안내를 반환하고 원시 reasonCode 를 노출하지 않는다`() {
        seedMapping()
        issueImportPort.nextResult = IssueImportResult.failure(IssueImportResult.FORBIDDEN)

        val json = dispatchExpectingOk("create $PROJECT_KEY 권한 없는 생성")

        assertThat(json).contains("권한이 없습니다")
        assertThat(json).doesNotContain(IssueImportResult.FORBIDDEN)
    }

    // ── (9) search 결과 0 → 결과 없음 안내 ───────────────────────────────────────────

    @Test
    fun `search - 결과가 없으면 결과 없음 안내를 반환한다`() {
        seedMapping()
        issueSearchPort.nextOutcome = SlashSearchOutcome.Success(IssueSearchPage.empty(FIRST_PAGE, PAGE_SIZE))

        val json = dispatchExpectingOk("search $PROJECT_KEY status=done")

        assertThat(json).contains("결과 없음")
    }

    // ── 공용 흐름 헬퍼 ───────────────────────────────────────────────────────────────

    /** 유효 서명으로 [text] 명령을 보내 200 ack 를 확인하고 @Async 완료를 기다린 뒤 캡처된 블록 JSON 을 돌려준다. */
    private fun dispatchExpectingOk(text: String): String {
        mockMvc.perform(postCommand(commandBody(text))).andExpect(status().isOk)
        awaitAsyncSettled()
        return capturedBlocks.captured.toString()
    }

    /** 표준 form 바디를 만든다(`command`/`text`/`user_id`/`team_id`/`response_url`). */
    private fun commandBody(text: String): String =
        formBody(
            "command" to "/atlas",
            "text" to text,
            "user_id" to SLACK_USER_ID,
            "team_id" to TEAM_ID,
            "response_url" to RESPONSE_URL,
        )

    private fun postCommand(
        body: String,
        signature: String? = null,
    ): MockHttpServletRequestBuilder {
        val timestamp = Instant.now().truncatedTo(ChronoUnit.SECONDS).epochSecond.toString()
        return post("/slack/commands")
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .content(body)
            .header(TIMESTAMP_HEADER, timestamp)
            .header(SIGNATURE_HEADER, signature ?: signatureFor(timestamp, body))
    }

    // ── 서명 헬퍼 — SlackSignatureVerifier 와 동일한 v0 계산 재현 ─────────────────────

    private fun signatureFor(
        timestamp: String,
        rawBody: String,
    ): String {
        val baseString = "v0:$timestamp:$rawBody"
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(SecretKeySpec(SLASH_SIGNING_SECRET.toByteArray(Charsets.UTF_8), HMAC_ALGORITHM))
        return "v0=" + HexFormat.of().formatHex(mac.doFinal(baseString.toByteArray(Charsets.UTF_8)))
    }

    /** `key=value&…` form 바디를 만든다. 각 키/값은 컨트롤러 `decodeForm` 이 역으로 UTF-8 디코드할 수 있게 인코딩한다. */
    private fun formBody(vararg fields: Pair<String, String>): String =
        fields.joinToString("&") { (key, value) -> "${enc(key)}=${enc(value)}" }

    private fun enc(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

    // ── 시드/검색 헬퍼 ───────────────────────────────────────────────────────────────

    private fun seedMapping() = mappingRepository.upsert(USER_ID, SLACK_USER_ID, TEAM_ID)

    private fun searchHit(): IssueSearchHit =
        IssueSearchHit(
            key = ISSUE_KEY,
            summary = "검색 결과 이슈",
            typeKey = "bug",
            currentStateKey = "open",
            assigneeId = null,
            priority = 3,
            priorityName = "Medium",
            projectKey = PROJECT_KEY,
            updatedAt = Instant.now(),
        )

    private fun pageOf(hit: IssueSearchHit): IssueSearchPage =
        IssueSearchPage(items = listOf(hit), total = 1L, page = FIRST_PAGE, size = PAGE_SIZE)

    // ── 상태 초기화 / @Async 결정성 헬퍼 ─────────────────────────────────────────────

    private fun clearState() {
        jdbc.update("DELETE FROM user_slack_mapping", emptyMap<String, Any>())
        issueUnfurlPort.visibleIssues.clear()
        issueSearchPort.reset()
        issueImportPort.reset()
    }

    /** `slackCommandExecutor` 의 활성 스레드·대기열이 모두 비워질 때까지 폴링한다(@Async 제출은 HTTP 응답 이전에 끝남). */
    private fun awaitAsyncSettled() {
        await().atMost(Duration.ofSeconds(ASYNC_AWAIT_SECONDS)).untilAsserted {
            assertThat(commandExecutor.threadPoolExecutor.activeCount).isZero()
            assertThat(commandExecutor.threadPoolExecutor.queue).isEmpty()
        }
    }

    /**
     * response_url POST 를 캡처하는 `@Primary` [SlackResponseUrlClient] 오버라이드.
     *
     * 컴포넌트 스캔된 실 [SlackResponseUrlClient](생성자 기본 [org.springframework.web.client.RestClient]) 와
     * 함께 등록되므로 `@Primary` 로 [SlashCommandService] 주입 우선순위를 이 mockk 가 가진다. 실 HTTP 전송은
     * Task 4 단위 테스트가 검증했고, 이 E2E 는 마지막 `post` 인자(responseUrl·blocks)만 캡처해 검증한다.
     */
    @TestConfiguration
    class CaptureResponseUrlClientConfig {
        /**
         * 캡처용 mockk [SlackResponseUrlClient] 빈.
         *
         * @return `post` 호출을 캡처할 수 있는 mockk. 각 테스트가 `every { post(...) } returns true` 로 재설정한다.
         */
        @Bean
        @Primary
        fun captureResponseUrlClient(): SlackResponseUrlClient = mockk()
    }

    private companion object {
        const val TIMESTAMP_HEADER = "X-Slack-Request-Timestamp"
        const val SIGNATURE_HEADER = "X-Slack-Signature"
        const val HMAC_ALGORITHM = "HmacSHA256"
        const val ASYNC_AWAIT_SECONDS = 5L

        const val USER_ID_STRING = "55555555-5555-4555-8555-555555555555"
        val USER_ID: UUID = UUID.fromString(USER_ID_STRING)
        const val TEAM_ID = "T0SLASHTEAM01"
        const val SLACK_USER_ID = "U0SLASHUSER01"
        const val PROJECT_KEY = "PROJ"
        const val ISSUE_KEY = "PROJ-1"
        const val RESPONSE_URL = "https://hooks.slack.com/commands/T0SLASHTEAM01/1111/abcdefg"

        const val FIRST_PAGE = 0
        const val PAGE_SIZE = 10
    }
}
