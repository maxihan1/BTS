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
 * | ⑥ 순서 교체 | `updateColumnOrder` 가 display_order 를 0부터 다시 매긴다 | R10·J25 |
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

    // ── ⑥ 컬럼 순서 교체 (R10 · J25) ──────────────────────────────────────────

    @Test
    fun `updateColumnOrder 가 display_order 를 0부터 다시 매기고 갱신 건수를 돌려준다`() {
        // ★리뷰 CONCERNS C3 — 이 batch renumber SQL 은 이 PR 이 새로 쓴 DB 쓰기 경로인데
        //   어떤 테스트도 실행하지 않았다(E2E 는 MSW 라 백엔드에 닿지 않는다). 메서드 본문을
        //   `return 0` 으로 바꿔도 전부 초록이었다.
        val board = insertBoard(listOf(col("todo"), col("in_progress"), col("done")))
        val (first, second, third) = board.columns

        val affected = columnStateRepository.updateColumnOrder(board.id, listOf(third.id, first.id, second.id))

        assertThat(affected).isEqualTo(3)
        assertThat(boardRepository.findById(board.id)!!.columns.map { it.id })
            .containsExactly(third.id, first.id, second.id)
        // display_order 값 자체를 본다 — 조회 정렬만 보면 0·1·2 가 아니라 5·9·12 여도 통과한다.
        assertThat(displayOrders(board.id)).containsExactlyInAnyOrderEntriesOf(
            mapOf(third.id to 0, first.id to 1, second.id to 2),
        )
    }

    @Test
    fun `updateColumnOrder 는 타 보드 컬럼을 건드리지 않고 건수에서도 빠진다`() {
        // 서비스의 집합 판정이 뚫려도 남의 보드를 흔들지 못하는 두 번째 방벽(boardId 술어).
        val mine = insertBoard(listOf(col("todo"), col("done")))
        val other = insertBoard(listOf(col("todo")))
        val stranger = other.columns[0]

        val affected = columnStateRepository.updateColumnOrder(mine.id, listOf(stranger.id, mine.columns[1].id))

        assertThat(affected).isEqualTo(1)
        assertThat(displayOrders(other.id)[stranger.id]).isEqualTo(0)
    }

    @Test
    fun `updateColumnOrder 는 빈 목록에 SQL 을 보내지 않고 0 을 돌려준다`() {
        val board = insertBoard(listOf(col("todo")))

        assertThat(columnStateRepository.updateColumnOrder(board.id, emptyList())).isEqualTo(0)
        assertThat(displayOrders(board.id)[board.columns[0].id]).isEqualTo(0)
    }

    // ── 헬퍼 ───────────────────────────────────────────────────────────────────

    /** board_columns.display_order 원본 값 — 조회 정렬이 아니라 저장된 숫자를 본다. */
    private fun displayOrders(boardId: UUID): Map<UUID, Int> =
        dsl.select(BOARD_COLUMNS.ID, BOARD_COLUMNS.DISPLAY_ORDER)
            .from(BOARD_COLUMNS)
            .where(BOARD_COLUMNS.BOARD_ID.eq(boardId))
            .fetch()
            .associate { it.value1()!! to it.value2()!! }

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
