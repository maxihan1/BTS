// 저장된 필터 jOOQ Repository 구현 — saved_filters 테이블 CRUD + OCC(낙관적 동시성 제어) (FR-SR-03)

package com.bts.search.savedfilter.persistence

import com.bts.search.jooq.tables.records.SavedFiltersRecord
import com.bts.search.jooq.tables.references.SAVED_FILTER_SHARES
import com.bts.search.jooq.tables.references.SAVED_FILTERS
import com.bts.search.savedfilter.application.SavedFilterRepository
import com.bts.search.savedfilter.domain.SavedFilter
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.impl.DSL
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

    /**
     * actor 가 열람 가능한 단건 필터를 조회한다 (소유자 또는 공유 술어 B3 매칭).
     *
     * `WHERE id = :id AND (owner_id = :actorId OR <sharedWithPredicate>)` 조건으로 조회한다.
     * 비소유자인 경우 공유 술어가 매칭될 때만 반환하므로 존재 은닉이 자동으로 적용된다.
     */
    @Transactional(readOnly = true)
    override fun findVisibleById(
        id: UUID,
        actorId: UUID,
        projectKeys: Set<String>,
        groupIds: Set<String>,
    ): SavedFilter? =
        dsl
            .selectFrom(SAVED_FILTERS)
            .where(SAVED_FILTERS.ID.eq(id))
            .and(
                SAVED_FILTERS.OWNER_ID.eq(actorId)
                    .or(sharedWithPredicate(projectKeys, groupIds)),
            )
            .fetchOne()
            ?.let { toDomain(it) }

    /**
     * actor 에게 공유된 비소유 필터 목록을 페이지네이션으로 반환한다.
     *
     * `WHERE owner_id != :actorId AND <sharedWithPredicate>` 조건으로 조회한다.
     * `ORDER BY created_at ASC, id ASC` + `LIMIT :size OFFSET :page * :size`.
     */
    @Transactional(readOnly = true)
    override fun findSharedWith(
        actorId: UUID,
        projectKeys: Set<String>,
        groupIds: Set<String>,
        page: Int,
        size: Int,
    ): List<SavedFilter> =
        dsl
            .selectFrom(SAVED_FILTERS)
            .where(SAVED_FILTERS.OWNER_ID.ne(actorId))
            .and(sharedWithPredicate(projectKeys, groupIds))
            .orderBy(SAVED_FILTERS.CREATED_AT.asc(), SAVED_FILTERS.ID.asc())
            .limit(size)
            .offset(page.toLong() * size.toLong())
            .fetch()
            .map { toDomain(it) }

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

    /**
     * 단일 가시성 술어 (B3 핵심) — 공유 매칭 EXISTS 조각.
     *
     * [findVisibleById] 와 [findSharedWith] 가 이 헬퍼를 공유해 술어 일관성을 보장한다.
     * 같은 조각에서 두 메서드가 만들어지므로 parity 가 자동으로 성립한다.
     *
     * 생성하는 SQL 조각.
     * ```sql
     * EXISTS (
     *   SELECT 1 FROM saved_filter_shares
     *   WHERE filter_id = saved_filters.id
     *   AND (
     *     share_type = 'AUTHENTICATED'
     *     OR (share_type = 'PROJECT' AND target_id IN (:projectKeys))  -- projectKeys 비어 있으면 제외
     *     OR (share_type = 'GROUP'   AND target_id IN (:groupIds))     -- groupIds 비어 있으면 제외
     *   )
     * )
     * ```
     *
     * [projectKeys] 또는 [groupIds] 가 빈 집합이면 해당 분기를 조건에서 제외한다.
     * jOOQ `.in(emptyCollection)` 의 버전별 동작 차이를 피하기 위한 명시적 가드.
     *
     * @param projectKeys actor 가 속한 프로젝트 키 집합.
     * @param groupIds actor 가 속한 그룹 ID 집합.
     * @return 공유 매칭 EXISTS 조건.
     */
    private fun sharedWithPredicate(
        projectKeys: Set<String>,
        groupIds: Set<String>,
    ): Condition {
        val conditions =
            buildList {
                add(SAVED_FILTER_SHARES.SHARE_TYPE.eq("AUTHENTICATED"))
                if (projectKeys.isNotEmpty()) {
                    add(
                        SAVED_FILTER_SHARES.SHARE_TYPE.eq("PROJECT")
                            .and(SAVED_FILTER_SHARES.TARGET_ID.`in`(projectKeys)),
                    )
                }
                if (groupIds.isNotEmpty()) {
                    add(
                        SAVED_FILTER_SHARES.SHARE_TYPE.eq("GROUP")
                            .and(SAVED_FILTER_SHARES.TARGET_ID.`in`(groupIds)),
                    )
                }
            }

        return DSL.exists(
            DSL.selectOne()
                .from(SAVED_FILTER_SHARES)
                .where(SAVED_FILTER_SHARES.FILTER_ID.eq(SAVED_FILTERS.ID))
                .and(DSL.or(conditions)),
        )
    }
}
