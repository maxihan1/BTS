// user_ooo 테이블 접근 인터페이스 — replace-upsert / raw·활성 필터 조회 / 삭제 (FR-PR-03)

package com.atlas.bts.identity.ooo

import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * user_ooo 접근 포트 (FR-PR-03).
 *
 * OOO 는 통짜 값이라 필드별 병합이 없다 — [upsert] 는 전 컬럼을 교체하고, 해제는 [deleteByUserId] 로 한다.
 * [findByUserId] 는 종료 여부와 무관하게 raw row 를 반환한다(GET 의 `ends_at > now` 필터는 서비스 책임,
 * 스펙 G3). [findActiveByUserId] 는 `startsAt <= now < endsAt` 인 경우만 반환한다(whoami 활성 판정 —
 * 명시적으로 전달된 [Clock] 기준이라 DB `NOW()`에 의존하지 않고 호출 측 결정성을 보장한다).
 */
interface OutOfOfficeRepository {
    /**
     * OOO 를 upsert 한다(전 컬럼 교체 = replace). user_id 가 처음이면 INSERT(lazy 생성), 있으면 UPDATE.
     */
    fun upsert(
        userId: UUID,
        startsAt: Instant,
        endsAt: Instant,
        delegateUserId: UUID?,
        message: String?,
    )

    /** raw 조회 — 종료 여부 무필터(delegateName 은 users LEFT JOIN 파생). 미설정이면 null. */
    fun findByUserId(userId: UUID): OutOfOffice?

    /** 활성(`startsAt <= now < endsAt`) OOO 만 조회한다. [clock] 기준 now. 비활성/미설정이면 null. */
    fun findActiveByUserId(
        userId: UUID,
        clock: Clock,
    ): OutOfOffice?

    /** OOO 를 삭제한다(해제). 행이 없어도 멱등하게 무시된다. */
    fun deleteByUserId(userId: UUID)
}
