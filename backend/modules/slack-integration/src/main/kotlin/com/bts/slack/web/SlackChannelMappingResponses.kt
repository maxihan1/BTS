// Slack 채널↔프로젝트 매핑 CRUD 요청/응답 DTO — team_id 비노출 (FR-SL-06 Task 7)

package com.bts.slack.web

import java.time.Instant
import java.util.UUID

/**
 * `POST /api/v1/slack/channel-mappings` 요청 바디 (FR-SL-06 Task 7).
 *
 * `eventTypes` 형식/카탈로그 검증(EC1 — 빈 집합/미지 wireValue → 400)은 도메인
 * [com.bts.slack.domain.ChannelProjectMapping] 의 `init` 블록이 담당한다. 이 모듈은
 * `spring-boot-starter-validation`(Bean Validation provider)이 없어 `@field:*` 애노테이션이 무동작이므로
 * 붙이지 않는다(automation `CreateAutomationRuleRequest` 선례와 동일 판단).
 *
 * @property projectKey 매핑 대상 프로젝트 키.
 * @property channelId 게시 대상 Slack 채널 id(`C…`).
 * @property channelName 채널 표시명(nullable, UI 편의용).
 * @property eventTypes 이 매핑으로 게시할 이벤트 유형(wire 문자열) 집합. 비어 있으면 서비스가 400 으로 거부한다.
 */
data class CreateChannelMappingRequest(
    val projectKey: String,
    val channelId: String,
    val channelName: String? = null,
    val eventTypes: Set<String> = emptySet(),
)

/**
 * `PATCH /api/v1/slack/channel-mappings/{id}` 요청 바디 — null 필드는 미변경(PATCH 의미, FR-SL-06 Task 7).
 *
 * @property channelId 새 채널 id. null 이면 기존 유지.
 * @property channelName 새 채널 표시명. null 이면 기존 유지.
 * @property eventTypes 새 이벤트 필터. null 이면 기존 유지, 제공 시 도메인이 재검증한다(EC1).
 */
data class UpdateChannelMappingRequest(
    val channelId: String? = null,
    val channelName: String? = null,
    val eventTypes: Set<String>? = null,
)

/**
 * 채널 매핑 응답 뷰 (FR-SL-06 Task 7).
 *
 * **`team_id` 비노출** — 매핑 생성 시 유일 워크스페이스 설치에서 내부적으로 해석되는 값이라 응답에
 * 담지 않는다(spec §API "매핑 뷰").
 *
 * @property id 매핑 식별자.
 * @property projectKey 매핑 대상 프로젝트 키.
 * @property channelId 게시 대상 Slack 채널 id.
 * @property channelName 채널 표시명(nullable).
 * @property eventTypes 이 매핑으로 게시할 이벤트 유형(wire 문자열) 목록 — 결정적 직렬화를 위해 정렬된다.
 * @property createdAt 매핑 생성 시각(ISO-8601 직렬화).
 * @property updatedAt 매핑 최종 갱신 시각(ISO-8601 직렬화).
 */
data class ChannelMappingResponse(
    val id: UUID,
    val projectKey: String,
    val channelId: String,
    val channelName: String?,
    val eventTypes: List<String>,
    val createdAt: Instant,
    val updatedAt: Instant,
)
