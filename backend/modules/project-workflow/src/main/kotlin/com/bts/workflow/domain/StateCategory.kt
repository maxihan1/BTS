// 워크플로우 상태 카테고리 — TODO / IN_PROGRESS / DONE

package com.bts.workflow.domain

/**
 * 워크플로우 상태 카테고리.
 *
 * 모든 [WorkflowState] 는 이 세 카테고리 중 하나에 속한다.
 * 카테고리는 이슈 완료 여부 / 보드 레인 배치 등 상위 로직에서 사용된다.
 */
enum class StateCategory {
    TODO,
    IN_PROGRESS,
    DONE,
}
