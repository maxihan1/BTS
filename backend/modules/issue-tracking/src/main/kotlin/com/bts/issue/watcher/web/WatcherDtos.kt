// 워처 REST API 요청/응답 DTO — AddWatcherRequest, WatcherSummary, WatcherListResponse (FR-WT-01)

package com.bts.issue.watcher.web

import com.fasterxml.jackson.annotation.JsonInclude
import java.util.UUID

/**
 * POST /api/v1/issues/{key}/watchers 요청 바디.
 *
 * body 없이 요청하면 null 로 역직렬화되며 self(actor 본인) 추가로 처리한다.
 * [userId] 를 명시하면 해당 사용자를 워처로 추가한다.
 *
 * @property userId 추가할 워처 사용자 UUID. null 이면 self.
 */
data class AddWatcherRequest(
    val userId: UUID? = null,
)

/**
 * 워처 단건 요약 — userId + displayName.
 *
 * [WatcherListResponse.watchers] 원소.
 *
 * @property userId 워처 사용자 UUID.
 * @property displayName 표시명.
 */
data class WatcherSummary(
    val userId: UUID,
    val displayName: String,
)

/**
 * GET /api/v1/issues/{key}/watchers 응답 바디.
 *
 * @property watchers 워처 목록.
 * @property count 총 워처 수.
 * @property isWatching 현재 actor 가 워처인지 여부.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class WatcherListResponse(
    val watchers: List<WatcherSummary>,
    val count: Int,
    val isWatching: Boolean,
)
