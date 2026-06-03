// 가용 전이 열거 결과 — sealed interface 로 호출자 BC 가 when exhaustive 분기 처리

package com.bts.shared.workflow

/**
 * 가용 전이 열거 결과를 표현하는 sealed interface.
 *
 * project-workflow 모듈이 가용 전이 목록 조회를 완료한 후 호출자 BC(바운디드 컨텍스트)에 반환한다.
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
     * 가용 전이 목록 조회에 성공했을 때 반환된다.
     *
     * [transitions] 가 빈 리스트일 수 있다 — fromState 에서 이동 가능한 전이가 없는 경우.
     *
     * @param transitions 현재 상태에서 이동 가능한 전이 뷰 목록.
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
 * 가용 전이 뷰 — published language 최소 표면.
 *
 * project-workflow 내부 타입(Transition, WorkflowGate 등)을 노출하지 않는다.
 * 전이 동일성은 ([fromStateKey], [toStateKey]) 쌍으로 식별한다.
 * ADR 2026-05-28-workflow-transition-identity-policy 참조.
 *
 * @param fromStateKey 전이 출발 상태 키.
 * @param toStateKey 전이 도착 상태 키.
 * @param name 전이 표시 이름. UI 버튼 레이블 용도로만 사용하며 식별자가 아니다.
 * @param toCategory 전이 목표 상태의 카테고리 문자열(예: "DONE", "IN_PROGRESS", "TODO").
 *   프론트엔드가 종료(DONE) 전이를 판별할 때 사용한다.
 *   null 은 미계산 상태를 의미하며 테스트 픽스처에서만 허용된다.
 *   실 API 응답은 항상 non-null 값이 채워진다(후속 task A4 에서 보장).
 *   BC 격리 원칙에 따라 내부 enum(StateCategory) 대신 문자열로 노출한다.
 */
data class AvailableTransitionView(
    val fromStateKey: String,
    val toStateKey: String,
    val name: String,
    val toCategory: String? = null,
)
