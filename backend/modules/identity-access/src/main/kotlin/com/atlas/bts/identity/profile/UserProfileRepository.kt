// user_profiles 테이블 접근 인터페이스 — 아바타/타임존/부서 upsert (FR-PR-01)

package com.atlas.bts.identity.profile

import java.util.UUID

/**
 * user_profiles 테이블 접근 인터페이스 (FR-PR-01).
 *
 * 구현체: [JdbcUserProfileRepository].
 */
interface UserProfileRepository {
    /**
     * user_id 로 프로필 조회.
     *
     * 프로필 행은 lazy 생성된다 — [upsertProfile] / [setAvatarObjectKey] 를 한 번도 호출하지 않은
     * 사용자는 행 자체가 없을 수 있다.
     *
     * @return 존재하면 [UserProfile], 없으면 null
     */
    fun findByUserId(userId: UUID): UserProfile?

    /**
     * timezone / department UPSERT (lazy 생성).
     *
     * **avatar_object_key 는 건드리지 않는다** — 이미 설정된 아바타를 그대로 보존한다.
     * 3-state(설정/해제/미변경) 판정은 이 메서드 호출 전 서비스 레이어가 최종값을 결정해 넘긴다.
     *
     * @param department null 이면 부서를 명시적으로 NULL 로 저장한다 (미지정)
     */
    fun upsertProfile(
        userId: UUID,
        timezone: String,
        department: String?,
    )

    /**
     * avatar_object_key UPSERT (lazy 생성).
     *
     * **timezone / department 는 건드리지 않는다.** 신규 INSERT 시 timezone 은 컬럼 기본값(UTC),
     * department 는 NULL 로 초기화되고, 기존 프로필이 있으면 그 값을 그대로 보존한다.
     */
    fun setAvatarObjectKey(
        userId: UUID,
        objectKey: String,
    )

    /**
     * avatar_object_key 를 NULL 로 초기화 (아바타 삭제).
     *
     * 프로필 행이 없으면 조용히 무시한다 (0 행 영향 — 예외 없음).
     */
    fun clearAvatar(userId: UUID)
}
