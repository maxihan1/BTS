// boards / board_columns 테이블 jOOQ DSL 접근 — agile-planning BC (FR-BD-01, FR-BD-03)

package com.bts.agileplanning.repository

import com.bts.agileplanning.domain.Board
import com.bts.agileplanning.domain.BoardColumn
import com.bts.agileplanning.domain.BoardType
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
 * ## FR-BD-01-2 확장 (Task 1)
 * - [updateName]: boards.name 갱신 + updated_at bump. soft-deleted 보드 제외(affected 0 → null).
 * - [softDelete]: boards.deleted_at 세팅. 이미 삭제된 보드는 재삭제하지 않고 false.
 *
 * @param dsl jOOQ DSLContext
 */
@Repository
// boards/board_columns 한 Aggregate 의 영속 연산 묶음이라 쪼개면 두 리포지토리가 같은 테이블을 나눠 갖는다.
@Suppress("TooManyFunctions")
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
            .set(BOARDS.BOARD_TYPE, board.boardType.name)
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

    // ── seedColumns ───────────────────────────────────────────────────────────

    /**
     * 기존 보드에 컬럼을 채운다 (FR-BD-04 자가 치유). **이미 있는 상태 키는 건드리지 않는다.**
     *
     * ### 왜 필요한가
     * V506 백필은 복제할 칸반 보드를 못 찾은 프로젝트에 **컬럼 0개 보드**를 남긴다
     * ([BoardApplicationService.ensureScrumBoard] 도 같은 상태를 만든다). 그 보드는 조회 시
     * 스스로 컬럼을 채워야 영원히 빈 보드로 남지 않는다.
     *
     * ### 왜 `ON CONFLICT DO NOTHING` 인가
     * 호출자가 「컬럼이 비었나」를 읽고 쓰는 사이에 다른 요청이 먼저 채울 수 있다. 그때 늦은 쪽이
     * 컬럼을 두 벌 만들면 카드가 갈린다. `V500__boards.sql:39` 의 `UNIQUE (board_id, state_key)` 에
     * 판정을 맡기면 늦은 쪽은 **0행을 쓰고 조용히 물러난다** — 애플리케이션이 판정을 흉내 내지 않는다.
     *
     * @param boardId 대상 보드 UUID.
     * @param columns 시드할 컬럼 목록. 비었으면 아무것도 하지 않는다.
     * @return 실제로 INSERT 된 행 수. 경쟁에서 진 호출은 0.
     */
    @Transactional
    fun seedColumns(
        boardId: UUID,
        columns: List<BoardColumn>,
    ): Int {
        if (columns.isEmpty()) return 0

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
        columns.forEach { col ->
            insertStep.values(
                col.id,
                boardId,
                col.stateKey,
                col.name,
                col.category,
                col.displayOrder,
                col.wipLimit,
            )
        }
        val inserted =
            insertStep
                .onConflict(BOARD_COLUMNS.BOARD_ID, BOARD_COLUMNS.STATE_KEY)
                .doNothing()
                .execute()
        log.debug("컬럼 자가 치유 — boardId={}, inserted={}", boardId, inserted)
        return inserted
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
     * 그 프로젝트의 **가장 오래된 활성 스크럼 보드 id** 를 반환한다. 없으면 `null`.
     *
     * 스프린트는 보드에 매달리므로(`ADR 2026-09-01` D2) 보드를 지정하지 않은 스프린트 생성 요청이
     * 붙을 자리를 찾는 데 쓴다. 컬럼까지 로드할 필요가 없어 id 만 집는다.
     *
     * @param projectKey 대상 프로젝트 키.
     * @return 스크럼 보드 UUID. 없으면 `null`.
     */
    @Transactional(readOnly = true)
    fun findScrumBoardIdByProject(projectKey: String): UUID? =
        dsl.select(BOARDS.ID)
            .from(BOARDS)
            .where(BOARDS.PROJECT_KEY.eq(projectKey))
            .and(BOARDS.DELETED_AT.isNull)
            .and(BOARDS.BOARD_TYPE.eq(BoardType.SCRUM.name))
            .orderBy(BOARDS.CREATED_AT.asc())
            .limit(1)
            .fetchOne(BOARDS.ID)

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
     * 보드 이름을 갱신하고 갱신된 보드를 반환한다.
     *
     * soft-deleted 보드(deleted_at IS NOT NULL)는 갱신 대상에서 제외한다 —
     * WHERE 절의 `deleted_at IS NULL` 조건이 [updateSwimlaneField] 와 동일하게 적용된다.
     * affected 행이 0 이면(존재하지 않거나 soft-deleted) null 을 반환한다(404 신호).
     *
     * @param boardId 갱신할 보드 UUID
     * @param name 새로운 보드 이름. 공백 검증은 상위 계층(도메인/서비스) 책임이다
     * @return 갱신된 보드, 존재하지 않거나 soft-deleted 이면 null
     */
    @Transactional
    fun updateName(
        boardId: UUID,
        name: String,
    ): Board? {
        log.debug("보드 이름 갱신 — boardId={}", boardId)

        val now = OffsetDateTime.now(ZoneOffset.UTC)
        val affected =
            dsl.update(BOARDS)
                .set(BOARDS.NAME, name)
                .set(BOARDS.UPDATED_AT, now)
                .where(BOARDS.ID.eq(boardId))
                .and(BOARDS.DELETED_AT.isNull)
                .execute()

        if (affected == 0) return null
        return findById(boardId)
    }

    /**
     * 보드를 소프트 삭제한다 — deleted_at 을 채우고 행은 보존한다(DATA.md §1.2).
     *
     * 이미 soft-deleted 인 보드(deleted_at IS NOT NULL)는 갱신 대상에서 제외하므로
     * 삭제 시각이 덮어써지지 않고 false 를 반환한다(멱등 재삭제 신호).
     * board_columns 는 삭제하지 않는다 — 보드가 조회에서 제외되면 컬럼도 함께 사라진다.
     *
     * @param boardId 소프트 삭제할 보드 UUID
     * @return 이번 호출이 실제로 삭제했으면 true, 존재하지 않거나 이미 삭제됐으면 false
     */
    @Transactional
    fun softDelete(boardId: UUID): Boolean {
        log.debug("보드 소프트 삭제 — boardId={}", boardId)

        val now = OffsetDateTime.now(ZoneOffset.UTC)
        val affected =
            dsl.update(BOARDS)
                .set(BOARDS.DELETED_AT, now)
                .set(BOARDS.UPDATED_AT, now)
                .where(BOARDS.ID.eq(boardId))
                .and(BOARDS.DELETED_AT.isNull)
                .execute()

        return affected > 0
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
        // CHECK 제약이 허용값을 DB 에서 이미 막으므로 여기 도달한 값은 유효하다.
        // 그래도 valueOf 대신 from 을 쓰는 이유는 예외 종류를 하나로 모으기 위함이다.
        val boardType =
            boardRecord.boardType?.let { BoardType.from(it) }
                ?: error("boards.board_type 이 null — id=$id")

        return Board(
            id = id,
            projectKey = boardRecord.projectKey,
            name = boardRecord.name,
            columns = columnRecords.map { toColumnDomain(it) },
            createdAt = createdAt,
            updatedAt = updatedAt,
            deletedAt = boardRecord.deletedAt?.toInstant(),
            swimlaneField = swimlaneField,
            boardType = boardType,
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
