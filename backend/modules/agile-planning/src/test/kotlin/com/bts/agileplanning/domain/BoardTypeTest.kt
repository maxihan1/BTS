// BoardType 파싱·기본값 계약 테스트 — 허용값 밖 입력이 이름 있는 예외로 떨어지는지 고정한다

package com.bts.agileplanning.domain

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

@DisplayName("BoardType — 보드 종류(스크럼/칸반)")
class BoardTypeTest {
    @Test
    fun `허용값을 대소문자 무관하게 파싱한다`() {
        assertThat(BoardType.from("SCRUM")).isEqualTo(BoardType.SCRUM)
        assertThat(BoardType.from("scrum")).isEqualTo(BoardType.SCRUM)
        assertThat(BoardType.from("KANBAN")).isEqualTo(BoardType.KANBAN)
    }

    @Test
    fun `null 이면 KANBAN 이다 — 기존 호출자가 종류를 안 보내도 깨지지 않는다`() {
        assertThat(BoardType.from(null)).isEqualTo(BoardType.KANBAN)
    }

    /**
     * ★ 맨 [IllegalArgumentException] 이 아니라 **이름 있는 예외**여야 한다.
     *
     * `Board.kt` 의 [BoardNameInvalidException] KDoc 이 이유를 적는다 — 맨
     * `IllegalArgumentException` 을 400 으로 매핑하면 호출 사슬 어디에서 터지든
     * (예: `NumberFormatException`) 내부 버그가 400 으로 나가 5xx 경보에서 사라진다.
     */
    @Test
    fun `허용값 밖이면 이름 있는 예외를 던진다`() {
        assertThatThrownBy { BoardType.from("SCRAM") }
            .isInstanceOf(BoardTypeInvalidException::class.java)
            .hasMessageContaining("SCRAM")
    }

    @Test
    fun `빈 문자열도 허용값 밖이다`() {
        assertThatThrownBy { BoardType.from("") }
            .isInstanceOf(BoardTypeInvalidException::class.java)
    }

    @Test
    fun `Board 의 기본 종류는 KANBAN 이다 — 기존 보드 전량 보존`() {
        val board =
            Board(
                id = UUID.randomUUID(),
                projectKey = "ATLAS",
                name = "보드",
                columns = emptyList(),
                createdAt = Instant.now(),
                updatedAt = Instant.now(),
            )
        assertThat(board.boardType).isEqualTo(BoardType.KANBAN)
    }
}
