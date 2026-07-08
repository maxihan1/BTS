// 단축키 조회/PATCH 응답 DTO — action/keyCombo/trigger/customized 완비 5종 (FR-PF-03 Task 5)

package com.atlas.bts.identity.dto

/**
 * GET/PATCH `/api/v1/users/me/keymap` 응답.
 *
 * [com.atlas.bts.identity.keymap.UserKeymapService.getKeymap] / `.patchKeymap` 이 반환하는
 * effective 키맵(override 병합 후 action 5종 완비)을 그대로 노출한다.
 *
 * @property bindings action 5종 완비 effective 키맵.
 */
data class KeymapResponse(
    val bindings: List<KeymapBindingView>,
)

/**
 * 단축키 바인딩 하나의 조회용 표현.
 *
 * [com.atlas.bts.identity.keymap.KeymapBinding] 의 action/keyCombo 에 컨트롤러가 파생시킨
 * trigger/customized 를 더한 뷰다.
 *
 * @property action action 안정 식별자.
 * @property keyCombo 현재 적용 중인 key_combo(override 또는 기본값).
 * @property trigger 발화 방식(`single`/`leader`) —
 * [com.atlas.bts.identity.keymap.KeymapBinding.trigger] 파생 값을 소문자 문자열로 노출.
 * @property customized 기본 key_combo 와 다르면 true(사용자가 재지정함).
 */
data class KeymapBindingView(
    val action: String,
    val keyCombo: String,
    val trigger: String,
    val customized: Boolean,
)
