// PATCH /api/v1/issues/{key} 요청 바디 DTO — Jakarta Validation 어노테이션으로 입력값 검증

package com.bts.issue.adapter.inbound.rest

import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.Size

/**
 * 이슈 수정 REST 요청 바디.
 *
 * 변경 가능한 필드만 포함한다 (partial update, RFC 7396 JSON Merge Patch).
 * [expectedVersion] 은 낙관적 잠금(optimistic locking)을 위해 필수다.
 *
 * @property summary 새 이슈 제목. null 이면 변경하지 않는다 (Jakarta Bean Validation `@Pattern` 은
 *   null 을 통과시키므로 RFC 7396 시맨틱과 호환). 명시적 빈 문자열 또는 공백만으로 구성된 입력은
 *   400 거부 (PR #23 adversarial F-1 — `?: ""` 제거 후 빈 문자열 명시 입력 가드 추가).
 *   regex `^(?=.*\S).+$` = 비공백 문자 1자 이상 포함 강제. 최대 200자.
 * @property typeId 새 이슈 유형 ID. null 이면 변경하지 않는다 (RFC 7396 JSON Merge Patch).
 *   양수 필수. 존재하지 않거나 비활성 타입이면 404 ISSUE_TYPE_NOT_FOUND.
 * @property expectedVersion 읽어온 시점의 버전 값. DB 버전과 다르면 409 Version Conflict.
 */
data class UpdateIssueRequest(
    @field:Size(max = 200, message = "summary는 200자 이하여야 합니다.")
    @field:Pattern(
        regexp = "^(?=.*\\S).+$",
        message = "summary가 명시되었으면 공백이 아니어야 합니다.",
    )
    val summary: String?,
    @field:Positive(message = "typeId는 양수여야 합니다.")
    val typeId: Long? = null,
    @field:NotNull(message = "expectedVersion은 필수입니다.")
    val expectedVersion: Long,
)
