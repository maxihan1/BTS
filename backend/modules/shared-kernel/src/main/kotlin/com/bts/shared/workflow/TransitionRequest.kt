// 워크플로우 전이 요청 DTO — 호출자 BC 가 제공

package com.bts.shared.workflow

import io.konform.validation.Validation
import io.konform.validation.ValidationResult
import io.konform.validation.jsonschema.minLength
import io.konform.validation.jsonschema.minimum

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
 * 워크플로우 전이 요청 DTO.
 *
 * 호출자 BC(바운디드 컨텍스트 — 책임 범위로 나눈 도메인 단위, 예: issue-tracking)가
 * project-workflow 에 전이를 요청할 때 이 DTO 를 구성해 전달한다.
 *
 * @param workflowKey 적용할 워크플로우의 고유 키. 예: "DEFAULT", "BUGFIX".
 * @param issueKey 전이 대상 이슈의 키. 예: "BTS-1".
 * @param fromStateKey 현재 이슈 상태 키. 예: "TODO".
 * @param toStateKey 전이 목표 상태 키. 예: "IN_PROGRESS".
 * @param actorId 전이를 실행하는 사용자 ID.
 * @param issueFields 현재 이슈의 커스텀 필드 스냅샷. Validator/PostAction 평가에 사용한다.
 * @param actorRoles 실행자의 역할 집합. 권한 기반 Validator 에서 사용한다.
 * @param version 낙관적 잠금(optimistic lock) 버전. 최소값 1. 동시 수정 충돌 감지에 사용한다.
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
