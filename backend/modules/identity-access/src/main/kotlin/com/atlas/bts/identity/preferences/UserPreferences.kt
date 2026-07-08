// user_preferences 테이블 행 매핑 엔티티 — 테마/로케일/날짜형식 (FR-PF-01)

package com.atlas.bts.identity.preferences

import java.util.UUID

/**
 * 사용자 환경설정 엔티티 (FR-PF-01, V031 user_preferences).
 *
 * users 테이블과 1:1 확장 — 테마/로케일/날짜형식 3개 필드만 담는다. 세 필드 모두 허용값이
 * 고정된 열거형 성격이라 companion object 에 허용 목록 + 기본값을 응집해
 * [UserPreferencesService] 의 검증/기본값 채움이 참조한다.
 *
 * 프론트엔드 `apps/web/src/api/preferences.ts` THEMES/LOCALES,
 * `apps/web/src/lib/date-preferences.ts` DATE_PRESETS 와 1:1 대응 — 값 목록을 바꿀 때
 * 두 곳을 함께 갱신해야 한다.
 *
 * @property userId users.id FK 이자 PK (1:1).
 * @property theme UI 테마 — [THEMES] 중 하나.
 * @property locale 로케일 — [LOCALES] 중 하나 (저장만, UI 번역은 후속 범위).
 * @property dateFormat 날짜 표시 형식 — [DATE_FORMATS] 중 하나.
 */
data class UserPreferences(
    val userId: UUID,
    val theme: String,
    val locale: String,
    val dateFormat: String,
) {
    companion object {
        /** 지원 테마 값 3종. */
        val THEMES: Set<String> = setOf("light", "dark", "system")

        /** 지원 로케일 값 2종(저장만, UI 번역은 후속 범위). */
        val LOCALES: Set<String> = setOf("ko", "en")

        /** 지원 날짜 표시 형식 4종. */
        val DATE_FORMATS: Set<String> = setOf("iso", "kr", "us", "eu")

        /** [theme] 기본값. */
        const val DEFAULT_THEME: String = "system"

        /** [locale] 기본값. */
        const val DEFAULT_LOCALE: String = "ko"

        /** [dateFormat] 기본값. */
        const val DEFAULT_DATE_FORMAT: String = "iso"
    }
}
