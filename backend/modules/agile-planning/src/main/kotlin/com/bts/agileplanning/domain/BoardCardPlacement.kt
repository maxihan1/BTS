// 칸반 보드 컬럼 시드·카드 배치 순수 도메인 로직 — I/O 없는 정적 함수 모음

package com.bts.agileplanning.domain

import com.bts.shared.board.BoardIssueView
import com.bts.shared.workflow.WorkflowStateView
import java.util.UUID

/**
 * 칸반 보드 컬럼 시드 및 카드 배치 순수 도메인 로직.
 *
 * 이 object 는 I/O 없이 순수 함수만으로 구성된다.
 * Spring 컴포넌트가 아니며 외부 의존(DB, Spring Context)이 없다.
 *
 * ## 책임
 * - [seedColumns] — 워크플로우 상태 목록을 보드 컬럼으로 변환 (displayOrder 오름차순 정렬).
 * - [placeCards] — 이슈 목록을 current_state_key 기준으로 컬럼에 배치.
 *   어떤 컬럼에도 매핑되지 않는 이슈는 제외된다 (E2 엣지 케이스).
 *   컬럼 내 카드 정렬 기준: priority ASC(1=최상위), 동순위는 issueKey ASC 보조.
 *
 * ## BC 격리
 * [com.bts.shared.workflow.WorkflowStateView] 와 [com.bts.shared.board.BoardIssueView] 를
 * 통해 cross-BC 데이터를 받는다.
 * issue-tracking · project-workflow · identity-access 내부 패키지를 직접 import 하지 않는다.
 */
object BoardCardPlacement {
    /** 컬럼 내 카드 정렬: priority ASC → issueKey ASC 보조. */
    private val CARD_COMPARATOR: Comparator<BoardIssueView> =
        compareBy<BoardIssueView> { it.priority }.thenBy { it.key }

    /**
     * 워크플로우 상태 목록을 보드 컬럼으로 시드한다.
     *
     * 각 [WorkflowStateView] 는 [BoardColumn] 1개로 변환되며, [WorkflowStateView.displayOrder]
     * 오름차순으로 정렬된다. 빈 목록이 주어지면 빈 컬럼 목록이 반환된다.
     *
     * @param states 워크플로우 상태 목록. WorkflowStateCatalog.listStates 반환 값.
     * @return [BoardColumn] 목록. displayOrder 오름차순 정렬.
     */
    fun seedColumns(states: List<WorkflowStateView>): List<BoardColumn> =
        states
            .sortedBy { it.displayOrder }
            .map { state ->
                BoardColumn(
                    id = UUID.randomUUID(),
                    stateKey = state.key,
                    name = state.name,
                    category = state.category,
                    displayOrder = state.displayOrder,
                )
            }

    /**
     * 이슈 목록을 컬럼에 배치한 결과를 반환한다.
     *
     * 이슈의 [BoardIssueView.currentStateKey] 가 [columns] 중 어느 [BoardColumn.stateKey] 와도
     * 일치하지 않으면 해당 이슈는 결과에서 제외된다 (E2 미매핑 상태 이슈 제외).
     * 모든 컬럼은 결과에 포함되며, 이슈 없는 컬럼은 빈 [PlacedColumn.cards] 를 갖는다.
     *
     * 컬럼 내 카드 정렬 기준.
     * 1. [BoardIssueView.priority] ASC (1 = 최상위 우선순위).
     * 2. [BoardIssueView.key] ASC (동순위 이슈의 안정 보조 기준).
     *
     * @param columns 보드 컬럼 목록. 순서는 그대로 유지된다.
     * @param issues 배치할 이슈 목록. BoardIssueLookupPort.listVisibleIssuesByProject 반환 값.
     * @return 컬럼별 카드 배치 결과 목록. 입력 [columns] 와 동일 순서.
     */
    fun placeCards(
        columns: List<BoardColumn>,
        issues: List<BoardIssueView>,
    ): List<PlacedColumn> {
        val issuesByStateKey: Map<String, List<BoardIssueView>> =
            issues.groupBy { it.currentStateKey }

        return columns.map { column ->
            val cards =
                issuesByStateKey[column.stateKey]
                    ?.sortedWith(CARD_COMPARATOR)
                    ?: emptyList()
            PlacedColumn(column = column, cards = cards)
        }
    }
}

/**
 * 컬럼 + 배치된 카드 목록 결과 VO.
 *
 * [BoardCardPlacement.placeCards] 가 반환하는 읽기 전용 값 객체.
 *
 * @property column 보드 컬럼 정보.
 * @property cards 이 컬럼에 배치된 카드(이슈) 목록. priority ASC, issueKey ASC 정렬.
 */
data class PlacedColumn(
    val column: BoardColumn,
    val cards: List<BoardIssueView>,
)
