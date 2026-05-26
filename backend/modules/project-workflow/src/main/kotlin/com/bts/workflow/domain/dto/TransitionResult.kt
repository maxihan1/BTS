// 워크플로우 전이 결과 — sealed interface 로 호출자 BC 가 when exhaustive 분기 처리

package com.bts.workflow.domain.dto

/**
 * 워크플로우 전이 결과를 표현하는 sealed interface.
 *
 * project-workflow 모듈이 전이 검증을 완료한 후 호출자 BC(바운디드 컨텍스트)에 반환한다.
 * 호출자는 `when` 식으로 모든 케이스를 exhaustive 하게 처리해야 하며, `else` 브랜치 추가는
 * 새 케이스 누락 위험이 있으므로 금지한다.
 *
 * ### 호출자 BC 권장 처리 패턴 (issue-tracking BC 기준)
 *
 * ```kotlin
 * when (val result = workflowPort.planSafe(req)) {
 *     is TransitionResult.Success            -> applyPlan(result.plan)
 *     is TransitionResult.ValidatorFailure   -> throw IssueTransitionNotAllowedException(result.message)
 *     is TransitionResult.WorkflowNotFound   -> throw IssueTransitionNotAllowedException("워크플로우를 찾을 수 없습니다: ${result.key}")
 *     is TransitionResult.ExpressionTimeout  -> throw IssueTransitionNotAllowedException(result.message)
 * }
 * ```
 */
sealed interface TransitionResult {

    /**
     * 전이 검증을 통과했을 때 반환된다.
     *
     * 호출자 BC 는 [plan] 을 자신의 트랜잭션 안에서 이슈에 적용해야 한다.
     *
     * @param plan 호출자 BC 가 적용할 전이 실행 계획.
     */
    data class Success(val plan: TransitionPlan) : TransitionResult

    /**
     * 등록된 [com.bts.workflow.domain.spi.WorkflowValidator] 중 하나 이상이 전이를 거부했을 때 반환된다.
     *
     * issue-tracking BC 는 이 케이스를 `IssueTransitionNotAllowedException` 으로 변환해 던져야 한다.
     *
     * @param message 거부 사유. 사용자에게 노출 가능한 한국어 메시지를 권장한다.
     */
    data class ValidatorFailure(val message: String) : TransitionResult

    /**
     * [TransitionRequest.workflowKey] 에 해당하는 워크플로우가 DB 에 없을 때 반환된다.
     *
     * issue-tracking BC 는 이 케이스를 `IssueTransitionNotAllowedException` 으로 변환해 던져야 한다.
     *
     * @param key 조회에 실패한 워크플로우 키.
     */
    data class WorkflowNotFound(val key: String) : TransitionResult

    /**
     * SpEL(Spring Expression Language) 조건식 평가가 제한 시간을 초과했을 때 반환된다.
     *
     * 일시적인 성능 문제일 수 있으므로 호출자 BC 는 재시도 전략을 고려할 수 있다.
     * issue-tracking BC 는 이 케이스를 `IssueTransitionNotAllowedException` 으로 변환해 던져야 한다.
     *
     * @param message 타임아웃 상세 메시지.
     */
    data class ExpressionTimeout(val message: String) : TransitionResult
}
