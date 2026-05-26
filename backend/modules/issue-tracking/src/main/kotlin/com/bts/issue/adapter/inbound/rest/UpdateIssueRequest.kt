// PATCH /api/v1/issues/{key} 요청 바디 DTO — Jakarta Validation 어노테이션으로 입력값 검증

package com.bts.issue.adapter.inbound.rest

import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size

/**
 * 이슈 수정 REST 요청 바디.
 *
 * 변경 가능한 필드만 포함한다 (partial update).
 * [expectedVersion] 은 낙관적 잠금(optimistic locking)을 위해 필수다.
 *
 * @property summary 새 이슈 제목. null 이면 변경하지 않는다. 최대 200자.
 * @property expectedVersion 읽어온 시점의 버전 값. DB 버전과 다르면 409 Version Conflict.
 */
data class UpdateIssueRequest(
    @field:Size(max = 200, message = "summary는 200자 이하여야 합니다.")
    val summary: String?,
    @field:NotNull(message = "expectedVersion은 필수입니다.")
    val expectedVersion: Long,
)
