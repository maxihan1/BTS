// user_preferences 테이블 접근 인터페이스 (FR-PF-01)

package com.atlas.bts.identity.preferences

import java.util.UUID

/**
 * user_preferences 테이블 접근 인터페이스 (FR-PF-01).
 *
 * 구현체: [JdbcUserPreferencesRepository].
 */
interface UserPreferencesRepository {
    /**
     * user_id 로 환경설정을 조회한다.
     *
     * 환경설정 행은 lazy 생성된다 — [upsert] 를 한 번도 호출하지 않은 사용자는 행 자체가 없을 수 있다.
     * 기본값 채움은 이 리포지토리가 아니라 [UserPreferencesService] 책임이다.
     *
     * @return 존재하면 [UserPreferences], 없으면 null.
     */
    fun findByUserId(userId: UUID): UserPreferences?

    /**
     * 환경설정을 UPSERT 한다(lazy 생성).
     *
     * [preferences] 는 [UserPreferencesService] 가 현재값/기본값과 병합해 계산한 최종(effective)
     * 상태를 그대로 전달한다 — 이 메서드는 3개 필드 전체를 덮어쓴다(부분 SET 없음).
     */
    fun upsert(preferences: UserPreferences)
}
