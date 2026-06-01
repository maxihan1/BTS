// 프로젝트 멤버 API 응답 DTO — ProjectMembership 도메인 객체를 HTTP 응답으로 변환 (FR-PM-01 Task 6)

package com.atlas.bts.identity.web.dto

import com.fasterxml.jackson.annotation.JsonProperty
import com.atlas.bts.identity.project.ProjectMembership
import java.time.Instant
import java.util.UUID

/**
 * 프로젝트 멤버 단건 응답 DTO (FR-PM-01 Task 6).
 *
 * addMember / changeRole 응답 및 listMembers 배열 항목으로 사용한다.
 * 모든 필드는 [ProjectMembership] 도메인 객체에서 직접 매핑한다.
 *
 * @param projectId 프로젝트 식별자
 * @param userId 사용자 식별자
 * @param role 역할 문자열 ("PROJECT_ADMIN" 또는 "MEMBER")
 * @param createdAt 멤버십 최초 생성 시각 (ISO-8601 UTC)
 * @param updatedAt 마지막 역할 변경 시각 (ISO-8601 UTC)
 */
data class ProjectMemberResponse(
    @JsonProperty("projectId") val projectId: UUID,
    @JsonProperty("userId") val userId: UUID,
    @JsonProperty("role") val role: String,
    @JsonProperty("createdAt") val createdAt: Instant,
    @JsonProperty("updatedAt") val updatedAt: Instant,
) {
    companion object {
        /** [ProjectMembership] 도메인 객체를 DTO로 변환한다. */
        fun from(membership: ProjectMembership): ProjectMemberResponse = ProjectMemberResponse(
            projectId = membership.projectId,
            userId = membership.userId,
            role = membership.role.name,
            createdAt = membership.createdAt,
            updatedAt = membership.updatedAt,
        )
    }
}

/**
 * POST /members body DTO (FR-PM-01 Task 6).
 *
 * @param userId 초대할 사용자 UUID
 * @param role 역할 문자열 — [com.atlas.bts.identity.project.ProjectRole.from] 으로 파싱
 */
data class AddMemberRequest(
    val userId: UUID,
    val role: String,
)

/**
 * PATCH /members/{userId} body DTO (FR-PM-01 Task 6).
 *
 * @param role 변경할 역할 문자열 — [com.atlas.bts.identity.project.ProjectRole.from] 으로 파싱
 */
data class ChangeRoleRequest(
    val role: String,
)
