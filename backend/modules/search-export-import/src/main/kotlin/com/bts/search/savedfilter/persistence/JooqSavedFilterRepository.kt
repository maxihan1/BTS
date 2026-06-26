// 저장된 필터 jOOQ Repository 구현 — saved_filters 테이블 CRUD + OCC(낙관적 동시성 제어) (FR-SR-03)

package com.bts.search.savedfilter.persistence

import com.bts.search.jooq.tables.records.SavedFiltersRecord
import com.bts.search.jooq.tables.references.SAVED_FILTERS
import com.bts.search.savedfilter.application.SavedFilterRepository
import com.bts.search.savedfilter.domain.SavedFilter
import org.jooq.DSLContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * SavedFilterRepository 의 jOOQ 구현체.
 *
 * jOOQ DSL 만 사용한다 — SQL 문자열 결합 금지 (DATA.md §5).
 * 모든 public 메서드에 @Transactional 을 명시한다 (DATA.md §6).
 *
 * ## OCC (낙관적 동시성 제어)
 * [update] 는 `WHERE id = ? AND version = ?` 으로 정확한 버전에만 UPDATE 를 실행한다.
 * 동시에 다른 트랜잭션이 먼저 수정했으면 0행 → null 반환으로 충돌을 신호한다.
 *
 * ## 이름 중복
 * UNIQUE(owner_id, name) 위반 시 jOOQ DataAccessException 을 그대로 전파한다.
 * 서비스 계층이 DuplicateNameException 으로 변환해 HTTP 409 를 반환한다.
 *
 * @param dsl jOOQ DSLContext — SQL 을 코드로 안전하게 작성하는 라이브러리의 핵심 진입점
 */
@Repository
class JooqSavedFilterRepository(
    private val dsl: DSLContext,
) : SavedFilterRepository {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 새 필터를 INSERT 하고 DB 가 채운 id / 타임스탬프를 포함해 반환한다.
     *
     * id = UUID.randomUUID(), created_at / updated_at = DB DEFAULT now(), version = DB DEFAULT 0.
     * UNIQUE(owner_id, name) 위반 시 DataAccessException 을 전파한다.
     */
    @Transactional
    override fun save(filter: SavedFilter): SavedFilter {
        val id = UUID.randomUUID()
        log.debug("필터 저장 — ownerId={}, name={}", filter.ownerId, filter.name)

        val record =
            dsl.insertInto(SAVED_FILTERS)
                .set(SAVED_FILTERS.ID, id)
                .set(SAVED_FILTERS.OWNER_ID, filter.ownerId)
                .set(SAVED_FILTERS.NAME, filter.name)
                .set(SAVED_FILTERS.AQL_QUERY, filter.aqlQuery)
                .set(SAVED_FILTERS.PROJECT_KEY, filter.projectKey)
                .returning()
                .fetchOne()
                ?: error("INSERT 후 RETURNING 실패 — ownerId=${filter.ownerId}, name=${filter.name}")

        return toDomain(record)
    }

    /**
     * id 로 필터를 조회한다.
     */
    @Transactional(readOnly = true)
    override fun findById(id: UUID): SavedFilter? =
        dsl.selectFrom(SAVED_FILTERS)
            .where(SAVED_FILTERS.ID.eq(id))
            .fetchOne()
            ?.let { toDomain(it) }

    /**
     * owner 의 전체 필터 목록을 created_at ASC 정렬로 반환한다.
     */
    @Transactional(readOnly = true)
    override fun findByOwner(ownerId: UUID): List<SavedFilter> =
        dsl.selectFrom(SAVED_FILTERS)
            .where(SAVED_FILTERS.OWNER_ID.eq(ownerId))
            .orderBy(SAVED_FILTERS.CREATED_AT.asc())
            .fetch()
            .map { toDomain(it) }

    /**
     * OCC UPDATE — `WHERE id = ? AND version = ?` 로 정확한 버전을 갱신한다.
     *
     * SET name = ?, aql_query = ?, version = version + 1, updated_at = now()
     * RETURNING 으로 갱신된 행을 반환한다.
     * 0행이면 OCC 충돌(stale version) — null 반환.
     * UNIQUE(owner_id, name) 위반 시 DataAccessException 을 전파한다.
     */
    @Transactional
    override fun update(filter: SavedFilter): SavedFilter? {
        val id = filter.id ?: error("update 호출 시 filter.id 는 null 이 될 수 없습니다.")
        log.debug("필터 업데이트 — id={}, version={}", id, filter.version)

        val record =
            dsl.update(SAVED_FILTERS)
                .set(SAVED_FILTERS.NAME, filter.name)
                .set(SAVED_FILTERS.AQL_QUERY, filter.aqlQuery)
                .set(SAVED_FILTERS.VERSION, SAVED_FILTERS.VERSION.add(1))
                .set(SAVED_FILTERS.UPDATED_AT, OffsetDateTime.now(ZoneOffset.UTC))
                .where(SAVED_FILTERS.ID.eq(id))
                .and(SAVED_FILTERS.VERSION.eq(filter.version))
                .returning()
                .fetchOne()

        if (record == null) {
            log.debug("OCC 충돌 또는 행 부재 — id={}, version={}", id, filter.version)
        }

        return record?.let { toDomain(it) }
    }

    /**
     * id 로 필터를 하드 DELETE 한다.
     *
     * @return 삭제된 행이 있으면 true, 없으면 false
     */
    @Transactional
    override fun deleteById(id: UUID): Boolean {
        log.debug("필터 삭제 — id={}", id)

        val deleted =
            dsl.deleteFrom(SAVED_FILTERS)
                .where(SAVED_FILTERS.ID.eq(id))
                .execute()

        return deleted > 0
    }

    // ── private mapper ─────────────────────────────────────────────────────────

    /**
     * jOOQ SavedFiltersRecord 를 도메인 SavedFilter 로 변환한다.
     *
     * created_at / updated_at 은 TIMESTAMPTZ(OffsetDateTime) → Instant 로 변환한다.
     * version 은 DB DEFAULT 0 이어서 실제 null 이 오지 않지만, 안전하게 error() 처리한다.
     */
    private fun toDomain(record: SavedFiltersRecord): SavedFilter =
        SavedFilter(
            id = record.id,
            ownerId = record.ownerId,
            name = record.name,
            aqlQuery = record.aqlQuery,
            projectKey = record.projectKey,
            createdAt = record.createdAt?.toInstant(),
            updatedAt = record.updatedAt?.toInstant(),
            version = record.version ?: error("version 이 null — id=${record.id}"),
        )
}
