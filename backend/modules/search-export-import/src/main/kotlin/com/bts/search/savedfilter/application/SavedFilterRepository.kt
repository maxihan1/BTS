// 저장된 필터 영속성 포트 인터페이스 — application 계층 DIP 경계 (FR-SR-03)

package com.bts.search.savedfilter.application

import com.bts.search.savedfilter.domain.SavedFilter
import java.util.UUID

/**
 * 저장된 AQL 필터 영속성 포트 인터페이스 (Port Interface — 의존성 역전 원칙 경계).
 *
 * application 계층이 정의하고 persistence 계층이 구현한다.
 * 서비스는 이 인터페이스만 의존하므로 jOOQ 구현 세부사항이 도메인 로직에 노출되지 않는다.
 *
 * ## 예외 정책
 * - 이름 중복 시 native 예외(jOOQ DataAccessException / Spring DuplicateKeyException)를 그대로 전파.
 *   서비스 계층이 409 ConflictException 으로 변환한다.
 * - [update] 의 OCC 충돌(stale version)은 null 반환으로 표현한다. 예외를 던지지 않는다.
 */
interface SavedFilterRepository {
    /**
     * 새 필터를 저장하고 id / 타임스탬프가 채워진 도메인 객체를 반환한다.
     *
     * INSERT 시 id = UUID 자동 생성, created_at / updated_at = now(), version = 0.
     * UNIQUE(owner_id, name) 위반 시 DataAccessException 을 전파한다.
     *
     * @param filter 저장할 필터 ([SavedFilter.id] 는 null 이어야 한다)
     * @return id / 타임스탬프 채워진 저장 결과
     */
    fun save(filter: SavedFilter): SavedFilter

    /**
     * id 로 필터를 조회한다.
     *
     * @param id 조회할 필터 식별자
     * @return 필터 도메인 객체, 없으면 null
     */
    fun findById(id: UUID): SavedFilter?

    /**
     * owner 의 전체 필터 목록을 반환한다.
     *
     * @param ownerId 소유자 사용자 UUID
     * @return 해당 owner 소유 필터 목록 (타 owner 행 미포함), created_at ASC 정렬
     */
    fun findByOwner(ownerId: UUID): List<SavedFilter>

    /**
     * 낙관적 동시성 제어(OCC)를 적용해 필터를 업데이트한다.
     *
     * WHERE id = ? AND version = ? 조건으로 UPDATE 를 실행한다.
     * - 0행 → OCC 충돌(다른 트랜잭션이 먼저 수정) → null 반환
     * - 1행 → version + 1, updated_at = now() 적용 후 갱신된 필터 반환
     *
     * UNIQUE(owner_id, name) 위반 시 DataAccessException 을 전파한다.
     *
     * @param filter 업데이트할 필터 (id/version 이 WHERE 절 키로 사용됨)
     * @return 갱신된 필터, OCC 충돌 시 null
     */
    fun update(filter: SavedFilter): SavedFilter?

    /**
     * id 로 필터를 삭제한다. 하드 DELETE (소프트 삭제 없음 — DATA.md §3).
     *
     * @param id 삭제할 필터 식별자
     * @return 삭제된 행이 있으면 true, 없으면 false
     */
    fun deleteById(id: UUID): Boolean

    /**
     * actor 가 열람 가능한 단건 필터를 조회한다 (소유자 또는 공유 매칭).
     *
     * `WHERE id = :id AND (owner_id = :actorId OR sharedWithPredicate)` 조건으로 조회한다.
     * 비소유자인 경우 공유 술어(B3)가 매칭될 때만 반환하므로 존재 은닉이 자동으로 적용된다.
     *
     * @param id 조회할 필터 UUID.
     * @param actorId 요청 actor UUID.
     * @param projectKeys actor 가 속한 프로젝트 키 집합. 비어 있으면 PROJECT 공유 매칭 없음.
     * @param groupIds actor 가 속한 그룹 ID 집합. 비어 있으면 GROUP 공유 매칭 없음.
     * @return 열람 가능한 필터 도메인 객체, 없거나 비가시면 null.
     */
    fun findVisibleById(
        id: UUID,
        actorId: UUID,
        projectKeys: Set<String>,
        groupIds: Set<String>,
    ): SavedFilter?

    /**
     * actor 에게 공유된 비소유 필터 목록을 페이지네이션으로 반환한다.
     *
     * `WHERE owner_id != :actorId AND sharedWithPredicate` 조건으로 조회한다.
     * `ORDER BY created_at ASC, id ASC` 후 `LIMIT :size OFFSET :page * :size` 를 적용한다.
     *
     * @param actorId 요청 actor UUID.
     * @param projectKeys actor 가 속한 프로젝트 키 집합.
     * @param groupIds actor 가 속한 그룹 ID 집합.
     * @param page 0-based 페이지 번호.
     * @param size 페이지당 최대 항목 수.
     * @return 공유된 비소유 필터 목록 (created_at ASC, id ASC 정렬).
     */
    fun findSharedWith(
        actorId: UUID,
        projectKeys: Set<String>,
        groupIds: Set<String>,
        page: Int,
        size: Int,
    ): List<SavedFilter>
}
