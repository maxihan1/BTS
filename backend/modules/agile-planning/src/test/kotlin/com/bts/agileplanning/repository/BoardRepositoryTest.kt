// agile-planning BoardRepository 통합 테스트 — swimlaneField / wipLimit 새 컬럼 read/write + update 2메서드 (FR-BD-03)

package com.bts.agileplanning.repository

import com.bts.agileplanning.AgilePlanningTestBootApplication
import com.bts.agileplanning.AgilePlanningTestcontainersConfig
import com.bts.agileplanning.domain.Board
import com.bts.agileplanning.domain.BoardColumn
import com.bts.agileplanning.domain.SwimlaneField
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import java.util.UUID

/**
 * [BoardRepository] 통합 테스트.
 *
 * Testcontainers PostgreSQL 16-alpine 위에서 Flyway V500~V501 마이그레이션을 적용한 뒤
 * [BoardRepository] 의 새 컬럼(swimlaneField / wipLimit) read/write 와 update 2메서드를 검증한다.
 *
 * ## 검증 범위 (FR-BD-03 Task 3)
 * 1. insert → findById 라운드트립 — swimlaneField / wipLimit 보존.
 * 2. updateSwimlaneField — 갱신 후 findById 반영 + 존재하지 않는 boardId → null.
 * 3. updateColumnWipLimit — 갱신(7)/해제(null) 후 findById 반영.
 * 4. 타 보드 소속 / 존재하지 않는 columnId → null(affected 0).
 *
 * ## 설정 공유
 * [AgilePlanningTestcontainersConfig] 의 singleton PostgreSQL 컨테이너와 Flyway 마이그레이션을 재사용한다.
 * (migration: BoardSchemaMigrationTest 은 Spring 컨텍스트 없이 직접 JDBC 를 사용하므로 별개 컨테이너.)
 */
@SpringBootTest(classes = [AgilePlanningTestBootApplication::class])
@Import(AgilePlanningTestcontainersConfig::class)
@ActiveProfiles("test")
class BoardRepositoryTest {
    @Autowired
    private lateinit var boardRepository: BoardRepository

    // ── 헬퍼 ──────────────────────────────────────────────────────────────────

    /** 기본 컬럼 1개를 포함한 Board 도메인 객체를 생성한다. */
    private fun buildBoard(
        swimlaneField: SwimlaneField = SwimlaneField.NONE,
        wipLimit: Int? = null,
    ): Board {
        val colId = UUID.randomUUID()
        return Board(
            id = UUID.randomUUID(),
            projectKey = "TEST",
            name = "테스트 보드 ${UUID.randomUUID()}",
            columns =
                listOf(
                    BoardColumn(
                        id = colId,
                        stateKey = "open",
                        name = "열림",
                        category = "TODO",
                        displayOrder = 0,
                        wipLimit = wipLimit,
                    ),
                ),
            createdAt = java.time.Instant.now(),
            updatedAt = java.time.Instant.now(),
            swimlaneField = swimlaneField,
        )
    }

    // ── (1) insert → findById 라운드트립 ──────────────────────────────────────

    @Test
    fun `insert 후 findById 가 swimlaneField ASSIGNEE 를 보존한다`() {
        val board = buildBoard(swimlaneField = SwimlaneField.ASSIGNEE)
        val saved = boardRepository.insert(board)

        assertThat(saved.swimlaneField).isEqualTo(SwimlaneField.ASSIGNEE)
        val found = boardRepository.findById(saved.id)
        assertThat(found).isNotNull
        assertThat(found!!.swimlaneField).isEqualTo(SwimlaneField.ASSIGNEE)
    }

    @Test
    fun `insert 후 findById 가 컬럼 wipLimit 5 를 보존한다`() {
        val board = buildBoard(wipLimit = 5)
        val saved = boardRepository.insert(board)

        val found = boardRepository.findById(saved.id)
        assertThat(found).isNotNull
        assertThat(found!!.columns).hasSize(1)
        assertThat(found.columns[0].wipLimit).isEqualTo(5)
    }

    @Test
    fun `insert 후 findById 가 컬럼 wipLimit null 을 보존한다`() {
        val board = buildBoard(wipLimit = null)
        val saved = boardRepository.insert(board)

        val found = boardRepository.findById(saved.id)
        assertThat(found).isNotNull
        assertThat(found!!.columns[0].wipLimit as Int?).isNull()
    }

    // ── (2) updateSwimlaneField ───────────────────────────────────────────────

    @Test
    fun `updateSwimlaneField 후 findById 가 PRIORITY 를 반영한다`() {
        val board = buildBoard(swimlaneField = SwimlaneField.NONE)
        boardRepository.insert(board)

        val updated = boardRepository.updateSwimlaneField(board.id, SwimlaneField.PRIORITY)
        assertThat(updated).isNotNull
        assertThat(updated!!.swimlaneField).isEqualTo(SwimlaneField.PRIORITY)

        val found = boardRepository.findById(board.id)
        assertThat(found!!.swimlaneField).isEqualTo(SwimlaneField.PRIORITY)
    }

    @Test
    fun `updateSwimlaneField 존재하지 않는 boardId 는 null 을 반환한다`() {
        val result = boardRepository.updateSwimlaneField(UUID.randomUUID(), SwimlaneField.ASSIGNEE)
        assertThat(result as Any?).isNull()
    }

    // ── (3) updateColumnWipLimit — 설정 및 해제 ───────────────────────────────

    @Test
    fun `updateColumnWipLimit 7 로 설정 후 findById 컬럼 wipLimit 이 7 이다`() {
        val board = buildBoard(wipLimit = null)
        val saved = boardRepository.insert(board)
        val columnId = saved.columns[0].id

        val updated = boardRepository.updateColumnWipLimit(saved.id, columnId, 7)
        assertThat(updated).isNotNull
        assertThat(updated!!.wipLimit).isEqualTo(7)

        val found = boardRepository.findById(saved.id)
        assertThat(found!!.columns[0].wipLimit).isEqualTo(7)
    }

    @Test
    fun `updateColumnWipLimit null 로 해제 후 findById 컬럼 wipLimit 이 null 이다`() {
        val board = buildBoard(wipLimit = 3)
        val saved = boardRepository.insert(board)
        val columnId = saved.columns[0].id

        val updated = boardRepository.updateColumnWipLimit(saved.id, columnId, null)
        assertThat(updated as Any?).isNotNull()
        assertThat(updated!!.wipLimit as Int?).isNull()

        val found = boardRepository.findById(saved.id)
        assertThat(found!!.columns[0].wipLimit as Int?).isNull()
    }

    // ── (4) updateColumnWipLimit — 타 보드 소속 / 미존재 columnId → null ───────

    @Test
    fun `updateColumnWipLimit 존재하지 않는 columnId 는 null 을 반환한다`() {
        val board = buildBoard()
        val saved = boardRepository.insert(board)

        val result = boardRepository.updateColumnWipLimit(saved.id, UUID.randomUUID(), 10)
        assertThat(result as Any?).isNull()
    }

    @Test
    fun `updateColumnWipLimit 타 보드 소속 columnId 는 null 을 반환한다`() {
        val boardA = buildBoard()
        val boardB = buildBoard()
        val savedA = boardRepository.insert(boardA)
        val savedB = boardRepository.insert(boardB)

        // boardB 의 columnId 를 boardA 의 boardId 로 업데이트 시도 — 타 보드 소속이므로 null.
        val columnBId = savedB.columns[0].id
        val result = boardRepository.updateColumnWipLimit(savedA.id, columnBId, 5)
        assertThat(result as Any?).isNull()
    }
}
