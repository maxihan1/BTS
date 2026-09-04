// agile-planning BoardRepository 통합 테스트 — swimlaneField / wipLimit 컬럼 (FR-BD-03) + updateName / softDelete (FR-BD-01-2)

package com.bts.agileplanning.repository

import com.bts.agileplanning.AgilePlanningTestBootApplication
import com.bts.agileplanning.AgilePlanningTestcontainersConfig
import com.bts.agileplanning.domain.Board
import com.bts.agileplanning.domain.BoardColumn
import com.bts.agileplanning.domain.SwimlaneField
import com.bts.agileplanning.jooq.tables.references.BOARDS
import org.assertj.core.api.Assertions.assertThat
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import java.time.OffsetDateTime
import java.time.ZoneOffset
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
 * ## 검증 범위 (FR-BD-01-2 Task 1)
 * 5. updateName — 이름 갱신 + updated_at bump / soft-deleted 보드는 null 이고 행이 변경되지 않는다.
 * 6. softDelete — deleted_at 세팅(하드 삭제 아님) 후 findById null / 이미 삭제된 보드는 false.
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

    /** soft-delete 필터를 우회한 원본 행 검증용 — [BoardRepository.findById] 로는 삭제된 행을 볼 수 없다. */
    @Autowired
    private lateinit var dsl: DSLContext

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
                        stateKeys = listOf("open"),
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

    /** soft-delete 필터를 우회해 boards.name 원본 값을 읽는다. */
    private fun rawName(boardId: UUID): String? =
        dsl.select(BOARDS.NAME)
            .from(BOARDS)
            .where(BOARDS.ID.eq(boardId))
            .fetchOne(BOARDS.NAME)

    /** soft-delete 필터를 우회해 boards.deleted_at 원본 값을 읽는다. 하드 삭제면 행 자체가 없어 null 이다. */
    private fun rawDeletedAt(boardId: UUID): OffsetDateTime? =
        dsl.select(BOARDS.DELETED_AT)
            .from(BOARDS)
            .where(BOARDS.ID.eq(boardId))
            .fetchOne(BOARDS.DELETED_AT)

    /** 테스트 준비용 — SUT 를 거치지 않고 deleted_at 을 직접 채워 soft-deleted 상태를 만든다. */
    private fun markDeleted(boardId: UUID) {
        dsl.update(BOARDS)
            .set(BOARDS.DELETED_AT, OffsetDateTime.now(ZoneOffset.UTC))
            .where(BOARDS.ID.eq(boardId))
            .execute()
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

    @Test
    fun `insert 후 findById 가 swimlaneField EPIC 을 보존한다 (FR-EP-01 활성화)`() {
        val board = buildBoard(swimlaneField = SwimlaneField.EPIC)
        val saved = boardRepository.insert(board)

        assertThat(saved.swimlaneField).isEqualTo(SwimlaneField.EPIC)
        val found = boardRepository.findById(saved.id)
        assertThat(found).isNotNull
        assertThat(found!!.swimlaneField).isEqualTo(SwimlaneField.EPIC)
    }

    @Test
    fun `updateSwimlaneField EPIC 으로 갱신하면 findById 가 EPIC 을 반환한다 (FR-EP-01 활성화)`() {
        val board = buildBoard(swimlaneField = SwimlaneField.NONE)
        boardRepository.insert(board)

        val updated = boardRepository.updateSwimlaneField(board.id, SwimlaneField.EPIC)
        assertThat(updated).isNotNull
        assertThat(updated!!.swimlaneField).isEqualTo(SwimlaneField.EPIC)

        val found = boardRepository.findById(board.id)
        assertThat(found!!.swimlaneField).isEqualTo(SwimlaneField.EPIC)
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

    // ── (5) updateName — 갱신 / soft-deleted 제외 ─────────────────────────────

    @Test
    fun `updateName 이 이름을 갱신하고 updated_at 을 올린다`() {
        val saved = boardRepository.insert(buildBoard())

        val updated = requireNotNull(boardRepository.updateName(saved.id, "이름 변경됨"))

        assertThat(updated.name).isEqualTo("이름 변경됨")
        assertThat(updated.updatedAt).isAfter(saved.updatedAt)

        val found = requireNotNull(boardRepository.findById(saved.id))
        assertThat(found.name).isEqualTo("이름 변경됨")
    }

    @Test
    fun `updateName 이 soft-deleted 보드에는 null 을 반환한다`() {
        val saved = boardRepository.insert(buildBoard())
        markDeleted(saved.id)

        val result = boardRepository.updateName(saved.id, "삭제된 보드 이름 변경 시도")

        assertThat(result as Any?).isNull()
        // null 반환만으로는 부족하다 — WHERE 에 deleted_at IS NULL 이 빠져도 findById 가 null 이라
        // 통과해 버린다. 행이 실제로 변경되지 않았는지 원본을 직접 확인한다.
        assertThat(rawName(saved.id)).isEqualTo(saved.name)
    }

    // ── (6) softDelete ────────────────────────────────────────────────────────

    @Test
    fun `softDelete 가 deleted_at 을 채우고 이후 findById 가 null 이다`() {
        val saved = boardRepository.insert(buildBoard())

        val deleted = boardRepository.softDelete(saved.id)

        assertThat(deleted).isTrue()
        // 하드 삭제였다면 행이 사라져 rawDeletedAt 도 null 이 된다 — 소프트 삭제임을 구분한다.
        assertThat(rawDeletedAt(saved.id)).isNotNull()
        assertThat(boardRepository.findById(saved.id) as Any?).isNull()
    }

    @Test
    fun `softDelete 가 이미 삭제된 보드에 false 를 반환한다`() {
        val saved = boardRepository.insert(buildBoard())
        markDeleted(saved.id)

        val result = boardRepository.softDelete(saved.id)

        assertThat(result).isFalse()
    }
}
