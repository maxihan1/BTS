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

    // ──────────────────────────────────────────────────────────────────────
    // 픽스처 헬퍼
    // ──────────────────────────────────────────────────────────────────────

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
    ) = BoardIssueView(
        key = key,
        summary = summary,
        currentStateKey = currentStateKey,
        assigneeId = null,
        priority = priority,
        version = 1L,
    )

    // ──────────────────────────────────────────────────────────────────────
    // (a) 컬럼 시드 — WorkflowStateView → BoardColumn 매핑 + displayOrder 정렬
    // ──────────────────────────────────────────────────────────────────────

    @Nested
    inner class SeedColumns {

        @Test
        fun `WorkflowStateView 목록을 displayOrder 오름차순으로 컬럼 시드한다`() {
            val states = listOf(
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
            val states = listOf(
                stateView("open", displayOrder = 1),
                stateView("closed", displayOrder = 2),
            )

            val columns = BoardCardPlacement.seedColumns(states)

            val ids = columns.map { it.id }
            assertThat(ids).doesNotHaveDuplicates()
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // (b) 카드 배치 — BoardIssueView → current_state_key 기준 컬럼 배치
    // ──────────────────────────────────────────────────────────────────────

    @Nested
    inner class PlaceCards {

        @Test
        fun `이슈를 current_state_key가 일치하는 컬럼에 배치한다`() {
            val todoColumn = BoardColumn(
                id = UUID.randomUUID(),
                stateKey = "open",
                name = "열림",
                category = "TODO",
                displayOrder = 1,
            )
            val inProgressColumn = BoardColumn(
                id = UUID.randomUUID(),
                stateKey = "in-progress",
                name = "진행 중",
                category = "IN_PROGRESS",
                displayOrder = 2,
            )
            val issues = listOf(
                issueView("PROJ-1", "open"),
                issueView("PROJ-2", "in-progress"),
                issueView("PROJ-3", "open"),
            )

            val placed = BoardCardPlacement.placeCards(
                columns = listOf(todoColumn, inProgressColumn),
                issues = issues,
            )

            val todoCards = placed.first { it.column.stateKey == "open" }.cards
            val inProgressCards = placed.first { it.column.stateKey == "in-progress" }.cards
            assertThat(todoCards.map { it.key }).containsExactlyInAnyOrder("PROJ-1", "PROJ-3")
            assertThat(inProgressCards.map { it.key }).containsExactly("PROJ-2")
        }

        @Test
        fun `이슈가 없는 컬럼은 빈 카드 목록으로 포함된다`() {
            val emptyColumn = BoardColumn(
                id = UUID.randomUUID(),
                stateKey = "closed",
                name = "완료",
                category = "DONE",
                displayOrder = 3,
            )

            val placed = BoardCardPlacement.placeCards(
                columns = listOf(emptyColumn),
                issues = emptyList(),
            )

            assertThat(placed).hasSize(1)
            assertThat(placed.single().cards).isEmpty()
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // (c) 미매핑 상태 이슈 제외 (E2)
    // ──────────────────────────────────────────────────────────────────────

    @Nested
    inner class UnmappedStateExclusion {

        @Test
        fun `컬럼에 없는 state_key의 이슈는 보드에서 제외된다`() {
            val column = BoardColumn(
                id = UUID.randomUUID(),
                stateKey = "open",
                name = "열림",
                category = "TODO",
                displayOrder = 1,
            )
            val issues = listOf(
                issueView("PROJ-1", "open"),
                issueView("PROJ-2", "unknown-state"), // 어떤 컬럼에도 없는 상태
            )

            val placed = BoardCardPlacement.placeCards(
                columns = listOf(column),
                issues = issues,
            )

            val allCards = placed.flatMap { it.cards }
            assertThat(allCards.map { it.key }).containsExactly("PROJ-1")
            assertThat(allCards.map { it.key }).doesNotContain("PROJ-2")
        }

        @Test
        fun `모든 이슈가 미매핑이면 모든 컬럼의 카드가 비어 있다`() {
            val column = BoardColumn(
                id = UUID.randomUUID(),
                stateKey = "open",
                name = "열림",
                category = "TODO",
                displayOrder = 1,
            )
            val issues = listOf(
                issueView("PROJ-1", "ghost-state"),
                issueView("PROJ-2", "another-ghost"),
            )

            val placed = BoardCardPlacement.placeCards(
                columns = listOf(column),
                issues = issues,
            )

            assertThat(placed.flatMap { it.cards }).isEmpty()
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // (d) 컬럼 내 카드 정렬 — priority ASC, 동순위는 issueKey ASC 보조
    // ──────────────────────────────────────────────────────────────────────

    @Nested
    inner class CardSortOrder {

        @Test
        fun `컬럼 내 카드는 priority 오름차순으로 정렬된다`() {
            val column = BoardColumn(
                id = UUID.randomUUID(),
                stateKey = "open",
                name = "열림",
                category = "TODO",
                displayOrder = 1,
            )
            val issues = listOf(
                issueView("PROJ-3", "open", priority = 3),
                issueView("PROJ-1", "open", priority = 1),
                issueView("PROJ-2", "open", priority = 2),
            )

            val placed = BoardCardPlacement.placeCards(
                columns = listOf(column),
                issues = issues,
            )

            val keys = placed.single().cards.map { it.key }
            assertThat(keys).containsExactly("PROJ-1", "PROJ-2", "PROJ-3")
        }

        @Test
        fun `priority가 동일하면 issueKey 오름차순으로 안정 정렬된다`() {
            val column = BoardColumn(
                id = UUID.randomUUID(),
                stateKey = "open",
                name = "열림",
                category = "TODO",
                displayOrder = 1,
            )
            val issues = listOf(
                issueView("PROJ-3", "open", priority = 2),
                issueView("PROJ-1", "open", priority = 2),
                issueView("PROJ-2", "open", priority = 2),
            )

            val placed = BoardCardPlacement.placeCards(
                columns = listOf(column),
                issues = issues,
            )

            val keys = placed.single().cards.map { it.key }
            assertThat(keys).containsExactly("PROJ-1", "PROJ-2", "PROJ-3")
        }

        @Test
        fun `priority 1이 최상위이고 숫자가 클수록 낮은 우선순위다`() {
            val column = BoardColumn(
                id = UUID.randomUUID(),
                stateKey = "open",
                name = "열림",
                category = "TODO",
                displayOrder = 1,
            )
            val issues = listOf(
                issueView("PROJ-LOW", "open", priority = 5),
                issueView("PROJ-HIGH", "open", priority = 1),
            )

            val placed = BoardCardPlacement.placeCards(
                columns = listOf(column),
                issues = issues,
            )

            val keys = placed.single().cards.map { it.key }
            assertThat(keys).containsExactly("PROJ-HIGH", "PROJ-LOW")
        }
    }
}
