// agile-planning BoardQuickFilterRepository 통합 테스트 — CRUD + UNIQUE 위반 전파 + 교차 보드 차단 (FR-UX-01 Task 4)

package com.bts.agileplanning.repository

import com.bts.agileplanning.AgilePlanningTestBootApplication
import com.bts.agileplanning.AgilePlanningTestcontainersConfig
import com.bts.agileplanning.domain.Board
import com.bts.agileplanning.domain.QuickFilter
import com.bts.agileplanning.jooq.tables.references.BOARD_QUICK_FILTERS
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.test.context.ActiveProfiles
import java.time.Instant
import java.util.UUID

/**
 * [BoardQuickFilterRepository] 통합 테스트.
 *
 * Testcontainers PostgreSQL 위에서 Flyway V504 마이그레이션을 적용한 뒤
 * insert/findByBoardId/findByIdAndBoardId/update/delete/countByBoardId 를 검증한다.
 *
 * ## 검증 범위 (FR-UX-01 Task 4)
 * 1. insert → findByIdAndBoardId 라운드트립.
 * 2. insert 시 UNIQUE(board_id, name) 위반 — 리포지토리가 삼키지 않고 그대로 전파(서비스 T5 가 409 로 변환).
 * 3. findByBoardId — created_at ASC 정렬.
 * 4. findByIdAndBoardId — 타 보드 소속이면 null(교차 참조 차단).
 * 5. update — name/query 갱신 + updated_at 갱신.
 * 6. delete — 삭제 행 수 반영(boolean).
 * 7. countByBoardId — 20건 상한 검사용(T5) 개수 집계.
 *
 * ## 설정 공유
 * [AgilePlanningTestcontainersConfig] 의 singleton PostgreSQL 컨테이너와 Flyway 마이그레이션을 재사용한다.
 * 이 설정의 DSLContext 는 `JooqExceptionTranslator` 를 등록하므로, UNIQUE 위반은
 * jOOQ-native 예외가 아닌 Spring [DataIntegrityViolationException] 으로 관측된다.
 */
@SpringBootTest(classes = [AgilePlanningTestBootApplication::class])
@Import(AgilePlanningTestcontainersConfig::class)
@ActiveProfiles("test")
class BoardQuickFilterRepositoryTest {
    @Autowired
    private lateinit var quickFilterRepository: BoardQuickFilterRepository

    @Autowired
    private lateinit var boardRepository: BoardRepository

    @Autowired
    private lateinit var dsl: DSLContext

    // ── 헬퍼 ──────────────────────────────────────────────────────────────────

    /** FK 충족용 보드를 시드한다. 컬럼은 이 테스트 범위 밖이라 빈 목록. */
    private fun seedBoard(): Board {
        val board =
            Board(
                id = UUID.randomUUID(),
                projectKey = "TEST",
                name = "테스트 보드 ${UUID.randomUUID()}",
                columns = emptyList(),
                createdAt = Instant.now(),
                updatedAt = Instant.now(),
            )
        return boardRepository.insert(board)
    }

    private fun quickFilter(
        boardId: UUID,
        name: String = "내 버그",
        query: String = "assignee=me&label=bug",
    ) = QuickFilter(
        id = UUID.randomUUID(),
        boardId = boardId,
        name = name,
        query = query,
    )

    // ── (1) insert → findByIdAndBoardId 라운드트립 ────────────────────────────

    @Test
    fun `insert 후 findByIdAndBoardId 로 조회하면 저장한 필드가 보존된다`() {
        val board = seedBoard()
        val filter = quickFilter(boardId = board.id, name = "내 버그", query = "assignee=me&label=bug")

        val saved = quickFilterRepository.insert(filter)
        assertThat(saved.id).isEqualTo(filter.id)
        assertThat(saved.boardId).isEqualTo(board.id)
        assertThat(saved.name).isEqualTo("내 버그")
        assertThat(saved.query).isEqualTo("assignee=me&label=bug")

        val found = quickFilterRepository.findByIdAndBoardId(filter.id, board.id)
        assertThat(found).isNotNull
        assertThat(found!!.name).isEqualTo("내 버그")
        assertThat(found.query).isEqualTo("assignee=me&label=bug")
    }

    // ── (2) insert — UNIQUE(board_id, name) 위반 전파 ─────────────────────────

    @Test
    fun `insert 시 같은 board_id+name 이 이미 있으면 UNIQUE 위반 예외가 전파된다`() {
        val board = seedBoard()
        quickFilterRepository.insert(quickFilter(boardId = board.id, name = "내 버그", query = "assignee=me"))

        val duplicate = quickFilter(boardId = board.id, name = "내 버그", query = "label=bug")

        assertThatThrownBy { quickFilterRepository.insert(duplicate) }
            .isInstanceOf(DataIntegrityViolationException::class.java)
    }

    @Test
    fun `insert 시 다른 보드면 같은 name 이어도 성공한다`() {
        val boardA = seedBoard()
        val boardB = seedBoard()
        quickFilterRepository.insert(quickFilter(boardId = boardA.id, name = "내 버그"))

        val savedB = quickFilterRepository.insert(quickFilter(boardId = boardB.id, name = "내 버그"))
        assertThat(savedB.boardId).isEqualTo(boardB.id)
    }

    // ── (3) findByBoardId — created_at ASC ────────────────────────────────────

    @Test
    fun `findByBoardId 는 created_at ASC 순서로 반환한다`() {
        val board = seedBoard()
        val first = quickFilterRepository.insert(quickFilter(boardId = board.id, name = "먼저"))
        Thread.sleep(10)
        val second = quickFilterRepository.insert(quickFilter(boardId = board.id, name = "나중"))

        val result = quickFilterRepository.findByBoardId(board.id)
        assertThat(result).hasSize(2)
        assertThat(result.map { it.id }).containsExactly(first.id, second.id)
    }

    @Test
    fun `findByBoardId 는 다른 보드의 퀵필터를 포함하지 않는다`() {
        val boardA = seedBoard()
        val boardB = seedBoard()
        quickFilterRepository.insert(quickFilter(boardId = boardA.id, name = "A필터"))
        quickFilterRepository.insert(quickFilter(boardId = boardB.id, name = "B필터"))

        val result = quickFilterRepository.findByBoardId(boardA.id)
        assertThat(result).hasSize(1)
        assertThat(result[0].name).isEqualTo("A필터")
    }

    // ── (4) findByIdAndBoardId — 타 보드 소속이면 null ─────────────────────────

    @Test
    fun `findByIdAndBoardId 는 타 보드 소속이면 null 을 반환한다`() {
        val boardA = seedBoard()
        val boardB = seedBoard()
        val saved = quickFilterRepository.insert(quickFilter(boardId = boardA.id))

        val result = quickFilterRepository.findByIdAndBoardId(saved.id, boardB.id)
        assertThat(result as Any?).isNull()
    }

    @Test
    fun `findByIdAndBoardId 는 존재하지 않는 id 면 null 을 반환한다`() {
        val board = seedBoard()
        val result = quickFilterRepository.findByIdAndBoardId(UUID.randomUUID(), board.id)
        assertThat(result as Any?).isNull()
    }

    // ── (5) update — name/query 갱신 + updated_at 갱신 ─────────────────────────

    @Test
    fun `update 는 name과 query 를 갱신한다`() {
        val board = seedBoard()
        val saved = quickFilterRepository.insert(quickFilter(boardId = board.id, name = "원래이름", query = "label=a"))

        val updated = quickFilterRepository.update(saved.copy(name = "새이름", query = "label=b"))
        assertThat(updated.name).isEqualTo("새이름")
        assertThat(updated.query).isEqualTo("label=b")

        val found = quickFilterRepository.findByIdAndBoardId(saved.id, board.id)
        assertThat(found!!.name).isEqualTo("새이름")
        assertThat(found.query).isEqualTo("label=b")
    }

    @Test
    fun `update 는 updated_at 을 갱신한다`() {
        val board = seedBoard()
        val saved = quickFilterRepository.insert(quickFilter(boardId = board.id))
        val before =
            dsl.selectFrom(BOARD_QUICK_FILTERS)
                .where(BOARD_QUICK_FILTERS.ID.eq(saved.id))
                .fetchOne()!!
                .updatedAt

        Thread.sleep(10)
        quickFilterRepository.update(saved.copy(name = "갱신된이름"))

        val after =
            dsl.selectFrom(BOARD_QUICK_FILTERS)
                .where(BOARD_QUICK_FILTERS.ID.eq(saved.id))
                .fetchOne()!!
                .updatedAt
        assertThat(after).isAfter(before)
    }

    // ── (6) delete — 삭제 행 수 반영 ────────────────────────────────────────────

    @Test
    fun `delete 는 성공 시 true 를 반환하고 findByIdAndBoardId 가 null 이 된다`() {
        val board = seedBoard()
        val saved = quickFilterRepository.insert(quickFilter(boardId = board.id))

        val result = quickFilterRepository.delete(saved.id, board.id)
        assertThat(result).isTrue()
        assertThat(quickFilterRepository.findByIdAndBoardId(saved.id, board.id) as Any?).isNull()
    }

    @Test
    fun `delete 는 타 보드 소속이면 false 를 반환하고 삭제하지 않는다`() {
        val boardA = seedBoard()
        val boardB = seedBoard()
        val saved = quickFilterRepository.insert(quickFilter(boardId = boardA.id))

        val result = quickFilterRepository.delete(saved.id, boardB.id)
        assertThat(result).isFalse()
        assertThat(quickFilterRepository.findByIdAndBoardId(saved.id, boardA.id)).isNotNull
    }

    @Test
    fun `delete 는 존재하지 않는 id 면 false 를 반환한다`() {
        val board = seedBoard()
        val result = quickFilterRepository.delete(UUID.randomUUID(), board.id)
        assertThat(result).isFalse()
    }

    // ── (7) countByBoardId ──────────────────────────────────────────────────

    @Test
    fun `countByBoardId 는 해당 보드의 퀵필터 개수를 반환한다`() {
        val board = seedBoard()
        assertThat(quickFilterRepository.countByBoardId(board.id)).isEqualTo(0)

        quickFilterRepository.insert(quickFilter(boardId = board.id, name = "필터1"))
        quickFilterRepository.insert(quickFilter(boardId = board.id, name = "필터2"))

        assertThat(quickFilterRepository.countByBoardId(board.id)).isEqualTo(2)
    }

    @Test
    fun `countByBoardId 는 다른 보드의 퀵필터를 세지 않는다`() {
        val boardA = seedBoard()
        val boardB = seedBoard()
        quickFilterRepository.insert(quickFilter(boardId = boardA.id, name = "A필터"))

        assertThat(quickFilterRepository.countByBoardId(boardB.id)).isEqualTo(0)
    }
}
