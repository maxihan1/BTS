// POST /api/v1/issues 요청 바디 DTO — Jakarta Validation 어노테이션으로 입력값 검증

package com.bts.issue.adapter.inbound.rest

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.Size
import java.util.UUID

/**
 * 이슈 생성 REST 요청 바디.
 *
 * Jakarta Bean Validation 으로 입력값을 검증한다 (DEVELOPMENT.md — Kotlin prefix 어노테이션 필수).
 *
 * @property projectKey 이슈를 생성할 프로젝트 키. 공백 불가.
 * @property typeId 이슈 유형 id (issue_types.id BIGINT). null 이면 컨트롤러에서 task fallback 처리.
 *   클라이언트가 양수를 전달하면 해당 타입으로 생성한다.
 * @property summary 이슈 제목. 공백 불가, 최대 200자.
 * @property componentIds 이슈에 연결할 컴포넌트 UUID 목록. 생략 시 빈 목록으로 처리한다 (FR-CM-03).
 */
data class CreateIssueRequest(
    @field:NotBlank(message = "projectKey는 비어 있을 수 없습니다.")
    val projectKey: String,
    @field:Positive(message = "typeId 는 양수여야 합니다.")
    val typeId: Long? = null,
    @field:NotBlank(message = "summary는 비어 있을 수 없습니다.")
    @field:Size(max = 200, message = "summary는 200자 이하여야 합니다.")
    val summary: String,
    val componentIds: List<UUID> = emptyList(),
)
