// Issue REST 응답 DTO — Issue Aggregate를 REST 레이어에서 직렬화 가능한 형태로 변환

package com.bts.issue.adapter.inbound.rest

import com.bts.issue.domain.Issue
import java.time.Instant
import java.util.UUID

/**
 * 이슈 단건 REST 응답 DTO.
 *
 * [Issue] 도메인 Aggregate를 외부 API 응답 형식으로 변환한다.
 * 도메인 VO([com.bts.issue.domain.IssueId], [com.bts.issue.domain.ActorId] 등)는
 * REST 레이어에서 원시 타입(UUID, String)으로 노출한다.
 *
 * @property key 이슈 키 문자열. 예: `"ATLAS-42"`
 * @property id 이슈 내부 식별자 UUID.
 * @property projectKey 이슈가 속한 프로젝트 키. 예: `"ATLAS"`
 * @property summary 이슈 제목.
 * @property currentStateKey 현재 워크플로우 상태 키. 예: `"OPEN"`
 * @property reporterId 이슈를 생성한 행위자의 UUID.
 * @property version 낙관적 잠금 버전.
 * @property createdAt 이슈 생성 시각.
 * @property updatedAt 이슈 마지막 수정 시각.
 */
data class IssueResponse(
    val key: String,
    val id: UUID,
    val projectKey: String,
    val summary: String,
    val currentStateKey: String,
    val reporterId: UUID,
    val version: Long,
    val createdAt: Instant?,
    val updatedAt: Instant?,
) {
    companion object {
        /**
         * [Issue] Aggregate와 프로젝트 키를 받아 [IssueResponse] DTO를 생성한다.
         *
         * @param issue 변환할 이슈 Aggregate.
         * @param projectKey 이슈가 속한 프로젝트 키 문자열.
         */
        fun from(
            issue: Issue,
            projectKey: String,
        ): IssueResponse = IssueResponse(
            key = issue.key.value,
            id = issue.id.value,
            projectKey = projectKey,
            summary = issue.summary,
            currentStateKey = issue.currentStateKey,
            reporterId = issue.reporterId.value,
            version = issue.version,
            createdAt = issue.createdAt,
            updatedAt = issue.updatedAt,
        )
    }
}
