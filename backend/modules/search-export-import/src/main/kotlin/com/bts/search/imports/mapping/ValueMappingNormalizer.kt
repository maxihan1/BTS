// FR-IM-02 Import 값매핑 정규화 + 상태/유형/우선순위 소스값 수집 순수 헬퍼 (Task 3)

package com.bts.search.imports.mapping

import com.bts.search.imports.parse.ParsedImportRow

/**
 * Import 값매핑에서 소스값(상태/유형/우선순위 이름)을 정규화하고, 여러 행에 걸쳐 등장하는
 * distinct 소스값을 [ValueTargetField] 별로 수집하는 순수 헬퍼.
 *
 * [UserMappingNormalizer] 를 미러한 구조다. F2 정규화 삼자 일치의 유일 진실원천이다 — distinct
 * 소스값 수집(Task 5 `ImportMappingService.collectValues`)·`import_value_mappings` 저장 키(Task 4)·
 * 프로세서 행별 해석(Task 6 `ImportJobProcessor`)이 반드시 이 [normalize] 를 거쳐야 하며, 별도로
 * 대소문자/공백을 정규화하지 않는다. 어긋나면 같은 소스값을 가리키는 값이 서로 다른 키로 취급되어
 * 조용한 오치환(엉뚱한 상태/유형/우선순위로 저장)이 발생한다.
 */
object ValueMappingNormalizer {
    /**
     * 소스값(상태/유형/우선순위 이름) 원본 문자열을 정규화한다 — 앞뒤 공백 제거 후 소문자 변환.
     *
     * @param raw 원본 소스값 문자열
     * @return 정규화된 소스값 문자열
     */
    fun normalize(raw: String): String = raw.trim().lowercase()

    /**
     * 여러 행에 걸쳐 등장하는 상태/유형/우선순위 소스값을 [ValueTargetField] 별로 정규화해 수집한다.
     *
     * 대상 필드는 상태 이름([ValueTargetField.STATUS]), 유형 이름([ValueTargetField.TYPE]),
     * 우선순위 이름([ValueTargetField.PRIORITY]) 3종이다.
     *
     * null 이거나 공백만으로 이뤄진 소스값은 제외한다. 대소문자/공백만 다른 소스값은 [normalize] 를
     * 거쳐 하나로 합쳐진다(dedup). 결과 맵은 값이 없는 필드도 빈 집합으로 항상 3개 키를 포함한다.
     *
     * @param rows 파싱된 import 행 목록
     * @return [ValueTargetField] 별 정규화된 소스값 집합(dedup 완료)
     */
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
