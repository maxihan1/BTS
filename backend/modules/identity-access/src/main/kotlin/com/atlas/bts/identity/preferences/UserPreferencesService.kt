// 사용자 환경설정 조회/부분 PATCH 유스케이스 서비스 (FR-PF-01 Task 2)

package com.atlas.bts.identity.preferences

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * 사용자 환경설정(테마/로케일/날짜형식) 조회/수정 유스케이스 서비스 (FR-PF-01 Task 2).
 *
 * user_preferences 행이 없는 사용자에게는 기본값([UserPreferences.DEFAULT_THEME] 등)을 채워
 * 노출한다 — 행을 강제로 생성하지 않는다(lazy 생성은 [patchPreferences] 최초 호출 시점).
 *
 * ## PATCH 는 2-state
 * profile 의 `department` 와 달리 theme/locale/dateFormat 은 값이 있으면 반드시 [UserPreferences.THEMES]
 * 등 고정 열거값 중 하나이고 "삭제(null)" 개념이 없다. 따라서 [PreferencesPatch] 는 3-state
 * (`ProfilePatchField`) 가 아니라 `필드? = null`(부재=미변경) 2-state 로 충분하다(eng-review E-4, 프론트
 * `PreferencesPatchBody` 와 동일 설계).
 *
 * ## 검증 우선 원칙
 * [patchPreferences] 는 제공된 필드를 리포지토리 조회/쓰기 이전에 모두 검증한다 — 하나라도
 * 허용값 밖이면 [PreferencesValidationException] 을 던지고 어떤 write 도 발생하지 않는다
 * (UserProfileService.patchProfile 과 동일한 "부분 적용 없음" 원칙).
 *
 * @param repository user_preferences 테이블 접근.
 */
@Service
class UserPreferencesService(
    private val repository: UserPreferencesRepository,
) {
    /**
     * 사용자 환경설정을 조회한다.
     *
     * user_preferences 행이 없으면 기본값(theme=[UserPreferences.DEFAULT_THEME],
     * locale=[UserPreferences.DEFAULT_LOCALE], dateFormat=[UserPreferences.DEFAULT_DATE_FORMAT])
     * 으로 채운다.
     *
     * @param userId 조회 대상 사용자 id.
     * @return 유효 [UserPreferences](저장값 또는 기본값).
     */
    @Transactional(readOnly = true)
    fun getPreferences(userId: UUID): UserPreferences = repository.findByUserId(userId) ?: defaults(userId)

    /**
     * 환경설정을 부분(2-state) PATCH 로 갱신한다.
     *
     * [patch] 에 명시된 필드만 새 값으로 검증·반영하고, 부재(null) 필드는 현재값(행이 없으면 기본값)을
     * 그대로 유지한다. 검증은 리포지토리 조회/쓰기보다 먼저 수행되므로, 하나라도 실패하면
     * 아무 write 도 발생하지 않는다.
     *
     * @param userId 갱신 대상 사용자 id.
     * @param patch 부분 변경 의도(부재=미변경).
     * @return 갱신 후 effective [UserPreferences].
     * @throws PreferencesValidationException theme/locale/dateFormat 중 하나라도 허용값 밖일 때.
     */
    @Transactional
    fun patchPreferences(
        userId: UUID,
        patch: PreferencesPatch,
    ): UserPreferences {
        patch.theme?.let(::validateTheme)
        patch.locale?.let(::validateLocale)
        patch.dateFormat?.let(::validateDateFormat)

        val current = repository.findByUserId(userId)
        val effective =
            UserPreferences(
                userId = userId,
                theme = patch.theme ?: current?.theme ?: UserPreferences.DEFAULT_THEME,
                locale = patch.locale ?: current?.locale ?: UserPreferences.DEFAULT_LOCALE,
                dateFormat = patch.dateFormat ?: current?.dateFormat ?: UserPreferences.DEFAULT_DATE_FORMAT,
            )
        repository.upsert(effective)
        return effective
    }

    // ── private helpers ───────────────────────────────────────────────────────

    /** 저장된 행이 없는 사용자를 위한 기본값 [UserPreferences] 를 만든다. */
    private fun defaults(userId: UUID): UserPreferences =
        UserPreferences(
            userId = userId,
            theme = UserPreferences.DEFAULT_THEME,
            locale = UserPreferences.DEFAULT_LOCALE,
            dateFormat = UserPreferences.DEFAULT_DATE_FORMAT,
        )

    private fun validateTheme(theme: String) {
        if (theme !in UserPreferences.THEMES) {
            throw PreferencesValidationException("유효하지 않은 테마 값입니다.")
        }
    }

    private fun validateLocale(locale: String) {
        if (locale !in UserPreferences.LOCALES) {
            throw PreferencesValidationException("유효하지 않은 로케일 값입니다.")
        }
    }

    private fun validateDateFormat(dateFormat: String) {
        if (dateFormat !in UserPreferences.DATE_FORMATS) {
            throw PreferencesValidationException("유효하지 않은 날짜 형식 값입니다.")
        }
    }
}

/**
 * [UserPreferencesService.patchPreferences] 입력 — theme/locale/dateFormat 2-state PATCH (FR-PF-01).
 *
 * 각 필드 기본값은 `null`(부재=미변경)이다. 세 필드 모두 고정 열거값이라 "명시적 삭제" 개념이
 * 없으므로(profile 의 `department: String?` 과 달리) 3-state sealed 타입 없이 nullable 로 충분하다.
 */
data class PreferencesPatch(
    val theme: String? = null,
    val locale: String? = null,
    val dateFormat: String? = null,
)
