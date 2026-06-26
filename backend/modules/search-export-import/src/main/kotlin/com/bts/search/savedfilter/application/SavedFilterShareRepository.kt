// 저장된 필터 공유 영속성 포트 인터페이스 — application 계층 DIP 경계 (FR-SR-03 PR2)

package com.bts.search.savedfilter.application

import com.bts.search.savedfilter.domain.SavedFilterShare
import java.util.UUID

/**
 * 저장된 필터 공유 영속성 포트 인터페이스 (Port Interface — 의존성 역전 원칙 경계).
 *
 * application 계층이 정의하고 persistence 계층이 구현한다.
 * 서비스는 이 인터페이스만 의존하므로 jOOQ 구현 세부사항이 도메인 로직에 노출되지 않는다.
 *
 * ## 원자성 보장
 * [replaceShares]는 호출측 `@Transactional` 트랜잭션 안에서 실행되어야 한다.
 * 같은 트랜잭션에서 DELETE + INSERT가 원자적으로 처리된다.
 *
 * ## 중복 안전망
 * DB에 `UNIQUE(filter_id, share_type, target_id) NULLS NOT DISTINCT` 제약이 있다.
 * 서비스 계층이 [SavedFilterShare.normalize]로 dedupe를 먼저 수행하지만,
 * 중복 입력 시 DB 제약이 안전망으로 작동해 DataAccessException 을 전파한다.
 */
interface SavedFilterShareRepository {
    /**
     * 특정 필터의 공유 목록을 교체한다 (delete-then-insert).
     *
     * 해당 [filterId]의 기존 공유를 전부 삭제한 뒤 [shares]를 새로 INSERT 한다.
     * [shares]가 빈 리스트면 삭제만 수행한다 (공유 전체 제거).
     * 호출측이 `@Transactional` 을 보유해야 원자적으로 동작한다.
     *
     * @param filterId 공유를 교체할 필터 UUID.
     * @param shares 새 공유 목록. 서비스 계층이 dedupe/상한 검사를 마친 목록이라 가정한다.
     * @throws org.jooq.exception.DataAccessException 중복 [shares] 항목으로 UNIQUE 위반 시.
     */
    fun replaceShares(
        filterId: UUID,
        shares: List<SavedFilterShare>,
    )

    /**
     * 여러 필터 ID의 공유 목록을 배치로 조회한다 (N+1 차단).
     *
     * [filterIds]가 빈 집합이면 DB 쿼리 없이 빈 Map을 즉시 반환한다.
     * 공유가 없는 filterId는 반환 Map에 포함되지 않는다.
     *
     * @param filterIds 조회할 필터 UUID 집합.
     * @return filterId → 공유 목록 Map. 공유가 없는 filterId는 Key에 없음.
     */
    fun findByFilterIds(filterIds: Set<UUID>): Map<UUID, List<SavedFilterShare>>
}
