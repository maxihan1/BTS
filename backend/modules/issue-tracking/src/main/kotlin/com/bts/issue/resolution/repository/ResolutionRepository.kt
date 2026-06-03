// resolutions 테이블 jOOQ Repository — 활성 목록 조회 + 단건 조회 (FR-IS-07 Task B3)

package com.bts.issue.resolution.repository

import com.bts.issue.jooq.tables.records.ResolutionsRecord
import com.bts.issue.jooq.tables.references.RESOLUTIONS
import com.bts.issue.resolution.domain.Resolution
import org.jooq.DSLContext
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Resolution Repository.
 *
 * jOOQ DSLContext 를 통해 `resolutions` 테이블에 접근한다.
 * V011 마이그레이션이 표준 5종(fixed/wontfix/duplicate/cannotreproduce/done) seed 를 INSERT 한다.
 *
 * ## 설계 결정
 *
 * - 활성 필터. `WHERE deleted_at IS NULL` (DATA.md §3 소프트 삭제).
 * - generated RESOLUTIONS 참조 사용 — B1 codegen 산출 상수 직접 활용.
 * - findAllActive 는 display_order ASC 정렬 보장.
 * - findById 는 Q3(B6 존재성 검증) · B11(IssueResponse resolution) 에서 재사용.
 *
 * @see Resolution
 */
@Repository
class ResolutionRepository(
    private val dsl: DSLContext,
) {
    /**
     * 활성 Resolution 목록을 display_order ASC 로 반환한다.
     *
     * `deleted_at IS NULL` 인 row 만 반환한다.
     * V011 seed 직후에는 표준 5종이 display_order 1~5 순으로 반환된다.
     *
     * @return 활성 [Resolution] 리스트. 비어있을 수 있다.
     */
    @Transactional(readOnly = true)
    fun findAllActive(): List<Resolution> =
        dsl.selectFrom(RESOLUTIONS)
            .where(RESOLUTIONS.DELETED_AT.isNull)
            .orderBy(RESOLUTIONS.DISPLAY_ORDER.asc())
            .fetch()
            .map(::toResolution)

    /**
     * UUID 로 활성 Resolution 을 단건 조회한다.
     *
     * soft-deleted row 는 반환하지 않는다 (`deleted_at IS NULL` 필터).
     *
     * @param id 조회할 Resolution UUID (DB PK).
     * @return 매칭되는 활성 [Resolution], 없으면 null.
     */
    @Transactional(readOnly = true)
    fun findById(id: UUID): Resolution? =
        dsl.selectFrom(RESOLUTIONS)
            .where(RESOLUTIONS.ID.eq(id))
            .and(RESOLUTIONS.DELETED_AT.isNull)
            .fetchOne()
            ?.let(::toResolution)

    // ── private helpers ───────────────────────────────────────────────────────

    /**
     * [ResolutionsRecord] 를 [Resolution] 도메인 객체로 변환한다.
     */
    private fun toResolution(record: ResolutionsRecord): Resolution =
        Resolution(
            id = record.id ?: error("resolutions.id must not be null"),
            key = record.key ?: error("resolutions.key must not be null"),
            name = record.name ?: error("resolutions.name must not be null"),
            description = record.description,
            displayOrder = record.displayOrder ?: error("resolutions.display_order must not be null"),
            isStandard = record.isStandard ?: false,
            createdAt = (record.createdAt ?: error("resolutions.created_at must not be null")).toInstant(),
            updatedAt = (record.updatedAt ?: error("resolutions.updated_at must not be null")).toInstant(),
            deletedAt = record.deletedAt?.toInstant(),
        )
}
