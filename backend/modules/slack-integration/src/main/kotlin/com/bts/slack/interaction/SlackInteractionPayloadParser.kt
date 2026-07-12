// Slack 인터랙티브 payload 원문 JSON을 SlackInteractionPayload로 파싱 (FR-SL-05 Task 5)
@file:Suppress("ktlint:standard:filename", "MatchingDeclarationName")

package com.bts.slack.interaction

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Slack `POST /slack/interactions`가 보내는 `payload` 필드(JSON 문자열)를 [SlackInteractionPayload]로
 * 파싱한다.
 *
 * ## 방어적 파싱 — Slack 필드 방대함 (`SlackEventEnvelope` 선례 동형)
 * Slack이 실제로 보내는 필드는 이보다 훨씬 많다(`api_app_id`·`token`·`container`·`enterprise` 등).
 * 이 파서는 [SlackInteractionPayload]가 필요로 하는 필드만 [RawEnvelope]로 옮겨 담고, [JsonIgnoreProperties]
 * 로 나머지를 무시한다. 개별 필드 누락(예: `channel` 부재)은 예외를 던지지 않고 null/빈 값으로 수렴하며,
 * 유효하지 않은 JSON 자체는 [SlackInteractionPayload.Unknown]으로 수렴한다(호출자가 200 no-op 처리).
 *
 * ## raw 보존 — 해석은 소비자 책임
 * `view.private_metadata`(JSON 문자열)와 `view.state.values`(중첩 Map)는 이 파서가 해석하지 않고
 * 그대로 옮겨 담기만 한다. 실제 의미 해석은 [SlackInteractionPayload] KDoc 참조 — 후속 오케스트레이션
 * 서비스의 책임이다.
 *
 * ## ObjectMapper — Spring 자동구성 빈 재사용
 * 신규 ObjectMapper를 만들지 않고 Spring Boot가 자동구성한 빈을 주입받는다(`SlackEventsController` 선례).
 *
 * @param objectMapper JSON 파싱에 사용하는 Jackson 매퍼(Spring 자동 구성 빈).
 */
@Component
class SlackInteractionPayloadParser(
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Slack 인터랙티브 payload 원문(JSON 문자열)을 파싱한다.
     *
     * @param rawJson `payload` 필드 원문 JSON 문자열.
     * @return `type=block_actions` → [SlackInteractionPayload.BlockActions],
     *   `type=view_submission` → [SlackInteractionPayload.ViewSubmission], 그 외(알 수 없는 타입·`type`
     *   부재·유효하지 않은 JSON) → [SlackInteractionPayload.Unknown].
     */
    fun parse(rawJson: String): SlackInteractionPayload {
        val envelope = readEnvelope(rawJson) ?: return SlackInteractionPayload.Unknown
        return when (envelope.type) {
            TYPE_BLOCK_ACTIONS -> toBlockActions(envelope)
            TYPE_VIEW_SUBMISSION -> toViewSubmission(envelope)
            else -> SlackInteractionPayload.Unknown
        }
    }

    /**
     * 원문을 [RawEnvelope]로 역직렬화한다.
     *
     * @return 역직렬화 결과. 유효한 JSON이 아니면 `null`(무시 — 원문·예외 message는 로그에 남기지 않는다).
     */
    @Suppress("SwallowedException") // 유효하지 않은 JSON은 Unknown 으로 수렴 — 원문·예외 message 는 노출하지 않는다
    private fun readEnvelope(rawJson: String): RawEnvelope? =
        try {
            objectMapper.readValue(rawJson, RawEnvelope::class.java)
        } catch (e: JsonProcessingException) {
            log.warn("slack_interaction_payload_parse_failed")
            null
        }

    private fun toBlockActions(envelope: RawEnvelope): SlackInteractionPayload.BlockActions =
        SlackInteractionPayload.BlockActions(
            userId = envelope.user?.id,
            teamId = envelope.team?.id,
            triggerId = envelope.triggerId,
            responseUrl = envelope.responseUrl,
            channel = envelope.channel?.id,
            messageTs = envelope.message?.ts,
            actions = envelope.actions.orEmpty().map { it.toAction() },
        )

    private fun toViewSubmission(envelope: RawEnvelope): SlackInteractionPayload.ViewSubmission =
        SlackInteractionPayload.ViewSubmission(
            userId = envelope.user?.id,
            teamId = envelope.team?.id,
            callbackId = envelope.view?.callbackId,
            privateMetadata = envelope.view?.privateMetadata,
            stateValues = envelope.view?.state?.values.orEmpty(),
        )

    private fun RawAction.toAction() = SlackInteractionPayload.BlockActions.Action(actionId = actionId, value = value)

    private companion object {
        const val TYPE_BLOCK_ACTIONS = "block_actions"
        const val TYPE_VIEW_SUBMISSION = "view_submission"
    }
}

/**
 * [SlackInteractionPayloadParser] 내부 파싱 전용 원문 봉투. Slack 원문 JSON 최상위 형태를 그대로 반영한다.
 *
 * @property type 최상위 인터랙티브 종류(`block_actions`/`view_submission`/그 외).
 * @property user payload를 발생시킨 Slack 사용자.
 * @property team 발신 워크스페이스.
 * @property triggerId `block_actions`의 모달 오픈용 1회용 토큰. JSON 키는 `trigger_id`.
 * @property responseUrl `block_actions`의 지연 응답용 웹훅 URL. JSON 키는 `response_url`.
 * @property channel `block_actions` 원본 메시지 채널.
 * @property message `block_actions` 원본 메시지.
 * @property actions `block_actions`의 클릭된 액션 목록.
 * @property view `view_submission`의 모달 상세.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
private data class RawEnvelope(
    val type: String?,
    val user: RawUser?,
    val team: RawTeam?,
    @JsonProperty("trigger_id") val triggerId: String?,
    @JsonProperty("response_url") val responseUrl: String?,
    val channel: RawChannel?,
    val message: RawMessage?,
    val actions: List<RawAction>?,
    val view: RawView?,
)

/** [RawEnvelope.user] — Slack 사용자 식별자만 필요하다. */
@JsonIgnoreProperties(ignoreUnknown = true)
private data class RawUser(
    val id: String?,
)

/** [RawEnvelope.team] — 워크스페이스 식별자만 필요하다. */
@JsonIgnoreProperties(ignoreUnknown = true)
private data class RawTeam(
    val id: String?,
)

/** [RawEnvelope.channel] — 채널 식별자만 필요하다. */
@JsonIgnoreProperties(ignoreUnknown = true)
private data class RawChannel(
    val id: String?,
)

/** [RawEnvelope.message] — 메시지 타임스탬프만 필요하다. */
@JsonIgnoreProperties(ignoreUnknown = true)
private data class RawMessage(
    val ts: String?,
)

/** [RawEnvelope.actions] 배열 원소. JSON 키는 `action_id`. */
@JsonIgnoreProperties(ignoreUnknown = true)
private data class RawAction(
    @JsonProperty("action_id") val actionId: String?,
    val value: String?,
)

/** [RawEnvelope.view] — `view_submission`의 모달 상세. JSON 키는 `callback_id`/`private_metadata`. */
@JsonIgnoreProperties(ignoreUnknown = true)
private data class RawView(
    @JsonProperty("callback_id") val callbackId: String?,
    @JsonProperty("private_metadata") val privateMetadata: String?,
    val state: RawState?,
)

/** [RawView.state] — 모달 입력 블록별 사용자 선택값. 해석 없이 raw Map으로 보존한다. */
@JsonIgnoreProperties(ignoreUnknown = true)
private data class RawState(
    val values: Map<String, Any?>?,
)
