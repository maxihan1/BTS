// BoardCardPlacement 순수 도메인 로직 단위테스트 — 컬럼 시드·카드 배치·미매핑 제외·정렬

package com.bts.agileplanning.domain

import com.bts.shared.board.BoardIssueView
import com.bts.shared.workflow.WorkflowStateView
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * [BoardCardPlacement] 순수 단위테스트.
 *
 * 외부 의존(DB, Spring, mock) 없이 순수 코틀린 함수만 검증한다.
 *
 * 검증 시나리오.
 * - (a) WorkflowStateView 리스트 → 컬럼 시드 (state_key·name·category·displayOrder 매핑, displayOrder 오름차순 정렬)
 * - (b) BoardIssueView 리스트 → current_state_key 기준 컬럼 배치
 * - (c) 미매핑 상태 이슈 제외 (E2)
 * - (d) 컬럼 내 카드 정렬 = priority ASC, 동순위는 issueKey ASC 보조
 */
class BoardCardPlacementTest {
    // 픽스처 헬퍼
    private fun stateView(
        key: String,
        name: String = key,
        category: String = "TODO",
        displayOrder: Int = 0,
    ) = WorkflowStateView(key = key, name = name, category = category, displayOrder = displayOrder)

    private fun issueView(
        key: String,
        currentStateKey: String,
        priority: Int = 3,
        summary: String = "summary-$key",
        rank: String? = null,
    ) = BoardIssueView(
        key = key,
        summary = summary,
        currentStateKey = currentStateKey,
        assigneeId = null,
        priority = priority,
        version = 1L,
        rank = rank,
    )

    private fun column(
        stateKey: String,
        displayOrder: Int = 1,
        category: String = "TODO",
    ) = BoardColumn(
        id = UUID.randomUUID(),
        stateKey = stateKey,
        name = stateKey,
        category = category,
        displayOrder = displayOrder,
    )

    // (a) 컬럼 시드 — WorkflowStateView → BoardColumn 매핑 + displayOrder 정렬
    @Nested
    inner class SeedColumns {
        @Test
        fun `WorkflowStateView 목록을 displayOrder 오름차순으로 컬럼 시드한다`() {
            val states =
                listOf(
                    stateView("in-progress", "진행 중", "IN_PROGRESS", displayOrder = 2),
                    stateView("open", "열림", "TODO", displayOrder = 1),
                    stateView("closed", "완료", "DONE", displayOrder = 3),
                )

            val columns = BoardCardPlacement.seedColumns(states)

            assertThat(columns).hasSize(3)
            assertThat(columns.map { it.stateKey })
                .containsExactly("open", "in-progress", "closed")
        }

        @Test
        fun `컬럼의 stateKey·name·category·displayOrder가 WorkflowStateView와 1대1 매핑된다`() {
            val state = stateView("in-progress", "진행 중", "IN_PROGRESS", displayOrder = 5)

            val columns = BoardCardPlacement.seedColumns(listOf(state))

            val column = columns.single()
            assertThat(column.stateKey).isEqualTo("in-progress")
            assertThat(column.name).isEqualTo("진행 중")
            assertThat(column.category).isEqualTo("IN_PROGRESS")
            assertThat(column.displayOrder).isEqualTo(5)
        }

        @Test
        fun `빈 상태 목록이면 빈 컬럼 목록을 반환한다`() {
            val columns = BoardCardPlacement.seedColumns(emptyList())

            assertThat(columns).isEmpty()
        }

        @Test
        fun `각 컬럼은 고유한 UUID id를 갖는다`() {
            val states =
                listOf(
                    stateView("open", displayOrder = 1),
                    stateView("closed", displayOrder = 2),
                )

            val columns = BoardCardPlacement.seedColumns(states)

            val ids = columns.map { it.id }
            assertThat(ids).doesNotHaveDuplicates()
        }
    }

    // (b) 카드 배치 — BoardIssueView → current_state_key 기준 컬럼 배치
    @Nested
    inner class PlaceCards {
        @Test
        fun `이슈를 current_state_key가 일치하는 컬럼에 배치한다`() {
            val todoColumn = column("open", displayOrder = 1)
            val inProgressColumn = column("in-progress", displayOrder = 2, category = "IN_PROGRESS")
            val issues =
                listOf(
                    issueView("PROJ-1", "open"),
                    issueView("PROJ-2", "in-progress"),
                    issueView("PROJ-3", "open"),
                )

            val result =
                BoardCardPlacement.placeCards(
                    columns = listOf(todoColumn, inProgressColumn),
                    issues = issues,
                )

            val todoCards = result.columns.first { it.column.stateKey == "open" }.cards
            val inProgressCards = result.columns.first { it.column.stateKey == "in-progress" }.cards
            assertThat(todoCards.map { it.key }).containsExactlyInAnyOrder("PROJ-1", "PROJ-3")
            assertThat(inProgressCards.map { it.key }).containsExactly("PROJ-2")
        }

        @Test
        fun `이슈가 없는 컬럼은 빈 카드 목록으로 포함된다`() {
            val emptyColumn = column("closed", category = "DONE")

            val result =
                BoardCardPlacement.placeCards(
                    columns = listOf(emptyColumn),
                    issues = emptyList(),
                )

            assertThat(result.columns).hasSize(1)
            assertThat(result.columns.single().cards).isEmpty()
        }
    }

    // (c) 미매핑 상태 이슈 제외 (E2) + unplacedCount 신호
    @Nested
    inner class UnmappedStateExclusion {
        @Test
        fun `컬럼에 없는 state_key의 이슈는 보드에서 제외된다`() {
            val openColumn = column("open")
            val issues =
                listOf(
                    issueView("PROJ-1", "open"),
                    issueView("PROJ-2", "unknown-state"),
                )

            val result =
                BoardCardPlacement.placeCards(
                    columns = listOf(openColumn),
                    issues = issues,
                )

            val allCards = result.columns.flatMap { it.cards }
            assertThat(allCards.map { it.key }).containsExactly("PROJ-1")
            assertThat(allCards.map { it.key }).doesNotContain("PROJ-2")
        }

        @Test
        fun `모든 이슈가 미매핑이면 모든 컬럼의 카드가 비어 있다`() {
            val openColumn = column("open")
            val issues =
                listOf(
                    issueView("PROJ-1", "ghost-state"),
                    issueView("PROJ-2", "another-ghost"),
                )

            val result =
                BoardCardPlacement.placeCards(
                    columns = listOf(openColumn),
                    issues = issues,
                )

            assertThat(result.columns.flatMap { it.cards }).isEmpty()
        }

        @Test
        fun `미매핑 이슈가 있으면 unplacedCount 가 해당 수만큼 반환된다`() {
            val openColumn = column("open")
            val issues =
                listOf(
                    issueView("PROJ-1", "open"),
                    issueView("PROJ-2", "unknown-state"),
                    issueView("PROJ-3", "another-ghost"),
                )

            val result =
                BoardCardPlacement.placeCards(
                    columns = listOf(openColumn),
                    issues = issues,
                )

            assertThat(result.unplacedCount).isEqualTo(2)
        }

        @Test
        fun `미매핑 이슈가 없으면 unplacedCount 가 0 이다`() {
            val openColumn = column("open")
            val issues = listOf(issueView("PROJ-1", "open"))

            val result =
                BoardCardPlacement.placeCards(
                    columns = listOf(openColumn),
                    issues = issues,
                )

            assertThat(result.unplacedCount).isEqualTo(0)
        }
    }

    // (d) 컬럼 내 카드 정렬 — priority ASC, 동순위는 issueKey ASC 보조
    @Nested
    inner class CardSortOrder {
        @Test
        fun `컬럼 내 카드는 priority 오름차순으로 정렬된다`() {
            val openColumn = column("open")
            val issues =
                listOf(
                    issueView("PROJ-3", "open", priority = 3),
                    issueView("PROJ-1", "open", priority = 1),
                    issueView("PROJ-2", "open", priority = 2),
                )

            val result =
                BoardCardPlacement.placeCards(
                    columns = listOf(openColumn),
                    issues = issues,
                )

            val keys = result.columns.single().cards.map { it.key }
            assertThat(keys).containsExactly("PROJ-1", "PROJ-2", "PROJ-3")
        }

        @Test
        fun `priority가 동일하면 issueKey 오름차순으로 안정 정렬된다`() {
            val openColumn = column("open")
            val issues =
                listOf(
                    issueView("PROJ-3", "open", priority = 2),
                    issueView("PROJ-1", "open", priority = 2),
                    issueView("PROJ-2", "open", priority = 2),
                )

            val result =
                BoardCardPlacement.placeCards(
                    columns = listOf(openColumn),
                    issues = issues,
                )

            val keys = result.columns.single().cards.map { it.key }
            assertThat(keys).containsExactly("PROJ-1", "PROJ-2", "PROJ-3")
        }

        @Test
        fun `priority 1이 최상위이고 숫자가 클수록 낮은 우선순위다`() {
            val openColumn = column("open")
            val issues =
                listOf(
                    issueView("PROJ-LOW", "open", priority = 5),
                    issueView("PROJ-HIGH", "open", priority = 1),
                )

            val result =
                BoardCardPlacement.placeCards(
                    columns = listOf(openColumn),
                    issues = issues,
                )

            val keys = result.columns.single().cards.map { it.key }
            assertThat(keys).containsExactly("PROJ-HIGH", "PROJ-LOW")
        }

        @Test
        fun `rank가 있는 카드는 priority와 무관하게 rank 오름차순으로 정렬된다`() {
            val openColumn = column("open")
            // priority 만 보면 PROJ-LOW 가 먼저와야 하지만, rank 가 우선한다.
            val issues =
                listOf(
                    issueView("PROJ-LOW", "open", priority = 1, rank = "c"),
                    issueView("PROJ-MID", "open", priority = 2, rank = "b"),
                    issueView("PROJ-HIGH", "open", priority = 3, rank = "a"),
                )

            val result =
                BoardCardPlacement.placeCards(
                    columns = listOf(openColumn),
                    issues = issues,
                )

            val keys = result.columns.single().cards.map { it.key }
            assertThat(keys).containsExactly("PROJ-HIGH", "PROJ-MID", "PROJ-LOW")
        }

        @Test
        fun `rank가 없는 카드는 rank가 있는 카드보다 뒤에 정렬된다 (NULLS LAST)`() {
            val openColumn = column("open")
            // priority 만 보면 PROJ-NO-RANK 가 먼저와야 하지만, rank 있는 카드가 우선한다.
            val issues =
                listOf(
                    issueView("PROJ-NO-RANK", "open", priority = 1, rank = null),
                    issueView("PROJ-RANKED", "open", priority = 9, rank = "a"),
                )

            val result =
                BoardCardPlacement.placeCards(
                    columns = listOf(openColumn),
                    issues = issues,
                )

            val keys = result.columns.single().cards.map { it.key }
            assertThat(keys).containsExactly("PROJ-RANKED", "PROJ-NO-RANK")
        }

        @Test
        fun `rank가 동일하면 priority 오름차순으로 tiebreak 한다`() {
            val openColumn = column("open")
            val issues =
                listOf(
                    issueView("PROJ-LOW", "open", priority = 3, rank = "same"),
                    issueView("PROJ-HIGH", "open", priority = 1, rank = "same"),
                )

            val result =
                BoardCardPlacement.placeCards(
                    columns = listOf(openColumn),
                    issues = issues,
                )

            val keys = result.columns.single().cards.map { it.key }
            assertThat(keys).containsExactly("PROJ-HIGH", "PROJ-LOW")
        }

        @Test
        fun `rank와 priority가 모두 동일하면 issueKey 오름차순으로 tiebreak 한다`() {
            val openColumn = column("open")
            val issues =
                listOf(
                    issueView("PROJ-3", "open", priority = 2, rank = "same"),
                    issueView("PROJ-1", "open", priority = 2, rank = "same"),
                    issueView("PROJ-2", "open", priority = 2, rank = "same"),
                )

            val result =
                BoardCardPlacement.placeCards(
                    columns = listOf(openColumn),
                    issues = issues,
                )

            val keys = result.columns.single().cards.map { it.key }
            assertThat(keys).containsExactly("PROJ-1", "PROJ-2", "PROJ-3")
        }

        @Test
        fun `rank가 전부 null이면 기존과 동일하게 priority ASC 다음 issueKey ASC로 정렬된다 (무회귀)`() {
            val openColumn = column("open")
            val issues =
                listOf(
                    issueView("PROJ-3", "open", priority = 3, rank = null),
                    issueView("PROJ-1", "open", priority = 1, rank = null),
                    issueView("PROJ-2", "open", priority = 2, rank = null),
                )

            val result =
                BoardCardPlacement.placeCards(
                    columns = listOf(openColumn),
                    issues = issues,
                )

            val keys = result.columns.single().cards.map { it.key }
            assertThat(keys).containsExactly("PROJ-1", "PROJ-2", "PROJ-3")
        }
    }
}
