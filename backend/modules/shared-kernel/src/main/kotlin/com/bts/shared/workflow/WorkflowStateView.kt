// 워크플로우 상태 항목 VO — 이슈 이동 preview 및 칸반 보드 컬럼 매핑에서 사용

package com.bts.shared.workflow

/**
 * 워크플로우 단일 상태 항목 VO.
 *
 * [WorkflowStateCatalog.listStates] 가 반환하는 값 객체.
 * consumer BC(issue-tracking 이슈 이동 preview, agile-planning 칸반 보드 컬럼 시드 등)는
 * 이 VO 를 통해 대상 프로젝트에서 선택 가능한 상태 목록을 얻는다.
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
 * ### 기존 호출자 보호 (default 값)
 *
 * [category] 와 [displayOrder] 는 칸반 보드 컬럼 시드를 위해 추가됐다.
 * 기존 2-arg 호출자(key + name 만 넘기는 코드)는 default 값으로 보호되며 재컴파일 불요.
 * FR-MV-01 [isDone] 추가 전례와 동일한 패턴.
 *
 * @property key 상태 키. 예: `"open"`, `"in-progress"`, `"closed"`. 소문자, 워크플로우 YAML 정본 기준.
 * @property name 사용자에게 표시되는 상태 이름. 예: `"열림"`, `"진행 중"`, `"완료"`.
 * @property isDone 이 상태가 DONE 카테고리에 속하면 true. 이슈 이동 마법사에서 targetStateIsDone 을 채우는 데 사용된다.
 *   기본값 false — 생성 시 명시하지 않으면 비완료 상태로 간주한다.
 *   **정합 주의**: isDone 과 category 는 독립 필드이므로 생성 시 불일치가 가능하다.
 *   prod 구현체([WorkflowStateCatalog] 구현)는 `isDone = (category == "DONE")` 으로 항상 정합을 보장해야 한다.
 *   테스트 픽스처에서도 두 필드를 함께 지정해 불일치를 예방한다.
 * @property category 상태가 속하는 칸반 카테고리 이름. 워크플로우 YAML `category` 필드와 1:1.
 *   예: `"TODO"`, `"IN_PROGRESS"`, `"DONE"`. 기본값 `"TODO"` — 카테고리 미지정 상태는 미착수로 취급한다.
 *   agile-planning BC 가 칸반 보드 컬럼 시드 시 이 값을 컬럼 카테고리로 사용한다.
 * @property displayOrder 보드 컬럼 표시 순서(오름차순). 기본값 0. 구현체([WorkflowStateCatalog])가 채운다.
 */
data class WorkflowStateView(
    val key: String,
    val name: String,
    val isDone: Boolean = false,
    val category: String = "TODO",
    val displayOrder: Int = 0,
) {
    init {
        require(key.isNotBlank()) { "WorkflowStateView.key must not be blank." }
        require(name.isNotBlank()) { "WorkflowStateView.name must not be blank." }
    }
}
