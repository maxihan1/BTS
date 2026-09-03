// 보드 컬럼이 담은 워크플로우 상태 1건의 응답 DTO — 키·이름·카테고리 (R11)

package com.bts.agileplanning.web.dto

import com.bts.shared.workflow.WorkflowStateView

/**
 * 보드 컬럼에 매핑된 워크플로우 상태 1건의 응답 DTO.
 *
 * 컬럼:상태가 1:N 이 되면서 [BoardColumnResponse] · [BoardColumnWithCardsResponse] ·
 * [ColumnMetaResponse] 의 `stateKey: String` 이 `states: List<ColumnStateResponse>` 가 됐다(R11).
 *
 * ### 왜 키 배열이 아닌가
 *
 * 프론트가 카드를 떨굴 때 **해결 방안(resolution) 모달을 띄울지**를 대상 상태의 [category] 로
 * 판정해야 한다(R13). 지라는 resolution 을 **전환**에 붙이고(J7·J8) BTS 백엔드도 이미 대상 상태로
 * 판단한다(`IssueRepository` `targetStateIsDone`). 키만 주면 프론트가 상태 메타를 별도로 조회해야
 * 하고 그것이 N+1 이다.
 *
 * 컬럼의 [BoardColumnResponse.category] 는 담은 상태들의 **최댓값**이라(R5) 개별 상태의
 * [category] 와 다를 수 있다. 그 차이가 R13 이 읽는 값이므로 응답에 둘 다 남는다.
 *
 * @property key 워크플로우 상태 키. 예: `"in-progress"`. `issues.current_state_key` 와 같은 규격.
 * @property name 사용자에게 표시되는 상태 이름. 예: `"진행 중"`.
 * @property category 상태가 속한 칸반 카테고리. `"TODO"` · `"IN_PROGRESS"` · `"DONE"`.
 */
data class ColumnStateResponse(
    val key: String,
    val name: String,
    val category: String,
) {
    companion object {
        /** 카탈로그에 없는 키에 쓰는 카테고리. [WorkflowStateView.category] 의 기본값과 같다. */
        private const val FALLBACK_CATEGORY = "TODO"

        /** `WorkflowStateCatalog.listStates` 결과를 키로 색인한다. 컬럼마다 다시 만들지 않는다. */
        fun catalog(states: List<WorkflowStateView>): Map<String, WorkflowStateView> = states.associateBy { it.key }

        /**
         * 컬럼의 상태 키 목록을 표시 정보가 붙은 응답으로 푼다. 순서는 입력 순서(= `display_order`)를 보존한다.
         *
         * ### 카탈로그에 없는 키를 드롭하지 않는 이유
         *
         * `board_columns` 는 `workflow_states` 를 FK 로 참조하지 않는다(BC 격리). 워크플로우에서
         * 상태를 지워도 매핑 행은 남으므로 「카탈로그에 없는 매핑」이 실제로 생긴다. 그때 키를
         * 드롭하면 **컬럼이 조용히 비어 보이고** 운영자는 매핑이 남아 있다는 사실조차 모른다.
         * 키를 이름으로 써서 보이게 둔다 — 고치려면 먼저 보여야 한다.
         *
         * @param stateKeys 컬럼이 담은 상태 키 목록([com.bts.agileplanning.domain.BoardColumn.stateKeys]).
         * @param catalog [catalog] 로 만든 색인.
         */
        fun resolve(
            stateKeys: List<String>,
            catalog: Map<String, WorkflowStateView>,
        ): List<ColumnStateResponse> =
            stateKeys.map { key ->
                val view = catalog[key]
                ColumnStateResponse(
                    key = key,
                    name = view?.name ?: key,
                    category = view?.category ?: FALLBACK_CATEGORY,
                )
            }

        /** 카탈로그 항목 1건을 그대로 응답으로 옮긴다. 미매핑 목록(R8)이 쓴다. */
        fun from(view: WorkflowStateView): ColumnStateResponse =
            ColumnStateResponse(key = view.key, name = view.name, category = view.category)
    }
}
