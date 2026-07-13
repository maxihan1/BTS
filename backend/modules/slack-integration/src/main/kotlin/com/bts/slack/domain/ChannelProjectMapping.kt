// slack_channel_project_map 테이블 매핑 도메인 VO — 프로젝트 ↔ Slack 채널 + 이벤트 필터 (FR-SL-06 Task 2)

package com.bts.slack.domain

import java.time.Instant
import java.util.UUID

/**
 * `slack_channel_project_map` 한 행에 대응하는 도메인 VO — 프로젝트가 특정 Slack 채널로 활동 피드를
 * 브로드캐스트할 때 사용할 이벤트 필터를 표현한다(FR-SL-06 결정 #3 — 매핑 키는 `projectKey`, 라우팅 시점
 * cross-BC projectKey→UUID 조회를 회피한다).
 *
 * ## 이벤트 필터 검증
 * [eventTypes] 는 이 매핑이 채널로 게시할 이벤트 유형 집합이다. 다음 두 경우 생성 시점에 즉시 거부한다.
 * - [eventTypes] 가 비어 있으면(채널로 게시할 이벤트가 하나도 없는 매핑은 무의미) 거부(EC1).
 * - [eventTypes] 에 [SlackChannelEventType] 카탈로그에 없는(notification 이 발행하지 않는) 문자열이
 *   섞여 있으면 거부(EC1 — 미지 wireValue 는 400). [SlackChannelEventType] 이 notification enum 을
 *   BC 격리로 직접 import 하지 않고 미러한 이유는 그 파일 KDoc 참조.
 *
 * @property id 매핑 식별자(uuid pk).
 * @property teamId Slack 워크스페이스 id(`T…`) — 매핑 생성 시 유일 `SlackInstall` 에서 해석.
 * @property projectKey 매핑 대상 프로젝트 키(cross-BC, BC 격리로 FK 아님).
 * @property channelId Slack 채널 id(`C…`) — 게시 대상.
 * @property channelName 채널 표시명(nullable, UI 편의용).
 * @property eventTypes 이 매핑으로 채널에 게시할 이벤트 유형(wire 문자열) 집합. 비어 있을 수 없다.
 * @property createdAt 매핑 생성 시각.
 * @property updatedAt 매핑 최종 갱신 시각.
 */
data class ChannelProjectMapping(
    val id: UUID,
    val teamId: String,
    val projectKey: String,
    val channelId: String,
    val channelName: String?,
    val eventTypes: Set<String>,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    init {
        require(eventTypes.isNotEmpty()) { EMPTY_EVENT_TYPES_MESSAGE }
        val unknown = eventTypes.filterNot { SlackChannelEventType.isKnown(it) }
        require(unknown.isEmpty()) { "$UNKNOWN_EVENT_TYPES_MESSAGE_PREFIX$unknown" }
    }

    companion object {
        /** [eventTypes] 가 빈 집합일 때의 검증 실패 메시지(EC1). */
        const val EMPTY_EVENT_TYPES_MESSAGE = "eventTypes must not be empty"

        /** [eventTypes] 에 미지 wire 문자열이 섞였을 때의 검증 실패 메시지 접두어(뒤에 미지 값 목록이 붙는다, EC1). */
        const val UNKNOWN_EVENT_TYPES_MESSAGE_PREFIX = "unknown eventTypes: "
    }
}
