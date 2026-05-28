// 워크플로우 결정 결과 VO — WorkflowKeyResolver consumer 가 사용하는 published language

package com.bts.shared.workflow

/**
 * 워크플로우 결정 결과 VO.
 *
 * [WorkflowKeyResolver.resolveStart] 가 반환하는 값 객체.
 * consumer BC(issue-tracking 등)는 이 VO 를 통해 이슈 생성 시 `currentStateKey` 와
 * 전이 요청 시 `workflowKey` 를 얻는다.
 *
 * ### 불변 계약
 *
 * - [workflowKey] 는 비어 있거나 공백만 있으면 안 된다.
 * - [startStateKey] 는 비어 있거나 공백만 있으면 안 된다.
 *
 * @property workflowKey 결정된 워크플로우 키. 예: `"software-default"`, `"bug-tracking"`.
 * @property startStateKey 워크플로우의 시작 상태 키. 예: `"open"` (소문자, 워크플로우 YAML 정본 기준).
 */
data class WorkflowStartState(
    val workflowKey: String,
    val startStateKey: String,
) {
    init {
        require(workflowKey.isNotBlank()) { "workflowKey must not be blank." }
        require(startStateKey.isNotBlank()) { "startStateKey must not be blank." }
    }
}
