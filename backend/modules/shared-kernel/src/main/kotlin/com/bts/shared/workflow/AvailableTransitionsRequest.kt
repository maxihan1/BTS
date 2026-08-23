// 가용 전환 열거 요청 DTO — 호출자 BC 가 fromState 기준 전환 목록을 조회할 때 제공

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
 * 가용 전환 열거 요청 DTO.
 *
 * 호출자 BC(바운디드 컨텍스트 — 책임 범위로 나눈 도메인 단위, 예: issue-tracking)가
 * project-workflow 에 특정 상태에서 이동 가능한 전환 목록을 요청할 때 이 DTO 를 구성해 전달한다.
 *
 * 전환의 1급 식별자는 `workflow_transitions.id` 다 —
 * ADR `docs/adr/2026-08-18-workflow-transition-id-identity.md` §D1 · §D3 참조.
 * (구 ADR `2026-05-28-workflow-transition-identity-policy` 의 「identity = (from, to)」 정책은
 * 그 ADR 이 대체했다. 같은 상태쌍에 이름만 다른 전환을 여럿 둘 수 있게 되어 2튜플로는 못 가른다.)
 *
 * @param workflowKey 적용할 워크플로우의 고유 키. 예: "DEFAULT", "BUGFIX".
 * @param fromStateKey 현재 이슈 상태 키. 예: "TODO".
 * @param issueKey 전환을 요청하는 이슈의 키. 예: "ATLAS-42".
 *   CustomExpression validator 의 `issue.key` 평가에 사용한다.
 *   actorId 와 혼동되어서는 안 된다 — 이 필드는 이슈 식별자이다.
 * @param actorId 전환을 요청하는 사용자 ID.
 * @param actorRoles 실행자의 역할 집합. 권한 기반 필터링에 사용한다.
 * @param issueFields 현재 이슈의 커스텀 필드 스냅샷. 조건부 전환 필터링에 사용한다.
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
