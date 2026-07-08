// 단축키 PATCH 요청 DTO — action 5종 완비 replace-all(2-state 아님) (FR-PF-03 Task 5)

package com.atlas.bts.identity.dto

/**
 * PATCH `/api/v1/users/me/keymap` 요청 바디.
 *
 * ## replace-all ([PreferencesPatchRequest] 2-state 와 대비)
 * [PreferencesPatchRequest] 는 필드별 부재=미변경 2-state 지만, 단축키는 action 5종 전체를
 * 한 번에 교체하는 replace-all 이다([com.atlas.bts.identity.keymap.UserKeymapService.patchKeymap]
 * KDoc) — action 5종 중 하나라도 빠지면 [com.atlas.bts.identity.keymap.KeymapValidator] 가
 * 화이트리스트 위반으로 판정해 400 이 된다.
 *
 * @property bindings 교체할 action×key_combo 목록(5종 완비 기대).
 */
data class KeymapPatchRequest(
    val bindings: List<KeymapBindingInput>,
)

/**
 * PATCH 요청의 바인딩 입력 하나.
 *
 * @property action action 안정 식별자(화이트리스트 밖이면 서비스가 400).
 * @property keyCombo 재지정할 key_combo(형식 위반·빈 값이면 서비스가 400).
 */
data class KeymapBindingInput(
    val action: String,
    val keyCombo: String,
)
