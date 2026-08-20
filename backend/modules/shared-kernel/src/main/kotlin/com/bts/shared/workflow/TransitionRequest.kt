// 워크플로우 전환 요청 DTO — 호출자 BC 가 제공

package com.bts.shared.workflow

import io.konform.validation.Validation
import io.konform.validation.ValidationResult
import io.konform.validation.jsonschema.minLength
import io.konform.validation.jsonschema.minimum
import java.util.UUID

/**
 * Konform 검증 규칙 — 모듈 수준에서 한 번만 생성해 재사용한다.
 *
 * Konform 은 Kotlin-native 선언형 검증 라이브러리다. `Validation<T> { }` 블록 안에
 * 필드별 규칙을 선언하면 입력 객체를 [ValidationResult] 로 평가한다.
 * - [io.konform.validation.Valid] — 모든 규칙 통과
 * - [io.konform.validation.Invalid] — 위반된 규칙 목록 포함
 */
private val transitionRequestValidation: Validation<TransitionRequest> =
    Validation {
        TransitionRequest::workflowKey { minLength(1) }
        TransitionRequest::issueKey { minLength(1) }
        TransitionRequest::fromStateKey { minLength(1) }
        TransitionRequest::toStateKey { minLength(1) }
        TransitionRequest::actorId { minLength(1) }
        TransitionRequest::version { minimum(1) }
    }

/**
 * 워크플로우 전환 요청 DTO.
 *
 * 호출자 BC(바운디드 컨텍스트 — 책임 범위로 나눈 도메인 단위, 예: issue-tracking)가
 * project-workflow 에 전환을 요청할 때 이 DTO 를 구성해 전달한다.
 *
 * 전환 동일성 식별 정책. 1급 식별자는 전환 ID 다 —
 * ADR `docs/adr/2026-08-18-workflow-transition-id-identity.md` §D3 참조.
 * [transitionId] 가 오면 그것으로 지목 실행하고, 없으면 (`fromStateKey`, `toStateKey`) 후보가
 * **정확히 1개일 때만** 실행한다. 2개 이상이면 409 `AMBIGUOUS_TRANSITION` 이다.
 *
 * @param workflowKey 적용할 워크플로우의 고유 키. 예: "DEFAULT", "BUGFIX".
 * @param issueKey 전환 대상 이슈의 키. 예: "BTS-1".
 * @param fromStateKey 현재 이슈 상태 키. 예: "TODO".
 * @param toStateKey 전환 목표 상태 키. 예: "IN_PROGRESS".
 * @param actorId 전환을 실행하는 사용자 ID.
 * @param issueFields 현재 이슈의 커스텀 필드 스냅샷. Validator/PostAction 평가에 사용한다.
 * @param actorRoles 실행자의 역할 집합. 권한 기반 Validator 에서 사용한다.
 * @param version 낙관적 잠금(optimistic lock) 버전. 최소값 1. 동시 수정 충돌 감지에 사용한다.
 * @param transitionId 실행할 전환을 지목하는 1급 식별자 (`workflow_transitions.id`). null 이면
 *   ([fromStateKey], [toStateKey]) 로 후보를 찾는다.
 *   **기본값 null 을 지우지 마라** — issue-tracking 등 다른 BC 의 기존 호출부가 전부 컴파일 실패한다
 *   (spec FR-WF-05 N2 — cross-BC 프로덕션 0줄). `TransitionRequestTest` 의 8파라미터 호출문이 그 가드다.
 */
data class TransitionRequest(
    val workflowKey: String,
    val issueKey: String,
    val fromStateKey: String,
    val toStateKey: String,
    val actorId: String,
    val issueFields: Map<String, Any?>,
    val actorRoles: Set<String>,
    val version: Long,
    val transitionId: UUID? = null,
) {
    /**
     * Konform DSL 로 필드 유효성을 검사한다.
     *
     * 모든 문자열 키 필드는 길이 1 이상이어야 하고 [version] 은 1 이상이어야 한다.
     *
     * @return [io.konform.validation.Valid] 또는 [io.konform.validation.Invalid].
     */
    fun validate(): ValidationResult<TransitionRequest> = transitionRequestValidation(this)
}
