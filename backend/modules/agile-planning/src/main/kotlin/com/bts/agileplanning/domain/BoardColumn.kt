// 보드 컬럼 값 객체 — 워크플로우 상태 1:1 매핑, 카드 배치 기준 단위

package com.bts.agileplanning.domain

import java.util.UUID

/**
 * 보드 컬럼 값 객체.
 *
 * 칸반 보드의 세로 열(컬럼). 워크플로우 상태 1개에 1:1 매핑된다.
 * 보드 생성 시 `WorkflowStateCatalog.listStates` 결과를 컬럼으로 시드한다.
 *
 * ### 상태 스냅샷
 * [stateKey] · [name] · [category] · [displayOrder] 는 생성 시점의 워크플로우 상태 스냅샷이다.
 * 워크플로우 상태가 이후 변경돼도 보드 컬럼은 자동 동기화하지 않는다(후속 FR 대상).
 *
 * ### 불변 계약
 * - [stateKey] 는 비어 있거나 공백만 있으면 안 된다.
 * - [name] 은 비어 있거나 공백만 있으면 안 된다.
 * - [category] 는 비어 있거나 공백만 있으면 안 된다.
 * - [displayOrder] 는 컬럼 표시 순서(오름차순). 값 자체는 제약 없음.
 * - [wipLimit] 는 null 이거나 양수(1 이상)여야 한다. 0·음수는 IllegalArgumentException.
 *
 * @property id 컬럼 UUID (PK).
 * @property stateKey 매핑된 워크플로우 상태 키. 예: `"open"`, `"in-progress"`, `"closed"`.
 * @property name 컬럼 표시 이름. 예: `"열림"`, `"진행 중"`, `"완료"`.
 * @property category 칸반 카테고리. `"TODO"` · `"IN_PROGRESS"` · `"DONE"` 중 하나.
 *   보드 그룹 표시 및 완료 컬럼 판별에 사용된다.
 * @property displayOrder 컬럼 표시 순서 (오름차순). 낮을수록 왼쪽에 표시.
 * @property wipLimit WIP(Work In Progress) 제한 수. null 이면 무제한. 양수만 허용.
 *   0 또는 음수는 IllegalArgumentException을 발생시킨다.
 */
data class BoardColumn(
    val id: UUID,
    val stateKey: String,
    val name: String,
    val category: String,
    val displayOrder: Int,
    val wipLimit: Int? = null,
) {
    init {
        require(stateKey.isNotBlank()) { "BoardColumn.stateKey must not be blank." }
        require(name.isNotBlank()) { "BoardColumn.name must not be blank." }
        require(category.isNotBlank()) { "BoardColumn.category must not be blank." }
        require(wipLimit == null || wipLimit > 0) { "BoardColumn.wipLimit must be null or positive." }
    }
}
