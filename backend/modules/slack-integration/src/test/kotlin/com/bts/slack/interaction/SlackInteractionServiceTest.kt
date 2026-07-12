// SlackInteractionService 단위 테스트 — 완료/담당자/코멘트 동기 flow·타입예외 분류·V703 감사 (FR-SL-05 PR1 Task 8, PR2 Task 4)
package com.bts.slack.interaction

import com.bts.shared.board.BoardTransitionCommand
import com.bts.shared.board.BoardTransitionResult
import com.bts.shared.board.IssueOptimisticLockException
import com.bts.shared.board.IssueTransitionPermissionDeniedException
import com.bts.shared.board.IssueTransitionPort
import com.bts.shared.issue.AddCommentCommand
import com.bts.shared.issue.AssignCommand
import com.bts.shared.issue.DoneTransition
import com.bts.shared.issue.IssueCompletionOptions
import com.bts.shared.issue.IssueCompletionOptionsPort
import com.bts.shared.issue.IssueMutationPermissionDeniedException
import com.bts.shared.issue.IssueMutationPort
import com.bts.shared.issue.MutationResult
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
 *
 * PR2(FR-SL-05 Task 4)부터는 담당자 변경(`atlas_assign`/`atlas_assign_modal`)·코멘트
 * 등록(`atlas_comment`/`atlas_comment_modal`)도 같은 버튼→모달→view_submission 패턴으로 검증한다.
 * [IssueMutationPort] 실패도 [IssueMutationPermissionDeniedException](권한) → generic 순으로 분류한다.
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
    private val issueMutationPort = mockk<IssueMutationPort>()
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
            issueMutationPort = issueMutationPort,
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
    private val assigneeSlackUserId = "U2"
    private val assigneeBtsUserId = UUID.fromString("33333333-3333-4333-8333-333333333333")

    private fun blockActionsComplete() =
        SlackInteractionPayload.BlockActions(
            userId = slackUserId,
            teamId = teamId,
            triggerId = "trig-1",
            responseUrl = "https://hooks.slack.com/actions/T1/1/abc",
            channel = channel,
            messageTs = ts,
            actions =
                listOf(SlackInteractionPayload.BlockActions.Action(actionId = "atlas_complete", value = issueKey)),
        )

    /** [blockActionsComplete]와 동일한 컨텍스트에 action_id만 바꾼 block_actions payload(담당자/코멘트 버튼용). */
    private fun blockActionsWith(actionId: String) =
        blockActionsComplete().copy(
            actions = listOf(SlackInteractionPayload.BlockActions.Action(actionId = actionId, value = issueKey)),
        )

    /** 담당자 변경 모달 제출 payload. [hasSelectedUser]=false 면 `selected_user` state가 누락된 방어 케이스. */
    private fun viewSubmissionAssign(hasSelectedUser: Boolean = true): SlackInteractionPayload.ViewSubmission {
        val stateValues =
            if (hasSelectedUser) {
                mapOf("assignee_block" to mapOf("assignee_select" to mapOf("selected_user" to assigneeSlackUserId)))
            } else {
                emptyMap()
            }
        return SlackInteractionPayload.ViewSubmission(
            userId = slackUserId,
            teamId = teamId,
            callbackId = "atlas_assign_modal",
            privateMetadata = """{"issueKey":"$issueKey"}""",
            stateValues = stateValues,
        )
    }

    /** 코멘트 등록 모달 제출 payload. [body]=null 이면 입력 state가 누락된 방어 케이스. */
    private fun viewSubmissionComment(body: String? = "댓글 본문"): SlackInteractionPayload.ViewSubmission {
        val stateValues =
            if (body != null) {
                mapOf("comment_block" to mapOf("comment_input" to mapOf("value" to body)))
            } else {
                emptyMap()
            }
        return SlackInteractionPayload.ViewSubmission(
            userId = slackUserId,
            teamId = teamId,
            callbackId = "atlas_comment_modal",
            privateMetadata = """{"issueKey":"$issueKey"}""",
            stateValues = stateValues,
        )
    }

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
    fun `atlas_complete 완료 가능 상태 없음(doneTransitions 빈 목록) — 모달 안 열고 ephemeral + V703 NOT_APPLICABLE`() {
        every { userMappingRepository.findUserIdBySlackUserId(slackUserId, teamId) } returns btsUserId
        every { completionOptionsPort.getCompletionOptions(issueKey, btsUserId) } returns
            IssueCompletionOptions(version = 3, doneTransitions = emptyList(), resolutions = emptyList())
        every { botTokenResolver.resolve(teamId) } returns botToken
        every { modalBuilder.buildCompletionModal(any(), any(), any(), any()) } returns "{}"
        every { messageClient.openModal(any(), any(), any()) } returns SlackSendResult.Sent
        every { responseUrlClient.post(any(), any()) } returns true

        val result = service.handle(blockActionsComplete())

        // 완료 가능한 전이가 없으면(이미 완료 등) 입력 없는 무효 모달을 열지 않고 안내로 수렴한다.
        assertThat(result).isEqualTo(InteractionResult.AckEmpty)
        verify(exactly = 0) { botTokenResolver.resolve(any()) }
        verify(exactly = 0) { messageClient.openModal(any(), any(), any()) }
        verify(exactly = 1) { responseUrlClient.post(any(), any()) }
        verify(exactly = 1) {
            interactionLogRepository.record(teamId, slackUserId, btsUserId, "COMPLETE", "NOT_APPLICABLE", issueKey)
        }
    }

    @Test
    fun `atlas_complete 모달 오픈 실패(views_open 영구 실패) — ephemeral 안내 + V703 ERROR`() {
        every { userMappingRepository.findUserIdBySlackUserId(slackUserId, teamId) } returns btsUserId
        every { completionOptionsPort.getCompletionOptions(issueKey, btsUserId) } returns completionOptions()
        every { botTokenResolver.resolve(teamId) } returns botToken
        every { modalBuilder.buildCompletionModal(completionOptions(), issueKey, channel, ts) } returns "{\"view\":1}"
        every { messageClient.openModal(botToken, "trig-1", "{\"view\":1}") } returns
            SlackSendResult.PermanentFailure("expired_trigger_id")
        every { responseUrlClient.post(any(), any()) } returns true

        val result = service.handle(blockActionsComplete())

        // 모달 오픈 실패(만료 trigger_id 등)는 침묵하지 않고 안내 + 감사한다.
        assertThat(result).isEqualTo(InteractionResult.AckEmpty)
        verify(exactly = 1) { responseUrlClient.post(any(), any()) }
        verify(exactly = 1) {
            interactionLogRepository.record(teamId, slackUserId, btsUserId, "COMPLETE", "ERROR", issueKey)
        }
    }

    @Test
    fun `atlas_view(상세보기 url 버튼) — no-op 빈 응답, 어떤 협력자도 호출하지 않는다`() {
        val payload =
            blockActionsComplete().copy(
                actions =
                    listOf(SlackInteractionPayload.BlockActions.Action(actionId = "atlas_view", value = issueKey)),
            )

        val result = service.handle(payload)

        assertThat(result).isEqualTo(InteractionResult.AckEmpty)
        verify(exactly = 0) { userMappingRepository.findUserIdBySlackUserId(any(), any()) }
        verify(exactly = 0) { messageClient.openModal(any(), any(), any()) }
        verify(exactly = 0) { responseUrlClient.post(any(), any()) }
    }

    // ── block_actions: atlas_assign / atlas_comment ───────────────────────────

    @Test
    fun `atlas_assign 클릭 — 완료옵션 조회 없이 담당자 모달을 연다`() {
        every { userMappingRepository.findUserIdBySlackUserId(slackUserId, teamId) } returns btsUserId
        every { botTokenResolver.resolve(teamId) } returns botToken
        every { modalBuilder.buildAssignModal(issueKey) } returns "{\"view\":\"assign\"}"
        every { messageClient.openModal(botToken, "trig-1", "{\"view\":\"assign\"}") } returns SlackSendResult.Sent

        val result = service.handle(blockActionsWith("atlas_assign"))

        assertThat(result).isEqualTo(InteractionResult.AckEmpty)
        verify(exactly = 1) { messageClient.openModal(botToken, "trig-1", "{\"view\":\"assign\"}") }
        verify(exactly = 0) { completionOptionsPort.getCompletionOptions(any(), any()) }
        verify(exactly = 0) { responseUrlClient.post(any(), any()) }
    }

    @Test
    fun `atlas_comment 클릭 — 완료옵션 조회 없이 코멘트 모달을 연다`() {
        every { userMappingRepository.findUserIdBySlackUserId(slackUserId, teamId) } returns btsUserId
        every { botTokenResolver.resolve(teamId) } returns botToken
        every { modalBuilder.buildCommentModal(issueKey) } returns "{\"view\":\"comment\"}"
        every { messageClient.openModal(botToken, "trig-1", "{\"view\":\"comment\"}") } returns SlackSendResult.Sent

        val result = service.handle(blockActionsWith("atlas_comment"))

        assertThat(result).isEqualTo(InteractionResult.AckEmpty)
        verify(exactly = 1) { messageClient.openModal(botToken, "trig-1", "{\"view\":\"comment\"}") }
        verify(exactly = 0) { completionOptionsPort.getCompletionOptions(any(), any()) }
        verify(exactly = 0) { responseUrlClient.post(any(), any()) }
    }

    @Test
    fun `atlas_assign 미연결(UNMAPPED) — 모달 대신 ephemeral 안내 + V703 ASSIGN UNMAPPED`() {
        every { userMappingRepository.findUserIdBySlackUserId(slackUserId, teamId) } returns null
        every { responseUrlClient.post(any(), any()) } returns true

        val result = service.handle(blockActionsWith("atlas_assign"))

        assertThat(result).isEqualTo(InteractionResult.AckEmpty)
        verify(exactly = 1) { responseUrlClient.post(any(), any()) }
        verify(exactly = 0) { modalBuilder.buildAssignModal(any()) }
        verify(exactly = 1) {
            interactionLogRepository.record(teamId, slackUserId, null, "ASSIGN", "UNMAPPED", issueKey)
        }
    }

    @Test
    fun `atlas_comment 미연결(UNMAPPED) — 모달 대신 ephemeral 안내 + V703 COMMENT UNMAPPED`() {
        every { userMappingRepository.findUserIdBySlackUserId(slackUserId, teamId) } returns null
        every { responseUrlClient.post(any(), any()) } returns true

        val result = service.handle(blockActionsWith("atlas_comment"))

        assertThat(result).isEqualTo(InteractionResult.AckEmpty)
        verify(exactly = 1) { responseUrlClient.post(any(), any()) }
        verify(exactly = 0) { modalBuilder.buildCommentModal(any()) }
        verify(exactly = 1) {
            interactionLogRepository.record(teamId, slackUserId, null, "COMMENT", "UNMAPPED", issueKey)
        }
    }

    @Test
    fun `atlas_assign 모달 오픈 실패(views_open 영구 실패) — ephemeral 안내 + V703 ASSIGN ERROR`() {
        every { userMappingRepository.findUserIdBySlackUserId(slackUserId, teamId) } returns btsUserId
        every { botTokenResolver.resolve(teamId) } returns botToken
        every { modalBuilder.buildAssignModal(issueKey) } returns "{\"view\":\"assign\"}"
        every { messageClient.openModal(botToken, "trig-1", "{\"view\":\"assign\"}") } returns
            SlackSendResult.PermanentFailure("expired_trigger_id")
        every { responseUrlClient.post(any(), any()) } returns true

        val result = service.handle(blockActionsWith("atlas_assign"))

        assertThat(result).isEqualTo(InteractionResult.AckEmpty)
        verify(exactly = 1) { responseUrlClient.post(any(), any()) }
        verify(exactly = 1) {
            interactionLogRepository.record(teamId, slackUserId, btsUserId, "ASSIGN", "ERROR", issueKey)
        }
    }

    @Test
    fun `atlas_comment 모달 오픈 실패(views_open 영구 실패) — ephemeral 안내 + V703 COMMENT ERROR`() {
        every { userMappingRepository.findUserIdBySlackUserId(slackUserId, teamId) } returns btsUserId
        every { botTokenResolver.resolve(teamId) } returns botToken
        every { modalBuilder.buildCommentModal(issueKey) } returns "{\"view\":\"comment\"}"
        every { messageClient.openModal(botToken, "trig-1", "{\"view\":\"comment\"}") } returns
            SlackSendResult.PermanentFailure("expired_trigger_id")
        every { responseUrlClient.post(any(), any()) } returns true

        val result = service.handle(blockActionsWith("atlas_comment"))

        assertThat(result).isEqualTo(InteractionResult.AckEmpty)
        verify(exactly = 1) { responseUrlClient.post(any(), any()) }
        verify(exactly = 1) {
            interactionLogRepository.record(teamId, slackUserId, btsUserId, "COMMENT", "ERROR", issueKey)
        }
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
    fun `모달 제출 성공 — 원본 메시지 갱신(봇토큰 복호화)이 실패해도 완료는 SUCCESS 유지(장식 실패가 성공을 뒤집지 않는다)`() {
        every { userMappingRepository.findUserIdBySlackUserId(slackUserId, teamId) } returns btsUserId
        every { transitionPort.transition(expectedCommand) } returns BoardTransitionResult(issueKey, toStateKey, 4)
        // 전이는 커밋된 뒤, 장식용 chat.update 를 위한 봇토큰 복호화가 실패(손상 암호문/키 불일치 등).
        every { botTokenResolver.resolve(teamId) } throws RuntimeException("decrypt failed")

        val result = service.handle(viewSubmission())

        // 전이는 이미 성공·커밋됨 → 장식용 갱신 실패가 완료를 실패로 오분류하거나 ERROR 로 오기록해선 안 된다.
        assertThat(result).isEqualTo(InteractionResult.AckEmpty)
        verify(exactly = 1) {
            interactionLogRepository.record(teamId, slackUserId, btsUserId, "COMPLETE", "SUCCESS", issueKey)
        }
        verify(exactly = 0) {
            interactionLogRepository.record(teamId, slackUserId, btsUserId, "COMPLETE", "ERROR", issueKey)
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

    // ── view_submission: atlas_assign_modal ───────────────────────────────────

    @Test
    fun `atlas_assign_modal 제출 성공 — 담당자 배정 실행 + V703 ASSIGN SUCCESS + 빈 200`() {
        every { userMappingRepository.findUserIdBySlackUserId(slackUserId, teamId) } returns btsUserId
        every { userMappingRepository.findUserIdBySlackUserId(assigneeSlackUserId, teamId) } returns
            assigneeBtsUserId
        every {
            issueMutationPort.assign(AssignCommand(btsUserId, issueKey, assigneeBtsUserId, dryRun = false))
        } returns MutationResult(issueKey, applied = true, version = 5)

        val result = service.handle(viewSubmissionAssign())

        assertThat(result).isEqualTo(InteractionResult.AckEmpty)
        verify(exactly = 1) {
            issueMutationPort.assign(AssignCommand(btsUserId, issueKey, assigneeBtsUserId, dryRun = false))
        }
        verify(exactly = 1) {
            interactionLogRepository.record(teamId, slackUserId, btsUserId, "ASSIGN", "SUCCESS", issueKey)
        }
    }

    @Test
    fun `atlas_assign_modal 제출 — 대상 사용자 미연결 → responseActionErrors(assignee_block) + V703 ASSIGN ERROR`() {
        every { userMappingRepository.findUserIdBySlackUserId(slackUserId, teamId) } returns btsUserId
        every { userMappingRepository.findUserIdBySlackUserId(assigneeSlackUserId, teamId) } returns null

        val result = service.handle(viewSubmissionAssign())

        assertThat(result).isInstanceOf(InteractionResult.ResponseActionErrors::class.java)
        assertThat((result as InteractionResult.ResponseActionErrors).json).contains("assignee_block")
        verify(exactly = 0) { issueMutationPort.assign(any()) }
        verify(exactly = 1) {
            interactionLogRepository.record(teamId, slackUserId, btsUserId, "ASSIGN", "ERROR", issueKey)
        }
    }

    @Test
    fun `atlas_assign_modal 제출 — selected_user state 누락 → responseActionErrors + V703 ASSIGN ERROR(assign 미호출)`() {
        every { userMappingRepository.findUserIdBySlackUserId(slackUserId, teamId) } returns btsUserId

        val result = service.handle(viewSubmissionAssign(hasSelectedUser = false))

        assertThat(result).isInstanceOf(InteractionResult.ResponseActionErrors::class.java)
        verify(exactly = 0) { issueMutationPort.assign(any()) }
        verify(exactly = 0) { userMappingRepository.findUserIdBySlackUserId(assigneeSlackUserId, teamId) }
        verify(exactly = 1) {
            interactionLogRepository.record(teamId, slackUserId, btsUserId, "ASSIGN", "ERROR", issueKey)
        }
    }

    @Test
    fun `atlas_assign_modal 제출 — 권한 거부 예외 → responseActionErrors + V703 ASSIGN PERMISSION_DENIED`() {
        every { userMappingRepository.findUserIdBySlackUserId(slackUserId, teamId) } returns btsUserId
        every { userMappingRepository.findUserIdBySlackUserId(assigneeSlackUserId, teamId) } returns
            assigneeBtsUserId
        every { issueMutationPort.assign(any()) } throws IssueMutationPermissionDeniedException("denied")

        val result = service.handle(viewSubmissionAssign())

        assertThat(result).isInstanceOf(InteractionResult.ResponseActionErrors::class.java)
        verify(exactly = 1) {
            interactionLogRepository.record(teamId, slackUserId, btsUserId, "ASSIGN", "PERMISSION_DENIED", issueKey)
        }
    }

    @Test
    fun `atlas_assign_modal 제출 미연결 actor — responseActionErrors + V703 ASSIGN UNMAPPED`() {
        every { userMappingRepository.findUserIdBySlackUserId(slackUserId, teamId) } returns null

        val result = service.handle(viewSubmissionAssign())

        assertThat(result).isInstanceOf(InteractionResult.ResponseActionErrors::class.java)
        verify(exactly = 0) { issueMutationPort.assign(any()) }
        verify(exactly = 1) {
            interactionLogRepository.record(teamId, slackUserId, null, "ASSIGN", "UNMAPPED", null)
        }
    }

    // ── view_submission: atlas_comment_modal ──────────────────────────────────

    @Test
    fun `atlas_comment_modal 제출 성공 — 댓글 추가 실행 + V703 COMMENT SUCCESS + 빈 200`() {
        val body = "댓글 본문"
        every { userMappingRepository.findUserIdBySlackUserId(slackUserId, teamId) } returns btsUserId
        every {
            issueMutationPort.addComment(AddCommentCommand(btsUserId, issueKey, body, dryRun = false))
        } returns MutationResult(issueKey, applied = true, version = null)

        val result = service.handle(viewSubmissionComment(body = body))

        assertThat(result).isEqualTo(InteractionResult.AckEmpty)
        verify(exactly = 1) {
            issueMutationPort.addComment(AddCommentCommand(btsUserId, issueKey, body, dryRun = false))
        }
        verify(exactly = 1) {
            interactionLogRepository.record(teamId, slackUserId, btsUserId, "COMMENT", "SUCCESS", issueKey)
        }
    }

    @Test
    fun `atlas_comment_modal 제출 — 권한 거부 예외 → responseActionErrors + V703 COMMENT PERMISSION_DENIED`() {
        every { userMappingRepository.findUserIdBySlackUserId(slackUserId, teamId) } returns btsUserId
        every { issueMutationPort.addComment(any()) } throws IssueMutationPermissionDeniedException("denied")

        val result = service.handle(viewSubmissionComment())

        assertThat(result).isInstanceOf(InteractionResult.ResponseActionErrors::class.java)
        verify(exactly = 1) {
            interactionLogRepository.record(teamId, slackUserId, btsUserId, "COMMENT", "PERMISSION_DENIED", issueKey)
        }
    }

    @Test
    fun `atlas_comment_modal 제출 — 본문 비어있음(state 누락) → responseActionErrors + V703 COMMENT ERROR(addComment 미호출)`() {
        every { userMappingRepository.findUserIdBySlackUserId(slackUserId, teamId) } returns btsUserId

        val result = service.handle(viewSubmissionComment(body = null))

        assertThat(result).isInstanceOf(InteractionResult.ResponseActionErrors::class.java)
        verify(exactly = 0) { issueMutationPort.addComment(any()) }
        verify(exactly = 1) {
            interactionLogRepository.record(teamId, slackUserId, btsUserId, "COMMENT", "ERROR", issueKey)
        }
    }

    @Test
    fun `atlas_comment_modal 제출 미연결 actor — responseActionErrors + V703 COMMENT UNMAPPED`() {
        every { userMappingRepository.findUserIdBySlackUserId(slackUserId, teamId) } returns null

        val result = service.handle(viewSubmissionComment())

        assertThat(result).isInstanceOf(InteractionResult.ResponseActionErrors::class.java)
        verify(exactly = 0) { issueMutationPort.addComment(any()) }
        verify(exactly = 1) {
            interactionLogRepository.record(teamId, slackUserId, null, "COMMENT", "UNMAPPED", null)
        }
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
                actions =
                    listOf(SlackInteractionPayload.BlockActions.Action(actionId = "atlas_unknown", value = issueKey)),
            )

        val result = service.handle(payload)

        assertThat(result).isEqualTo(InteractionResult.AckEmpty)
        verify(exactly = 0) { messageClient.openModal(any(), any(), any()) }
        verify(exactly = 0) { responseUrlClient.post(any(), any()) }
    }
}
