// 보드 컬럼 값 객체 — 워크플로우 상태 1:N 매핑, 카드 배치 기준 단위

package com.bts.agileplanning.domain

import java.util.UUID

/**
 * 보드 컬럼 값 객체.
 *
 * 칸반 보드의 세로 열(컬럼). 워크플로우 상태 **0개 이상**에 매핑된다.
 * 보드 생성 시 `WorkflowStateCatalog.listStates` 결과를 컬럼으로 시드하며,
 * 시드는 종전대로 **상태 1개당 컬럼 1개**다(R4) — 1:N 은 이후 매핑 변경으로만 만들어진다.
 *
 * ### 왜 1:N 인가
 * 지라는 한 컬럼에 여러 상태를 담고 각 상태를 **드롭존**으로 보여준다
 * (`docs/specs/2026-09-03-board-column-multi-state.md` J1·J3). 「진행 중」 컬럼에
 * `in_progress` + `in_review` 를 함께 두는 흔한 구성이 그것이다.
 *
 * ```
 *   컬럼 「진행 중」
 *   ├─ 드롭존 in_progress   ← 카드를 여기 떨구면 in_progress 로 전환
 *   └─ 드롭존 in_review     ← 카드를 여기 떨구면 in_review 로 전환
 * ```
 *
 * ### 상태 스냅샷
 * [stateKeys] · [name] · [category] · [displayOrder] 는 생성 시점의 워크플로우 상태 스냅샷이다.
 * 워크플로우 상태가 이후 변경돼도 보드 컬럼은 자동 동기화하지 않는다(부채 177).
 *
 * ### [category] 는 표시 전용이다
 * 담은 상태들의 category 최댓값이며([BoardCardPlacement.resolveCategory]) **어떤 로직 분기도
 * 이 값을 읽지 않는다.** 완료 판정은 전환 시점에 **대상 상태**로 한다 —
 * `IssueRepository` 의 워크플로우 validator 가 DONE 진입 시 resolution 필수를 강제하고,
 * 지라도 resolution 을 전환에 붙인다(J7·J8).
 *
 * ★ 이 문장은 R13 이후에야 참이다. 그 전까지 프론트 `board-drop.ts` 가 이 값으로 해결 방안
 * 모달을 띄울지 정했고, 1:N 이 되면 「진행 중」 컬럼에 `done` 이 섞이는 순간 평범한 드래그에도
 * 모달이 뜬다(plan 리뷰 BLOCKER-1).
 *
 * ### 불변 계약
 * - [stateKeys] 는 **빈 리스트를 허용한다**(E1·N4) — 매핑을 옮기는 중간 상태다.
 * - [stateKeys] 의 원소는 비어 있거나 공백만 있으면 안 되고, **중복이 없어야** 한다(E9).
 * - [name] · [category] 는 비어 있거나 공백만 있으면 안 된다.
 * - [displayOrder] 는 컬럼 표시 순서(오름차순). 값 자체는 제약 없음.
 * - [wipLimit] 는 null 이거나 양수(1 이상)여야 한다. 0·음수는 IllegalArgumentException.
 *
 * @property id 컬럼 UUID (PK).
 * @property stateKeys 매핑된 워크플로우 상태 키 목록. 컬럼 안 드롭존 순서(오름차순)로 담는다.
 *   빈 리스트면 카드 0장으로 렌더된다.
 * @property name 컬럼 표시 이름. 예: `"열림"`, `"진행 중"`, `"완료"`.
 * @property category 칸반 카테고리. `"TODO"` · `"IN_PROGRESS"` · `"DONE"` 중 하나. **표시 전용**.
 * @property displayOrder 컬럼 표시 순서 (오름차순). 낮을수록 왼쪽에 표시.
 * @property wipLimit WIP(Work In Progress) 제한 수. null 이면 무제한. 양수만 허용.
 */
data class BoardColumn(
    val id: UUID,
    val stateKeys: List<String>,
    val name: String,
    val category: String,
    val displayOrder: Int,
    val wipLimit: Int? = null,
) {
    init {
        require(stateKeys.all { it.isNotBlank() }) { "BoardColumn.stateKeys must not contain blank entries." }
        require(stateKeys.size == stateKeys.toSet().size) { "BoardColumn.stateKeys must not contain duplicates." }
        require(name.isNotBlank()) { "BoardColumn.name must not be blank." }
        require(category.isNotBlank()) { "BoardColumn.category must not be blank." }
        require(wipLimit == null || wipLimit > 0) { "BoardColumn.wipLimit must be null or positive." }
    }

    /**
     * 레거시 `board_columns.state_key` 컬럼에 쓸 값 — 이중 기록(E5)의 「첫 상태」다.
     *
     * **정의는 `(display_order, state_key)` 오름차순 최소**이고, [stateKeys] 가 이미 그 순서로
     * 담기므로 첫 원소가 곧 그것이다. 프론트의 과도기 전송(R12)도 **같은 규칙**을 써야 한다 —
     * 갈리면 레거시 컬럼과 화면이 어긋난다(plan 리뷰 CONCERN-2).
     *
     * 상태가 없으면 null 이고, `V508` 이 그 컬럼의 `NOT NULL` 을 풀어 그것을 받을 수 있게 했다(G1).
     */
    val legacyStateKey: String? get() = stateKeys.firstOrNull()
}
