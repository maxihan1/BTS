// 전환 요청 REST DTO — Konform 검증 + toDomain 매퍼

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
 * REST 전환 요청 DTO.
 *
 * 클라이언트가 이슈 전환을 요청할 때 HTTP 요청 바디로 전달하는 형태.
 * [toDomain] 을 통해 서비스 레이어에서 사용하는 도메인 DTO [TransitionRequest] 로 변환한다.
 *
 * 전환의 1급 식별자는 `workflow_transitions.id` 다 —
 * ADR `docs/adr/2026-08-18-workflow-transition-id-identity.md` §D1 · §D3 참조.
 * (구 ADR `2026-05-28-workflow-transition-identity-policy` 의 「identity = (from, to)」 정책은
 * 그 ADR 이 대체했다. 같은 상태쌍에 이름만 다른 전환을 여럿 둘 수 있게 되어 2튜플로는 못 가른다.)
 *
 * @property toStateKey 전환 목표 상태 키. 예: "IN_PROGRESS", "DONE".
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
     * @param issueKey 전환 대상 이슈의 키.
     * @param fromStateKey 이슈의 현재 상태 키.
     * @param actorId 전환을 실행하는 사용자 ID.
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
