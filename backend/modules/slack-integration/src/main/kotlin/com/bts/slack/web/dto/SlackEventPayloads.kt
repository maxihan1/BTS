// Slack Events API 원문 payload를 방어적으로 담는 DTO 3종 (FR-SL-03 Task 11)
// 파일명은 복수형(Payloads)으로 "이벤트 payload DTO 모음" 의미를 표현하고(SlackConnectionResponses.kt 선례),
// 최상위 봉투(Envelope)·내부 이벤트(InnerEvent)·링크(Link) 3개 타입이 하나의 payload 계약을 이루므로
// ktlint filename 규칙을 억제한다.
@file:Suppress("ktlint:standard:filename", "MatchingDeclarationName")

package com.bts.slack.web.dto

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

/**
 * Slack Events API 요청 바디 최상위 봉투 ([com.bts.slack.web.SlackEventsController] FR-SL-03 Task 11).
 *
 * Slack 이 실제로 보내는 필드는 이보다 훨씬 많다(`token`·`api_app_id`·`event_id`·`event_time`·
 * `authorizations` 등). 이 DTO 는 컨트롤러가 필요로 하는 필드만 선언하고 [JsonIgnoreProperties] 로
 * 나머지를 무시한다(방어적 파싱 — 알 수 없는 Slack 필드가 추가돼도 역직렬화가 깨지지 않는다).
 *
 * @property type 최상위 이벤트 종류. `url_verification`(챌린지 검증) 또는 `event_callback`(실이벤트).
 * @property challenge `type=url_verification` 일 때만 존재하는 왕복 값.
 * @property teamId `type=event_callback` 일 때 발신 워크스페이스 id(`T…`). JSON 키는 `team_id`.
 * @property event `type=event_callback` 일 때의 실제 이벤트 상세. `url_verification` 은 null.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class SlackEventEnvelope(
    val type: String?,
    val challenge: String?,
    @JsonProperty("team_id") val teamId: String?,
    val event: SlackInnerEvent?,
)

/**
 * `event_callback` 의 `event` 서브트리 ([SlackEventEnvelope.event]).
 *
 * @property type 이벤트 세부 종류(unfurl 대상은 `link_shared`만).
 * @property user 링크를 공유한 Slack 사용자 id(`event.user`). 봇 게시·메시지 편집 시 Slack 이 비워
 *   보낼 수 있어 nullable — 부재 시 처리는 [com.bts.slack.unfurl.SlackUnfurlService] 가 skip 한다.
 * @property channel unfurl 대상 메시지가 게시된 채널 id.
 * @property messageTs unfurl 대상 메시지 타임스탬프. JSON 키는 `message_ts`.
 * @property links 메시지에 붙은 링크 목록(Atlas 이슈가 아닌 링크도 섞여 온다).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class SlackInnerEvent(
    val type: String?,
    val user: String?,
    val channel: String?,
    @JsonProperty("message_ts") val messageTs: String?,
    val links: List<SlackEventLink>?,
)

/**
 * [SlackInnerEvent.links] 배열의 원소 하나.
 *
 * @property url 공유된 원문 URL. Slack 은 `domain` 필드도 함께 보내지만 컨트롤러는 URL 만 사용한다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class SlackEventLink(
    val url: String?,
)
