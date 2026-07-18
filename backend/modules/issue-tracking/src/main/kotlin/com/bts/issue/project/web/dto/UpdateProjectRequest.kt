// PATCH /api/v1/projects/{key} 요청 바디 DTO — name 만 다룸 (§4.2 (a)안, FR-PJ 후속)

package com.bts.issue.project.web.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

/** 프로젝트 이름 최대 길이 (CreateProjectRequest.PROJECT_NAME_MAX 와 동일한 정책값). */
private const val UPDATE_PROJECT_NAME_MAX = 255

/**
 * 프로젝트 이름 변경 REST 요청 바디 (§4.2 (a)안).
 *
 * lead 변경([ChangeProjectLeadRequest])·2FA 강제 변경([ChangeRequire2faRequest])은 이미
 * 별도 엔드포인트로 분리되어 있으므로, 이 DTO 는 name 하나만 다룬다.
 *
 * @property name 프로젝트 이름. 공백 불가, 255자 이하.
 */
data class UpdateProjectRequest(
    @field:NotBlank(message = "name은 비어 있을 수 없습니다.")
    @field:Size(max = UPDATE_PROJECT_NAME_MAX, message = "name은 255자 이하이어야 합니다.")
    val name: String,
)
