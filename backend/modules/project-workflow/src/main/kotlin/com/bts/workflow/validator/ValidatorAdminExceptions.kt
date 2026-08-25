// validator 관리 API 도메인 예외 — 검증 실패(400) / 편집 불가 타입(400) / 미존재(404)

package com.bts.workflow.validator

/**
 * validator 요청 검증 실패 예외 (HTTP 400 · `WORKFLOW_VALIDATOR_INVALID`).
 *
 * 미지원 type, 필수 config 키 누락, config 값 타입 불일치, 알 수 없는 enum 값에서 발생한다.
 * 판정은 전부 [com.bts.workflow.engine.WorkflowValidatorFactory.create] dry-run 이 내리고,
 * 이 예외는 그것이 던진 [IllegalArgumentException] 을 HTTP 계약으로 옮긴 것뿐이다 —
 * 사유 목록을 여기서 다시 세지 않는다.
 *
 * @param reason 검증 실패 사유.
 * @param cause 원인 예외. 팩토리가 던진 [IllegalArgumentException].
 */
class ValidatorValidationException(
    val reason: String,
    cause: Throwable? = null,
) : RuntimeException("validator 검증 실패: $reason", cause)

/**
 * 화면에서 편집할 수 없는 validator type 을 생성·수정하려 할 때 던지는 예외
 * (HTTP 400 · `WORKFLOW_VALIDATOR_TYPE_NOT_EDITABLE`).
 *
 * [ValidatorValidationException] 과 코드를 가르는 이유는 화면이 두 상황을 다르게 안내해야 하기
 * 때문이다 — 한쪽은 설정을 고쳐 다시 내면 되고, 다른 한쪽은 무엇을 고쳐도 이 경로로는 안 된다.
 *
 * @param type 거절된 validator type 식별자.
 */
class ValidatorTypeNotEditableException(
    val type: String,
) : RuntimeException("화면에서 편집할 수 없는 validator type 입니다: '$type'")

/**
 * validator 또는 전환이 존재하지 않을 때 던지는 예외 (HTTP 404 · `WORKFLOW_VALIDATOR_NOT_FOUND`).
 *
 * transitionKey 형식 오류, 전환 미존재, 합성 키가 유일하지 않음, id 가 그 전환 소속이 아님에서 발생한다.
 * 마지막 경우까지 404 로 묶는 것이 IDOR 차단이다 — 「있긴 한데 네 것이 아니다」를 알려 주면
 * 그 응답만으로 남의 전환에 규칙이 몇 개 붙어 있는지 세어 볼 수 있다.
 *
 * @param detail 조회를 시도한 대상 상세 정보.
 */
class ValidatorNotFoundException(
    val detail: String,
) : RuntimeException("validator 또는 전환을 찾을 수 없습니다: $detail")
