// 사용자 환경설정 조회/수정 응답 DTO — theme/locale/dateFormat (FR-PF-01 Task 3)

package com.atlas.bts.identity.dto

/**
 * GET/PATCH `/api/v1/users/me/preferences` 응답.
 *
 * [UserPreferences][com.atlas.bts.identity.preferences.UserPreferences] 의 3개 필드를 그대로 노출한다
 * (userId 는 인증 principal 로부터 파생되므로 응답 본문에 담지 않는다).
 *
 * @property theme UI 테마(`light`/`dark`/`system` 중 하나).
 * @property locale 로케일(`ko`/`en` 중 하나).
 * @property dateFormat 날짜 표시 형식(`iso`/`kr`/`us`/`eu` 중 하나).
 */
data class PreferencesResponse(
    val theme: String,
    val locale: String,
    val dateFormat: String,
)
