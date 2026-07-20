// 프로젝트 조회/수정 공용 REST 응답 DTO — id·key·name 3필드만 (게이트1 확정, YAGNI)

package com.bts.issue.project.web.dto

import com.bts.issue.project.domain.Project
import java.util.UUID

/**
 * 프로젝트 REST 응답 DTO (게이트1 확정 — id·key·name 3필드만).
 *
 * leadUserId·createdAt 등은 이 PR 범위 밖의 speculative 필드라 포함하지 않는다.
 * PR-5 에서 실제로 필요해지면 그때 추가한다(YAGNI).
 *
 * @property id 프로젝트 UUID.
 * @property key 프로젝트 key.
 * @property name 프로젝트 이름.
 */
data class ProjectResponse(
    val id: UUID,
    val key: String,
    val name: String,
    val archived: Boolean,
) {
    companion object {
        /**
         * [Project] 를 [ProjectResponse] 로 변환한다.
         *
         * @param project 조회/수정된 프로젝트(DB 저장 후 id 는 non-null).
         * @return HTTP 응답 DTO.
         */
        fun from(project: Project): ProjectResponse =
            ProjectResponse(
                id = requireNotNull(project.id) { "project.id 는 null 일 수 없다" },
                key = project.key,
                name = project.name,
                archived = project.archivedAt != null,
            )
    }
}
