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
 *   컬럼 내 카드 정렬 기준: rank ASC NULLS LAST → priority ASC(1=최상위) → issueKey ASC 보조.
 *
 * ## BC 격리
 * [com.bts.shared.workflow.WorkflowStateView] 와 [com.bts.shared.board.BoardIssueView] 를
 * 통해 cross-BC 데이터를 받는다.
 * issue-tracking · project-workflow · identity-access 내부 패키지를 직접 import 하지 않는다.
 */
object BoardCardPlacement {
    /**
     * 컬럼 내 카드 정렬 비교자 — rank ASC NULLS LAST → priority ASC → issueKey ASC.
     *
     * [BoardIssueView.rank] 가 있는 카드는 rank(LexoRank 문자열) 사전 순으로 앞에 온다.
     * rank 가 없는 카드([BoardIssueView.rank] `null`, 현재 대다수 보드)는 `nullsLast()` 로
     * 뒤로 밀려 [BoardIssueView.priority] ASC → [BoardIssueView.key] ASC 로 폴백 정렬된다.
     * rank 가 동값이거나 둘 다 null 이면 priority ASC 로, priority 도 동값이면 key ASC 로 보조 정렬한다.
     *
     * 이 우선순위는 [com.bts.agileplanning.application.BacklogApplicationService] 의
     * 백로그 이슈 정렬 선례(rank ASC NULLS LAST → key ASC)와 동일한 rank 규칙을 따른다.
     * 보드는 여기에 priority 보조 tiebreaker 를 추가로 유지해, rank 미부여 시 기존 정렬을 무회귀한다.
     */
    private val CARD_COMPARATOR: Comparator<BoardIssueView> =
        compareBy<BoardIssueView, String?>(nullsLast()) { it.rank }
            .thenBy { it.priority }
            .thenBy { it.key }

    /** category 진행도 — 클수록 진행된 것이다. [resolveCategory] 가 최댓값을 고른다. */
    private val CATEGORY_RANK = mapOf("TODO" to 0, "IN_PROGRESS" to 1, "DONE" to 2)

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
                    // 시드는 종전대로 상태 1개당 컬럼 1개다(R4). 1:N 은 매핑 변경으로만 만들어진다.
                    stateKeys = listOf(state.key),
                    name = state.name,
                    category = state.category,
                    displayOrder = state.displayOrder,
                )
            }

    /**
     * 컬럼이 담은 상태들의 category 를 하나로 접는다 — `DONE` > `IN_PROGRESS` > `TODO` 최댓값 (R5).
     *
     * ★ **결과는 표시 전용이다.** 어떤 로직 분기도 이 값을 읽지 않는다 —
     * 완료 판정은 전환 시점에 **대상 상태**로 한다(J7·J8 · `IssueRepository` 의 워크플로우 validator).
     * 그 사실은 R13 이 프론트의 마지막 분기를 걷어낸 뒤에야 참이 된다.
     *
     * 상태가 없으면 `TODO` — 빈 컬럼은 아직 아무 일도 안 하는 자리다.
     *
     * @param categories 컬럼이 담은 상태들의 category 목록. 순서는 무관하다.
     * @return 가장 진행된 category 하나.
     */
    fun resolveCategory(categories: List<String>): String = categories.maxByOrNull { CATEGORY_RANK[it] ?: 0 } ?: "TODO"

    /**
     * 어느 컬럼에도 매핑되지 않은 워크플로우 상태를 고른다 (R8 · J2).
     *
     * 지라의 **Unmapped statuses** 패널과 같은 목록이다 — 컬럼으로 끌어다 놓을 후보이고,
     * 그 상태의 이슈가 보드에서 빠진 이유이기도 하다. [placeCards] 의 `unplacedCount`(E2)가
     * 「몇 건이 빠졌나」만 세는 데 반해 이 목록은 **왜 빠졌나**를 알려준다.
     *
     * 기준 목록은 `listStates(projectKey, null)` 이어야 한다 — 컬럼 시드가 쓰는 것과 **같은 호출**이다
     * (G3). 이슈 타입별로 가르면 시드에는 있는데 미매핑 목록에는 없는(또는 그 반대) 상태가 생겨
     * 두 목록이 서로를 배신한다.
     *
     * @param columns 보드 컬럼 목록.
     * @param states 워크플로우 상태 목록. `listStates(projectKey, null)` 반환 값.
     * @return 매핑되지 않은 상태. **[states] 의 순서를 보존한다**(카탈로그 = 워크플로우 표시 순서).
     */
    fun unmappedStates(
        columns: List<BoardColumn>,
        states: List<WorkflowStateView>,
    ): List<WorkflowStateView> {
        val mapped = columns.flatMapTo(mutableSetOf()) { it.stateKeys }
        return states.filterNot { it.key in mapped }
    }

    /**
     * 이슈 목록을 컬럼에 배치한 결과를 반환한다.
     *
     * 이슈의 [BoardIssueView.currentStateKey] 가 [columns] 중 어느 [BoardColumn.stateKeys] 에도
     * 없으면 해당 이슈는 결과에서 제외된다 (E2 미매핑 상태 이슈 제외).
     * 모든 컬럼은 결과에 포함되며, 이슈 없는 컬럼은 빈 [PlacedColumn.cards] 를 갖는다.
     *
     * 컬럼 내 카드 정렬 기준.
     * 1. [BoardIssueView.rank] ASC, NULLS LAST (LexoRank 문자열 사전 순, 미부여 카드는 뒤로).
     * 2. [BoardIssueView.priority] ASC (1 = 최상위 우선순위, rank 동값·미부여 시 보조 기준).
     * 3. [BoardIssueView.key] ASC (rank·priority 도 동값인 이슈의 안정 보조 기준).
     *
     * 미매핑 이슈 수([PlacedBoardResult.unplacedCount])는 응답에 포함되어 클라이언트가 E2 상황을 인지할 수 있다.
     * **어느 상태가** 빠졌는지는 [unmappedStates] 가 따로 답한다(R8).
     *
     * @param columns 보드 컬럼 목록. 순서는 그대로 유지된다.
     * @param issues 배치할 이슈 목록. BoardIssueLookupPort.listVisibleIssuesByProject 반환 값.
     * @return [PlacedBoardResult]. columns 는 입력 [columns] 와 동일 순서. unplacedCount 는 미매핑 이슈 수.
     */
    fun placeCards(
        columns: List<BoardColumn>,
        issues: List<BoardIssueView>,
    ): PlacedBoardResult {
        val knownStateKeys = columns.flatMap { it.stateKeys }.toSet()
        val issuesByStateKey: Map<String, List<BoardIssueView>> =
            issues.groupBy { it.currentStateKey }

        val placedColumns =
            columns.map { column ->
                // 컬럼이 담은 **모든** 상태의 카드를 모아 한 번에 정렬한다(R3).
                // stateKeys 가 비면 카드 0장이다(E1·N4) — 매핑을 옮기는 중간 상태다.
                val cards =
                    column.stateKeys
                        .flatMap { issuesByStateKey[it].orEmpty() }
                        .sortedWith(CARD_COMPARATOR)
                PlacedColumn(column = column, cards = cards)
            }

        val unplacedCount = issues.count { it.currentStateKey !in knownStateKeys }
        return PlacedBoardResult(columns = placedColumns, unplacedCount = unplacedCount)
    }
}

/**
 * 컬럼 + 배치된 카드 목록 결과 VO.
 *
 * [BoardCardPlacement.placeCards] 가 반환하는 읽기 전용 값 객체.
 *
 * @property column 보드 컬럼 정보.
 * @property cards 이 컬럼에 배치된 카드(이슈) 목록. rank ASC NULLS LAST → priority ASC → issueKey ASC 정렬.
 */
data class PlacedColumn(
    val column: BoardColumn,
    val cards: List<BoardIssueView>,
)

/**
 * [BoardCardPlacement.placeCards] 전체 결과 VO.
 *
 * 컬럼 배치 결과와 미매핑 이슈 수를 함께 담는다.
 * 소비측([BoardApplicationService])이 truncated 플래그와 함께 [BoardPlacementResult] 로 조립한다.
 *
 * @property columns 카드가 배치된 컬럼 목록. 입력 컬럼 순서 유지.
 * @property unplacedCount 어느 컬럼에도 매핑되지 않아 제외된 이슈 수 (E2 미매핑 상태).
 */
data class PlacedBoardResult(
    val columns: List<PlacedColumn>,
    val unplacedCount: Int,
)
