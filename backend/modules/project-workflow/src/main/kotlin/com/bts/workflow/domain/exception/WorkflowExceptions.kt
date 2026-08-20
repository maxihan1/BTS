// FR-WF-01 도메인 예외 3종 — Validator 실패 / Workflow 부재 / SpEL timeout

package com.bts.workflow.domain.exception

import java.util.UUID

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

/**
 * 상태 편성 요청이 규칙을 어겼을 때 던진다. → 400
 *
 * 세 가지를 한 예외로 묶는다 — 순서 요청이 그 워크플로우의 상태 전부를 담지 않았을 때,
 * 남의 상태가 섞였을 때, 마지막 상태를 빼려 할 때. 전부 **요청이 잘못된** 경우다.
 */
class WorkflowStatusCompositionException(
    val workflowKey: String,
    val reason: String,
) : RuntimeException("Invalid status composition for workflow '$workflowKey': $reason")

/**
 * 이슈가 쓰고 있는 상태를 워크플로우에서 빼려 할 때 던진다. → 409
 *
 * 빼면 그 이슈들이 「워크플로우에 없는 상태」에 남는다. 일괄 이관은 로드맵 PR 10 의 마법사가 한다.
 */
class WorkflowStatusInUseException(
    val workflowKey: String,
    val statusKey: String,
    val issueCount: Long,
) : RuntimeException("Status '$statusKey' in workflow '$workflowKey' is used by $issueCount issue(s)")

/**
 * 워크플로우 쓰기 요청의 입력이 규칙을 어겼을 때 던진다. → 400
 *
 * ### 왜 `IllegalArgumentException` 을 쓰지 않는가
 * `WorkflowExceptionHandler` 는 `@RestControllerAdvice` 에 **스코프가 없어 전역**이다.
 * 거기에 `IllegalArgumentException` 핸들러를 달면 **다른 BC 의 `require()` 실패까지**
 * 400 으로 둔갑한다(`agile-planning` 의 도메인 invariant 등). 500 이어야 할 서버 결함이
 * 400 으로 보이면 장애 대응이 엉뚱한 곳을 판다.
 *
 * 그래서 이 BC 전용 예외를 따로 둔다 — 전역 advice 라도 **이 타입만** 잡는다.
 */
class WorkflowInvalidRequestException(
    val workflowKey: String,
    val reason: String,
) : RuntimeException("Invalid workflow request for '$workflowKey': $reason")

/**
 * 모호 전환 후보 1건.
 *
 * 예외와 409 응답이 함께 쓰는 최소 식별 정보다. 호출자는 [transitionId] 를 다시 실어 재요청하고,
 * [name] 으로 사람이 둘을 구분한다.
 *
 * @property transitionId 전환 1급 식별자 (`workflow_transitions.id`).
 * @property name 사람 친화 표시 라벨. 예: "조건부 승인".
 */
data class TransitionCandidate(
    val transitionId: UUID,
    val name: String,
)

/**
 * `transitionId` 없이 도착 상태만으로 전환을 요청했는데 후보가 둘 이상일 때 던진다. → 409
 *
 * ### 왜 예외인가 (결정 D-2)
 * `TransitionResult` 는 sealed interface 이고 issue-tracking 이 exhaustive `when` 으로 받는다.
 * 케이스를 더하면 그 BC 가 컴파일 실패한다 = cross-BC 프로덕션 변경. 전환 해석 실패는 이미
 * 예외 경로이므로([WorkflowNotFoundException]) 모호성도 같은 층에서 예외로 낸다.
 * ADR `docs/adr/2026-08-18-workflow-transition-id-identity.md` · spec FR-WF-05 §결정 D-2.
 *
 * ### 이 예외를 catch 해 폴백하지 마라
 * `WorkflowTransitionPort.plan` 이 `@Transactional(propagation = MANDATORY)` 라 이 예외는 호출자의
 * 공유 트랜잭션을 rollback-only 로 마킹한다. 삼키고 진행하면 커밋 시점에
 * `UnexpectedRollbackException` 으로 500 이 된다. 굳이 하려면 `REQUIRES_NEW` 격리 빈이 필요하다.
 *
 * @param workflowKey 모호성이 발생한 워크플로우 키.
 * @param candidates 조건을 만족하는 전환 후보 전량. 호출자에게 그대로 돌려준다.
 */
class AmbiguousTransitionException(
    val workflowKey: String,
    val candidates: List<TransitionCandidate>,
) : RuntimeException(
        "Ambiguous transition in workflow '$workflowKey': ${candidates.size} candidates match",
    )
