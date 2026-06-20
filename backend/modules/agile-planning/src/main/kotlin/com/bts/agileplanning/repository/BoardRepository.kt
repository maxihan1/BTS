// boards / board_columns 테이블 jOOQ DSL 접근 — agile-planning BC (FR-BD-01)

package com.bts.agileplanning.repository

import com.bts.agileplanning.domain.Board
import com.bts.agileplanning.domain.BoardColumn
import com.bts.agileplanning.jooq.tables.records.BoardColumnsRecord
import com.bts.agileplanning.jooq.tables.records.BoardsRecord
import com.bts.agileplanning.jooq.tables.references.BOARDS
import com.bts.agileplanning.jooq.tables.references.BOARD_COLUMNS
import org.jooq.DSLContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * boards / board_columns 테이블 CRUD 을 담당하는 Repository.
 *
 * jOOQ DSL 만 사용한다 — SQL 문자열 결합 금지 (DATA.md §5).
 * 모든 public 메서드에 [Transactional] 을 명시한다 (DATA.md §6).
 * soft-delete 필터: boards 조회 시 deleted_at IS NULL 조건 필수.
 *
 * @param dsl jOOQ DSLContext
 */
@Repository
class BoardRepository(
    private val dsl: DSLContext,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 보드와 컬럼을 함께 삽입하고 도메인 객체를 반환한다.
     *
     * boards 행을 먼저 INSERT 하고, board_columns 행을 batch INSERT 한다.
     * FK CASCADE 덕분에 board_columns 행은 boards 삭제 시 자동 제거된다.
     *
     * @param board 저장할 보드 도메인 객체 (id/columns 포함)
     * @return DB 타임스탬프가 반영된 최신 보드
     */
    @Transactional
    fun insert(board: Board): Board {
        log.debug("보드 삽입 — id={}, projectKey={}, columns={}", board.id, board.projectKey, board.columns.size)

        val now = OffsetDateTime.now(ZoneOffset.UTC)

        dsl.insertInto(BOARDS)
            .set(BOARDS.ID, board.id)
            .set(BOARDS.PROJECT_KEY, board.projectKey)
            .set(BOARDS.NAME, board.name)
            .set(BOARDS.CREATED_AT, now)
            .set(BOARDS.UPDATED_AT, now)
            .execute()

        if (board.columns.isNotEmpty()) {
            val insertStep =
                dsl.insertInto(
                    BOARD_COLUMNS,
                    BOARD_COLUMNS.ID,
                    BOARD_COLUMNS.BOARD_ID,
                    BOARD_COLUMNS.STATE_KEY,
                    BOARD_COLUMNS.NAME,
                    BOARD_COLUMNS.CATEGORY,
                    BOARD_COLUMNS.DISPLAY_ORDER,
                )
            board.columns.forEach { col ->
                insertStep.values(col.id, board.id, col.stateKey, col.name, col.category, col.displayOrder)
            }
            insertStep.execute()
        }

        return findById(board.id) ?: error("보드 INSERT 후 조회 실패 — id=${board.id}")
    }

    /**
     * 보드 단건을 컬럼과 함께 조회한다.
     *
     * soft-deleted 보드(deleted_at IS NOT NULL)는 반환하지 않는다.
     *
     * @param boardId 조회할 보드 UUID
     * @return 보드 도메인 객체, 없거나 soft-deleted 이면 null
     */
    @Transactional(readOnly = true)
    fun findById(boardId: UUID): Board? {
        val boardRecord =
            dsl.selectFrom(BOARDS)
                .where(BOARDS.ID.eq(boardId))
                .and(BOARDS.DELETED_AT.isNull)
                .fetchOne()
                ?: return null

        val columnRecords =
            dsl.selectFrom(BOARD_COLUMNS)
                .where(BOARD_COLUMNS.BOARD_ID.eq(boardId))
                .orderBy(BOARD_COLUMNS.DISPLAY_ORDER.asc())
                .fetch()

        return toDomain(boardRecord, columnRecords)
    }

    /**
     * 프로젝트별 활성 보드 목록을 반환한다.
     *
     * soft-deleted 보드는 제외한다. 컬럼은 조회하지 않는다(목록용).
     *
     * @param projectKey 조회할 프로젝트 키
     * @return 보드 목록 (컬럼 빈 목록)
     */
    @Transactional(readOnly = true)
    fun findAllByProjectKey(projectKey: String): List<Board> {
        return dsl.selectFrom(BOARDS)
            .where(BOARDS.PROJECT_KEY.eq(projectKey))
            .and(BOARDS.DELETED_AT.isNull)
            .orderBy(BOARDS.CREATED_AT.asc())
            .fetch()
            .map { toDomain(it, emptyList()) }
    }

    /**
     * [BoardsRecord] + [BoardColumnsRecord] 목록을 도메인 [Board] 로 변환한다.
     */
    private fun toDomain(
        boardRecord: BoardsRecord,
        columnRecords: List<BoardColumnsRecord>,
    ): Board {
        val id = boardRecord.id ?: error("boards.id 가 null — DB 데이터 손상")
        val createdAt =
            boardRecord.createdAt?.toInstant()
                ?: error("boards.created_at 이 null — id=$id")
        val updatedAt =
            boardRecord.updatedAt?.toInstant()
                ?: error("boards.updated_at 이 null — id=$id")

        val columns =
            columnRecords.map { col ->
                val colId = col.id ?: error("board_columns.id 가 null — boardId=$id")
                BoardColumn(
                    id = colId,
                    stateKey = col.stateKey,
                    name = col.name,
                    category = col.category,
                    displayOrder = col.displayOrder ?: 0,
                )
            }

        return Board(
            id = id,
            projectKey = boardRecord.projectKey,
            name = boardRecord.name,
            columns = columns,
            createdAt = createdAt,
            updatedAt = updatedAt,
            deletedAt = boardRecord.deletedAt?.toInstant(),
        )
    }
}
