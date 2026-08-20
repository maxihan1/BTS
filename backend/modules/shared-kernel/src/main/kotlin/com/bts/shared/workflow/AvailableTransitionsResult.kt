// 가용 전환 열거 결과 — sealed interface 로 호출자 BC 가 when exhaustive 분기 처리

package com.bts.shared.workflow

import java.util.UUID

/**
 * 가용 전환 열거 결과를 표현하는 sealed interface.
 *
 * project-workflow 모듈이 가용 전환 목록 조회를 완료한 후 호출자 BC(바운디드 컨텍스트)에 반환한다.
 * 호출자는 `when` 식으로 모든 케이스를 exhaustive 하게 처리해야 하며, `else` 브랜치 추가는
 * 새 케이스 누락 위험이 있으므로 금지한다.
 *
 * ### 호출자 BC 권장 처리 패턴 (issue-tracking BC 기준)
 *
 * ```kotlin
 * when (val result = workflowPort.availableTransitions(req)) {
 *     is AvailableTransitionsResult.Success          -> renderTransitionButtons(result.transitions)
 *     is AvailableTransitionsResult.WorkflowNotFound ->
 *         throw IssueWorkflowNotConfiguredException(projectKey, issueTypeKey) // 422
 * }
 * ```
 */
sealed interface AvailableTransitionsResult {
    /**
     * 가용 전환 목록 조회에 성공했을 때 반환된다.
     *
     * [transitions] 가 빈 리스트일 수 있다 — fromState 에서 이동 가능한 전환이 없는 경우.
     *
     * @param transitions 현재 상태에서 이동 가능한 전환 뷰 목록.
     */
    data class Success(val transitions: List<AvailableTransitionView>) : AvailableTransitionsResult

    /**
     * [AvailableTransitionsRequest.workflowKey] 에 해당하는 워크플로우가 DB 에 없을 때 반환된다.
     *
     * issue-tracking BC 는 이 케이스를 `IssueWorkflowNotConfiguredException`(HTTP 422) 으로 변환해 던져야 한다.
     * `IssueTransitionNotAllowedException`(409) 이 아님에 주의한다.
     *
     * @param key 조회에 실패한 워크플로우 키.
     */
    data class WorkflowNotFound(val key: String) : AvailableTransitionsResult
}

/**
 * 가용 전환 뷰 — published language 최소 표면.
 *
 * project-workflow 내부 타입(Transition, WorkflowGate 등)을 노출하지 않는다.
 * 전환의 1급 식별자는 [transitionId] 다 —
 * ADR `docs/adr/2026-08-18-workflow-transition-id-identity.md` §D1 · §D3 참조.
 * ([fromStateKey], [toStateKey]) 쌍은 더 이상 유일하지 않다. 같은 상태쌍에 이름만 다른 전환을
 * 여럿 둘 수 있기 때문이다.
 *
 * ### 값 채우기는 아직이다 — 지금 [transitionId]·[kind] 는 항상 null 이다
 * project-workflow 의 `WorkflowEngine.availableTransitions` 가 이 둘을 아직 넘기지 않는다.
 * 넘기기 시작하면 이 뷰를 **값으로 통째 비교**하는 기존 단위 테스트들(`WorkflowEngine*Test`)의
 * 기대값을 같은 커밋에서 함께 고쳐야 한다. 그때까지 호출자는 null 을 「미계산」으로 읽어야 하며
 * 「전환 ID 가 없다」로 읽으면 안 된다.
 *
 * @param fromStateKey 전환 출발 상태 키.
 * @param toStateKey 전환 도착 상태 키.
 * @param name 전환 표시 이름. UI 버튼 레이블 용도로만 사용하며 식별자가 아니다.
 * @param toCategory 전환 목표 상태의 카테고리 문자열(예: "DONE", "IN_PROGRESS", "TODO").
 *   프론트엔드가 종료(DONE) 전환을 판별할 때 사용한다.
 *   null 은 미계산 상태를 의미하며 테스트 픽스처에서만 허용된다.
 *   실 API 응답은 항상 non-null 값이 채워진다(후속 task A4 에서 보장).
 *   BC 격리 원칙에 따라 내부 enum(StateCategory) 대신 문자열로 노출한다.
 * @param transitionId 전환 1급 식별자(`workflow_transitions.id`). 호출자는 이 값을
 *   `TransitionRequest.transitionId` 에 그대로 되실어 모호성 없이 그 전환을 지목 실행할 수 있다
 *   (spec FR-WF-05 S4). null 은 미계산 상태다.
 *   **기본값 null 을 지우지 마라** — 3·4인자 기존 생성 지점이 전부 컴파일 실패한다
 *   (spec FR-WF-05 N2 — cross-BC 프로덕션 0줄). `AvailableTransitionViewTest` 의 3인자 생성이 그 가드다.
 * @param kind 전환 종류 문자열. "NORMAL"(출발 상태 지정) · "GLOBAL"(어느 상태에서나) ·
 *   "INITIAL"(이슈 생성 진입 전용, 이 목록에는 절대 안 나온다).
 *   BC 격리 원칙에 따라 내부 enum(TransitionKind) 대신 문자열로 노출한다. null 은 미계산 상태다.
 */
data class AvailableTransitionView(
    val fromStateKey: String,
    val toStateKey: String,
    val name: String,
    val toCategory: String? = null,
    val transitionId: UUID? = null,
    val kind: String? = null,
)
