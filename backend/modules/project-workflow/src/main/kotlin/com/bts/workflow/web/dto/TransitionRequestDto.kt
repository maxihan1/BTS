// 전이 요청 REST DTO — Konform 검증 + toDomain 매퍼

package com.bts.workflow.web.dto

import com.bts.shared.workflow.TransitionRequest
import io.konform.validation.Validation
import io.konform.validation.ValidationResult
import io.konform.validation.jsonschema.minLength
import io.konform.validation.jsonschema.minimum

/**
 * Konform 검증 규칙 — 모듈 수준에서 한 번만 생성해 재사용한다.
 *
 * [TransitionRequestDto.toStateKey] 는 길이 1 이상,
 * [TransitionRequestDto.version] 은 1 이상이어야 한다.
 */
private val transitionRequestDtoValidation: Validation<TransitionRequestDto> =
    Validation {
        TransitionRequestDto::toStateKey { minLength(1) }
        TransitionRequestDto::version { minimum(1) }
    }

/**
 * REST 전이 요청 DTO.
 *
 * 클라이언트가 이슈 전이를 요청할 때 HTTP 요청 바디로 전달하는 형태.
 * [toDomain] 을 통해 서비스 레이어에서 사용하는 도메인 DTO [TransitionRequest] 로 변환한다.
 *
 * 전이 동일성 식별 정책. [WorkflowTransition.key] (`from__to`) 합성 기반 매칭 —
 * ADR 2026-05-28-workflow-transition-identity-policy 참조.
 *
 * @property toStateKey 전이 목표 상태 키. 예: "IN_PROGRESS", "DONE".
 * @property fields 이슈의 커스텀 필드 스냅샷. Validator/PostAction 평가에 사용한다. 기본값 빈 Map.
 * @property version 낙관적 잠금(optimistic lock) 버전. 최솟값 1. 동시 수정 충돌 감지에 사용한다.
 */
data class TransitionRequestDto(
    val toStateKey: String,
    val fields: Map<String, Any?> = emptyMap(),
    val version: Long,
) {
    /**
     * Konform DSL 로 필드 유효성을 검사한다.
     *
     * @return [io.konform.validation.Valid] 또는 [io.konform.validation.Invalid].
     */
    fun validate(): ValidationResult<TransitionRequestDto> = transitionRequestDtoValidation(this)

    /**
     * REST DTO 를 도메인 DTO [TransitionRequest] 로 변환한다.
     *
     * 컨트롤러에서 경로 변수 / 인증 컨텍스트로부터 얻은 값을 함께 주입한다.
     *
     * @param workflowKey 적용할 워크플로우의 고유 키.
     * @param issueKey 전이 대상 이슈의 키.
     * @param fromStateKey 이슈의 현재 상태 키.
     * @param actorId 전이를 실행하는 사용자 ID.
     * @param actorRoles 실행자의 역할 집합.
     */
    fun toDomain(
        workflowKey: String,
        issueKey: String,
        fromStateKey: String,
        actorId: String,
        actorRoles: Set<String>,
    ): TransitionRequest =
        TransitionRequest(
            workflowKey = workflowKey,
            issueKey = issueKey,
            fromStateKey = fromStateKey,
            toStateKey = toStateKey,
            actorId = actorId,
            issueFields = fields,
            actorRoles = actorRoles,
            version = version,
        )
}
