// boards / board_columns 테이블 jOOQ DSL 접근 — agile-planning BC (FR-BD-01, FR-BD-03)

package com.bts.agileplanning.repository

import com.bts.agileplanning.domain.Board
import com.bts.agileplanning.domain.BoardColumn
import com.bts.agileplanning.domain.SwimlaneField
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
 * ## FR-BD-03 확장 (Task 3)
 * - insert: boards.swimlane_field / board_columns.wip_limit 컬럼 쓰기 추가.
 * - toDomain: 두 컬럼을 도메인 객체로 복원.
 * - [updateSwimlaneField]: boards.swimlane_field 단일 컬럼 갱신 + 재조회 반환.
 * - [updateColumnWipLimit]: board_columns.wip_limit 단일 컬럼 갱신 + 재조회 반환.
 *   affected 행이 0 이면 null 반환(404 신호) — 타 보드 소속 또는 미존재 columnId.
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
            .set(BOARDS.SWIMLANE_FIELD, board.swimlaneField.name)
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
                    BOARD_COLUMNS.WIP_LIMIT,
                )
            board.columns.forEach { col ->
                insertStep.values(
                    col.id,
                    board.id,
                    col.stateKey,
                    col.name,
                    col.category,
                    col.displayOrder,
                    col.wipLimit,
                )
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
     * 보드의 스윔레인 기준 필드를 갱신하고 갱신된 보드를 반환한다.
     *
     * affected 행이 0 이면(존재하지 않거나 soft-deleted) null 을 반환한다.
     *
     * @param boardId 갱신할 보드 UUID
     * @param swimlaneField 새로운 스윔레인 기준 필드
     * @return 갱신된 보드, 존재하지 않으면 null
     */
    @Transactional
    fun updateSwimlaneField(
        boardId: UUID,
        swimlaneField: SwimlaneField,
    ): Board? {
        log.debug("스윔레인 필드 갱신 — boardId={}, swimlaneField={}", boardId, swimlaneField)

        val now = OffsetDateTime.now(ZoneOffset.UTC)
        val affected =
            dsl.update(BOARDS)
                .set(BOARDS.SWIMLANE_FIELD, swimlaneField.name)
                .set(BOARDS.UPDATED_AT, now)
                .where(BOARDS.ID.eq(boardId))
                .and(BOARDS.DELETED_AT.isNull)
                .execute()

        if (affected == 0) return null
        return findById(boardId)
    }

    /**
     * 보드 컬럼의 WIP 제한을 갱신하고 갱신된 컬럼을 반환한다.
     *
     * boardId + columnId 쌍을 WHERE 조건으로 사용해 타 보드 소속 컬럼 갱신을 방지한다.
     * affected 행이 0 이면(타 보드 소속이거나 미존재) null 을 반환한다.
     *
     * @param boardId 보드 UUID — 컬럼이 이 보드에 속해야 한다
     * @param columnId 갱신할 컬럼 UUID
     * @param wipLimit 새로운 WIP 제한. null 이면 제한 해제
     * @return 갱신된 컬럼, 타 보드 소속이거나 미존재이면 null
     */
    @Transactional
    fun updateColumnWipLimit(
        boardId: UUID,
        columnId: UUID,
        wipLimit: Int?,
    ): BoardColumn? {
        log.debug("WIP 제한 갱신 — boardId={}, columnId={}, wipLimit={}", boardId, columnId, wipLimit)

        val affected =
            dsl.update(BOARD_COLUMNS)
                .set(BOARD_COLUMNS.WIP_LIMIT, wipLimit)
                .where(BOARD_COLUMNS.ID.eq(columnId))
                .and(BOARD_COLUMNS.BOARD_ID.eq(boardId))
                .execute()

        if (affected == 0) return null
        return dsl.selectFrom(BOARD_COLUMNS)
            .where(BOARD_COLUMNS.ID.eq(columnId))
            .fetchOne()
            ?.let { toColumnDomain(it) }
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
        val swimlaneField =
            boardRecord.swimlaneField?.let { SwimlaneField.valueOf(it) }
                ?: error("boards.swimlane_field 가 null — id=$id")

        return Board(
            id = id,
            projectKey = boardRecord.projectKey,
            name = boardRecord.name,
            columns = columnRecords.map { toColumnDomain(it) },
            createdAt = createdAt,
            updatedAt = updatedAt,
            deletedAt = boardRecord.deletedAt?.toInstant(),
            swimlaneField = swimlaneField,
        )
    }

    /**
     * [BoardColumnsRecord] 를 도메인 [BoardColumn] 으로 변환한다.
     *
     * [toDomain] 내 컬럼 변환과 [updateColumnWipLimit] 재조회 변환이 이 헬퍼를 공유한다.
     */
    private fun toColumnDomain(col: BoardColumnsRecord): BoardColumn {
        val colId = col.id ?: error("board_columns.id 가 null — boardId=${col.boardId}")
        return BoardColumn(
            id = colId,
            stateKey = col.stateKey,
            name = col.name,
            category = col.category,
            displayOrder = col.displayOrder ?: 0,
            wipLimit = col.wipLimit,
        )
    }
}
