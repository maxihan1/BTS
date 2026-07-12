// Slack 인터랙티브(완료/담당자/코멘트 버튼·모달) 페이로드를 동기 오케스트레이션하는 서비스 (FR-SL-05 PR1 Task 8, PR2 Task 4)
package com.bts.slack.interaction

import com.bts.shared.board.BoardTransitionCommand
import com.bts.shared.board.IssueOptimisticLockException
import com.bts.shared.board.IssueTransitionPermissionDeniedException
import com.bts.shared.board.IssueTransitionPort
import com.bts.shared.issue.AddCommentCommand
import com.bts.shared.issue.AssignCommand
import com.bts.shared.issue.IssueCompletionOptionsPort
import com.bts.shared.issue.IssueMutationPermissionDeniedException
import com.bts.shared.issue.IssueMutationPort
import com.bts.slack.application.SlackInteractionLogRepository
import com.bts.slack.application.SlackUserMappingRepository
import com.bts.slack.message.SlackMessageClient
import com.bts.slack.message.SlackResponseUrlClient
import com.bts.slack.message.SlackSendResult
import com.bts.slack.worker.SlackBotTokenResolver
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * `POST /slack/interactions`가 받은 이미 파싱된 [SlackInteractionPayload]를 처리해, 완료/담당자/코멘트
 * 세 인터랙션(버튼 → 모달 → 제출)을 **동기**로 오케스트레이션한다 (FR-SL-05 PR1 Task 8, PR2 Task 4).
 *
 * ## 스코프 — 완료 표시 + 담당자 변경 + 코멘트 등록 + 상세보기
 * - `block_actions`/`atlas_complete`: 역매핑 → 완료옵션 조회 → 완료 모달 오픈(`views.open`).
 * - `block_actions`/`atlas_assign`·`atlas_comment`: 역매핑 → (완료옵션 조회 없이) 담당자/코멘트 모달
 *   오픈(`views.open`). OCC(낙관적 동시성 제어) 없는 비종료 액션이라 완료와 달리 사전 조회가 없다.
 * - `view_submission`/`atlas_complete_modal`: 역매핑 → 전이 실행([IssueTransitionPort.transition]) →
 *   원본 DM 메시지 갱신(`chat.update`).
 * - `view_submission`/`atlas_assign_modal`·`atlas_comment_modal`: 역매핑 → 대상 파싱/역매핑(담당자만) →
 *   [IssueMutationPort.assign]/[IssueMutationPort.addComment] 실행. 장식용 메시지 갱신 없음(비종료 액션).
 * - `block_actions`/`atlas_view`(상세보기 url 버튼): 서버 처리가 없으므로 no-op(빈 200).
 * - 그 외(알 수 없는 action_id·callback_id·[SlackInteractionPayload.Unknown]): no-op(빈 200).
 *
 * ## 전부 동기 — @Async 없음
 * 완료 인터랙션은 (1) 모달을 여는 `trigger_id`가 3초 내 만료되고, (2) `view_submission` 응답은 Slack이
 * 모달을 닫을지/에러를 띄울지 결정하는 동기 계약([InteractionResult])이라 비동기로 미룰 수 없다. 컨트롤러가
 * 이 서비스의 반환값을 그대로 HTTP 본문으로 직렬화한다.
 *
 * ## actor는 오직 역매핑 결과 (위조 차단)
 * 전이/변경 행위자([BoardTransitionCommand.actorUserId], [AssignCommand.actorUserId],
 * [AddCommentCommand.actorUserId])는 서명 검증된 `user.id`/`team.id`를
 * [SlackUserMappingRepository.findUserIdBySlackUserId]로 역매핑한 BTS 사용자 id만 쓴다. request
 * body/param에서 actor를 받지 않는다([IssueTransitionPort] KDoc 동형). 담당자 변경의 배정 대상도
 * 동일 역매핑을 거친다 — 대상이 Slack `users_select`로 고른 Slack user id는 그 자체로 신뢰하지 않고,
 * BTS 사용자로 역매핑되지 않으면([TARGET_NOT_LINKED_MESSAGE]) 배정을 거부한다.
 *
 * ## 판정 불가는 거부 (fail-closed)
 * 미연결(역매핑 null)·무권한/미가시(getCompletionOptions null)·전이/변경 실패는 모두 안전하게 거부한다.
 * 이슈 존재·제목 등 내부 사정은 사용자 응답에 노출하지 않고 일반 메시지로 치환한다.
 *
 * ## 실패 분류 — 타입 예외로만 (catch 순서 주의)
 * [IssueTransitionPort.transition]/[IssueMutationPort.assign]/[IssueMutationPort.addComment]는 실패를
 * shared-kernel 타입 예외로 던진다. 클래스명 문자열 매칭이 아니라 `catch` 타입으로 분류한다 —
 * [IssueTransitionPermissionDeniedException]/[IssueMutationPermissionDeniedException](권한)·
 * [IssueOptimisticLockException](OCC)를 **generic catch보다 먼저** 잡아, 권한/충돌 신호가 ERROR로
 * 뭉개지지 않게 한다(best-effort-loop-permission-exception-nonprod-mask 계열 교훈).
 *
 * @param userMappingRepository Slack 사용자 → BTS 사용자 역매핑(null=미연결).
 * @param completionOptionsPort 완료 옵션 결합 fail-closed 조회(null=무권한/미가시).
 * @param modalBuilder 완료/담당자/코멘트 모달 view JSON 조립.
 * @param messageClient `views.open`/`chat.update` 호출 클라이언트.
 * @param responseUrlClient `block_actions`의 `response_url`로 ephemeral 안내를 보내는 best-effort 클라이언트.
 * @param transitionPort 완료 전이 실행 cross-BC 포트.
 * @param botTokenResolver teamId → 복호화된 봇 토큰(내부적으로 `SlackInstallRepository.findByTeamId` + 복호화).
 * @param interactionLogRepository V703 감사 로그(append-only, best-effort 기록).
 * @param issueMutationPort 담당자 배정/코멘트 추가 실행 cross-BC 포트(FR-SL-05 PR2).
 * @param objectMapper private_metadata 파싱 + Block Kit JSON 조립용 Jackson.
 */
@Component
// TooManyFunctions: handle/블록·모달 분기(완료·담당자·코멘트 3종) + private_metadata·state.values 추출 +
// 결과·블록 조립 + 감사가 모두 이 오케스트레이션의 응집된 단계라 임계값(11)을 넘는다. 쪼개면 호출부가 여러
// 협력자를 조립해야 해 응집이 깨진다(SlackBlockKitRenderer 동형 사유).
@Suppress("TooManyFunctions", "LongParameterList") // 생성자: 10종 협력자를 주입받는 오케스트레이터(관례 동형).
class SlackInteractionService(
    private val userMappingRepository: SlackUserMappingRepository,
    private val completionOptionsPort: IssueCompletionOptionsPort,
    private val modalBuilder: SlackModalBuilder,
    private val messageClient: SlackMessageClient,
    private val responseUrlClient: SlackResponseUrlClient,
    private val transitionPort: IssueTransitionPort,
    private val botTokenResolver: SlackBotTokenResolver,
    private val interactionLogRepository: SlackInteractionLogRepository,
    private val issueMutationPort: IssueMutationPort,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 파싱된 인터랙션 페이로드 한 건을 처리하고 컨트롤러가 직렬화할 [InteractionResult]를 반환한다.
     *
     * @param payload [SlackInteractionPayloadParser]가 만든 sealed 페이로드.
     * @return 빈 200([InteractionResult.AckEmpty]) 또는 모달 에러 표기
     *   ([InteractionResult.ResponseActionErrors]).
     */
    fun handle(payload: SlackInteractionPayload): InteractionResult =
        when (payload) {
            is SlackInteractionPayload.BlockActions -> handleBlockActions(payload)
            is SlackInteractionPayload.ViewSubmission -> handleViewSubmission(payload)
            SlackInteractionPayload.Unknown -> InteractionResult.AckEmpty
        }

    // ── block_actions ─────────────────────────────────────────────────────────

    /**
     * 클릭된 첫 액션의 action_id로 분기한다(버튼 클릭은 1개 액션만 전송). `atlas_complete`/`atlas_assign`/
     * `atlas_comment`만 처리하고, 상세보기(`atlas_view`) url 버튼·미지원 action_id는 서버 처리가 없어
     * no-op(빈 200)으로 수렴한다.
     */
    private fun handleBlockActions(payload: SlackInteractionPayload.BlockActions): InteractionResult {
        val action = payload.actions.firstOrNull() ?: return InteractionResult.AckEmpty
        return when (action.actionId) {
            ATLAS_COMPLETE_ACTION_ID -> handleCompleteButton(payload, action)
            ATLAS_ASSIGN_ACTION_ID -> handleAssignButton(payload, action)
            ATLAS_COMMENT_ACTION_ID -> handleCommentButton(payload, action)
            else -> InteractionResult.AckEmpty
        }
    }

    /**
     * 완료 버튼 클릭 — [resolveButtonContext]로 역매핑을 통과한 뒤, 완료 고유 게이트(완료옵션 조회)를 거쳐
     * [openButtonModal]로 완료 모달을 연다. 완료옵션 사전조회는 담당자/코멘트 버튼에는 없는 완료만의 단계다
     * (모달 콘텐츠, 즉 해결 방법·완료 상태 선택지가 이슈 상태에서 파생되기 때문).
     */
    @Suppress("ReturnCount") // 완료옵션 무권한/미가시·미완료가능 두 게이트의 early-return(가드 절, resolveButtonContext 동형).
    private fun handleCompleteButton(
        payload: SlackInteractionPayload.BlockActions,
        action: SlackInteractionPayload.BlockActions.Action,
    ): InteractionResult {
        val context = resolveButtonContext(payload, action, ACTION_TYPE_COMPLETE) ?: return InteractionResult.AckEmpty

        val options = completionOptionsPort.getCompletionOptions(context.issueKey, context.btsUserId)
        if (options == null) {
            sendEphemeral(payload.responseUrl, NO_PERMISSION_MESSAGE)
            record(
                context.teamId,
                context.slackUserId,
                context.btsUserId,
                ACTION_TYPE_COMPLETE,
                OUTCOME_PERMISSION_DENIED,
                context.issueKey,
            )
            return InteractionResult.AckEmpty
        }
        // 완료 가능한 DONE 전이가 없으면(이미 완료 등) 입력 블록 없는 무효 모달을 열지 않고 안내로 수렴한다
        // (views.open 무효 거부로 인한 무피드백·무감사 침묵 차단). 오류가 아니므로 별도 outcome 으로 기록한다.
        if (options.doneTransitions.isEmpty()) {
            sendEphemeral(payload.responseUrl, NO_COMPLETABLE_STATE_MESSAGE)
            record(
                context.teamId,
                context.slackUserId,
                context.btsUserId,
                ACTION_TYPE_COMPLETE,
                OUTCOME_NOT_APPLICABLE,
                context.issueKey,
            )
            return InteractionResult.AckEmpty
        }

        return openButtonModal(payload, context, ACTION_TYPE_COMPLETE) {
            modalBuilder.buildCompletionModal(
                options,
                context.issueKey,
                payload.channel.orEmpty(),
                payload.messageTs.orEmpty(),
            )
        }
    }

    /**
     * 담당자 변경 버튼 클릭 — [handleCompleteButton]과 같은 공통 게이트([resolveButtonContext] →
     * [openButtonModal])를 쓰되, 완료옵션 사전조회가 없다(담당자 모달 콘텐츠는 이슈 파생이 아니라 정적 입력 폼).
     */
    private fun handleAssignButton(
        payload: SlackInteractionPayload.BlockActions,
        action: SlackInteractionPayload.BlockActions.Action,
    ): InteractionResult {
        val context = resolveButtonContext(payload, action, ACTION_TYPE_ASSIGN) ?: return InteractionResult.AckEmpty
        return openButtonModal(payload, context, ACTION_TYPE_ASSIGN) { modalBuilder.buildAssignModal(context.issueKey) }
    }

    /** 코멘트 등록 버튼 클릭 — [handleAssignButton]과 동일한 공통 게이트(완료옵션 사전조회 없음). */
    private fun handleCommentButton(
        payload: SlackInteractionPayload.BlockActions,
        action: SlackInteractionPayload.BlockActions.Action,
    ): InteractionResult {
        val context = resolveButtonContext(payload, action, ACTION_TYPE_COMMENT) ?: return InteractionResult.AckEmpty
        return openButtonModal(payload, context, ACTION_TYPE_COMMENT) {
            modalBuilder.buildCommentModal(context.issueKey)
        }
    }

    /**
     * 버튼 클릭 공통 게이트 1단계 — userId/teamId/issueKey null 가드 → 역매핑. 완료/담당자/코멘트 세 버튼이
     * 공유하는 진입 게이트다([handleCompleteButton]/[handleAssignButton]/[handleCommentButton] 동형 사유,
     * plan-eng-review DRY 권고).
     *
     * id 누락은 side effect 없이(응답할 채널 자체가 불명확) `null`을 반환하고, 미연결은 ephemeral 안내 +
     * V703 UNMAPPED를 남긴 뒤 `null`을 반환한다 — 두 경우 모두 호출자는 `?: return InteractionResult.AckEmpty`
     * 로 수렴시킨다.
     *
     * @param actionType V703 감사에 남길 액션 종류(`COMPLETE`/`ASSIGN`/`COMMENT`).
     */
    @Suppress("ReturnCount") // userId/teamId/issueKey/역매핑 4개 fail-closed 게이트의 early-return(가드 절).
    private fun resolveButtonContext(
        payload: SlackInteractionPayload.BlockActions,
        action: SlackInteractionPayload.BlockActions.Action,
        actionType: String,
    ): ButtonContext? {
        val slackUserId = payload.userId ?: return null
        val teamId = payload.teamId ?: return null
        val issueKey = action.value ?: return null

        val btsUserId = userMappingRepository.findUserIdBySlackUserId(slackUserId, teamId)
        if (btsUserId == null) {
            sendEphemeral(payload.responseUrl, ACCOUNT_LINK_REQUIRED_MESSAGE)
            record(teamId, slackUserId, null, actionType, OUTCOME_UNMAPPED, issueKey)
            return null
        }
        return ButtonContext(teamId, slackUserId, issueKey, btsUserId)
    }

    /**
     * 버튼 클릭 공통 게이트 2단계 — 봇토큰/`trigger_id` 확보 후 [buildModal]이 만든 모달을 연다. 실패
     * (봇 미설치·`trigger_id` 부재·`openModal` 비-Sent)는 모두 ephemeral 안내 + V703 [actionType]/ERROR로
     * 수렴한다(모달 오픈 실패를 침묵시키지 않는다 — 만료 trigger_id·Slack 거부 등).
     */
    private fun openButtonModal(
        payload: SlackInteractionPayload.BlockActions,
        context: ButtonContext,
        actionType: String,
        buildModal: () -> String,
    ): InteractionResult {
        val botToken = botTokenResolver.resolve(context.teamId)
        val triggerId = payload.triggerId
        if (botToken == null || triggerId == null) {
            sendEphemeral(payload.responseUrl, GENERIC_ERROR_MESSAGE)
            record(context.teamId, context.slackUserId, context.btsUserId, actionType, OUTCOME_ERROR, context.issueKey)
            return InteractionResult.AckEmpty
        }
        if (messageClient.openModal(botToken, triggerId, buildModal()) !is SlackSendResult.Sent) {
            sendEphemeral(payload.responseUrl, GENERIC_ERROR_MESSAGE)
            record(context.teamId, context.slackUserId, context.btsUserId, actionType, OUTCOME_ERROR, context.issueKey)
        }
        return InteractionResult.AckEmpty
    }

    // ── view_submission ───────────────────────────────────────────────────────

    /** callback_id로 완료/담당자/코멘트 제출 처리기로 분기한다. 그 외 callback_id는 no-op(빈 200). */
    private fun handleViewSubmission(payload: SlackInteractionPayload.ViewSubmission): InteractionResult =
        when (payload.callbackId) {
            ATLAS_COMPLETE_MODAL_CALLBACK_ID -> handleCompleteSubmission(payload)
            ATLAS_ASSIGN_MODAL_CALLBACK_ID -> handleAssignSubmission(payload)
            ATLAS_COMMENT_MODAL_CALLBACK_ID -> handleCommentSubmission(payload)
            else -> InteractionResult.AckEmpty
        }

    /** 완료 모달 제출 — 역매핑 → private_metadata/상태값 추출 → 전이 실행. */
    @Suppress("ReturnCount") // actor·역매핑·metadata 각 fail-closed 게이트의 early-return(가드 절).
    private fun handleCompleteSubmission(payload: SlackInteractionPayload.ViewSubmission): InteractionResult {
        val slackUserId = payload.userId
        val teamId = payload.teamId
        if (slackUserId == null || teamId == null) {
            return responseActionErrors(DONE_TRANSITION_BLOCK_ID, GENERIC_ERROR_MESSAGE)
        }

        val btsUserId = userMappingRepository.findUserIdBySlackUserId(slackUserId, teamId)
        if (btsUserId == null) {
            // 모달 제출에는 ephemeral 응답 채널이 없어(response_url 부재) 모달 에러로만 안내한다.
            record(teamId, slackUserId, null, ACTION_TYPE_COMPLETE, OUTCOME_UNMAPPED, null)
            return responseActionErrors(DONE_TRANSITION_BLOCK_ID, ACCOUNT_LINK_REQUIRED_MESSAGE)
        }
        val actor = InteractionActor(teamId, slackUserId, btsUserId)

        val metadata = parsePrivateMetadata(payload.privateMetadata)
        val toStateKey =
            metadata?.let { selectedValue(payload.stateValues, DONE_TRANSITION_BLOCK_ID, DONE_TRANSITION_ACTION_ID) }
        if (metadata == null || toStateKey == null) {
            record(
                actor.teamId,
                actor.slackUserId,
                actor.btsUserId,
                ACTION_TYPE_COMPLETE,
                OUTCOME_ERROR,
                metadata?.issueKey,
            )
            return responseActionErrors(DONE_TRANSITION_BLOCK_ID, GENERIC_ERROR_MESSAGE)
        }

        val resolutionRaw = selectedValue(payload.stateValues, RESOLUTION_BLOCK_ID, RESOLUTION_ACTION_ID)
        return executeCompletion(actor, metadata, toStateKey, resolutionRaw)
    }

    /**
     * 담당자 변경 모달 제출 — 역매핑 → private_metadata에서 issueKey 파싱 → `users_select`로 고른 대상
     * Slack user id를 BTS 사용자로 역매핑 → [executeAssign]. 대상이 미연결이면 배정하지 않고 거부한다
     * (담당자로 지정하려는 대상 자체가 위조/오입력일 수 있어 fail-closed).
     */
    @Suppress("ReturnCount") // actor·역매핑·metadata·선택값 각 fail-closed 게이트의 early-return(가드 절).
    private fun handleAssignSubmission(payload: SlackInteractionPayload.ViewSubmission): InteractionResult {
        val slackUserId = payload.userId
        val teamId = payload.teamId
        if (slackUserId == null || teamId == null) return responseActionErrors(ASSIGNEE_BLOCK_ID, GENERIC_ERROR_MESSAGE)

        val btsUserId = userMappingRepository.findUserIdBySlackUserId(slackUserId, teamId)
        if (btsUserId == null) {
            record(teamId, slackUserId, null, ACTION_TYPE_ASSIGN, OUTCOME_UNMAPPED, null)
            return responseActionErrors(ASSIGNEE_BLOCK_ID, ACCOUNT_LINK_REQUIRED_MESSAGE)
        }
        val actor = InteractionActor(teamId, slackUserId, btsUserId)

        val issueKey = parseIssueKeyMetadata(payload.privateMetadata)
        if (issueKey == null) {
            record(actor.teamId, actor.slackUserId, actor.btsUserId, ACTION_TYPE_ASSIGN, OUTCOME_ERROR, null)
            return responseActionErrors(ASSIGNEE_BLOCK_ID, GENERIC_ERROR_MESSAGE)
        }

        val targetSlackUserId = selectedUser(payload.stateValues, ASSIGNEE_BLOCK_ID, ASSIGNEE_ACTION_ID)
        if (targetSlackUserId == null) {
            record(actor.teamId, actor.slackUserId, actor.btsUserId, ACTION_TYPE_ASSIGN, OUTCOME_ERROR, issueKey)
            return responseActionErrors(ASSIGNEE_BLOCK_ID, GENERIC_ERROR_MESSAGE)
        }

        val targetBtsUserId = userMappingRepository.findUserIdBySlackUserId(targetSlackUserId, teamId)
        if (targetBtsUserId == null) {
            record(actor.teamId, actor.slackUserId, actor.btsUserId, ACTION_TYPE_ASSIGN, OUTCOME_ERROR, issueKey)
            return responseActionErrors(ASSIGNEE_BLOCK_ID, TARGET_NOT_LINKED_MESSAGE)
        }

        return executeAssign(actor, issueKey, targetBtsUserId)
    }

    /** 코멘트 등록 모달 제출 — 역매핑 → private_metadata에서 issueKey 파싱 → 입력 본문 추출 → [executeComment]. */
    @Suppress("ReturnCount") // actor·역매핑·metadata·입력값 각 fail-closed 게이트의 early-return(가드 절).
    private fun handleCommentSubmission(payload: SlackInteractionPayload.ViewSubmission): InteractionResult {
        val slackUserId = payload.userId
        val teamId = payload.teamId
        if (slackUserId == null || teamId == null) return responseActionErrors(COMMENT_BLOCK_ID, GENERIC_ERROR_MESSAGE)

        val btsUserId = userMappingRepository.findUserIdBySlackUserId(slackUserId, teamId)
        if (btsUserId == null) {
            record(teamId, slackUserId, null, ACTION_TYPE_COMMENT, OUTCOME_UNMAPPED, null)
            return responseActionErrors(COMMENT_BLOCK_ID, ACCOUNT_LINK_REQUIRED_MESSAGE)
        }
        val actor = InteractionActor(teamId, slackUserId, btsUserId)

        val issueKey = parseIssueKeyMetadata(payload.privateMetadata)
        if (issueKey == null) {
            record(actor.teamId, actor.slackUserId, actor.btsUserId, ACTION_TYPE_COMMENT, OUTCOME_ERROR, null)
            return responseActionErrors(COMMENT_BLOCK_ID, GENERIC_ERROR_MESSAGE)
        }

        val body = plainInputValue(payload.stateValues, COMMENT_BLOCK_ID, COMMENT_ACTION_ID)
        if (body.isNullOrBlank()) {
            record(actor.teamId, actor.slackUserId, actor.btsUserId, ACTION_TYPE_COMMENT, OUTCOME_ERROR, issueKey)
            return responseActionErrors(COMMENT_BLOCK_ID, GENERIC_ERROR_MESSAGE)
        }

        return executeComment(actor, issueKey, body)
    }

    /**
     * 전이를 실행하고 성공/실패를 타입 예외로 분류한다.
     *
     * catch 순서는 좁은 타입(권한 → OCC) → generic 순이다 — 특정 신호가 generic ERROR로 뭉개지지 않게 한다.
     * `resolutionRaw` 파싱([UUID.fromString])이 실패하면(방어적 케이스) generic catch가 ERROR로 수렴시킨다.
     */
    @Suppress("TooGenericExceptionCaught") // generic catch는 전이 실패 최종 버킷 — 위의 타입 catch를 먼저 통과시킨다.
    private fun executeCompletion(
        actor: InteractionActor,
        metadata: CompletionMetadata,
        toStateKey: String,
        resolutionRaw: String?,
    ): InteractionResult =
        try {
            val resolutionId = resolutionRaw?.let { UUID.fromString(it) }
            transitionPort.transition(
                BoardTransitionCommand(
                    actorUserId = actor.btsUserId,
                    issueKey = metadata.issueKey,
                    toStateKey = toStateKey,
                    expectedVersion = metadata.expectedVersion,
                    resolutionId = resolutionId,
                ),
            )
            updateOriginalMessage(actor.teamId, metadata)
            record(
                actor.teamId,
                actor.slackUserId,
                actor.btsUserId,
                ACTION_TYPE_COMPLETE,
                OUTCOME_SUCCESS,
                metadata.issueKey,
            )
            InteractionResult.AckEmpty
        } catch (e: IssueTransitionPermissionDeniedException) {
            log.warn("slack_interaction_complete_denied errorType={}", e.javaClass.simpleName)
            record(
                actor.teamId,
                actor.slackUserId,
                actor.btsUserId,
                ACTION_TYPE_COMPLETE,
                OUTCOME_PERMISSION_DENIED,
                metadata.issueKey,
            )
            responseActionErrors(DONE_TRANSITION_BLOCK_ID, NO_PERMISSION_MESSAGE)
        } catch (e: IssueOptimisticLockException) {
            log.warn("slack_interaction_complete_conflict errorType={}", e.javaClass.simpleName)
            record(
                actor.teamId,
                actor.slackUserId,
                actor.btsUserId,
                ACTION_TYPE_COMPLETE,
                OUTCOME_CONFLICT,
                metadata.issueKey,
            )
            responseActionErrors(DONE_TRANSITION_BLOCK_ID, CONFLICT_MESSAGE)
        } catch (e: RuntimeException) {
            log.warn("slack_interaction_complete_failed errorType={}", e.javaClass.simpleName)
            record(
                actor.teamId,
                actor.slackUserId,
                actor.btsUserId,
                ACTION_TYPE_COMPLETE,
                OUTCOME_ERROR,
                metadata.issueKey,
            )
            responseActionErrors(DONE_TRANSITION_BLOCK_ID, GENERIC_ERROR_MESSAGE)
        }

    /**
     * 담당자를 배정하고 성공/실패를 타입 예외로 분류한다. catch 순서는 [executeCompletion]과 동형(권한 →
     * generic). 담당자 변경은 OCC 대상이 아니므로([IssueMutationPort.assign] KDoc) 충돌 분류가 없다.
     */
    @Suppress("TooGenericExceptionCaught") // generic catch는 변경 실패 최종 버킷 — 위의 타입 catch를 먼저 통과시킨다.
    private fun executeAssign(
        actor: InteractionActor,
        issueKey: String,
        assigneeId: UUID,
    ): InteractionResult =
        try {
            issueMutationPort.assign(AssignCommand(actor.btsUserId, issueKey, assigneeId, dryRun = false))
            record(actor.teamId, actor.slackUserId, actor.btsUserId, ACTION_TYPE_ASSIGN, OUTCOME_SUCCESS, issueKey)
            InteractionResult.AckEmpty
        } catch (e: IssueMutationPermissionDeniedException) {
            log.warn("slack_interaction_assign_denied errorType={}", e.javaClass.simpleName)
            record(
                actor.teamId,
                actor.slackUserId,
                actor.btsUserId,
                ACTION_TYPE_ASSIGN,
                OUTCOME_PERMISSION_DENIED,
                issueKey,
            )
            responseActionErrors(ASSIGNEE_BLOCK_ID, NO_PERMISSION_MESSAGE)
        } catch (e: RuntimeException) {
            log.warn("slack_interaction_assign_failed errorType={}", e.javaClass.simpleName)
            record(actor.teamId, actor.slackUserId, actor.btsUserId, ACTION_TYPE_ASSIGN, OUTCOME_ERROR, issueKey)
            responseActionErrors(ASSIGNEE_BLOCK_ID, GENERIC_ERROR_MESSAGE)
        }

    /** 코멘트를 추가하고 성공/실패를 타입 예외로 분류한다. catch 순서는 [executeAssign]과 동형. */
    @Suppress("TooGenericExceptionCaught") // generic catch는 변경 실패 최종 버킷 — 위의 타입 catch를 먼저 통과시킨다.
    private fun executeComment(
        actor: InteractionActor,
        issueKey: String,
        body: String,
    ): InteractionResult =
        try {
            issueMutationPort.addComment(AddCommentCommand(actor.btsUserId, issueKey, body, dryRun = false))
            record(actor.teamId, actor.slackUserId, actor.btsUserId, ACTION_TYPE_COMMENT, OUTCOME_SUCCESS, issueKey)
            InteractionResult.AckEmpty
        } catch (e: IssueMutationPermissionDeniedException) {
            log.warn("slack_interaction_comment_denied errorType={}", e.javaClass.simpleName)
            record(
                actor.teamId,
                actor.slackUserId,
                actor.btsUserId,
                ACTION_TYPE_COMMENT,
                OUTCOME_PERMISSION_DENIED,
                issueKey,
            )
            responseActionErrors(COMMENT_BLOCK_ID, NO_PERMISSION_MESSAGE)
        } catch (e: RuntimeException) {
            log.warn("slack_interaction_comment_failed errorType={}", e.javaClass.simpleName)
            record(actor.teamId, actor.slackUserId, actor.btsUserId, ACTION_TYPE_COMMENT, OUTCOME_ERROR, issueKey)
            responseActionErrors(COMMENT_BLOCK_ID, GENERIC_ERROR_MESSAGE)
        }

    /**
     * 전이 성공 후 원본 DM 메시지를 완료 문구로 갱신한다(장식용). 봇 미설치면 조용히 skip(전이는 이미 성공).
     *
     * 전이는 이미 독립 트랜잭션으로 커밋된 뒤라, 이 갱신(봇토큰 복호화·`chat.update`)의 실패가 성공한 완료를
     * 뒤집어선 안 된다. [executeCompletion]의 결과 분류 catch로 예외가 전파되면 SUCCESS가 ERROR로 오분류·
     * 오기록되므로, 여기서 best-effort로 삼키고 WARN만 남긴다([record] 동형).
     */
    @Suppress("TooGenericExceptionCaught") // 장식용 갱신 — 실패는 WARN만, 이미 커밋된 완료를 뒤집지 않는다.
    private fun updateOriginalMessage(
        teamId: String,
        metadata: CompletionMetadata,
    ) {
        try {
            val botToken = botTokenResolver.resolve(teamId) ?: return
            messageClient.updateMessage(botToken, metadata.channel, metadata.ts, completedBlocksJson())
        } catch (e: RuntimeException) {
            log.warn("slack_interaction_update_original_failed errorType={}", e.javaClass.simpleName)
        }
    }

    // ── private_metadata / state_values 추출 ───────────────────────────────────

    /**
     * 완료 모달의 `private_metadata`(JSON 문자열)를 파싱한다. [SlackModalBuilder]가 박제한
     * `{issueKey, expectedVersion, channel, ts}` 구조를 JsonNode로 읽는다(Kotlin 모듈 비의존). 필드
     * 누락·유효하지 않은 JSON은 `null`(호출자가 ERROR로 수렴).
     */
    @Suppress("ReturnCount", "SwallowedException") // 필드별 가드 절 + 유효하지 않은 metadata는 null 수렴(원문·예외 미노출).
    private fun parsePrivateMetadata(raw: String?): CompletionMetadata? {
        if (raw.isNullOrBlank()) return null
        return try {
            val node = objectMapper.readTree(raw)
            val issueKey = node.get(META_ISSUE_KEY)?.asText() ?: return null
            val expectedVersion = node.get(META_EXPECTED_VERSION)?.asLong() ?: return null
            val channel = node.get(META_CHANNEL)?.asText() ?: return null
            val ts = node.get(META_TS)?.asText() ?: return null
            CompletionMetadata(issueKey, expectedVersion, channel, ts)
        } catch (e: JsonProcessingException) {
            log.warn("slack_interaction_private_metadata_parse_failed")
            null
        }
    }

    /**
     * 담당자/코멘트 모달의 `private_metadata`(JSON 문자열)에서 `issueKey`만 읽는다. [SlackModalBuilder]가
     * 이 두 모달에는 `{issueKey}`만 박제한다([SlackModalBuilder.buildAssignModal]/[buildCommentModal] KDoc —
     * OCC·원본 메시지 갱신 컨텍스트가 불필요한 비종료 액션). 필드 누락·유효하지 않은 JSON은 `null`.
     */
    @Suppress("SwallowedException") // 유효하지 않은 metadata는 null 수렴(원문·예외 미노출), parsePrivateMetadata 동형.
    private fun parseIssueKeyMetadata(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        return try {
            objectMapper.readTree(raw).get(META_ISSUE_KEY)?.asText()
        } catch (e: JsonProcessingException) {
            log.warn("slack_interaction_private_metadata_parse_failed")
            null
        }
    }

    /**
     * `view.state.values`(중첩 Map)에서 `[blockId][actionId].selected_option.value`를 안전하게 읽는다.
     * 사용자가 선택하지 않았거나 구조가 어긋나면 `null`을 반환한다(불명은 없음으로 수렴).
     */
    private fun selectedValue(
        stateValues: Map<String, Any?>,
        blockId: String,
        actionId: String,
    ): String? {
        val actionState = (stateValues[blockId] as? Map<*, *>)?.get(actionId) as? Map<*, *>
        val selectedOption = actionState?.get(SELECTED_OPTION_FIELD) as? Map<*, *>
        return selectedOption?.get(VALUE_FIELD) as? String
    }

    /**
     * `view.state.values`에서 `[blockId][actionId].selected_user`를 안전하게 읽는다(`users_select` 전용,
     * [selectedValue]의 `static_select` 구조와 달리 `selected_option` 래핑이 없다).
     */
    private fun selectedUser(
        stateValues: Map<String, Any?>,
        blockId: String,
        actionId: String,
    ): String? {
        val actionState = (stateValues[blockId] as? Map<*, *>)?.get(actionId) as? Map<*, *>
        return actionState?.get(SELECTED_USER_FIELD) as? String
    }

    /**
     * `view.state.values`에서 `[blockId][actionId].value`를 안전하게 읽는다(`plain_text_input` 전용).
     */
    private fun plainInputValue(
        stateValues: Map<String, Any?>,
        blockId: String,
        actionId: String,
    ): String? {
        val actionState = (stateValues[blockId] as? Map<*, *>)?.get(actionId) as? Map<*, *>
        return actionState?.get(VALUE_FIELD) as? String
    }

    // ── 결과/블록 조립 ──────────────────────────────────────────────────────────

    /** `response_url`이 있으면 [message]를 ephemeral 블록으로 전송한다(best-effort). */
    private fun sendEphemeral(
        responseUrl: String?,
        message: String,
    ) {
        if (responseUrl == null) return
        responseUrlClient.post(responseUrl, sectionBlocks(message))
    }

    /**
     * `{"response_action":"errors","errors":{blockId: message}}` JSON을 만든다(모달 열어둔 채 에러 표기).
     *
     * @param blockId 에러를 표기할 입력 블록 id — 완료는 `done_transition_block`, 담당자는
     *   `assignee_block`, 코멘트는 `comment_block`(각 모달의 입력 블록과 일치해야 Slack이 표기한다).
     */
    private fun responseActionErrors(
        blockId: String,
        message: String,
    ): InteractionResult.ResponseActionErrors {
        val errors =
            objectMapper.createObjectNode().apply {
                put(blockId, message)
            }
        val root =
            objectMapper.createObjectNode().apply {
                put(RESPONSE_ACTION_FIELD, RESPONSE_ACTION_ERRORS)
                set<ObjectNode>(ERRORS_FIELD, errors)
            }
        return InteractionResult.ResponseActionErrors(objectMapper.writeValueAsString(root))
    }

    /** 완료 후 원본 메시지로 교체할 blocks JSON 문자열. */
    private fun completedBlocksJson(): String = objectMapper.writeValueAsString(sectionBlocks(COMPLETED_MESSAGE))

    /** `[{"type":"section","text":{"type":"mrkdwn","text":text}}]` — 단일 안내 문구 블록 배열. */
    private fun sectionBlocks(text: String): ArrayNode =
        objectMapper.createArrayNode().apply {
            add(
                objectMapper.createObjectNode().apply {
                    put(TYPE_FIELD, SECTION_BLOCK_TYPE)
                    set<ObjectNode>(
                        TEXT_FIELD,
                        objectMapper.createObjectNode().apply {
                            put(TYPE_FIELD, MRKDWN_TEXT_TYPE)
                            put(TEXT_FIELD, text)
                        },
                    )
                },
            )
        }

    // ── 감사 로그 (best-effort) ─────────────────────────────────────────────────

    /**
     * V703 감사 로그를 append-only로 남긴다. 기록 실패는 인터랙션을 막지 않되(best-effort) 조용히
     * 삼키지 않고 WARN으로 남긴다(감사 누락 은폐 방지).
     *
     * @param actionType `COMPLETE`/`ASSIGN`/`COMMENT` — 호출부(완료/담당자/코멘트 각 handle-/execute- 계열)가
     *   자신의 액션 종류를 명시한다(PR1은 `COMPLETE` 하드코딩이었으나 PR2부터 일반화).
     */
    @Suppress("TooGenericExceptionCaught", "LongParameterList") // best-effort 기록. 6개 파라미터는 flat 감사 레코드(repository 동형).
    private fun record(
        teamId: String,
        slackUserId: String,
        btsUserId: UUID?,
        actionType: String,
        outcome: String,
        issueKey: String?,
    ) {
        try {
            interactionLogRepository.record(teamId, slackUserId, btsUserId, actionType, outcome, issueKey)
        } catch (e: RuntimeException) {
            log.warn(
                "slack_interaction_log_record_failed actionType={} outcome={} errorType={}",
                actionType,
                outcome,
                e.javaClass.simpleName,
            )
        }
    }

    /** 역매핑으로 해석한 인터랙션 행위자 컨텍스트(전이·감사 공통). btsUserId는 매핑 확정 이후라 non-null. */
    private data class InteractionActor(
        val teamId: String,
        val slackUserId: String,
        val btsUserId: UUID,
    )

    /**
     * [resolveButtonContext]가 역매핑까지 통과시킨 버튼 클릭 컨텍스트([openButtonModal]로 이어진다).
     * btsUserId는 매핑 확정 이후라 non-null.
     */
    private data class ButtonContext(
        val teamId: String,
        val slackUserId: String,
        val issueKey: String,
        val btsUserId: UUID,
    )

    /** 완료 모달 `private_metadata`에서 파싱한 제출 처리 컨텍스트(toStateKey는 상태값에서 별도로 읽는다). */
    private data class CompletionMetadata(
        val issueKey: String,
        val expectedVersion: Long,
        val channel: String,
        val ts: String,
    )

    private companion object {
        // ── action_id / callback_id / block_id (SlackModalBuilder·SlackBlockKitRenderer의 값과 반드시 일치) ──
        const val ATLAS_COMPLETE_ACTION_ID = "atlas_complete"
        const val ATLAS_COMPLETE_MODAL_CALLBACK_ID = "atlas_complete_modal"
        const val ATLAS_ASSIGN_ACTION_ID = "atlas_assign"
        const val ATLAS_ASSIGN_MODAL_CALLBACK_ID = "atlas_assign_modal"
        const val ATLAS_COMMENT_ACTION_ID = "atlas_comment"
        const val ATLAS_COMMENT_MODAL_CALLBACK_ID = "atlas_comment_modal"
        const val RESOLUTION_BLOCK_ID = "resolution_block"
        const val RESOLUTION_ACTION_ID = "resolution_select"
        const val DONE_TRANSITION_BLOCK_ID = "done_transition_block"
        const val DONE_TRANSITION_ACTION_ID = "done_transition_select"
        const val ASSIGNEE_BLOCK_ID = "assignee_block"
        const val ASSIGNEE_ACTION_ID = "assignee_select"
        const val COMMENT_BLOCK_ID = "comment_block"
        const val COMMENT_ACTION_ID = "comment_input"

        // ── private_metadata 필드 (SlackModalBuilder.privateMetadata와 일치) ──
        const val META_ISSUE_KEY = "issueKey"
        const val META_EXPECTED_VERSION = "expectedVersion"
        const val META_CHANNEL = "channel"
        const val META_TS = "ts"

        // ── state.values / Block Kit 필드 ──
        const val SELECTED_OPTION_FIELD = "selected_option"
        const val SELECTED_USER_FIELD = "selected_user"
        const val VALUE_FIELD = "value"
        const val TYPE_FIELD = "type"
        const val TEXT_FIELD = "text"
        const val SECTION_BLOCK_TYPE = "section"
        const val MRKDWN_TEXT_TYPE = "mrkdwn"
        const val RESPONSE_ACTION_FIELD = "response_action"
        const val ERRORS_FIELD = "errors"
        const val RESPONSE_ACTION_ERRORS = "errors"

        // ── V703 action_type / outcome (JdbcSlackInteractionLogRepository와 일치) ──
        const val ACTION_TYPE_COMPLETE = "COMPLETE"
        const val ACTION_TYPE_ASSIGN = "ASSIGN"
        const val ACTION_TYPE_COMMENT = "COMMENT"
        const val OUTCOME_SUCCESS = "SUCCESS"
        const val OUTCOME_UNMAPPED = "UNMAPPED"
        const val OUTCOME_PERMISSION_DENIED = "PERMISSION_DENIED"
        const val OUTCOME_CONFLICT = "CONFLICT"
        const val OUTCOME_ERROR = "ERROR"

        // 볼 수 있으나(권한 OK) 현 상태에서 완료 가능한 전이가 없음 — 오류·거부와 구분되는 감사 신호.
        const val OUTCOME_NOT_APPLICABLE = "NOT_APPLICABLE"

        // ── 사용자 노출 메시지 (내부 사정 미노출 — 이슈 존재/제목 등을 드러내지 않는다) ──
        const val ACCOUNT_LINK_REQUIRED_MESSAGE =
            "Atlas 계정이 Slack에 연결되어 있지 않습니다. `/atlas help`로 연결 방법을 확인하세요."
        const val NO_PERMISSION_MESSAGE = "권한이 없거나 이슈를 볼 수 없습니다."
        const val NO_COMPLETABLE_STATE_MESSAGE = "지금은 완료할 수 있는 상태가 아닙니다."
        const val CONFLICT_MESSAGE = "이슈가 그 사이 변경되었습니다. 다시 시도해 주세요."
        const val GENERIC_ERROR_MESSAGE = "요청을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요."
        const val COMPLETED_MESSAGE = "✅ 완료 처리됨"

        /** 담당자로 지정하려 한 Slack 사용자가 Atlas에 연결돼 있지 않을 때(배정 대상 역매핑 실패). */
        const val TARGET_NOT_LINKED_MESSAGE = "선택한 사용자가 Atlas에 연결되어 있지 않습니다."
    }
}

/**
 * 컨트롤러가 HTTP로 직렬화할 인터랙션 처리 결과.
 *
 * Slack 인터랙션 응답은 (1) 빈 200으로 모달을 닫거나 no-op 처리하거나, (2) `response_action: errors`로
 * 모달을 열어둔 채 입력 블록에 에러를 표기하는 두 가지로 갈린다. 봇 토큰 조회·모달 오픈·전이·`response_url`
 * ephemeral 전송 같은 side effect는 이미 [SlackInteractionService] 내부에서 **동기** 수행됐고, 이 값은
 * 컨트롤러가 응답 본문으로 쓸 최종 산출물만 담는다.
 */
sealed interface InteractionResult {
    /** 빈 200 — `view_submission` 성공 시 모달 닫기, `block_actions`/Unknown no-op. */
    data object AckEmpty : InteractionResult

    /**
     * `view_submission` 검증 실패 — Slack `response_action: errors` JSON. 모달은 닫히지 않고 입력 블록에
     * 에러 문구가 표시된다.
     *
     * @property json `{"response_action":"errors","errors":{...}}` 직렬화 문자열.
     */
    data class ResponseActionErrors(val json: String) : InteractionResult
}
