// 단축키 PATCH 형식/화이트리스트/빈값 검증 실패 예외 (FR-PF-03 Task 4)

package com.atlas.bts.identity.keymap

/**
 * 단축키 PATCH 검증(화이트리스트/형식/빈값) 실패 예외(→ 400, 컨트롤러 매핑).
 *
 * [KeymapValidator.validate] 결과에 [KeymapViolation.Category.VALIDATION] 위반이 하나라도 있으면
 * [UserKeymapService.patchKeymap] 이 이 예외를 던진다 — 어떤 write 보다도 먼저 검증하므로
 * 부분 적용 없이 원자적으로 거부된다(`PreferencesValidationException` 선례).
 *
 * @param message 사용자 노출용 일반화 메시지(내부 정보 누출 없음).
 */
class KeymapValidationException(message: String) : RuntimeException(message)
