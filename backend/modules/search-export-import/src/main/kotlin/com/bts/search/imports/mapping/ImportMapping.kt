// FR-IM-02 소스(CSV/JSON) 필드 → BTS 대상 필드 매핑을 표현하는 불변 값 객체 — IGNORE 필터링 헬퍼 포함

package com.bts.search.imports.mapping

/**
 * CSV/JSON import 소스 필드 이름(예: CSV 헤더) → [TargetField] 키 매핑을 표현하는 값 객체.
 *
 * [entries] 의 값은 [TargetField.key] 중 하나이거나, 사용자가 해당 소스 필드를 매핑하지 않기로
 * 선택한 경우 [TargetField.IGNORE_KEY] 다. 원본 값 검증(미지 키 등)은 이 타입의 책임이 아니다
 * — [resolvedTargets] 가 IGNORE 와 미지 키를 동일하게 걸러낸다.
 *
 * @property entries 소스 필드 이름 → 대상 필드 키 문자열 매핑. 순서는 소스 필드(예: CSV 헤더) 순서를
 *   보존한다.
 */
data class ImportMapping(
    val entries: Map<String, String>,
) {
    /**
     * IGNORE 로 지정되었거나 카탈로그에 없는 대상 키를 제외하고, 나머지 항목을 [TargetField] 로
     * 해석한 맵을 반환한다.
     *
     * @return 소스 필드 이름 → 해석된 [TargetField] 맵. 순서는 [entries] 순서를 보존한다.
     */
    fun resolvedTargets(): Map<String, TargetField> =
        entries
            .filterValues { targetKey -> !TargetField.isIgnoreKey(targetKey) }
            .mapNotNull { (sourceField, targetKey) ->
                TargetField.fromKey(targetKey)?.let { targetField -> sourceField to targetField }
            }
            .toMap()
}
