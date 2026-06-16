// 워크플로우 상태 항목 VO — 이슈 이동 preview 가 대상 프로젝트 상태 후보를 표시하기 위해 사용

package com.bts.shared.workflow

/**
 * 워크플로우 단일 상태 항목 VO.
 *
 * [WorkflowStateCatalog.listStates] 가 반환하는 값 객체.
 * consumer BC(issue-tracking 이슈 이동 preview 등)는 이 VO 를 통해
 * 대상 프로젝트에서 선택 가능한 상태 목록을 얻는다.
 *
 * ### 불변 계약
 *
 * - [key] 는 비어 있거나 공백만 있으면 안 된다. 워크플로우 YAML 정본 키 기준.
 * - [name] 은 비어 있거나 공백만 있으면 안 된다. 사용자 노출 레이블.
 *
 * ### 읽기 전용 · 부수 효과 없음
 *
 * 이 VO 는 읽기 전용 조회 결과를 담는다. DB 쓰기나 이벤트 발행과 무관하다.
 *
 * @property key 상태 키. 예: `"open"`, `"in-progress"`, `"closed"`. 소문자, 워크플로우 YAML 정본 기준.
 * @property name 사용자에게 표시되는 상태 이름. 예: `"열림"`, `"진행 중"`, `"완료"`.
 */
data class WorkflowStateView(
    val key: String,
    val name: String,
) {
    init {
        require(key.isNotBlank()) { "WorkflowStateView.key must not be blank." }
        require(name.isNotBlank()) { "WorkflowStateView.name must not be blank." }
    }
}
