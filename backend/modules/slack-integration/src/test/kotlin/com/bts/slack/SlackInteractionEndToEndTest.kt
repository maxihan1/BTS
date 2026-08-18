// POST /slack/interactions 풀스택 E2E — V703(slack_interaction_log) 감사 추적 검증 중심 (FR-SL-05 PR1 Task 11)
package com.bts.slack

import com.bts.shared.board.BoardTransitionResult
import com.bts.shared.board.IssueOptimisticLockException
import com.bts.shared.board.IssueTransitionPermissionDeniedException
import com.bts.shared.issue.DoneTransition
import com.bts.shared.issue.IssueCompletionOptions
import com.bts.shared.issue.IssueMutationPermissionDeniedException
import com.bts.shared.issue.MutationResult
import com.bts.shared.issue.ResolutionOption
import com.bts.slack.application.SlackUserMappingRepository
import com.bts.slack.message.SlackResponseUrlClient
import com.bts.slack.worker.SlackBotTokenResolver
import com.fasterxml.jackson.databind.ObjectMapper
import com.slack.api.RequestConfigurator
import com.slack.api.methods.MethodsClient
import com.slack.api.methods.request.chat.ChatUpdateRequest
import com.slack.api.methods.request.views.ViewsOpenRequest
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
 * ([com.bts.slack.web.SlackInteractionsControllerTest] 동일 관례).
 */
private const val AUDIT_SIGNING_SECRET = "slack-interaction-audit-e2e-signing-secret-01234"

/**
 * `POST /slack/interactions` 풀스택 E2E — V703(`slack_interaction_log`) 감사 추적 중심 (FR-SL-05 PR1 Task 11).
 *
 * [com.bts.slack.web.SlackInteractionsControllerTest](Task 10)가 이미 필터 체인 permitAll 배선·서명검증·
 * 크기상한·[com.bts.slack.interaction.InteractionResult] HTTP 직렬화를 검증했으므로, 이 클래스는 그것을
 * 순수 복제하지 않는다. 이 클래스의 고유 가치는 두 가지다.
 * 1. **V703 감사 로그 검증** — 각 결과(SUCCESS/PERMISSION_DENIED/CONFLICT/UNMAPPED)가
 *    [com.bts.slack.interaction.SlackInteractionService.record]를 거쳐 `slack_interaction_log` 테이블에
 *    실제로 남는지, 서명 실패처럼 서비스에 닿지 못한 요청은 기록되지 않는지를 DB 직접 조회로 확인한다.
 * 2. **prod 조립 부팅 검증**은 이 클래스가 아니라 `:modules:app:test`(별도 Gradle 태스크)가 담당한다 —
 *    issue-tracking `IssueCompletionOptionsAdapter`/`IssueTransitionAdapter`가 이 서비스의 cross-BC 포트
 *    의존을 prod 조립에서 결선하는지는 여기서 검증하지 않는다(memory
 *    `prod-assembly-boot-verification-required`).
 *
 * ## 셋업은 T10 재사용 (실 필터 체인 + outbound stub/mock)
 * `springSecurity()` MockMvc 구성기로 실 [SlackTestSecurityConfig] 필터 체인을 얹고, cross-BC 완료
 * 옵션/전환은 [StubIssueCompletionOptionsPort]/[StubIssueTransitionPort]로, Slack SDK 호출은
 * [SlackTestcontainersConfig]의 `@Primary` mock [MethodsClient]로, 봇 토큰 해석/`response_url`은 이
 * 클래스의 [CaptureCollaboratorsConfig]로 대체한다(T10과 동형).
 *
 * ## 시나리오 (각각 유효 서명 생성 → payload POST → 결과 + V703 assert)
 * - 완료 왕복 성공 — block_actions 모달 오픈 후 view_submission 전환 성공 → V703 `SUCCESS` 1행.
 * - 무권한(전환 거부) — view_submission 전환이 권한 거부로 실패 → V703 `PERMISSION_DENIED`, 원본 메시지 미갱신.
 * - OCC 충돌 — view_submission 전환이 낙관적 잠금 충돌로 실패 → V703 `CONFLICT`.
 * - 미연결 — 역매핑되지 않은 Slack 사용자의 완료 버튼 클릭 → ephemeral 안내 + V703 `UNMAPPED`.
 * - 서명 실패 — 빈 401, V703 미기록.
 * - 무권한(완료옵션 없음, 선택) — block_actions 단계에서 완료옵션 미시드 → 모달 미오픈 + V703 `PERMISSION_DENIED`.
 *
 * ## 담당자·코멘트 시나리오 (FR-SL-05 PR2 Task 5)
 * `SlackInteractionService`(Task 4)가 이미 배선한 `IssueMutationPort` 경로를 [StubIssueMutationPort]로
 * 검증한다 — 완료 왕복과 달리 OCC 대상이 아니고 원본 메시지 갱신도 없다.
 * - 담당자 버튼 — block_actions `atlas_assign` → 담당자 배정 모달 오픈(`views.open`), V703 미기록.
 * - 담당자 배정 성공 — view_submission `atlas_assign_modal` 제출(대상 역매핑 성공) → `assign` 호출 인자
 *   정합 + V703 `ASSIGN`/`SUCCESS`.
 * - 대상 미연결 — 배정 대상 Slack 사용자가 역매핑되지 않음 → response_action errors + V703
 *   `ASSIGN`/`ERROR`, `assign` 미호출.
 * - 무권한 — `assign`이 [IssueMutationPermissionDeniedException] 을 던짐 → response_action errors +
 *   V703 `ASSIGN`/`PERMISSION_DENIED`.
 * - 코멘트 버튼 — block_actions `atlas_comment` → 코멘트 등록 모달 오픈(`views.open`), V703 미기록.
 * - 코멘트 등록 성공 — view_submission `atlas_comment_modal` 제출 → `addComment` 호출 인자 정합 + V703
 *   `COMMENT`/`SUCCESS`.
 * - actor 미연결 — 코멘트 모달 제출자 Slack 사용자가 역매핑되지 않음 → response_action errors(계정 연결
 *   안내) + V703 `COMMENT`/`UNMAPPED`.
 */
@SpringBootTest(
    classes = [SlackIntegrationTestBootApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.MOCK,
)
@Import(
    SlackTestcontainersConfig::class,
    SlackTestSecurityConfig::class,
    SlackInteractionEndToEndTest.CaptureCollaboratorsConfig::class,
)
@TestPropertySource(
    properties = [
        "bts.slack-encryption.key=slack-bot-token-encryption-key-for-audit-e2e",
        "bts.slack-encryption.salt=deadbeefcafef00d",
        "bts.slack.signing-secret=$AUDIT_SIGNING_SECRET",
        "bts.atlas.base-url=https://atlas.example.com",
    ],
)
class SlackInteractionEndToEndTest {
    @Autowired
    private lateinit var webApplicationContext: WebApplicationContext

    @Autowired
    private lateinit var mappingRepository: SlackUserMappingRepository

    @Autowired
    private lateinit var completionOptionsPort: StubIssueCompletionOptionsPort

    @Autowired
    private lateinit var transitionPort: StubIssueTransitionPort

    @Autowired
    private lateinit var mutationPort: StubIssueMutationPort

    @Autowired
    private lateinit var jdbc: JdbcTemplate

    @Autowired
    private lateinit var botTokenResolver: SlackBotTokenResolver

    @Autowired
    private lateinit var responseUrlClient: SlackResponseUrlClient

    @Autowired
    private lateinit var methodsClient: MethodsClient

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
        clearMocks(botTokenResolver, responseUrlClient, methodsClient)
        every { botTokenResolver.resolve(any()) } returns BOT_TOKEN
        every { responseUrlClient.post(any(), any()) } returns true
        // clearMocks 가 config 의 ok 스텁을 지웠으므로 재적용 — 모달 오픈/메시지 갱신 성공 경로.
        SlackTestcontainersConfig.stubSlackModalApisOk(methodsClient)
    }

    @AfterEach
    fun tearDown() {
        clearState()
    }

    // ── (1) 완료 왕복 성공 — block_actions 모달 오픈 → view_submission 전환 성공 → V703 SUCCESS ──────

    @Test
    fun `완료 왕복 성공 - block_actions 모달 오픈 후 view_submission 전환 성공이 V703 SUCCESS 로 기록된다`() {
        seedMapping()
        completionOptionsPort.completionOptionsByIssueKey[ISSUE_KEY] = completionOptions()

        mockMvc.perform(postInteraction(blockActionsCompletePayload())).andExpect(status().isOk)
        verify(exactly = 1) { methodsClient.viewsOpen(any<ViewsOpenConfigurator>()) }
        verify(exactly = 0) { responseUrlClient.post(any(), any()) }
        // block_actions 성공(모달 오픈)은 SlackInteractionService.record()를 거치지 않는다 — 아직 V703 미기록.
        assertThat(fetchInteractionLogRows()).isEmpty()

        transitionPort.succeedWith(BoardTransitionResult(ISSUE_KEY, TO_STATE_KEY, 4))
        mockMvc.perform(postInteraction(viewSubmissionPayload())).andExpect(status().isOk)
        verify(exactly = 1) { methodsClient.chatUpdate(any<ChatUpdateConfigurator>()) }

        val rows = fetchInteractionLogRows()
        assertThat(rows).hasSize(1)
        val row = rows.single()
        assertThat(row["team_id"]).isEqualTo(TEAM_ID)
        assertThat(row["slack_user_id"]).isEqualTo(SLACK_USER_ID)
        assertThat(row["bts_user_id"]).isEqualTo(BTS_USER_ID)
        assertThat(row["action_type"]).isEqualTo("COMPLETE")
        assertThat(row["outcome"]).isEqualTo("SUCCESS")
        assertThat(row["issue_key"]).isEqualTo(ISSUE_KEY)
    }

    // ── (2) 무권한 — 전환 권한 거부 → response_action errors + V703 PERMISSION_DENIED, 메시지 미갱신 ──

    @Test
    fun `무권한 - view_submission 전환 권한 거부는 V703 PERMISSION_DENIED 로 기록되고 원본 메시지는 갱신되지 않는다`() {
        seedMapping()
        transitionPort.failWith(IssueTransitionPermissionDeniedException("denied"))

        val body =
            mockMvc.perform(postInteraction(viewSubmissionPayload()))
                .andExpect(status().isOk)
                .andReturn().response.contentAsString
        assertThat(body).contains("response_action").contains("errors")
        verify(exactly = 0) { methodsClient.chatUpdate(any<ChatUpdateConfigurator>()) }

        val rows = fetchInteractionLogRows()
        assertThat(rows).hasSize(1)
        val row = rows.single()
        assertThat(row["team_id"]).isEqualTo(TEAM_ID)
        assertThat(row["slack_user_id"]).isEqualTo(SLACK_USER_ID)
        assertThat(row["bts_user_id"]).isEqualTo(BTS_USER_ID)
        assertThat(row["action_type"]).isEqualTo("COMPLETE")
        assertThat(row["outcome"]).isEqualTo("PERMISSION_DENIED")
        assertThat(row["issue_key"]).isEqualTo(ISSUE_KEY)
    }

    // ── (3) OCC 충돌 — response_action errors + V703 CONFLICT ────────────────────────────────────

    @Test
    fun `OCC 충돌 - view_submission 전환 낙관적 잠금 충돌은 V703 CONFLICT 로 기록된다`() {
        seedMapping()
        transitionPort.failWith(IssueOptimisticLockException("stale version"))

        val body =
            mockMvc.perform(postInteraction(viewSubmissionPayload()))
                .andExpect(status().isOk)
                .andReturn().response.contentAsString
        assertThat(body).contains("response_action").contains("errors")
        verify(exactly = 0) { methodsClient.chatUpdate(any<ChatUpdateConfigurator>()) }

        val rows = fetchInteractionLogRows()
        assertThat(rows).hasSize(1)
        val row = rows.single()
        assertThat(row["outcome"]).isEqualTo("CONFLICT")
        assertThat(row["issue_key"]).isEqualTo(ISSUE_KEY)
    }

    // ── (4) 미연결 — ephemeral 안내 + V703 UNMAPPED ───────────────────────────────────────────────

    @Test
    fun `미연결 - 역매핑되지 않은 사용자의 완료 버튼 클릭은 ephemeral 안내 후 V703 UNMAPPED 로 기록된다`() {
        // seedMapping() 미호출 — SLACK_USER_ID·TEAM_ID 조합이 역매핑되지 않은 상태.
        completionOptionsPort.completionOptionsByIssueKey[ISSUE_KEY] = completionOptions()

        val body =
            mockMvc.perform(postInteraction(blockActionsCompletePayload()))
                .andExpect(status().isOk)
                .andReturn().response.contentAsString
        assertThat(body).isEmpty()
        verify(exactly = 1) { responseUrlClient.post(any(), any()) }
        verify(exactly = 0) { methodsClient.viewsOpen(any<ViewsOpenConfigurator>()) }

        val rows = fetchInteractionLogRows()
        assertThat(rows).hasSize(1)
        val row = rows.single()
        assertThat(row["team_id"]).isEqualTo(TEAM_ID)
        assertThat(row["slack_user_id"]).isEqualTo(SLACK_USER_ID)
        assertThat(row["bts_user_id"]).isNull()
        assertThat(row["outcome"]).isEqualTo("UNMAPPED")
        assertThat(row["issue_key"]).isEqualTo(ISSUE_KEY)
    }

    // ── (5) 서명 실패 — 빈 401, V703 미기록 ───────────────────────────────────────────────────────

    @Test
    fun `서명 실패 - 빈 401 이고 V703 에는 아무 것도 기록되지 않는다`() {
        seedMapping()
        completionOptionsPort.completionOptionsByIssueKey[ISSUE_KEY] = completionOptions()

        mockMvc.perform(
            postInteraction(
                blockActionsCompletePayload(),
                signature = "v0=deadbeefdeadbeefdeadbeefdeadbeef",
            ),
        ).andExpect(status().isUnauthorized)

        verify(exactly = 0) { methodsClient.viewsOpen(any<ViewsOpenConfigurator>()) }
        assertThat(fetchInteractionLogRows()).isEmpty()
    }

    // ── (6) 무권한(완료옵션 없음, 선택) — block_actions 모달 미오픈 + V703 PERMISSION_DENIED ────────

    @Test
    fun `무권한(완료옵션 없음) - block_actions 는 모달을 열지 않고 V703 PERMISSION_DENIED 로 기록된다`() {
        seedMapping()
        // completionOptionsPort 미시드 — ISSUE_KEY 가 등록되지 않아 fail-closed(무권한/미가시)로 수렴한다.

        mockMvc.perform(postInteraction(blockActionsCompletePayload())).andExpect(status().isOk)

        verify(exactly = 0) { methodsClient.viewsOpen(any<ViewsOpenConfigurator>()) }
        verify(exactly = 1) { responseUrlClient.post(any(), any()) }

        val rows = fetchInteractionLogRows()
        assertThat(rows).hasSize(1)
        val row = rows.single()
        assertThat(row["bts_user_id"]).isEqualTo(BTS_USER_ID)
        assertThat(row["outcome"]).isEqualTo("PERMISSION_DENIED")
        assertThat(row["issue_key"]).isEqualTo(ISSUE_KEY)
    }

    // ── (7) 담당자 버튼 — block_actions atlas_assign → 담당자 배정 모달 오픈, V703 미기록 ─────────────

    @Test
    fun `담당자 버튼 - block_actions atlas_assign 클릭은 담당자 배정 모달을 연다`() {
        seedMapping()

        mockMvc.perform(postInteraction(blockActionsAssignPayload())).andExpect(status().isOk)

        verify(exactly = 1) { methodsClient.viewsOpen(any<ViewsOpenConfigurator>()) }
        // 버튼 클릭 성공(모달 오픈)은 record()를 거치지 않는다 — 완료 버튼과 동형(시나리오 1 상단 주석).
        assertThat(fetchInteractionLogRows()).isEmpty()
    }

    // ── (8) 담당자 배정 성공 — view_submission atlas_assign_modal 제출 → assign 호출 + V703 ASSIGN SUCCESS ──

    @Test
    fun `담당자 배정 성공 - view_submission atlas_assign_modal 제출은 대상 BTS 사용자로 배정되고 V703 ASSIGN SUCCESS 로 기록된다`() {
        seedMapping()
        seedTargetMapping()
        mutationPort.nextAssignResult = MutationResult(ISSUE_KEY, applied = true, version = 5)

        mockMvc.perform(postInteraction(assignSubmissionPayload(TARGET_SLACK_USER_ID))).andExpect(status().isOk)

        val command = mutationPort.lastAssignCommand
        assertThat(command).isNotNull
        assertThat(command?.actorUserId).isEqualTo(BTS_USER_ID)
        assertThat(command?.issueKey).isEqualTo(ISSUE_KEY)
        assertThat(command?.assigneeId).isEqualTo(TARGET_BTS_USER_ID)

        val rows = fetchInteractionLogRows()
        assertThat(rows).hasSize(1)
        val row = rows.single()
        assertThat(row["bts_user_id"]).isEqualTo(BTS_USER_ID)
        assertThat(row["action_type"]).isEqualTo("ASSIGN")
        assertThat(row["outcome"]).isEqualTo("SUCCESS")
        assertThat(row["issue_key"]).isEqualTo(ISSUE_KEY)
    }

    // ── (9) 대상 미연결 — response_action errors + V703 ASSIGN ERROR, assign 미호출 ──────────────────

    @Test
    fun `대상 미연결 - 매핑되지 않은 사용자를 담당자로 선택하면 response_action errors 와 V703 ASSIGN ERROR 로 기록되고 assign 은 호출되지 않는다`() {
        seedMapping()
        // seedTargetMapping() 미호출 — UNMAPPED_TARGET_SLACK_USER_ID 는 역매핑되지 않은 상태.

        val body =
            mockMvc.perform(postInteraction(assignSubmissionPayload(UNMAPPED_TARGET_SLACK_USER_ID)))
                .andExpect(status().isOk)
                .andReturn().response.contentAsString
        assertThat(body).contains("response_action").contains("errors")
        assertThat(mutationPort.lastAssignCommand).isNull()

        val rows = fetchInteractionLogRows()
        assertThat(rows).hasSize(1)
        val row = rows.single()
        assertThat(row["bts_user_id"]).isEqualTo(BTS_USER_ID)
        assertThat(row["action_type"]).isEqualTo("ASSIGN")
        assertThat(row["outcome"]).isEqualTo("ERROR")
        assertThat(row["issue_key"]).isEqualTo(ISSUE_KEY)
    }

    // ── (10) 무권한 — assign 이 권한 거부 예외 → response_action errors + V703 ASSIGN PERMISSION_DENIED ──

    @Test
    fun `무권한 - assign 이 권한 거부 예외를 던지면 response_action errors 와 V703 ASSIGN PERMISSION_DENIED 로 기록된다`() {
        seedMapping()
        seedTargetMapping()
        mutationPort.nextAssignError = IssueMutationPermissionDeniedException("denied")

        val body =
            mockMvc.perform(postInteraction(assignSubmissionPayload(TARGET_SLACK_USER_ID)))
                .andExpect(status().isOk)
                .andReturn().response.contentAsString
        assertThat(body).contains("response_action").contains("errors")

        val rows = fetchInteractionLogRows()
        assertThat(rows).hasSize(1)
        val row = rows.single()
        assertThat(row["bts_user_id"]).isEqualTo(BTS_USER_ID)
        assertThat(row["action_type"]).isEqualTo("ASSIGN")
        assertThat(row["outcome"]).isEqualTo("PERMISSION_DENIED")
        assertThat(row["issue_key"]).isEqualTo(ISSUE_KEY)
    }

    // ── (11) 코멘트 버튼 — block_actions atlas_comment → 코멘트 등록 모달 오픈, V703 미기록 ───────────

    @Test
    fun `코멘트 버튼 - block_actions atlas_comment 클릭은 코멘트 등록 모달을 연다`() {
        seedMapping()

        mockMvc.perform(postInteraction(blockActionsCommentPayload())).andExpect(status().isOk)

        verify(exactly = 1) { methodsClient.viewsOpen(any<ViewsOpenConfigurator>()) }
        assertThat(fetchInteractionLogRows()).isEmpty()
    }

    // ── (12) 코멘트 등록 성공 — view_submission atlas_comment_modal 제출 → addComment 호출 + V703 COMMENT SUCCESS ──

    @Test
    fun `코멘트 등록 성공 - view_submission atlas_comment_modal 제출은 addComment 를 호출하고 V703 COMMENT SUCCESS 로 기록된다`() {
        seedMapping()
        mutationPort.nextCommentResult = MutationResult(ISSUE_KEY, applied = true, version = null)

        mockMvc.perform(postInteraction(commentSubmissionPayload(COMMENT_BODY))).andExpect(status().isOk)

        val command = mutationPort.lastAddCommentCommand
        assertThat(command).isNotNull
        assertThat(command?.actorUserId).isEqualTo(BTS_USER_ID)
        assertThat(command?.issueKey).isEqualTo(ISSUE_KEY)
        assertThat(command?.body).isEqualTo(COMMENT_BODY)

        val rows = fetchInteractionLogRows()
        assertThat(rows).hasSize(1)
        val row = rows.single()
        assertThat(row["bts_user_id"]).isEqualTo(BTS_USER_ID)
        assertThat(row["action_type"]).isEqualTo("COMMENT")
        assertThat(row["outcome"]).isEqualTo("SUCCESS")
        assertThat(row["issue_key"]).isEqualTo(ISSUE_KEY)
    }

    // ── (13) actor 미연결 — 계정 연결 안내 + V703 COMMENT UNMAPPED, addComment 미호출 ─────────────────

    @Test
    fun `actor 미연결 - 매핑되지 않은 사용자가 코멘트 모달을 제출하면 계정 연결 안내와 V703 COMMENT UNMAPPED 로 기록된다`() {
        // seedMapping() 미호출 — 제출자 SLACK_USER_ID·TEAM_ID 조합이 역매핑되지 않은 상태.

        val body =
            mockMvc.perform(postInteraction(commentSubmissionPayload(COMMENT_BODY)))
                .andExpect(status().isOk)
                .andReturn().response.contentAsString
        assertThat(body).contains("response_action").contains("errors")
        assertThat(mutationPort.lastAddCommentCommand).isNull()

        val rows = fetchInteractionLogRows()
        assertThat(rows).hasSize(1)
        val row = rows.single()
        assertThat(row["team_id"]).isEqualTo(TEAM_ID)
        assertThat(row["slack_user_id"]).isEqualTo(SLACK_USER_ID)
        assertThat(row["bts_user_id"]).isNull()
        assertThat(row["action_type"]).isEqualTo("COMMENT")
        assertThat(row["outcome"]).isEqualTo("UNMAPPED")
    }

    // ── 요청 조립 헬퍼 ───────────────────────────────────────────────────────────────────────────

    /** `payload=<URL-encoded JSON>` form 바디를 만들어 유효/지정 서명으로 전송하는 요청 빌더를 만든다. */
    private fun postInteraction(
        payloadJson: String,
        signature: String? = null,
    ) = postRaw(
        body = "payload=" + URLEncoder.encode(payloadJson, StandardCharsets.UTF_8),
        signature = signature,
    )

    /** 원문 [body] 를 form 미디어 타입 + Slack 서명 헤더로 실어 보내는 요청 빌더(서명은 body 원문 기준 계산). */
    private fun postRaw(
        body: String,
        signature: String? = null,
    ): MockHttpServletRequestBuilder {
        val timestamp = Instant.now().truncatedTo(ChronoUnit.SECONDS).epochSecond.toString()
        return post("/slack/interactions")
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .content(body)
            .header(TIMESTAMP_HEADER, timestamp)
            .header(SIGNATURE_HEADER, signature ?: signatureFor(timestamp, body))
    }

    // ── payload JSON 조립 (Slack 원문 최상위 구조 재현 — SlackInteractionsControllerTest 동형) ──────

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

    private fun viewSubmissionPayload(): String {
        val root = objectMapper.createObjectNode()
        root.put("type", "view_submission")
        root.putObject("user").put("id", SLACK_USER_ID)
        root.putObject("team").put("id", TEAM_ID)
        val view = root.putObject("view")
        view.put("callback_id", "atlas_complete_modal")
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

    // ── payload JSON 조립 — 담당자·코멘트 (FR-SL-05 PR2 Task 5, blockActionsCompletePayload/viewSubmissionPayload 동형) ──

    private fun blockActionsAssignPayload(): String {
        val root = objectMapper.createObjectNode()
        root.put("type", "block_actions")
        root.putObject("user").put("id", SLACK_USER_ID)
        root.putObject("team").put("id", TEAM_ID)
        root.put("trigger_id", TRIGGER_ID)
        root.put("response_url", RESPONSE_URL)
        root.putObject("channel").put("id", CHANNEL_ID)
        root.putObject("message").put("ts", MESSAGE_TS)
        root.putArray("actions").addObject().put("action_id", "atlas_assign").put("value", ISSUE_KEY)
        return objectMapper.writeValueAsString(root)
    }

    private fun blockActionsCommentPayload(): String {
        val root = objectMapper.createObjectNode()
        root.put("type", "block_actions")
        root.putObject("user").put("id", SLACK_USER_ID)
        root.putObject("team").put("id", TEAM_ID)
        root.put("trigger_id", TRIGGER_ID)
        root.put("response_url", RESPONSE_URL)
        root.putObject("channel").put("id", CHANNEL_ID)
        root.putObject("message").put("ts", MESSAGE_TS)
        root.putArray("actions").addObject().put("action_id", "atlas_comment").put("value", ISSUE_KEY)
        return objectMapper.writeValueAsString(root)
    }

    /** `users_select` state — [targetSlackUserId]가 `selected_user`로 담긴 담당자 모달 제출 payload. */
    private fun assignSubmissionPayload(targetSlackUserId: String): String {
        val root = objectMapper.createObjectNode()
        root.put("type", "view_submission")
        root.putObject("user").put("id", SLACK_USER_ID)
        root.putObject("team").put("id", TEAM_ID)
        val view = root.putObject("view")
        view.put("callback_id", "atlas_assign_modal")
        view.put("private_metadata", issueKeyPrivateMetadata())
        val values = view.putObject("state").putObject("values")
        values.putObject("assignee_block").putObject("assignee_select").put("selected_user", targetSlackUserId)
        return objectMapper.writeValueAsString(root)
    }

    /** `plain_text_input` state — [body]가 `value`로 담긴 코멘트 모달 제출 payload. */
    private fun commentSubmissionPayload(body: String): String {
        val root = objectMapper.createObjectNode()
        root.put("type", "view_submission")
        root.putObject("user").put("id", SLACK_USER_ID)
        root.putObject("team").put("id", TEAM_ID)
        val view = root.putObject("view")
        view.put("callback_id", "atlas_comment_modal")
        view.put("private_metadata", issueKeyPrivateMetadata())
        val values = view.putObject("state").putObject("values")
        values.putObject("comment_block").putObject("comment_input").put("value", body)
        return objectMapper.writeValueAsString(root)
    }

    /**
     * 담당자/코멘트 모달의 `private_metadata` —
     * [com.bts.slack.interaction.SlackModalBuilder.buildAssignModal]/`buildCommentModal` 동형(issueKey 만).
     */
    private fun issueKeyPrivateMetadata(): String {
        val meta = objectMapper.createObjectNode()
        meta.put("issueKey", ISSUE_KEY)
        return objectMapper.writeValueAsString(meta)
    }

    // ── 서명 헬퍼 — SlackSignatureVerifier 와 동일한 v0 계산 재현 ─────────────────────────────────

    private fun signatureFor(
        timestamp: String,
        rawBody: String,
    ): String {
        val baseString = "v0:$timestamp:$rawBody"
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(SecretKeySpec(AUDIT_SIGNING_SECRET.toByteArray(Charsets.UTF_8), HMAC_ALGORITHM))
        return "v0=" + HexFormat.of().formatHex(mac.doFinal(baseString.toByteArray(Charsets.UTF_8)))
    }

    // ── V703 감사 로그 조회 헬퍼 ─────────────────────────────────────────────────────────────────

    /** `slack_interaction_log` 전 행을 오래된 순으로 직접 SELECT 한다(JdbcSlackInteractionLogRepositoryTest 동형). */
    private fun fetchInteractionLogRows(): List<Map<String, Any?>> =
        jdbc.queryForList(
            "SELECT team_id, slack_user_id, bts_user_id, action_type, outcome, issue_key" +
                " FROM slack_interaction_log ORDER BY created_at",
        )

    // ── 시드/정리 헬퍼 ───────────────────────────────────────────────────────────────────────────

    private fun completionOptions() =
        IssueCompletionOptions(
            version = EXPECTED_VERSION,
            doneTransitions = listOf(DoneTransition(TO_STATE_KEY, "In Review")),
            resolutions = listOf(ResolutionOption(RESOLUTION_ID, "Fixed")),
        )

    private fun seedMapping() = mappingRepository.upsert(BTS_USER_ID, SLACK_USER_ID, TEAM_ID)

    /** 담당자 배정 대상(assignee) 역매핑 시드 — actor([seedMapping])와 별도 사용자([TARGET_BTS_USER_ID]). */
    private fun seedTargetMapping() = mappingRepository.upsert(TARGET_BTS_USER_ID, TARGET_SLACK_USER_ID, TEAM_ID)

    private fun clearState() {
        jdbc.update("DELETE FROM user_slack_mapping")
        jdbc.update("DELETE FROM slack_interaction_log")
        completionOptionsPort.completionOptionsByIssueKey.clear()
        transitionPort.reset()
        mutationPort.reset()
    }

    /**
     * outbound 협력자 오버라이드 — 봇 토큰 해석기와 `response_url` 클라이언트를 `@Primary` mock 으로 대체한다
     * ([com.bts.slack.web.SlackInteractionsControllerTest.CaptureCollaboratorsConfig] 동형).
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

        const val BTS_USER_ID_STRING = "88888888-8888-4888-8888-888888888888"
        val BTS_USER_ID: UUID = UUID.fromString(BTS_USER_ID_STRING)
        const val RESOLUTION_ID_STRING = "99999999-9999-4999-9999-999999999999"
        val RESOLUTION_ID: UUID = UUID.fromString(RESOLUTION_ID_STRING)

        const val TEAM_ID = "T0AUDITTEAM01"
        const val SLACK_USER_ID = "U0AUDITUSER01"
        const val ISSUE_KEY = "PROJ-9"

        // ── 담당자 배정 대상(assignee) — actor(SLACK_USER_ID/BTS_USER_ID)와 별도 사용자 (FR-SL-05 PR2 Task 5) ──
        const val TARGET_BTS_USER_ID_STRING = "77777777-7777-4777-7777-777777777777"
        val TARGET_BTS_USER_ID: UUID = UUID.fromString(TARGET_BTS_USER_ID_STRING)
        const val TARGET_SLACK_USER_ID = "U0AUDITTARGET1"

        /** 어떤 시나리오에서도 [SlackUserMappingRepository]에 시드하지 않는 배정 대상 — "대상 미연결" 검증용. */
        const val UNMAPPED_TARGET_SLACK_USER_ID = "U0AUDITNOLINK1"

        const val COMMENT_BODY = "테스트 코멘트 본문입니다"
        const val CHANNEL_ID = "C0AUDITCHAN01"
        const val MESSAGE_TS = "1700000001.000100"
        const val TRIGGER_ID = "trig-audit-1"
        const val TO_STATE_KEY = "in-review"
        const val EXPECTED_VERSION = 3L
        const val BOT_TOKEN = "xoxb-audit-fake"
        const val RESPONSE_URL = "https://hooks.slack.com/actions/T0AUDITTEAM01/2222/hijklmn"
    }
}

/** `mockk`의 `viewsOpen`/`chatUpdate` 오버로드 모호성 해소용 타입 별칭([SlackMessageClientTest] 동형 필요). */
private typealias ViewsOpenConfigurator = RequestConfigurator<ViewsOpenRequest.ViewsOpenRequestBuilder>
private typealias ChatUpdateConfigurator = RequestConfigurator<ChatUpdateRequest.ChatUpdateRequestBuilder>
