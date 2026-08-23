// POST /api/v1/issues/{key}/transition 요청 바디 DTO — Jakarta Validation 어노테이션으로 입력값 검증

package com.bts.issue.adapter.inbound.rest

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import java.util.UUID

/**
 * 이슈 상태 전환 REST 요청 바디.
 *
 * [toStatusKey] 는 워크플로우 정의에 선언된 목표 상태 키다.
 * [expectedVersion] 은 낙관적 잠금(optimistic locking)을 위해 필수다.
 * [resolutionId] 는 DONE 상태로 전환할 때 지정하는 해결책 식별자다.
 * 비DONE 전환에서는 무시되며, 서비스 계층에서 clear 처리된다.
 *
 * [transitionId] 는 실행할 전환을 지목하는 1급 식별자다 (`workflow_transitions.id`).
 * 같은 (`from`, `to`) 상태쌍에 전환이 여럿 있을 수 있어 [toStatusKey] 만으로는 못 가르는 경우가 있다.
 * 그때 서버는 `409 AMBIGUOUS_TRANSITION` 과 후보 목록을 돌려주고, 클라이언트는 후보 하나의
 * `transitionId` 를 이 필드에 실어 재요청한다 (ADR `2026-08-18-workflow-transition-id-identity` §D3).
 *
 * @property toStatusKey 전환할 목표 상태 키. 예: "IN_PROGRESS". 공백 불가.
 * @property expectedVersion 읽어온 시점의 버전 값. DB 버전과 다르면 409 Version Conflict.
 * @property resolutionId DONE 전환 시 지정할 해결책 UUID. DONE 외 전환은 무시/clear된다.
 * @property transitionId 실행할 전환의 1급 식별자. null 이면 ([toStatusKey]) 로 후보를 찾아
 *   **정확히 1개일 때만** 실행한다. 하위호환을 위해 기본값 null 이며 **지우지 마라** —
 *   `transitionId` 를 모르는 기존 클라이언트가 전부 400 으로 떨어진다.
 */
data class TransitionIssueRequest(
    @field:NotBlank(message = "toStatusKey는 비어 있을 수 없습니다.")
    val toStatusKey: String,
    @field:NotNull(message = "expectedVersion은 필수입니다.")
    val expectedVersion: Long,
    val resolutionId: UUID? = null,
    val transitionId: UUID? = null,
)
