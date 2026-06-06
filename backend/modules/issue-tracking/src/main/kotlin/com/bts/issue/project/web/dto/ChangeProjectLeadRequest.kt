// PATCH /api/v1/projects/{projectIdOrKey}/lead 요청 바디 DTO — 리드 지정/해제

package com.bts.issue.project.web.dto

import java.util.UUID

/**
 * 프로젝트 리드 변경 REST 요청 바디.
 *
 * /lead 전용 서브리소스. 2-state: UUID = 지정, null = 해제.
 * [com.bts.issue.component.web.dto.ChangeComponentLeadRequest] 와 동형 패턴.
 *
 * @property leadUserId 새 리드 사용자 UUID. null 이면 리드 해제.
 */
data class ChangeProjectLeadRequest(
    val leadUserId: UUID? = null,
)
