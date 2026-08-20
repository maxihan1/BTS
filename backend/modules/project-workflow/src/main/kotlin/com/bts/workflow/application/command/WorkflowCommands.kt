// 워크플로우 쓰기 유스케이스의 입력 커맨드 — 컨트롤러 DTO 와 도메인 사이의 경계

package com.bts.workflow.application.command

/**
 * 새 워크플로우에 처음 편성할 상태 하나.
 *
 * `key` 가 전역 카탈로그(`statuses`)에 이미 있으면 그 항목을 재사용하고, 없으면 만든다.
 * 카탈로그는 사이트 전역이라 같은 키가 두 벌 생기지 않는다.
 */
data class WorkflowStatusSeed(
    val key: String,
    val name: String,
    val category: String,
    val displayOrder: Int,
)

/**
 * 워크플로우 생성 입력.
 *
 * ### 왜 [statuses] 가 필수인가
 * `Workflow.of()` invariant 가 「상태가 하나 이상」을 요구한다. 상태 없이 만들면 만들자마자
 * 조회가 `IllegalArgumentException` 으로 죽는다. Jira Cloud 도 새 워크플로우에 기본 상태를 준다.
 */
data class CreateWorkflowCommand(
    val key: String,
    val name: String,
    val description: String?,
    val statuses: List<WorkflowStatusSeed>,
)

/**
 * 워크플로우 수정 입력.
 *
 * `key` 는 받지 않는다 — 이슈·자동화·검색이 문자열로 참조하는 식별자라 바뀌면 조용히 끊긴다.
 * 상태 편성 변경은 별도 API(`/{key}/statuses`)가 담당한다.
 */
data class UpdateWorkflowCommand(
    val name: String,
    val description: String?,
)
