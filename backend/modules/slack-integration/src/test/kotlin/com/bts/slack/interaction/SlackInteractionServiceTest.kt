// SlackInteractionService 단위 테스트 — 완료 동기 flow·타입예외 분류·V703 감사 (FR-SL-05 PR1 Task 8)
package com.bts.slack.interaction

import com.bts.shared.board.BoardTransitionCommand
import com.bts.shared.board.BoardTransitionResult
import com.bts.shared.board.IssueOptimisticLockException
import com.bts.shared.board.IssueTransitionPermissionDeniedException
import com.bts.shared.board.IssueTransitionPort
import com.bts.shared.issue.DoneTransition
import com.bts.shared.issue.IssueCompletionOptions
import com.bts.shared.issue.IssueCompletionOptionsPort
import com.bts.shared.issue.ResolutionOption
import com.bts.slack.application.SlackInteractionLogRepository
import com.bts.slack.application.SlackUserMappingRepository
import com.bts.slack.message.SlackMessageClient
import com.bts.slack.message.SlackResponseUrlClient
import com.bts.slack.message.SlackSendResult
import com.bts.slack.worker.SlackBotTokenResolver
import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * [SlackInteractionService] 단위 테스트 — 모든 협력자를 mockk로 대체한다.
 *
 * 완료 인터랙션의 동기 오케스트레이션(역매핑 → 완료옵션/모달 또는 전이 → 감사)과, 전이 실패를
 * shared-kernel 타입 예외([IssueTransitionPermissionDeniedException]/[IssueOptimisticLockException])로
 * 분류해 서로 다른 결과·감사 outcome으로 수렴하는지 검증한다. actor는 오직 역매핑 결과만 쓰이며
 * (위조 차단), 미연결/무권한/충돌은 모두 안전하게 거부된다.
 */
class SlackInteractionServiceTest {
    private val userMappingRepository = mockk<SlackUserMappingRepository>()
    private val completionOptionsPort = mockk<IssueCompletionOptionsPort>()
    private val modalBuilder = mockk<SlackModalBuilder>()
    private val messageClient = mockk<SlackMessageClient>()
    private val responseUrlClient = mockk<SlackResponseUrlClient>()
    private val transitionPort = mockk<IssueTransitionPort>()
    private val botTokenResolver = mockk<SlackBotTokenResolver>()
    private val interactionLogRepository = mockk<SlackInteractionLogRepository>(relaxed = true)
    private val objectMapper = ObjectMapper()

    private val service =
        SlackInteractionService(
            userMappingRepository = userMappingRepository,
            completionOptionsPort = completionOptionsPort,
            modalBuilder = modalBuilder,
            messageClient = messageClient,
            responseUrlClient = responseUrlClient,
            transitionPort = transitionPort,
            botTokenResolver = botTokenResolver,
            interactionLogRepository = interactionLogRepository,
            objectMapper = objectMapper,
        )

    private val teamId = "T1"
    private val slackUserId = "U1"
    private val btsUserId = UUID.fromString("11111111-1111-4111-8111-111111111111")
    private val resolutionId = UUID.fromString("22222222-2222-4222-8222-222222222222")
    private val issueKey = "PROJ-1"
    private val channel = "C1"
    private val ts = "111.222"
    private val toStateKey = "in-review"
    private val botToken = "xoxb-token"

    private fun blockActionsComplete() =
        SlackInteractionPayload.BlockActions(
            userId = slackUserId,
            teamId = teamId,
            triggerId = "trig-1",
            responseUrl = "https://hooks.slack.com/actions/T1/1/abc",
            channel = channel,
            messageTs = ts,
            actions = listOf(SlackInteractionPayload.BlockActions.Action(actionId = "atlas_complete", value = issueKey)),
        )

    private fun completionOptions() =
        IssueCompletionOptions(
            version = 3,
            doneTransitions = listOf(DoneTransition(toStateKey, "In Review")),
            resolutions = listOf(ResolutionOption(resolutionId, "Fixed")),
        )

    private fun viewSubmission(
        callbackId: String? = "atlas_complete_modal",
        withResolution: Boolean = true,
    ): SlackInteractionPayload.ViewSubmission {
        val stateValues =
            buildMap<String, Any?> {
                put(
                    "done_transition_block",
                    mapOf("done_transition_select" to mapOf("selected_option" to mapOf("value" to toStateKey))),
                )
                if (withResolution) {
                    put(
                        "resolution_block",
                        mapOf(
                            "resolution_select" to
                                mapOf("selected_option" to mapOf("value" to resolutionId.toString())),
                        ),
                    )
                }
            }
        return SlackInteractionPayload.ViewSubmission(
            userId = slackUserId,
            teamId = teamId,
            callbackId = callbackId,
            privateMetadata = """{"issueKey":"$issueKey","expectedVersion":3,"channel":"$channel","ts":"$ts"}""",
            stateValues = stateValues,
        )
    }

    private val expectedCommand =
        BoardTransitionCommand(
            actorUserId = btsUserId,
            issueKey = issueKey,
            toStateKey = toStateKey,
            expectedVersion = 3,
            resolutionId = resolutionId,
        )

    // ── block_actions: atlas_complete ─────────────────────────────────────────

    @Test
    fun `atlas_complete 클릭 — 완료옵션 조회 후 봇토큰으로 모달을 연다`() {
        every { userMappingRepository.findUserIdBySlackUserId(slackUserId, teamId) } returns btsUserId
        every { completionOptionsPort.getCompletionOptions(issueKey, btsUserId) } returns completionOptions()
        every { botTokenResolver.resolve(teamId) } returns botToken
        every { modalBuilder.buildCompletionModal(completionOptions(), issueKey, channel, ts) } returns "{\"view\":1}"
        every { messageClient.openModal(botToken, "trig-1", "{\"view\":1}") } returns SlackSendResult.Sent

        val result = service.handle(blockActionsComplete())

        assertThat(result).isEqualTo(InteractionResult.AckEmpty)
        verify(exactly = 1) { messageClient.openModal(botToken, "trig-1", "{\"view\":1}") }
        verify(exactly = 0) { responseUrlClient.post(any(), any()) }
        // 모달 오픈 성공은 완료 자체가 아니므로 V703을 남기지 않는다(완료 SUCCESS는 view_submission 전이 시점).
        verify(exactly = 0) { interactionLogRepository.record(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `atlas_complete 미연결(UNMAPPED) — 모달 대신 ephemeral 안내 + V703 UNMAPPED`() {
        every { userMappingRepository.findUserIdBySlackUserId(slackUserId, teamId) } returns null
        every { responseUrlClient.post(any(), any()) } returns true

        val result = service.handle(blockActionsComplete())

        assertThat(result).isEqualTo(InteractionResult.AckEmpty)
        verify(exactly = 1) { responseUrlClient.post(blockActionsComplete().responseUrl!!, any()) }
        verify(exactly = 0) { completionOptionsPort.getCompletionOptions(any(), any()) }
        verify(exactly = 0) { messageClient.openModal(any(), any(), any()) }
        verify(exactly = 1) {
            interactionLogRepository.record(teamId, slackUserId, null, "COMPLETE", "UNMAPPED", issueKey)
        }
    }

    @Test
    fun `atlas_complete 무권한 또는 미가시(getCompletionOptions null) — 모달 안 열고 ephemeral + V703 PERMISSION_DENIED`() {
        every { userMappingRepository.findUserIdBySlackUserId(slackUserId, teamId) } returns btsUserId
        every { completionOptionsPort.getCompletionOptions(issueKey, btsUserId) } returns null
        every { responseUrlClient.post(any(), any()) } returns true

        val result = service.handle(blockActionsComplete())

        assertThat(result).isEqualTo(InteractionResult.AckEmpty)
        verify(exactly = 1) { responseUrlClient.post(any(), any()) }
        verify(exactly = 0) { botTokenResolver.resolve(any()) }
        verify(exactly = 0) { messageClient.openModal(any(), any(), any()) }
        verify(exactly = 1) {
            interactionLogRepository.record(teamId, slackUserId, btsUserId, "COMPLETE", "PERMISSION_DENIED", issueKey)
        }
    }

    @Test
    fun `atlas_view(상세보기 url 버튼) — no-op 빈 응답, 어떤 협력자도 호출하지 않는다`() {
        val payload =
            blockActionsComplete().copy(
                actions = listOf(SlackInteractionPayload.BlockActions.Action(actionId = "atlas_view", value = issueKey)),
            )

        val result = service.handle(payload)

        assertThat(result).isEqualTo(InteractionResult.AckEmpty)
        verify(exactly = 0) { userMappingRepository.findUserIdBySlackUserId(any(), any()) }
        verify(exactly = 0) { messageClient.openModal(any(), any(), any()) }
        verify(exactly = 0) { responseUrlClient.post(any(), any()) }
    }

    // ── view_submission: atlas_complete_modal ─────────────────────────────────

    @Test
    fun `모달 제출 성공 — 전이 실행 + 원본 메시지 갱신 + 빈 200 + V703 SUCCESS`() {
        every { userMappingRepository.findUserIdBySlackUserId(slackUserId, teamId) } returns btsUserId
        every { transitionPort.transition(expectedCommand) } returns BoardTransitionResult(issueKey, toStateKey, 4)
        every { botTokenResolver.resolve(teamId) } returns botToken
        every { messageClient.updateMessage(botToken, channel, ts, any()) } returns SlackSendResult.Sent

        val result = service.handle(viewSubmission())

        assertThat(result).isEqualTo(InteractionResult.AckEmpty)
        verify(exactly = 1) { transitionPort.transition(expectedCommand) }
        verify(exactly = 1) { messageClient.updateMessage(botToken, channel, ts, any()) }
        verify(exactly = 1) {
            interactionLogRepository.record(teamId, slackUserId, btsUserId, "COMPLETE", "SUCCESS", issueKey)
        }
    }

    @Test
    fun `모달 제출 — 권한 거부 예외는 response_action errors + V703 PERMISSION_DENIED, 메시지 갱신 없음`() {
        every { userMappingRepository.findUserIdBySlackUserId(slackUserId, teamId) } returns btsUserId
        every { transitionPort.transition(expectedCommand) } throws
            IssueTransitionPermissionDeniedException("denied")

        val result = service.handle(viewSubmission())

        assertThat(result).isInstanceOf(InteractionResult.ResponseActionErrors::class.java)
        assertThat((result as InteractionResult.ResponseActionErrors).json).contains("response_action", "errors")
        verify(exactly = 0) { messageClient.updateMessage(any(), any(), any(), any()) }
        verify(exactly = 1) {
            interactionLogRepository.record(teamId, slackUserId, btsUserId, "COMPLETE", "PERMISSION_DENIED", issueKey)
        }
    }

    @Test
    fun `모달 제출 — OCC 충돌 예외는 response_action errors + V703 CONFLICT`() {
        every { userMappingRepository.findUserIdBySlackUserId(slackUserId, teamId) } returns btsUserId
        every { transitionPort.transition(expectedCommand) } throws IssueOptimisticLockException("conflict")

        val result = service.handle(viewSubmission())

        assertThat(result).isInstanceOf(InteractionResult.ResponseActionErrors::class.java)
        verify(exactly = 0) { messageClient.updateMessage(any(), any(), any(), any()) }
        verify(exactly = 1) {
            interactionLogRepository.record(teamId, slackUserId, btsUserId, "COMPLETE", "CONFLICT", issueKey)
        }
    }

    @Test
    fun `모달 제출 — 그 외 예외는 response_action errors + V703 ERROR (권한 OCC를 먼저 삼키지 않는다)`() {
        every { userMappingRepository.findUserIdBySlackUserId(slackUserId, teamId) } returns btsUserId
        every { transitionPort.transition(expectedCommand) } throws IllegalStateException("boom")

        val result = service.handle(viewSubmission())

        assertThat(result).isInstanceOf(InteractionResult.ResponseActionErrors::class.java)
        verify(exactly = 1) {
            interactionLogRepository.record(teamId, slackUserId, btsUserId, "COMPLETE", "ERROR", issueKey)
        }
    }

    @Test
    fun `모달 제출 미연결 — ephemeral 불가하므로 response_action errors + V703 UNMAPPED`() {
        every { userMappingRepository.findUserIdBySlackUserId(slackUserId, teamId) } returns null

        val result = service.handle(viewSubmission())

        assertThat(result).isInstanceOf(InteractionResult.ResponseActionErrors::class.java)
        verify(exactly = 0) { transitionPort.transition(any()) }
        verify(exactly = 1) {
            interactionLogRepository.record(teamId, slackUserId, null, "COMPLETE", "UNMAPPED", null)
        }
    }

    @Test
    fun `모달 제출 성공 — resolution 없이도 전이한다(resolutionId null)`() {
        val commandNoResolution = expectedCommand.copy(resolutionId = null)
        every { userMappingRepository.findUserIdBySlackUserId(slackUserId, teamId) } returns btsUserId
        every { transitionPort.transition(commandNoResolution) } returns BoardTransitionResult(issueKey, toStateKey, 4)
        every { botTokenResolver.resolve(teamId) } returns botToken
        every { messageClient.updateMessage(botToken, channel, ts, any()) } returns SlackSendResult.Sent

        val result = service.handle(viewSubmission(withResolution = false))

        assertThat(result).isEqualTo(InteractionResult.AckEmpty)
        verify(exactly = 1) { transitionPort.transition(commandNoResolution) }
    }

    @Test
    fun `알 수 없는 callback_id 모달 제출 — no-op 빈 200`() {
        val result = service.handle(viewSubmission(callbackId = "some_other_modal"))

        assertThat(result).isEqualTo(InteractionResult.AckEmpty)
        verify(exactly = 0) { userMappingRepository.findUserIdBySlackUserId(any(), any()) }
        verify(exactly = 0) { transitionPort.transition(any()) }
    }

    // ── Unknown / 알 수 없는 action_id ─────────────────────────────────────────

    @Test
    fun `Unknown payload — no-op 빈 200`() {
        val result = service.handle(SlackInteractionPayload.Unknown)

        assertThat(result).isEqualTo(InteractionResult.AckEmpty)
        verify(exactly = 0) { userMappingRepository.findUserIdBySlackUserId(any(), any()) }
    }

    @Test
    fun `알 수 없는 action_id block_actions — no-op 빈 200`() {
        val payload =
            blockActionsComplete().copy(
                actions = listOf(SlackInteractionPayload.BlockActions.Action(actionId = "atlas_unknown", value = issueKey)),
            )

        val result = service.handle(payload)

        assertThat(result).isEqualTo(InteractionResult.AckEmpty)
        verify(exactly = 0) { messageClient.openModal(any(), any(), any()) }
        verify(exactly = 0) { responseUrlClient.post(any(), any()) }
    }
}
