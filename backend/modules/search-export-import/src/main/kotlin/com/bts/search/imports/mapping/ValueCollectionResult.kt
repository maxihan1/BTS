// FR-IM-02 PR-C Import 값매핑 UI collectValues 응답 — 대상 필드별 소스값+자동추천 목록 (Task 5)
package com.bts.search.imports.mapping

/**
 * [ImportMappingService.collectValues] 의 결과.
 *
 * @property values [ValueTargetField] 별 [ValueCollectionEntry] 목록. 값이 없는 필드도 빈 리스트로
 *   항상 3 개 키([ValueTargetField.STATUS]/[ValueTargetField.TYPE]/[ValueTargetField.PRIORITY])를
 *   포함한다([ValueMappingNormalizer.collectValues] 계약과 동일).
 */
data class ValueCollectionResult(
    val values: Map<ValueTargetField, List<ValueCollectionEntry>>,
)

/**
 * [ValueCollectionResult] 의 개별 항목 — 소스값 하나에 대한 자동추천 매핑 정보.
 *
 * @property sourceValue [ValueMappingNormalizer.normalize] 로 정규화된 소스값(상태/유형/우선순위 이름).
 * @property suggestedTargetValue 정규화 정확일치로 찾은 자동추천 대상 값(대소문자 보존 원본 표기).
 *   실재하는 후보를 찾지 못하면 null — 매핑 UI 가 사용자에게 수동 선택을 요구한다.
 */
data class ValueCollectionEntry(
    val sourceValue: String,
    val suggestedTargetValue: String?,
)
