// user_statuses 테이블 접근 인터페이스 — 상태 replace-upsert / 만료 필터 조회 / 삭제 (FR-PR-02)

package com.atlas.bts.identity.status

import java.time.Instant
import java.util.UUID

/**
 * user_statuses 접근 포트 (FR-PR-02).
 *
 * 상태는 통짜 값이라 필드별 병합이 없다 — [upsert] 는 전 컬럼을 교체하고, 해제는 [deleteByUserId] 로 한다.
 * 조회([findActiveByUserId])는 만료 상태를 SQL 레벨에서 제외한다(스케줄러 없이 lazy 필터).
 */
interface UserStatusRepository {
    /**
     * 상태를 upsert 한다(전 컬럼 교체 = replace). user_id 가 처음이면 INSERT(lazy 생성), 있으면 UPDATE.
     *
     * emoji·text 중 최소 하나는 non-null 이어야 한다(V028 CHECK). 둘 다 null 이면 해제이므로
     * 이 메서드가 아니라 [deleteByUserId] 를 호출한다 — 검증/분기는 서비스 책임.
     */
    fun upsert(
        userId: UUID,
        emoji: String?,
        text: String?,
        expiresAt: Instant?,
    )

    /** 활성 상태(미만료)를 조회한다. 미설정 또는 만료면 null. */
    fun findActiveByUserId(userId: UUID): UserStatus?

    /** 상태를 삭제한다(해제). 행이 없어도 멱등하게 무시된다. */
    fun deleteByUserId(userId: UUID)
}
