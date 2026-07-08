// 단축키 PATCH 충돌(완전중복/leader접두/dead-leader) 검출 예외 (FR-PF-03 Task 4)

package com.atlas.bts.identity.keymap

/**
 * 단축키 PATCH 충돌(완전중복/leader 접두/dead-leader) 검출 예외(→ 409, 컨트롤러 매핑).
 *
 * [KeymapValidator.validate] 결과에 [KeymapViolation.Category.VALIDATION] 위반은 없고
 * [KeymapViolation.Category.CONFLICT] 위반만 있으면 [UserKeymapService.patchKeymap] 이 이
 * 예외를 던진다.
 *
 * @param message 사용자 노출용 일반화 메시지(내부 정보 누출 없음).
 * @param conflicts 검출된 충돌 위반 목록 — 컨트롤러(Task 5)가 응답 `conflicts` 필드로 노출.
 */
class KeymapConflictException(
    message: String,
    val conflicts: List<KeymapViolation>,
) : RuntimeException(message)
