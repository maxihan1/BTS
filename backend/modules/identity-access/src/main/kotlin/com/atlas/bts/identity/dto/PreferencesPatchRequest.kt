// 환경설정 PATCH 요청 DTO — nullable 2-state(부재=미변경) (FR-PF-01 Task 3)

package com.atlas.bts.identity.dto

/**
 * PATCH `/api/v1/users/me/preferences` 요청 바디.
 *
 * ## 2-state 표현 (eng-review E-4)
 * theme/locale/dateFormat 은 값이 있으면 반드시 고정 열거값 중 하나이고 "명시적 삭제(null)" 개념이 없다
 * (profile 의 `department` 3-state 와 대비). 따라서 각 필드를 nullable 로 선언해 `null`(부재)=미변경,
 * 값=변경 의도의 2-state 만 표현한다 —
 * [PreferencesPatch][com.atlas.bts.identity.preferences.PreferencesPatch] 와 동일 설계.
 *
 * Jackson 은 JSON 바디에 필드가 없으면 Kotlin 기본값(`null`)을 유지하고, `"field": null` 로 명시돼도
 * 동일하게 `null` 로 매핑한다 — 두 경우 모두 "미변경"으로 수렴하므로 2-state 로 충분하다.
 *
 * @property theme 부재=미변경, 명시=검증 후 반영(허용값 밖이면 서비스가 400).
 * @property locale 부재=미변경, 명시=검증 후 반영(허용값 밖이면 서비스가 400).
 * @property dateFormat 부재=미변경, 명시=검증 후 반영(허용값 밖이면 서비스가 400).
 */
data class PreferencesPatchRequest(
    val theme: String? = null,
    val locale: String? = null,
    val dateFormat: String? = null,
)
