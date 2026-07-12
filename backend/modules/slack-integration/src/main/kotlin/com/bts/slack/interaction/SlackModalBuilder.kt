// IssueCompletionOptions 로 Slack 완료 모달 view JSON(resolution/done 전이 select) 을 조립하는 컴포넌트 (FR-SL-05 Task 7)

package com.bts.slack.interaction

import com.bts.shared.issue.DoneTransition
import com.bts.shared.issue.IssueCompletionOptions
import com.bts.shared.issue.ResolutionOption
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import org.springframework.stereotype.Component

/**
 * `atlas_complete` 버튼 클릭으로 여는 Slack "이슈 완료" 모달의 `view` payload(Block Kit JSON)를
 * 조립한다 (FR-SL-05 Task 7).
 *
 * ## private_metadata에는 toStateKey 를 담지 않는다
 * 완료 전이 실행에 필요한 `toStateKey`는 사용자가 [buildCompletionModal]이 만든 done 전이
 * `static_select`에서 실제로 선택한 값이다 — 모달을 여는 시점에 미리 박제하면(private_metadata)
 * 제출 시점에 사용자가 고른 값과 어긋날 수 있어, [SlackInteractionPayload.ViewSubmission.stateValues]
 * (제출 시점 상태)에서만 읽도록 강제한다. private_metadata에는 제출 처리에 필요한 나머지 컨텍스트
 * (이슈 키·OCC 버전·원본 메시지 채널/타임스탬프)만 담는다.
 *
 * ## resolutions 빈 목록 → 섹션 생략 (E6)
 * 프로젝트에 resolution이 구성되지 않은 경우 `static_select`에 옵션이 0개가 되어 Slack API가
 * 거부하므로, 아예 섹션을 만들지 않는다.
 *
 * @param objectMapper Block Kit JSON 직렬화용 Jackson.
 */
@Component
class SlackModalBuilder(
    private val objectMapper: ObjectMapper,
) {
    /**
     * [options]로 완료 모달 `view` payload JSON 문자열을 만든다.
     *
     * @param options 완료 옵션 스냅샷([com.bts.shared.issue.IssueCompletionOptionsPort.getCompletionOptions]).
     * @param issueKey 완료 처리 대상 이슈 키.
     * @param channel 완료 버튼이 달린 원본 DM 메시지의 채널 id(`chat.update` 갱신에 필요).
     * @param ts 완료 버튼이 달린 원본 DM 메시지의 타임스탬프(`chat.update` 갱신에 필요).
     * @return `views.open`의 `view` 파라미터에 그대로 실을 수 있는 view payload JSON 문자열.
     */
    fun buildCompletionModal(
        options: IssueCompletionOptions,
        issueKey: String,
        channel: String,
        ts: String,
    ): String {
        val blocks =
            objectMapper.createArrayNode().apply {
                if (options.resolutions.isNotEmpty()) {
                    add(resolutionInputBlock(options.resolutions))
                }
                if (options.doneTransitions.isNotEmpty()) {
                    add(doneTransitionInputBlock(options.doneTransitions))
                }
            }

        val view =
            objectMapper.createObjectNode().apply {
                put(TYPE_FIELD, MODAL_VIEW_TYPE)
                put(CALLBACK_ID_FIELD, ATLAS_COMPLETE_MODAL_CALLBACK_ID)
                set<ObjectNode>(TITLE_FIELD, plainText(MODAL_TITLE))
                set<ObjectNode>(SUBMIT_FIELD, plainText(SUBMIT_BUTTON_TEXT))
                set<ObjectNode>(CLOSE_FIELD, plainText(CLOSE_BUTTON_TEXT))
                put(PRIVATE_METADATA_FIELD, privateMetadata(issueKey, options.version, channel, ts))
                set<ArrayNode>(BLOCKS_FIELD, blocks)
            }

        return objectMapper.writeValueAsString(view)
    }

    /** `{issueKey, expectedVersion, channel, ts}` — toStateKey는 절대 포함하지 않는다(KDoc 참조). */
    private fun privateMetadata(
        issueKey: String,
        expectedVersion: Long,
        channel: String,
        ts: String,
    ): String {
        val metadata =
            objectMapper.createObjectNode().apply {
                put("issueKey", issueKey)
                put("expectedVersion", expectedVersion)
                put("channel", channel)
                put("ts", ts)
            }
        return objectMapper.writeValueAsString(metadata)
    }

    /** resolution 선택 `input` 블록 — 옵션 = [resolutions]. */
    private fun resolutionInputBlock(resolutions: List<ResolutionOption>): ObjectNode =
        inputBlock(
            blockId = RESOLUTION_BLOCK_ID,
            label = RESOLUTION_LABEL,
            actionId = RESOLUTION_ACTION_ID,
            options = resolutions.map { it.label to it.id.toString() },
        )

    /** done 전이 선택 `input` 블록 — 옵션 = [doneTransitions]. */
    private fun doneTransitionInputBlock(doneTransitions: List<DoneTransition>): ObjectNode =
        inputBlock(
            blockId = DONE_TRANSITION_BLOCK_ID,
            label = DONE_TRANSITION_LABEL,
            actionId = DONE_TRANSITION_ACTION_ID,
            options = doneTransitions.map { it.label to it.toStateKey },
        )

    /**
     * `{"type": "input", "block_id": blockId, "label": plainText(label),
     *   "element": {"type": "static_select", "action_id": actionId, "options": [...]}}`.
     *
     * @param options `표시 라벨 to value` 쌍 목록.
     */
    private fun inputBlock(
        blockId: String,
        label: String,
        actionId: String,
        options: List<Pair<String, String>>,
    ): ObjectNode {
        val element =
            objectMapper.createObjectNode().apply {
                put(TYPE_FIELD, STATIC_SELECT_TYPE)
                put(ACTION_ID_FIELD, actionId)
                set<ArrayNode>(
                    OPTIONS_FIELD,
                    objectMapper.createArrayNode().apply {
                        options.forEach { (optionLabel, optionValue) -> add(selectOption(optionLabel, optionValue)) }
                    },
                )
            }

        return objectMapper.createObjectNode().apply {
            put(TYPE_FIELD, INPUT_BLOCK_TYPE)
            put(BLOCK_ID_FIELD, blockId)
            set<ObjectNode>(LABEL_FIELD, plainText(label))
            set<ObjectNode>(ELEMENT_FIELD, element)
        }
    }

    /** `{"text": plainText(label), "value": value}` — static_select 옵션 하나. */
    private fun selectOption(
        label: String,
        value: String,
    ): ObjectNode =
        objectMapper.createObjectNode().apply {
            set<ObjectNode>(TEXT_FIELD, plainText(label))
            put(VALUE_FIELD, value)
        }

    /** `{"type": "plain_text", "text": text}`. */
    private fun plainText(text: String): ObjectNode =
        objectMapper.createObjectNode().apply {
            put(TYPE_FIELD, PLAIN_TEXT_TYPE)
            put(TEXT_FIELD, text)
        }

    private companion object {
        const val TYPE_FIELD = "type"
        const val TEXT_FIELD = "text"
        const val VALUE_FIELD = "value"
        const val BLOCKS_FIELD = "blocks"
        const val BLOCK_ID_FIELD = "block_id"
        const val LABEL_FIELD = "label"
        const val ELEMENT_FIELD = "element"
        const val OPTIONS_FIELD = "options"
        const val ACTION_ID_FIELD = "action_id"
        const val CALLBACK_ID_FIELD = "callback_id"
        const val TITLE_FIELD = "title"
        const val SUBMIT_FIELD = "submit"
        const val CLOSE_FIELD = "close"
        const val PRIVATE_METADATA_FIELD = "private_metadata"

        const val MODAL_VIEW_TYPE = "modal"
        const val PLAIN_TEXT_TYPE = "plain_text"
        const val STATIC_SELECT_TYPE = "static_select"
        const val INPUT_BLOCK_TYPE = "input"

        const val MODAL_TITLE = "이슈 완료"
        const val SUBMIT_BUTTON_TEXT = "완료"
        const val CLOSE_BUTTON_TEXT = "취소"
        const val RESOLUTION_LABEL = "해결 방법"
        const val DONE_TRANSITION_LABEL = "완료 상태"

        const val RESOLUTION_BLOCK_ID = "resolution_block"
        const val RESOLUTION_ACTION_ID = "resolution_select"
        const val DONE_TRANSITION_BLOCK_ID = "done_transition_block"
        const val DONE_TRANSITION_ACTION_ID = "done_transition_select"

        /** `view.callback_id` — [com.bts.slack.interaction] 제출 핸들러(후속 Task)가 매칭한다. */
        const val ATLAS_COMPLETE_MODAL_CALLBACK_ID = "atlas_complete_modal"
    }
}
