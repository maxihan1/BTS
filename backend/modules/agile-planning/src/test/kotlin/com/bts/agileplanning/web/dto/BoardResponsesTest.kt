// BoardColumnWithCardsResponse.wipLimit/wipExceeded + BoardDetailResponse.swimlaneField DTO 변환 단위테스트

package com.bts.agileplanning.web.dto

import com.bts.agileplanning.application.BoardPlacementResult
import com.bts.agileplanning.domain.Board
import com.bts.agileplanning.domain.BoardColumn
import com.bts.agileplanning.domain.PlacedColumn
import com.bts.agileplanning.domain.SwimlaneField
import com.bts.shared.board.BoardIssueView
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * BoardColumnWithCardsResponse.from + BoardDetailResponse.of DTO 변환 단위테스트.
 *
 * 외부 의존(DB, Spring, mock) 없이 순수 변환 로직만 검증한다.
 *
 * 검증 시나리오.
 * - (a) wipLimit echo — placed.column.wipLimit 값 그대로 반영
 * - (b) wipExceeded 경계값 — 초과/미만/같음/null/카드0건 케이스
 * - (c) swimlaneField — board.swimlaneField.name 을 문자열로 노출
 * - (d) 기존 필드 보존 — 신규 필드 추가 후 기존 필드 비파괴 확인
 */
class BoardResponsesTest {
    // --- 픽스처 헬퍼 ---

    private fun column(wipLimit: Int? = null) =
        BoardColumn(
            id = UUID.randomUUID(),
            stateKey = "open",
            name = "열림",
            category = "TODO",
            displayOrder = 1,
            wipLimit = wipLimit,
        )

    private fun card(key: String = "PROJ-1") =
        BoardIssueView(
            key = key,
            summary = "테스트 이슈",
            currentStateKey = "open",
            assigneeId = null,
            priority = 1,
            version = 0L,
        )

    private fun cards(count: Int): List<BoardIssueView> = (1..count).map { card("PROJ-$it") }

    private fun placed(
        wipLimit: Int? = null,
        cardCount: Int = 0,
    ): PlacedColumn = PlacedColumn(column = column(wipLimit), cards = cards(cardCount))

    private fun board(swimlaneField: SwimlaneField = SwimlaneField.NONE) =
        Board(
            id = UUID.randomUUID(),
            projectKey = "PROJ",
            name = "테스트 보드",
            columns = emptyList(),
            createdAt = Instant.now(),
            updatedAt = Instant.now(),
            swimlaneField = swimlaneField,
        )

    private fun placementResult(columns: List<PlacedColumn> = emptyList()) =
        BoardPlacementResult(
            columns = columns,
            truncated = false,
            unplacedCount = 0,
        )

    // --- (a) wipLimit echo ---

    @Nested
    inner class WipLimitEcho {
        @Test
        fun `wipLimit null 인 컬럼은 응답 wipLimit 도 null 이다`() {
            val response = BoardColumnWithCardsResponse.from(placed(wipLimit = null))
            assertThat(response.wipLimit).isNull()
        }

        @Test
        fun `wipLimit 3 인 컬럼은 응답 wipLimit 도 3 이다`() {
            val response = BoardColumnWithCardsResponse.from(placed(wipLimit = 3))
            assertThat(response.wipLimit).isEqualTo(3)
        }

        @Test
        fun `wipLimit 1 인 컬럼은 응답 wipLimit 도 1 이다`() {
            val response = BoardColumnWithCardsResponse.from(placed(wipLimit = 1))
            assertThat(response.wipLimit).isEqualTo(1)
        }
    }

    // --- (b) wipExceeded 경계값 ---

    @Nested
    inner class WipExceeded {
        @Test
        fun `wipLimit 3 카드 5건이면 wipExceeded true 다`() {
            val response = BoardColumnWithCardsResponse.from(placed(wipLimit = 3, cardCount = 5))
            assertThat(response.wipExceeded).isTrue()
        }

        @Test
        fun `wipLimit 3 카드 2건이면 wipExceeded false 다`() {
            val response = BoardColumnWithCardsResponse.from(placed(wipLimit = 3, cardCount = 2))
            assertThat(response.wipExceeded).isFalse()
        }

        @Test
        fun `wipLimit 3 카드 3건이면 같으므로 wipExceeded false 다`() {
            val response = BoardColumnWithCardsResponse.from(placed(wipLimit = 3, cardCount = 3))
            assertThat(response.wipExceeded).isFalse()
        }

        @Test
        fun `wipLimit null 이면 카드가 많아도 wipExceeded false 다`() {
            val response = BoardColumnWithCardsResponse.from(placed(wipLimit = null, cardCount = 100))
            assertThat(response.wipExceeded).isFalse()
        }

        @Test
        fun `카드 0건이면 wipExceeded false 다`() {
            val response = BoardColumnWithCardsResponse.from(placed(wipLimit = 3, cardCount = 0))
            assertThat(response.wipExceeded).isFalse()
        }

        @Test
        fun `wipLimit null 카드 0건이면 wipExceeded false 다`() {
            val response = BoardColumnWithCardsResponse.from(placed(wipLimit = null, cardCount = 0))
            assertThat(response.wipExceeded).isFalse()
        }
    }

    // --- (c) swimlaneField 노출 ---

    @Nested
    inner class SwimlaneFieldExposure {
        @Test
        fun `board swimlaneField NONE 이면 응답 swimlaneField 는 문자열 NONE 이다`() {
            val response = BoardDetailResponse.of(board(SwimlaneField.NONE), placementResult())
            assertThat(response.swimlaneField).isEqualTo("NONE")
        }

        @Test
        fun `board swimlaneField ASSIGNEE 이면 응답 swimlaneField 는 문자열 ASSIGNEE 이다`() {
            val response = BoardDetailResponse.of(board(SwimlaneField.ASSIGNEE), placementResult())
            assertThat(response.swimlaneField).isEqualTo("ASSIGNEE")
        }

        @Test
        fun `board swimlaneField PRIORITY 이면 응답 swimlaneField 는 문자열 PRIORITY 이다`() {
            val response = BoardDetailResponse.of(board(SwimlaneField.PRIORITY), placementResult())
            assertThat(response.swimlaneField).isEqualTo("PRIORITY")
        }
    }

    // --- (d) BoardCardResponse.priority 노출 ---

    @Nested
    inner class BoardCardResponsePriority {
        @Test
        fun `BoardCardResponse 는 BoardIssueView 의 priority 를 그대로 노출한다`() {
            val view = card("PROJ-1")
            val response = BoardCardResponse.from(view)
            assertThat(response.priority).isEqualTo(view.priority)
        }

        @Test
        fun `priority 0 인 카드는 응답 priority 도 0 이다`() {
            val view =
                BoardIssueView(
                    key = "PROJ-2",
                    summary = "우선순위 최상",
                    currentStateKey = "open",
                    assigneeId = null,
                    priority = 0,
                    version = 0L,
                )
            val response = BoardCardResponse.from(view)
            assertThat(response.priority).isEqualTo(0)
        }

        @Test
        fun `priority 99 인 카드는 응답 priority 도 99 이다`() {
            val view =
                BoardIssueView(
                    key = "PROJ-3",
                    summary = "우선순위 최하",
                    currentStateKey = "open",
                    assigneeId = null,
                    priority = 99,
                    version = 0L,
                )
            val response = BoardCardResponse.from(view)
            assertThat(response.priority).isEqualTo(99)
        }
    }

    // --- (e) 기존 필드 보존 ---

    @Nested
    inner class ExistingFieldsPreserved {
        @Test
        fun `BoardColumnWithCardsResponse 기존 필드가 신규 필드 추가 후에도 유지된다`() {
            val col = column(wipLimit = 2)
            val cardList = cards(1)
            val placedColumn = PlacedColumn(column = col, cards = cardList)
            val response = BoardColumnWithCardsResponse.from(placedColumn)

            assertThat(response.columnId).isEqualTo(col.id)
            assertThat(response.stateKey).isEqualTo(col.stateKey)
            assertThat(response.name).isEqualTo(col.name)
            assertThat(response.category).isEqualTo(col.category)
            assertThat(response.displayOrder).isEqualTo(col.displayOrder)
            assertThat(response.cards).hasSize(1)
        }

        @Test
        fun `BoardDetailResponse 기존 필드가 신규 필드 추가 후에도 유지된다`() {
            val b = board()
            val result = placementResult()
            val response = BoardDetailResponse.of(b, result)

            assertThat(response.boardId).isEqualTo(b.id)
            assertThat(response.projectKey).isEqualTo(b.projectKey)
            assertThat(response.name).isEqualTo(b.name)
            assertThat(response.truncated).isFalse()
            assertThat(response.unplacedCount).isEqualTo(0)
        }
    }
}
