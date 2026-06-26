// 저장된 필터 공유 jOOQ Repository 구현 — saved_filter_shares 테이블 CRUD (FR-SR-03 PR2)

package com.bts.search.savedfilter.persistence

import com.bts.search.savedfilter.application.SavedFilterShareRepository
import com.bts.search.savedfilter.domain.SavedFilterShare
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
 * @param dsl jOOQ DSLContext — SQL 을 코드로 안전하게 작성하는 라이브러리의 핵심 진입점
 */
@Repository
class JooqSavedFilterShareRepository(
    private val dsl: DSLContext,
) : SavedFilterShareRepository {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    override fun replaceShares(filterId: UUID, shares: List<SavedFilterShare>) {
        TODO("Task 6 GREEN 에서 구현 예정")
    }

    @Transactional(readOnly = true)
    override fun findByFilterIds(filterIds: Set<UUID>): Map<UUID, List<SavedFilterShare>> {
        TODO("Task 6 GREEN 에서 구현 예정")
    }
}
