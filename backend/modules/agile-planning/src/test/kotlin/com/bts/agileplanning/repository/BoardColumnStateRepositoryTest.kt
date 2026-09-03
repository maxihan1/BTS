// agile-planning BoardColumnStateRepository 통합 테스트 — 컬럼 ↔ 상태 1:N 읽기·쓰기 (V508)

package com.bts.agileplanning.repository

import com.bts.agileplanning.AgilePlanningTestBootApplication
import com.bts.agileplanning.AgilePlanningTestcontainersConfig
import com.bts.agileplanning.domain.Board
import com.bts.agileplanning.domain.BoardColumn
import com.bts.agileplanning.jooq.tables.references.BOARD_COLUMNS
import org.assertj.core.api.Assertions.assertThat
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import java.util.UUID

/**
 * [BoardColumnStateRepository] 통합 테스트 — 컬럼이 상태 여러 개를 담는 읽기·쓰기 경로.
 *
 * ## 왜 별도 리포지터리인가 (N2)
 * `BoardRepository.kt` 는 이미 `DEVELOPMENT.md §2.1` 의 파일 줄수 상한을 넘겼다(부채 157).
 * 새 조인 쿼리를 거기 넣으면 그 부채가 더 깊어진다. 컬럼–상태 매핑은 자체 응집이 있는 관심사라
 * 분리해도 어색하지 않다.
 *
 * ## 이 클래스가 지는 판정
 *
 * | 축 | 무엇 | 근거 |
 * |---|---|---|
 * | ① 조인 읽기 | 컬럼별 상태 집합을 한 번의 조회로 읽는다 | R1·N3 |
 * | ② 다중 상태 쓰기 | 컬럼에 상태 둘을 쓰면 두 행이 생기고 순서가 보존된다 | R1 |
 * | ③ **이중 기록** | 쓰기가 레거시 `board_columns.state_key` 에 「첫 상태」를 함께 남긴다 | E5 |
 * | ④ 상태 0개 | 상태를 비우면 레거시 칸이 NULL 이 된다 | E1·G1 |
 * | ⑤ 교체 의미론 | `replaceStates` 가 집합을 통째로 갈아끼운다 | R9 |
 *
 * ★ ③ 의 「첫」은 `(display_order, state_key)` 오름차순 최소이고, 그 정의는
 * [BoardColumn.legacyStateKey] 한 곳에 산다. 문서에만 적으면 이중 기록과 프론트 전송(R12)이
 * 서로 다른 「첫」을 골라 레거시 컬럼과 화면이 갈린다(plan 리뷰 CONCERN-2).
 *
 * ## 설정 공유
 * [AgilePlanningTestcontainersConfig] 의 singleton PostgreSQL 컨테이너를 재사용한다.
 * 마이그레이션 자체의 검증은 전용 컨테이너를 쓰는
 * [com.bts.agileplanning.migration.BoardColumnStatesMigrationTest] 가 진다.
 */
@SpringBootTest(classes = [AgilePlanningTestBootApplication::class])
@Import(AgilePlanningTestcontainersConfig::class)
@ActiveProfiles("test")
class BoardColumnStateRepositoryTest {
    @Autowired
    private lateinit var boardRepository: BoardRepository

    @Autowired
    private lateinit var columnStateRepository: BoardColumnStateRepository

    @Autowired
    private lateinit var dsl: DSLContext

    // ── ① 조인 읽기 (R1 · N3) ──────────────────────────────────────────────────

    @Test
    fun `보드 조회가 컬럼별 상태 집합을 함께 읽는다`() {
        val board = insertBoard(listOf(col("todo"), col("in_progress")))

        val found = boardRepository.findById(board.id)

        assertThat(found).isNotNull
        assertThat(found!!.columns.map { it.stateKeys })
            .containsExactly(listOf("todo"), listOf("in_progress"))
    }

    // ── ② 다중 상태 쓰기 (R1) ──────────────────────────────────────────────────

    @Test
    fun `컬럼에 상태 둘을 쓰면 두 행이 생기고 순서가 보존된다`() {
        val board = insertBoard(listOf(col("in_progress")))
        val columnId = board.columns.single().id

        columnStateRepository.replaceStates(board.id, columnId, listOf("in_progress", "in_review"))

        val found = boardRepository.findById(board.id)
        assertThat(found!!.columns.single().stateKeys).containsExactly("in_progress", "in_review")
    }

    // ── ③ 이중 기록 (E5) ───────────────────────────────────────────────────────

    @Test
    fun `쓰기가 레거시 state_key 에 첫 상태를 함께 남긴다`() {
        val board = insertBoard(listOf(col("in_progress")))
        val columnId = board.columns.single().id

        columnStateRepository.replaceStates(board.id, columnId, listOf("in_progress", "in_review"))

        // 레거시 칸은 롤백 대비 사본이다. 읽기는 이미 board_column_states 만 본다.
        val legacy =
            dsl.select(BOARD_COLUMNS.STATE_KEY)
                .from(BOARD_COLUMNS)
                .where(BOARD_COLUMNS.ID.eq(columnId))
                .fetchOne(BOARD_COLUMNS.STATE_KEY)

        assertThat(legacy).isEqualTo("in_progress")
    }

    // ── ④ 상태 0개 (E1 · G1) ───────────────────────────────────────────────────

    @Test
    fun `상태를 전부 비우면 레거시 state_key 가 NULL 이 된다`() {
        val board = insertBoard(listOf(col("todo")))
        val columnId = board.columns.single().id

        columnStateRepository.replaceStates(board.id, columnId, emptyList())

        val legacy =
            dsl.select(BOARD_COLUMNS.STATE_KEY)
                .from(BOARD_COLUMNS)
                .where(BOARD_COLUMNS.ID.eq(columnId))
                .fetchOne(BOARD_COLUMNS.STATE_KEY)

        assertThat(legacy).isNull()
        assertThat(boardRepository.findById(board.id)!!.columns.single().stateKeys).isEmpty()
    }

    // ── ⑤ 교체 의미론 (R9) ─────────────────────────────────────────────────────

    @Test
    fun `replaceStates 는 집합을 통째로 갈아끼운다`() {
        val board = insertBoard(listOf(col("todo")))
        val columnId = board.columns.single().id
        columnStateRepository.replaceStates(board.id, columnId, listOf("a", "b", "c"))

        columnStateRepository.replaceStates(board.id, columnId, listOf("b", "d"))

        assertThat(boardRepository.findById(board.id)!!.columns.single().stateKeys)
            .containsExactly("b", "d")
    }

    @Test
    fun `한 보드의 다른 컬럼이 쓰는 상태를 넣으면 거부된다`() {
        // 편차 X1 — 한 상태는 한 보드에서 한 컬럼에만. DB 가 UNIQUE(board_id, state_key) 로 진다.
        val board = insertBoard(listOf(col("todo"), col("done")))
        val (first, second) = board.columns

        val thrown =
            runCatching {
                columnStateRepository.replaceStates(board.id, second.id, listOf("todo"))
            }.exceptionOrNull()

        assertThat(thrown).isNotNull
        // 원래 매핑은 그대로다.
        assertThat(boardRepository.findById(board.id)!!.columns.first { it.id == first.id }.stateKeys)
            .containsExactly("todo")
    }

    // ── 헬퍼 ───────────────────────────────────────────────────────────────────

    private fun col(stateKey: String) =
        BoardColumn(
            id = UUID.randomUUID(),
            stateKeys = listOf(stateKey),
            name = stateKey,
            category = "TODO",
            displayOrder = 0,
        )

    private fun insertBoard(columns: List<BoardColumn>): Board =
        boardRepository.insert(
            Board(
                id = UUID.randomUUID(),
                projectKey = "CST${UUID.randomUUID().toString().take(4).uppercase()}",
                name = "컬럼 상태 테스트 보드",
                columns = columns.mapIndexed { i, c -> c.copy(displayOrder = i) },
                createdAt = java.time.Instant.now(),
                updatedAt = java.time.Instant.now(),
            ),
        )
}
