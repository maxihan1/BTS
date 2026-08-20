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

/**
 * 전환 정의 입력. 생성(`POST`)과 수정(`PUT`)이 **같은 모양**을 쓴다.
 *
 * `PUT` 은 표현을 통째로 갈아 끼우는 REST 의 replace 의미이므로 부분 필드를 따로 받지 않는다.
 * 두 유스케이스가 검증 규칙도 같아 커맨드를 나누면 규칙이 두 벌이 되고 한쪽만 고쳐질 자리가 생긴다.
 *
 * @property fromStatusKey 출발 상태 키. [kind] 가 `NORMAL` 이면 필수이고 `GLOBAL`·`INITIAL` 이면 없어야 한다
 *   (`Workflow.of()` invariant 5 와 같은 규칙)
 * @property toStatusKey 도착 상태 키. 도착지 없는 전환은 어느 종류에도 없다
 * @property name 사람 친화 표시 라벨. 매칭에 쓰지 않는다 — identity 는 전환 id 다
 * @property kind 전환 종류 문자열(`NORMAL`·`GLOBAL`·`INITIAL`). null 이면 `NORMAL` 로 본다.
 *   문자열로 받는 것은 **알 수 없는 값을 400 으로 돌려주기 위해서**다. enum 으로 받으면 역직렬화가
 *   먼저 죽어 「무엇이 잘못됐는지」를 말할 자리가 사라진다
 */
data class TransitionDefinitionCommand(
    val fromStatusKey: String?,
    val toStatusKey: String,
    val name: String,
    val kind: String?,
)
