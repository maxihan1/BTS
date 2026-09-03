// BoardColumn.wipLimit 불변식 + SwimlaneField enum + Board.swimlaneField 기본값 단위테스트

package com.bts.agileplanning.domain

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * BoardColumn.wipLimit 불변식, SwimlaneField enum, Board.swimlaneField 기본값 단위테스트.
 *
 * 외부 의존(DB, Spring, mock) 없이 순수 코틀린 도메인 로직만 검증한다.
 *
 * 검증 시나리오.
 * - (a) BoardColumn.wipLimit 불변식 — 0·음수 거부, null·양수 허용
 * - (b) SwimlaneField enum — 정상 값 3개, 미정의 값 거부, 총 개수 3
 * - (c) Board.swimlaneField 기본값 — 미지정 시 SwimlaneField.NONE
 */
class BoardWipSwimlaneDomainTest {
    // 픽스처 헬퍼
    private fun baseColumn(wipLimit: Int? = null) =
        BoardColumn(
            id = UUID.randomUUID(),
            stateKeys = listOf("open"),
            name = "열림",
            category = "TODO",
            displayOrder = 1,
            wipLimit = wipLimit,
        )

    private fun baseBoard(swimlaneField: SwimlaneField = SwimlaneField.NONE) =
        Board(
            id = UUID.randomUUID(),
            projectKey = "PROJ",
            name = "테스트 보드",
            columns = emptyList(),
            createdAt = Instant.now(),
            updatedAt = Instant.now(),
            swimlaneField = swimlaneField,
        )

    // (a) BoardColumn.wipLimit 불변식
    @Nested
    inner class WipLimitInvariant {
        @Test
        fun `wipLimit 0 전달 시 IllegalArgumentException이 발생한다`() {
            assertThatThrownBy { baseColumn(wipLimit = 0) }
                .isInstanceOf(IllegalArgumentException::class.java)
        }

        @Test
        fun `wipLimit 음수 전달 시 IllegalArgumentException이 발생한다`() {
            assertThatThrownBy { baseColumn(wipLimit = -1) }
                .isInstanceOf(IllegalArgumentException::class.java)
        }

        @Test
        fun `wipLimit null 전달 시 정상 생성된다`() {
            val column = baseColumn(wipLimit = null)
            assertThat(column.wipLimit).isNull()
        }

        @Test
        fun `wipLimit 양수 전달 시 정상 생성된다`() {
            val column = baseColumn(wipLimit = 5)
            assertThat(column.wipLimit).isEqualTo(5)
        }

        @Test
        fun `wipLimit 1 전달 시 정상 생성된다`() {
            val column = baseColumn(wipLimit = 1)
            assertThat(column.wipLimit).isEqualTo(1)
        }
    }

    // (b) SwimlaneField enum 검증
    @Nested
    inner class SwimlaneFieldEnum {
        @Test
        fun `NONE 값을 정상 조회한다`() {
            val field = SwimlaneField.valueOf("NONE")
            assertThat(field).isEqualTo(SwimlaneField.NONE)
        }

        @Test
        fun `ASSIGNEE 값을 정상 조회한다`() {
            val field = SwimlaneField.valueOf("ASSIGNEE")
            assertThat(field).isEqualTo(SwimlaneField.ASSIGNEE)
        }

        @Test
        fun `PRIORITY 값을 정상 조회한다`() {
            val field = SwimlaneField.valueOf("PRIORITY")
            assertThat(field).isEqualTo(SwimlaneField.PRIORITY)
        }

        @Test
        fun `EPIC 값을 정상 조회한다 (FR-EP-01 활성화)`() {
            val field = SwimlaneField.valueOf("EPIC")
            assertThat(field).isEqualTo(SwimlaneField.EPIC)
        }

        @Test
        fun `SwimlaneField 값이 정확히 4개다 (NONE+ASSIGNEE+PRIORITY+EPIC)`() {
            assertThat(SwimlaneField.entries.size).isEqualTo(4)
        }
    }

    // (c) Board.swimlaneField 기본값
    @Nested
    inner class BoardSwimlaneFieldDefault {
        @Test
        fun `swimlaneField 미지정 시 기본값 SwimlaneField_NONE이다`() {
            val board =
                Board(
                    id = UUID.randomUUID(),
                    projectKey = "PROJ",
                    name = "기본값 보드",
                    columns = emptyList(),
                    createdAt = Instant.now(),
                    updatedAt = Instant.now(),
                )
            assertThat(board.swimlaneField).isEqualTo(SwimlaneField.NONE)
        }

        @Test
        fun `swimlaneField ASSIGNEE 지정 시 그대로 보관된다`() {
            val board = baseBoard(swimlaneField = SwimlaneField.ASSIGNEE)
            assertThat(board.swimlaneField).isEqualTo(SwimlaneField.ASSIGNEE)
        }

        @Test
        fun `swimlaneField PRIORITY 지정 시 그대로 보관된다`() {
            val board = baseBoard(swimlaneField = SwimlaneField.PRIORITY)
            assertThat(board.swimlaneField).isEqualTo(SwimlaneField.PRIORITY)
        }
    }
}
