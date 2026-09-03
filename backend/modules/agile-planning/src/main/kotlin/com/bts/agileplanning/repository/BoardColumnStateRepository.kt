// board_column_states 테이블 접근 — 컬럼 ↔ 워크플로우 상태 1:N 매핑의 읽기·쓰기 (V508)

package com.bts.agileplanning.repository

import com.bts.agileplanning.domain.BoardColumn
import com.bts.agileplanning.jooq.tables.records.BoardColumnsRecord
import com.bts.agileplanning.jooq.tables.references.BOARD_COLUMNS
import com.bts.agileplanning.jooq.tables.references.BOARD_COLUMN_STATES
import org.jooq.DSLContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * `board_column_states` 접근 — 보드 컬럼이 담는 상태 목록의 정본.
 *
 * ## 왜 [BoardRepository] 와 분리했나 (N2)
 * `BoardRepository.kt` 가 이미 `DEVELOPMENT.md §2.1` 의 파일 줄수 상한을 넘겼다(부채 157).
 * 새 조인·쓰기를 거기 넣으면 그 부채가 깊어진다. 컬럼–상태 매핑은 자체 응집이 있는 관심사다.
 *
 * ## 읽기 정본과 이중 기록
 * **읽기는 이 테이블만 본다.** `board_columns.state_key` 는 `DATA.md §4` 3단 분할의 롤백 대비
 * 사본일 뿐이고 DROP 은 부채 178 이 진다.
 *
 * 쓰기는 두 곳을 **같은 트랜잭션에서** 채운다(E5). 컬럼이 상태를 여럿 담으면 레거시 칸에는
 * **첫 상태**를 쓰는데, 그 정의는 `(display_order, state_key)` 오름차순 최소이고
 * [com.bts.agileplanning.domain.BoardColumn.legacyStateKey] 한 곳에 산다 — 문서에만 적으면
 * 이중 기록과 프론트 전송(R12)이 서로 다른 「첫」을 골라 레거시 칸과 화면이 갈린다.
 *
 * ## 편차 X1 은 DB 가 진다
 * 「한 상태는 한 보드에서 한 컬럼에만」은 `UNIQUE (board_id, state_key)` 가 지킨다. 애플리케이션이
 * 그 판정을 흉내 내지 않는다 — 읽고 쓰는 사이의 경쟁을 코드로는 못 막는다.
 * 그리고 비정규화된 `board_id` 자체의 정합은 **복합 FK** `(column_id, board_id)` 가 진다.
 *
 * @param dsl jOOQ DSLContext
 */
@Repository
class BoardColumnStateRepository(
    private val dsl: DSLContext,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 보드의 컬럼별 상태 키 목록을 **한 번의 조회**로 읽는다 (N3).
     *
     * 컬럼 수만큼 쿼리를 날리면 보드 하나에 N+1 이 된다. 컬럼 단위 조회를 열지 않는 이유가 그것이다.
     *
     * @param boardId 대상 보드 UUID.
     * @return `columnId → 상태 키 목록`. 상태가 0개인 컬럼은 **키가 아예 없다** —
     *   호출자는 `orEmpty()` 로 받는다(E1).
     */
    @Transactional(readOnly = true)
    fun findStateKeysByBoard(boardId: UUID): Map<UUID, List<String>> =
        dsl.select(BOARD_COLUMN_STATES.COLUMN_ID, BOARD_COLUMN_STATES.STATE_KEY)
            .from(BOARD_COLUMN_STATES)
            .where(BOARD_COLUMN_STATES.BOARD_ID.eq(boardId))
            .orderBy(
                BOARD_COLUMN_STATES.DISPLAY_ORDER.asc(),
                BOARD_COLUMN_STATES.STATE_KEY.asc(),
            )
            .fetch()
            .groupBy({ it[BOARD_COLUMN_STATES.COLUMN_ID]!! }, { it[BOARD_COLUMN_STATES.STATE_KEY]!! })

    /**
     * 컬럼이 담는 상태 집합을 **통째로 교체**한다 (R9).
     *
     * 추가·제거를 각각의 연산으로 두지 않는 이유 — 「지금 이 컬럼의 상태 집합」이 클라이언트와
     * 서버 사이에서 갈릴 수 있다. 집합 전체를 받으면 X1 위반을 **한 요청 안에서** 판정할 수 있다.
     *
     * 이중 기록(E5)을 같은 트랜잭션에서 함께 수행한다 — 레거시 칸에 첫 상태(또는 빈 집합이면 NULL).
     *
     * @param boardId 대상 보드 UUID. 복합 FK 가 [columnId] 의 실제 소유 보드와 같은지 검사한다.
     * @param columnId 대상 컬럼 UUID.
     * @param stateKeys 새 상태 집합. 순서가 곧 컬럼 안 드롭존 순서다. 빈 리스트를 허용한다(E1).
     * @throws org.springframework.dao.DuplicateKeyException 다른 컬럼이 쓰는 상태가 섞였을 때(X1).
     */
    @Transactional
    fun replaceStates(
        boardId: UUID,
        columnId: UUID,
        stateKeys: List<String>,
    ) {
        log.debug("컬럼 상태 교체 — boardId={}, columnId={}, states={}", boardId, columnId, stateKeys)

        dsl.deleteFrom(BOARD_COLUMN_STATES)
            .where(BOARD_COLUMN_STATES.COLUMN_ID.eq(columnId))
            .execute()

        if (stateKeys.isNotEmpty()) {
            val insertStep =
                dsl.insertInto(
                    BOARD_COLUMN_STATES,
                    BOARD_COLUMN_STATES.COLUMN_ID,
                    BOARD_COLUMN_STATES.BOARD_ID,
                    BOARD_COLUMN_STATES.STATE_KEY,
                    BOARD_COLUMN_STATES.DISPLAY_ORDER,
                )
            stateKeys.forEachIndexed { order, key -> insertStep.values(columnId, boardId, key, order) }
            insertStep.execute()
        }

        // 이중 기록 — 레거시 칸은 롤백 대비 사본이다(E5). 「첫」은 위 INSERT 의 display_order 0 과 같다.
        // board_columns 에는 updated_at 이 없다(V500) — 그래서 갱신 시각을 남기지 않는다.
        dsl.update(BOARD_COLUMNS)
            .set(BOARD_COLUMNS.STATE_KEY, stateKeys.firstOrNull())
            .where(BOARD_COLUMNS.ID.eq(columnId))
            .execute()
    }

    /**
     * [BoardColumnsRecord] 를 도메인 [com.bts.agileplanning.domain.BoardColumn] 으로 변환한다.
     *
     * 상태 목록의 정본이 이 클래스이므로 변환도 여기 둔다 — [BoardRepository] 에 두면 그 파일이
     * 줄수 상한을 더 넘긴다(N2 · 부채 157).
     *
     * @param col board_columns 레코드.
     * @param stateKeysByColumn [findStateKeysByBoard] 결과. 안 주면 레거시 칸으로 폴백한다 —
     *   이중 기록이라 두 값이 같다(E5). 단건 재조회 경로가 그 폴백을 쓴다.
     */
    fun toDomain(
        col: BoardColumnsRecord,
        stateKeysByColumn: Map<UUID, List<String>> = emptyMap(),
    ): BoardColumn {
        val colId = col.id ?: error("board_columns.id 가 null — boardId=${col.boardId}")
        return BoardColumn(
            id = colId,
            stateKeys = stateKeysByColumn[colId] ?: listOfNotNull(col.stateKey),
            name = col.name,
            category = col.category,
            displayOrder = col.displayOrder ?: 0,
            wipLimit = col.wipLimit,
        )
    }
}
