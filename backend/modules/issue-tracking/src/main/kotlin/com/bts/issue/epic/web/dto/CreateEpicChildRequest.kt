// 에픽 자식 연결 요청 DTO — POST /api/v1/issues/{key}/epic-children

package com.bts.issue.epic.web.dto

import jakarta.validation.constraints.NotBlank

/**
 * 에픽 자식 연결 요청 DTO (FR-EP-01 Task 6).
 *
 * POST `/api/v1/issues/{epicKey}/epic-children` 요청 본문.
 *
 * @property childKey 에픽에 연결할 자식 이슈 키. 예: `"ATLAS-42"`. 공백 불가.
 */
data class CreateEpicChildRequest(
    @field:NotBlank(message = "childKey 는 비어 있을 수 없습니다.")
    val childKey: String,
)
