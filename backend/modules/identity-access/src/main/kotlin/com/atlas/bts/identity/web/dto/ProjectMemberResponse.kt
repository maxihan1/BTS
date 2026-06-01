// 프로젝트 멤버 API 응답 DTO — ProjectMembership / ProjectMemberView를 HTTP 응답으로 변환 (FR-PM-01 Task B3)

package com.atlas.bts.identity.web.dto

import com.fasterxml.jackson.annotation.JsonProperty
import com.atlas.bts.identity.project.ProjectMembership
import com.atlas.bts.identity.project.ProjectMemberView
import java.time.Instant
import java.util.UUID

/**
 * 프로젝트 멤버 단건 응답 DTO (FR-PM-01 Task B3).
 *
 * addMember / changeRole 응답 및 listMembers 배열 항목으로 사용한다.
 * [from(ProjectMembership)] — displayName / username 없는 도메인 객체 전용.
 * [from(ProjectMemberView)] — users 조인 결과를 동봉하는 뷰 전용 (B3 C-2).
 *
 * @param projectId 프로젝트 식별자
 * @param userId 사용자 식별자
 * @param role 역할 문자열 ("PROJECT_ADMIN" 또는 "MEMBER")
 * @param createdAt 멤버십 최초 생성 시각 (ISO-8601 UTC)
 * @param updatedAt 마지막 역할 변경 시각 (ISO-8601 UTC)
 * @param displayName 사용자 표시 이름 — users 조인 결과. orphan 멤버십 또는 미설정 시 null
 * @param username 사용자 이름 — users 조인 결과. orphan 멤버십 시 null
 */
data class ProjectMemberResponse(
    @JsonProperty("projectId") val projectId: UUID,
    @JsonProperty("userId") val userId: UUID,
    @JsonProperty("role") val role: String,
    @JsonProperty("createdAt") val createdAt: Instant,
    @JsonProperty("updatedAt") val updatedAt: Instant,
    @JsonProperty("displayName") val displayName: String?,
    @JsonProperty("username") val username: String?,
) {
    companion object {
        /**
         * [ProjectMembership] 도메인 객체를 DTO로 변환한다.
         *
         * displayName / username 정보를 갖지 않으므로 두 필드는 null이다.
         * POST/PATCH 직후 도메인 반환값만 있는 경우의 내부 사용을 위해 유지한다.
         * 컨트롤러는 이 overload 대신 [from(ProjectMemberView)]를 사용해 null을 피한다.
         */
        fun from(membership: ProjectMembership): ProjectMemberResponse = ProjectMemberResponse(
            projectId = membership.projectId,
            userId = membership.userId,
            role = membership.role.name,
            createdAt = membership.createdAt,
            updatedAt = membership.updatedAt,
            displayName = null,
            username = null,
        )

        /**
         * [ProjectMemberView] 조인 결과를 DTO로 변환한다 (B3 C-2).
         *
         * users LEFT JOIN으로 조회한 displayName / username을 그대로 포함한다.
         * orphan 멤버십인 경우 두 필드는 null이다.
         */
        fun from(view: ProjectMemberView): ProjectMemberResponse = ProjectMemberResponse(
            projectId = view.projectId,
            userId = view.userId,
            role = view.role.name,
            createdAt = view.createdAt,
            updatedAt = view.updatedAt,
            displayName = view.displayName,
            username = view.username,
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
