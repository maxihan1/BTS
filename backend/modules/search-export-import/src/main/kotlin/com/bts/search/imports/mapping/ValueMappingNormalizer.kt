// Import 값매핑 정규화 + 상태/유형/우선순위 소스값 수집 순수 헬퍼
package com.bts.search.imports.mapping

import com.bts.search.imports.parse.ParsedImportRow

/** [UserMappingNormalizer] 미러 — 값매핑 정규화 + 수집 헬퍼. */
object ValueMappingNormalizer {
    /** 소스값 원본 문자열을 정규화한다 — 앞뒤 공백 제거 후 소문자 변환. */
    fun normalize(raw: String): String = raw.trim().lowercase()

    /** 여러 행에 걸친 상태/유형/우선순위 소스값을 필드별로 정규화 수집한다. */
    fun collectValues(rows: List<ParsedImportRow>): Map<ValueTargetField, Set<String>> =
        mapOf(
            ValueTargetField.STATUS to collect(rows) { it.statusName },
            ValueTargetField.TYPE to collect(rows) { it.typeName },
            ValueTargetField.PRIORITY to collect(rows) { it.priorityName },
        )

    private fun collect(
        rows: List<ParsedImportRow>,
        selector: (ParsedImportRow) -> String?,
    ): Set<String> =
        rows
            .mapNotNull(selector)
            .filter { it.isNotBlank() }
            .map { normalize(it) }
            .toSet()
}
