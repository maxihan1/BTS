// 저장된 필터 공유 jOOQ Repository 구현 — saved_filter_shares 테이블 CRUD (FR-SR-03 PR2)

package com.bts.search.savedfilter.persistence

import com.bts.search.jooq.tables.references.SAVED_FILTER_SHARES
import com.bts.search.savedfilter.application.SavedFilterShareRepository
import com.bts.search.savedfilter.domain.SavedFilterShare
import com.bts.search.savedfilter.domain.ShareType
import org.jooq.DSLContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * SavedFilterShareRepository 의 jOOQ 구현체.
 *
 * jOOQ DSL 만 사용한다 — SQL 문자열 결합 금지 (DATA.md §5).
 * 모든 public 메서드에 @Transactional 을 명시한다 (DATA.md §6).
 *
 * ## delete-then-insert 원자성
 * [replaceShares] 는 DELETE + batchInsert 가 같은 트랜잭션에서 원자적으로 실행되어야 한다.
 * 호출측 서비스가 `@Transactional` 을 보유하고 있어야 한다.
 *
 * ## UNIQUE NULLS NOT DISTINCT 안전망
 * DB 제약 `UNIQUE(filter_id, share_type, target_id) NULLS NOT DISTINCT` 가 중복 삽입을
 * 차단한다. UNIQUE 위반 시 jOOQ DataAccessException(SQLState 23505) 을 그대로 전파한다.
 * 운영 환경에서는 JooqAutoConfiguration 이 Spring DuplicateKeyException 으로 변환한다.
 * (메모리 jooq-exception-translator-409-dependency)
 *
 * @param dsl jOOQ DSLContext — SQL 을 코드로 안전하게 작성하는 라이브러리의 핵심 진입점
 */
@Repository
class JooqSavedFilterShareRepository(
    private val dsl: DSLContext,
) : SavedFilterShareRepository {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 특정 필터의 공유 목록을 교체한다 (delete-then-insert).
     *
     * 1. 해당 [filterId] 의 기존 shares 를 전부 DELETE 한다.
     * 2. [shares] 가 비어 있으면 삭제만 수행하고 리턴한다 (공유 전체 제거).
     * 3. [shares] 의 각 항목을 batchInsert 로 한 번에 INSERT 한다.
     *    모든 records 가 동일한 changed-field 세트를 가지므로 단일 PreparedStatement 재사용.
     *
     * `target_id` 컬럼은 AUTHENTICATED 의 경우 null — `set(field, value)` 로
     * null 값을 명시적으로 "changed" 상태로 표시해 batchInsert 일관성을 보장한다.
     *
     * @param filterId 공유를 교체할 필터 UUID.
     * @param shares 새 공유 목록 (서비스가 dedupe/상한 검사를 마친 상태 가정).
     * @throws org.jooq.exception.DataAccessException 중복 shares 항목으로 UNIQUE 위반 시.
     */
    @Transactional
    override fun replaceShares(
        filterId: UUID,
        shares: List<SavedFilterShare>,
    ) {
        log.debug("공유 교체 — filterId={}, count={}", filterId, shares.size)

        dsl.deleteFrom(SAVED_FILTER_SHARES)
            .where(SAVED_FILTER_SHARES.FILTER_ID.eq(filterId))
            .execute()

        if (shares.isEmpty()) return

        val records =
            shares.map { share ->
                dsl.newRecord(SAVED_FILTER_SHARES).also { rec ->
                    // null 값을 명시적으로 changed 로 설정 — batchInsert 가 동일 SQL 구조로 묶임.
                    rec.set(SAVED_FILTER_SHARES.FILTER_ID, filterId)
                    rec.set(SAVED_FILTER_SHARES.SHARE_TYPE, share.shareType.name)
                    rec.set(SAVED_FILTER_SHARES.TARGET_ID, share.targetId)
                }
            }

        dsl.batchInsert(records).execute()
    }

    /**
     * 여러 필터 ID 의 공유 목록을 배치로 조회한다 (N+1 차단).
     *
     * [filterIds] 가 빈 집합이면 DB 쿼리 없이 빈 Map 을 즉시 반환한다.
     * 조회 결과를 Kotlin [groupBy] 로 `filterId → List<SavedFilterShare>` 로 변환한다.
     * 공유가 없는 filterId 는 반환 Map 에 포함되지 않는다.
     *
     * @param filterIds 조회할 필터 UUID 집합.
     * @return filterId → 공유 목록 Map.
     */
    @Transactional(readOnly = true)
    override fun findByFilterIds(filterIds: Set<UUID>): Map<UUID, List<SavedFilterShare>> {
        if (filterIds.isEmpty()) return emptyMap()

        return dsl
            .selectFrom(SAVED_FILTER_SHARES)
            .where(SAVED_FILTER_SHARES.FILTER_ID.`in`(filterIds))
            .fetch()
            .groupBy(
                { record -> record.filterId },
                { record -> SavedFilterShare(ShareType.from(record.shareType), record.targetId) },
            )
    }
}
