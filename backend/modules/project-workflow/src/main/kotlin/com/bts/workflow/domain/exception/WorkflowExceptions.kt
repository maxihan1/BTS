// project-workflow 도메인 예외 11종 + 전환 후보 값 객체 1종 (FR-WF-01·04·05)

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
 * 워크플로우는 있으나 그 **초안**이 없다 — 404.
 *
 * [WorkflowInvalidRequestException] 으로 접으면 400 이 나가고 프론트 표가 그것을 「요청 내용이
 * 올바르지 않습니다」라는 범용 문구로 옮긴다 — 「초안이 없다」가 「요청이 잘못됐다」로 보이고,
 * 404 를 기대해 분기하는 클라이언트는 그 분기에 영영 닿지 못한다.
 *
 * @property workflowKey 초안이 없는 워크플로우 키.
 */
class WorkflowDraftNotFoundException(
    val workflowKey: String,
) : RuntimeException("Draft not found for workflow '$workflowKey'")

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
    cause: Throwable? = null,
) : RuntimeException("Invalid workflow request for '$workflowKey': $reason", cause)

/**
 * 상태 이관 요청의 매핑이 규칙을 어겼을 때 던진다. → 400 `WORKFLOW_MIGRATION_INVALID_MAPPING`
 *
 * ### 왜 코드를 하나로 모으는가
 * 위반 축은 여럿이다 — 빠지지 않는 출발지 · 초안에 없는 도착지 · live 편성에 없는 도착지 ·
 * 빈 목록 · 중복 출발지 · 범위 없음 · 형제 워크플로우 · 상한 초과. 축마다 코드를 따로 내면
 * 프론트의 **양방향 차집합 가드**(`workflow-admin-error.test.ts`)가 축 수만큼 행을 요구하고,
 * 축이 하나 늘 때마다 프론트가 red 로 막힌다. 어느 축인지는 [reason] 이 싣는다 — 사람이 읽을
 * 곳은 메시지지 코드가 아니다.
 *
 * ### 왜 [WorkflowInvalidRequestException] 을 재사용하지 않는가
 * 그쪽은 **초안 자체**가 틀렸다는 뜻이라 화면이 편집기로 돌려보낸다. 이 예외는 초안은 멀쩡하고
 * **이관 매핑만** 틀린 것이라 화면이 이관 모달에 머물러야 한다. 둘을 한 코드로 묶으면 화면이
 * 어디로 보낼지 고를 수 없다.
 *
 * @property workflowKey 이관을 요청받은 워크플로우 키.
 * @property reason 어느 축이 왜 막혔는지. 응답 `detail` 로 그대로 나간다.
 */
class WorkflowMigrationInvalidMappingException(
    val workflowKey: String,
    val reason: String,
) : RuntimeException("Invalid status migration mapping for '$workflowKey': $reason")

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

/**
 * 전환 정의가 「워크플로우당 최초 전환 1개」 규칙과 부딪힐 때 던진다. → 409
 *
 * 두 자리에서 난다 — 최초(`INITIAL`) 전환이 이미 있는데 하나 더 만들려 할 때(spec E2), 그리고
 * 하나뿐인 최초 전환을 지우려 할 때(spec E5). 둘 다 「지금 상태와 요청이 부딪힌다」라서 409 이고,
 * 어느 쪽인지는 [reason] 이 사람 말로 갈라 준다.
 *
 * ### 왜 `ResponseStatusException` 이 아닌가 (되돌리지 마라)
 * 이전 구현은 이 예외가 스스로 상태 코드를 지는
 * [org.springframework.web.server.ResponseStatusException] 이었다. 그러면 본문이 이 BC 표준
 * `{ "error": { "code", "message" } }` 가 아니라 **빈 본문 + `sendError` 경유 Boot 기본 오류 페이지**
 * 로 나간다(실측 — MockMvc standalone 에서 본문 길이 0, `json can not be null or empty`).
 * 워크플로우 편집 화면 하나가 두 가지 오류 형식을 다뤄야 해서 도메인 예외로 되돌리고 매핑은
 * [com.bts.workflow.web.TransitionConflictExceptionHandler] 에 맡긴다.
 *
 * @param workflowKey 충돌이 난 워크플로우 키. 응답에는 싣지 않고 로그로만 남긴다.
 * @param reason 사람이 읽을 수 있는 충돌 사유. 그대로 응답 `message` 가 된다.
 */
class TransitionConflictException(
    val workflowKey: String,
    val reason: String,
) : RuntimeException("Transition conflict in workflow '$workflowKey': $reason")

/**
 * 초안을 뜬 뒤 다른 세션이 먼저 발행해 `base_version` 이 어긋났을 때 던진다. → 409
 *
 * ### 왜 덮어쓰지 않는가
 * 초안은 「그 시점의 정의」를 기준으로 편집된 것이다. 그 사이 남이 상태를 지웠다면 지금 초안을
 * 그대로 발행하는 것은 남의 편집을 말없이 되돌리는 일이 된다. 관리자에게 다시 뜨게 하는 편이
 * 잃는 것이 적다.
 *
 * @property workflowKey 대상 워크플로우 키.
 * @property expected 초안이 들고 있던 버전.
 * @property actual 지금 DB 의 버전.
 */
class WorkflowVersionConflictException(
    val workflowKey: String,
    val expected: Long,
    val actual: Long,
) : RuntimeException("Workflow '$workflowKey' version conflict: expected $expected but was $actual")

/**
 * 발행으로 빠지는 상태에 이슈가 남아 있는데 이관 매핑이 오지 않았을 때 던진다. → 409
 *
 * ### Jira Cloud 와 같은 방식이다
 * Jira 는 발행을 막지 않는다 — 발행 요청이 `statusMappings` 를 **함께 받고**, 매핑이 필요한데
 * 없으면 화면이 모달로 묻는다(「In the modal that shows up, choose new statuses in the New status
 * column」 · support.atlassian.com, 2026-08-26 조회). 이 예외의 [pending] 이 그 모달을 그릴 재료다.
 *
 * 매핑이 없는 채로 발행을 허용하면 이슈가 「워크플로우에 없는 상태」를 가리키게 되고, 그 이슈는
 * 이후 어떤 전환도 계산할 수 없다. FR-WF-07 이 닫으려는 결함이 정확히 그것이다.
 *
 * @property workflowKey 대상 워크플로우 키.
 * @property pending 이관 대상 — 상태 키별 남은 이슈 수.
 */
class WorkflowPublishMappingRequiredException(
    val workflowKey: String,
    val pending: Map<String, Long>,
) : RuntimeException(
        "Workflow '$workflowKey' publish needs status mappings for: " +
            pending.entries.joinToString(", ") { "${it.key}(${it.value})" },
    )
