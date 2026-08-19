// FR-WF-01 도메인 예외 3종 — Validator 실패 / Workflow 부재 / SpEL timeout

package com.bts.workflow.domain.exception

/**
 * [com.bts.workflow.domain.spi.WorkflowValidator] 가 전환을 거부할 때 던지는 예외.
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
        "Validator '$validatorType' failed${field?.let { " on field '$it'" } ?: ""}: $reason",
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
) : RuntimeException("SpEL expression evaluation exceeded ${timeoutMillis}ms: '$expression'", cause)

/**
 * 이미 살아 있는 워크플로우가 같은 `key` 를 쓰고 있을 때 던진다. → 409
 *
 * 소프트 삭제된 워크플로우의 key 는 이 예외의 대상이 **아니다** — `V206` 이 key 유니크를
 * `WHERE deleted_at IS NULL` 부분 인덱스로 바꿔 지운 key 를 다시 쓸 수 있게 했다.
 */
class WorkflowKeyConflictException(
    val workflowKey: String,
) : RuntimeException("Workflow key already in use: '$workflowKey'")

/**
 * 다른 자원이 참조 중인 워크플로우를 지우려 할 때 던진다. → 409
 *
 * 판정 기준은 **스킴 매핑**(`workflow_scheme_issue_type_mappings`)이다. 그 FK 는 `ON DELETE RESTRICT`
 * 라 하드 삭제는 DB 가 막지만, 이 PR 의 삭제는 소프트 삭제라 DB 가 개입하지 않는다.
 * 그래서 애플리케이션이 직접 센다.
 */
class WorkflowInUseException(
    val workflowKey: String,
    val referenceCount: Int,
) : RuntimeException("Workflow '$workflowKey' is referenced by $referenceCount scheme mapping(s)")

/**
 * 편집이 잠긴 워크플로우를 고치려 할 때 던진다. → 409
 *
 * `workflows.is_locked` 는 `V205` 가 만들었다. 발행 중처럼 일시적으로 수정을 막아야 할 때 쓴다.
 */
class WorkflowLockedException(
    val workflowKey: String,
) : RuntimeException("Workflow '$workflowKey' is locked for editing")
