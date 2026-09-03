// 프로젝트 활동 피드 REST 응답 DTO — 기존 ChangeItemResponse 재사용

package com.bts.issue.summary.web.dto

import com.bts.issue.adapter.inbound.rest.ChangeItemResponse
import com.bts.issue.summary.domain.ProjectActivityEntry
import com.fasterxml.jackson.annotation.JsonInclude
import java.time.Instant
import java.util.UUID

/**
 * 프로젝트 활동 피드 응답 DTO.
 *
 * @property entries 최신순 활동 항목.
 */
data class ProjectActivityResponse(
    val entries: List<ProjectActivityEntryResponse>,
) {
    companion object {
        /**
         * 도메인 [ProjectActivityEntry] 목록을 응답 DTO 로 변환한다.
         *
         * @param entries 서비스가 조립한 활동 항목들.
         * @return JSON 직렬화 가능한 응답 DTO.
         */
        fun from(entries: List<ProjectActivityEntry>): ProjectActivityResponse =
            ProjectActivityResponse(entries.map(ProjectActivityEntryResponse::from))
    }
}

/**
 * 활동 피드 항목 하나 — 변경 그룹 하나에 대응한다.
 *
 * 변경 항목은 이슈 단건 changelog 와 같은 [ChangeItemResponse] 를 **재사용**한다. 모양이 같은 DTO 를
 * 하나 더 만들면 필드가 추가될 때 한쪽만 늘어난다.
 *
 * @property issueKey 기록 시점 이슈 키. 프로젝트 피드는 줄마다 이슈를 밝혀야 한다.
 * @property actorId 변경 주체 UUID. 시스템 자동 변경이면 키가 응답에서 빠진다.
 * @property actorName 표시명. 조회 실패 시 키가 빠진다.
 * @property createdAt 변경 발생 시각(ISO-8601 UTC).
 * @property items 변경 항목 목록.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class ProjectActivityEntryResponse(
    val issueKey: String,
    val actorId: UUID?,
    val actorName: String?,
    val createdAt: Instant,
    val items: List<ChangeItemResponse>,
) {
    companion object {
        fun from(entry: ProjectActivityEntry): ProjectActivityEntryResponse =
            ProjectActivityEntryResponse(
                issueKey = entry.issueKey,
                actorId = entry.actorId,
                actorName = entry.actorName,
                createdAt = entry.createdAt,
                items = entry.items.map { ChangeItemResponse.from(it) },
            )
    }
}
