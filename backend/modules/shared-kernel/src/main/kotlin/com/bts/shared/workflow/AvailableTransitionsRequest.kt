// 가용 전이 열거 요청 DTO — 호출자 BC 가 fromState 기준 전이 목록을 조회할 때 제공

package com.bts.shared.workflow

import io.konform.validation.Validation
import io.konform.validation.ValidationResult
import io.konform.validation.jsonschema.minLength

/**
 * Konform 검증 규칙 — 모듈 수준에서 한 번만 생성해 재사용한다.
 */
private val availableTransitionsRequestValidation: Validation<AvailableTransitionsRequest> =
    Validation {
        AvailableTransitionsRequest::workflowKey { minLength(1) }
        AvailableTransitionsRequest::fromStateKey { minLength(1) }
        AvailableTransitionsRequest::issueKey { minLength(1) }
    }

/**
 * 가용 전이 열거 요청 DTO.
 *
 * 호출자 BC(바운디드 컨텍스트 — 책임 범위로 나눈 도메인 단위, 예: issue-tracking)가
 * project-workflow 에 특정 상태에서 이동 가능한 전이 목록을 요청할 때 이 DTO 를 구성해 전달한다.
 *
 * 전이 동일성 식별 정책. (fromStateKey, toStateKey) 합성 기반 매칭 —
 * ADR 2026-05-28-workflow-transition-identity-policy 참조.
 *
 * @param workflowKey 적용할 워크플로우의 고유 키. 예: "DEFAULT", "BUGFIX".
 * @param fromStateKey 현재 이슈 상태 키. 예: "TODO".
 * @param issueKey 전이를 요청하는 이슈의 키. 예: "ATLAS-42".
 *   CustomExpression validator 의 `issue.key` 평가에 사용한다.
 *   actorId 와 혼동되어서는 안 된다 — 이 필드는 이슈 식별자이다.
 * @param actorId 전이를 요청하는 사용자 ID.
 * @param actorRoles 실행자의 역할 집합. 권한 기반 필터링에 사용한다.
 * @param issueFields 현재 이슈의 커스텀 필드 스냅샷. 조건부 전이 필터링에 사용한다.
 */
data class AvailableTransitionsRequest(
    val workflowKey: String,
    val fromStateKey: String,
    val issueKey: String,
    val actorId: String,
    val actorRoles: Set<String>,
    val issueFields: Map<String, Any?>,
) {
    /**
     * Konform DSL 로 필드 유효성을 검사한다.
     *
     * [workflowKey] 와 [fromStateKey] 는 길이 1 이상이어야 한다.
     *
     * @return [io.konform.validation.Valid] 또는 [io.konform.validation.Invalid].
     */
    fun validate(): ValidationResult<AvailableTransitionsRequest> = availableTransitionsRequestValidation(this)
}
