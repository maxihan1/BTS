// 워크플로우 전이 결과 — sealed interface 로 호출자 BC 가 when exhaustive 분기 처리

package com.bts.workflow.domain.dto

sealed interface TransitionResult {
    data class Success(val plan: TransitionPlan) : TransitionResult
    data class ValidatorFailure(val message: String) : TransitionResult
    data class WorkflowNotFound(val key: String) : TransitionResult
    data class ExpressionTimeout(val message: String) : TransitionResult
}
