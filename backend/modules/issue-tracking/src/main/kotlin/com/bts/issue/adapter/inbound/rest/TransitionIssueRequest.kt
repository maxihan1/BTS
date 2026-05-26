// POST /api/v1/issues/{key}/transition 요청 바디 DTO — Jakarta Validation 어노테이션으로 입력값 검증

package com.bts.issue.adapter.inbound.rest

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull

/**
 * 이슈 상태 전이 REST 요청 바디.
 *
 * [toStatusKey] 는 워크플로우 정의에 선언된 목표 상태 키다.
 * [expectedVersion] 은 낙관적 잠금(optimistic locking)을 위해 필수다.
 *
 * @property toStatusKey 전이할 목표 상태 키. 예: "IN_PROGRESS". 공백 불가.
 * @property expectedVersion 읽어온 시점의 버전 값. DB 버전과 다르면 409 Version Conflict.
 */
data class TransitionIssueRequest(
    @field:NotBlank(message = "toStatusKey는 비어 있을 수 없습니다.")
    val toStatusKey: String,
    @field:NotNull(message = "expectedVersion은 필수입니다.")
    val expectedVersion: Long,
)
