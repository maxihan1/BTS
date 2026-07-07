// 환경설정 PATCH 필드 검증 실패 예외 (FR-PF-01)

package com.atlas.bts.identity.preferences

/**
 * 환경설정(PATCH) 필드 검증 실패 예외(→ 400, 컨트롤러 매핑).
 *
 * theme/locale/dateFormat 중 하나라도 허용값([UserPreferences.THEMES]/[UserPreferences.LOCALES]/
 * [UserPreferences.DATE_FORMATS]) 밖이면 [UserPreferencesService.patchPreferences] 가 이 예외를
 * 던진다 — 어떤 write 보다도 먼저 검증하므로 부분 적용 없이 원자적으로 거부된다.
 *
 * @param message 사용자 노출용 일반화 메시지(내부 정보 누출 없음).
 */
class PreferencesValidationException(message: String) : RuntimeException(message)
