// 워크플로우 도메인 예외 3종 — validator 실패, 워크플로우 미발견, SpEL 평가 타임아웃

package com.bts.workflow.domain.exception

/**
 * [com.bts.workflow.domain.spi.WorkflowValidator] 가 전이를 거부할 때 던지는 예외.
 *
 * @param validatorType 거부한 Validator 의 type 식별자. 예: "RequiredFieldValidator".
 * @param field 문제가 된 필드 이름. 특정 필드에 국한되지 않는 경우 null.
 * @param reason 사람이 읽을 수 있는 거부 사유.
 */
class WorkflowValidatorFailureException(
    val validatorType: String,
    val field: String?,
    val reason: String,
) : RuntimeException(
    if (field != null) {
        "Validator '$validatorType' rejected transition on field '$field': $reason"
    } else {
        "Validator '$validatorType' rejected transition: $reason"
    },
)

/**
 * 요청한 워크플로우 키에 해당하는 워크플로우가 없을 때 던지는 예외.
 *
 * @param workflowKey 조회를 시도한 워크플로우 키. 예: "WF-MISSING".
 */
class WorkflowNotFoundException(
    val workflowKey: String,
) : RuntimeException("Workflow not found: '$workflowKey'")

/**
 * SpEL(Spring Expression Language) 표현식 평가가 제한 시간 내에 완료되지 않을 때 던지는 예외.
 *
 * @param expression 평가에 실패한 SpEL 표현식 문자열.
 * @param timeoutMillis 허용된 최대 평가 시간 (밀리초).
 * @param cause 타임아웃의 원인 예외. 없으면 null.
 */
class WorkflowExpressionTimeoutException(
    val expression: String,
    val timeoutMillis: Long,
    cause: Throwable? = null,
) : RuntimeException("SpEL expression timed out after ${timeoutMillis}ms: '$expression'", cause)
