// PATCH /api/v1/projects/{projectIdOrKey}/lead 응답 바디 DTO — projectId + leadUserId

package com.bts.issue.project.web.dto

import com.bts.issue.project.application.ProjectLeadResult
import java.util.UUID

/**
 * 프로젝트 리드 변경 REST 응답 바디.
 *
 * [ProjectLeadResult] 를 HTTP 응답으로 변환한다.
 * [com.bts.issue.component.web.dto.ComponentResponse] 의 리드 관련 필드와 동형.
 *
 * @property projectId 대상 프로젝트 UUID.
 * @property leadUserId 지정된 리드 사용자 UUID. null 이면 리드 없음.
 */
data class ProjectLeadResponse(
    val projectId: UUID,
    val leadUserId: UUID?,
) {
    companion object {
        /**
         * [ProjectLeadResult] 도메인 결과로부터 응답 DTO 를 생성한다.
         *
         * @param result 서비스 레이어가 반환한 도메인 결과.
         * @return 변환된 [ProjectLeadResponse].
         */
        fun from(result: ProjectLeadResult): ProjectLeadResponse =
            ProjectLeadResponse(
                projectId = result.projectId,
                leadUserId = result.leadUserId,
            )
    }
}
