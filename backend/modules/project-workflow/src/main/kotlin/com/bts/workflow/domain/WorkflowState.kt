// 워크플로우 상태 + 카테고리 (TODO/IN_PROGRESS/DONE)

package com.bts.workflow.domain

/**
 * 워크플로우 단일 상태.
 *
 * @property key 상태 식별 키. [Workflow] 내에서 고유해야 한다.
 * @property name 사람이 읽을 수 있는 상태 이름.
 * @property category 상위 카테고리 (TODO / IN_PROGRESS / DONE).
 * @property displayOrder 보드/목록에서 표시되는 순서. 작을수록 앞.
 */
data class WorkflowState(
    val key: String,
    val name: String,
    val category: StateCategory,
    val displayOrder: Int,
)
