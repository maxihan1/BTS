// board_quick_filters 테이블 jOOQ DSL 접근 — agile-planning BC (FR-UX-01)

package com.bts.agileplanning.repository

import com.bts.agileplanning.domain.QuickFilter
import com.bts.agileplanning.jooq.tables.records.BoardQuickFiltersRecord
import com.bts.agileplanning.jooq.tables.references.BOARD_QUICK_FILTERS
import org.jooq.Condition
import org.jooq.DSLContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * board_quick_filters 테이블 CRUD 를 담당하는 Repository.
 *
 * jOOQ DSL 만 사용한다 — SQL 문자열 결합 금지 (DATA.md §5).
 * 모든 public 메서드에 [Transactional] 을 명시한다 (DATA.md §6).
 *
 * ## UNIQUE(board_id, name) 위반
 * [insert] 는 제약 위반 시 예외를 삼키지 않고 그대로 전파한다. 서비스 계층(T5)이
 * jOOQ-native([org.jooq.exception.IntegrityConstraintViolationException]) 와
 * Spring([org.springframework.dao.DataIntegrityViolationException]) 두 경로 모두 잡아
 * 409 도메인 예외로 변환한다 (memory: jooq-exception-translator-409-dependency).
 *
 * @param dsl jOOQ DSLContext
 */
@Repository
class BoardQuickFilterRepository(
    private val dsl: DSLContext,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 퀵필터를 삽입하고 저장된 도메인 객체를 반환한다.
     *
     * UNIQUE(board_id, name) 위반 시 발생하는 예외는 그대로 호출자에게 전파한다.
     *
     * @param quickFilter 저장할 퀵필터 도메인 객체 (id 포함, 호출자가 생성)
     * @return DB 에 반영된 최신 퀵필터
     */
    @Transactional
    fun insert(quickFilter: QuickFilter): QuickFilter {
        log.debug("퀵필터 삽입 — id={}, boardId={}, name={}", quickFilter.id, quickFilter.boardId, quickFilter.name)

        val now = OffsetDateTime.now(ZoneOffset.UTC)
        dsl.insertInto(BOARD_QUICK_FILTERS)
            .set(BOARD_QUICK_FILTERS.ID, quickFilter.id)
            .set(BOARD_QUICK_FILTERS.BOARD_ID, quickFilter.boardId)
            .set(BOARD_QUICK_FILTERS.NAME, quickFilter.name)
            .set(BOARD_QUICK_FILTERS.QUERY, quickFilter.query)
            .set(BOARD_QUICK_FILTERS.CREATED_AT, now)
            .set(BOARD_QUICK_FILTERS.UPDATED_AT, now)
            .execute()

        return findByIdAndBoardId(quickFilter.id, quickFilter.boardId)
            ?: error("퀵필터 INSERT 후 조회 실패 — id=${quickFilter.id}")
    }

    /**
     * 보드에 속한 퀵필터 목록을 created_at ASC 순으로 반환한다.
     *
     * @param boardId 조회할 보드 UUID
     * @return 퀵필터 목록 (created_at 오름차순)
     */
    @Transactional(readOnly = true)
    fun findByBoardId(boardId: UUID): List<QuickFilter> {
        return dsl.selectFrom(BOARD_QUICK_FILTERS)
            .where(BOARD_QUICK_FILTERS.BOARD_ID.eq(boardId))
            .orderBy(BOARD_QUICK_FILTERS.CREATED_AT.asc())
            .fetch()
            .map { toDomain(it) }
    }

    /**
     * boardId 소속인 퀵필터 단건을 조회한다.
     *
     * id 는 일치하지만 boardId 가 다르면(교차 참조) null 을 반환한다.
     *
     * @param id 조회할 퀵필터 UUID
     * @param boardId 소속 보드 UUID
     * @return 퀵필터 도메인 객체, 미존재이거나 타 보드 소속이면 null
     */
    @Transactional(readOnly = true)
    fun findByIdAndBoardId(
        id: UUID,
        boardId: UUID,
    ): QuickFilter? {
        return dsl.selectFrom(BOARD_QUICK_FILTERS)
            .where(scopedTo(id, boardId))
            .fetchOne()
            ?.let { toDomain(it) }
    }

    /**
     * 퀵필터의 name/query 를 갱신하고 updated_at 을 현재 시각으로 갱신한다.
     *
     * boardId 가 다르면(교차 참조) 갱신 대상이 없어 예외를 던진다 — 호출자(서비스)가
     * [findByIdAndBoardId] 로 존재를 사전 확인했다는 전제.
     *
     * @param quickFilter 갱신할 필드(name/query)를 담은 도메인 객체 (id/boardId 로 대상 특정)
     * @return 갱신된 퀵필터
     */
    @Transactional
    fun update(quickFilter: QuickFilter): QuickFilter {
        log.debug("퀵필터 갱신 — id={}, boardId={}", quickFilter.id, quickFilter.boardId)

        val now = OffsetDateTime.now(ZoneOffset.UTC)
        val affected =
            dsl.update(BOARD_QUICK_FILTERS)
                .set(BOARD_QUICK_FILTERS.NAME, quickFilter.name)
                .set(BOARD_QUICK_FILTERS.QUERY, quickFilter.query)
                .set(BOARD_QUICK_FILTERS.UPDATED_AT, now)
                .where(scopedTo(quickFilter.id, quickFilter.boardId))
                .execute()

        if (affected == 0) {
            error("퀵필터 UPDATE 대상 없음 — id=${quickFilter.id}, boardId=${quickFilter.boardId}")
        }
        return findByIdAndBoardId(quickFilter.id, quickFilter.boardId)
            ?: error("퀵필터 UPDATE 후 조회 실패 — id=${quickFilter.id}")
    }

    /**
     * boardId 소속인 퀵필터를 삭제한다.
     *
     * @param id 삭제할 퀵필터 UUID
     * @param boardId 소속 보드 UUID
     * @return 삭제된 행이 있으면 true, 미존재이거나 타 보드 소속이면 false
     */
    @Transactional
    fun delete(
        id: UUID,
        boardId: UUID,
    ): Boolean {
        log.debug("퀵필터 삭제 — id={}, boardId={}", id, boardId)

        val affected = dsl.deleteFrom(BOARD_QUICK_FILTERS).where(scopedTo(id, boardId)).execute()
        return affected > 0
    }

    /**
     * 보드에 속한 퀵필터 개수를 반환한다. 20건 상한(soft cap, 서비스 T5) 검사용.
     *
     * @param boardId 조회할 보드 UUID
     * @return 해당 보드의 퀵필터 개수
     */
    @Transactional(readOnly = true)
    fun countByBoardId(boardId: UUID): Int {
        return dsl.selectCount()
            .from(BOARD_QUICK_FILTERS)
            .where(BOARD_QUICK_FILTERS.BOARD_ID.eq(boardId))
            .fetchOne(0, Int::class.java) ?: 0
    }

    /**
     * id + boardId 쌍으로 대상을 특정하는 [Condition]. 타 보드 소속 행에 대한
     * 조회/갱신/삭제를 방지한다(findByIdAndBoardId/update/delete 공유).
     */
    private fun scopedTo(
        id: UUID,
        boardId: UUID,
    ): Condition = BOARD_QUICK_FILTERS.ID.eq(id).and(BOARD_QUICK_FILTERS.BOARD_ID.eq(boardId))

    /**
     * [BoardQuickFiltersRecord] 를 도메인 [QuickFilter] 로 변환한다.
     */
    private fun toDomain(record: BoardQuickFiltersRecord): QuickFilter {
        val id = record.id ?: error("board_quick_filters.id 가 null — DB 데이터 손상")
        return QuickFilter(
            id = id,
            boardId = record.boardId,
            name = record.name,
            query = record.query,
        )
    }
}
