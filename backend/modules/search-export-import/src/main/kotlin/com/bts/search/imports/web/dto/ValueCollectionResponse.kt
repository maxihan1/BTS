// Import 값 매핑 수집(collect) 응답 DTO — 대상 필드별 소스값+자동추천 목록 (FR-IM-02 PR-C Task 8)

package com.bts.search.imports.web.dto

import com.bts.search.imports.mapping.ValueCollectionEntry
import com.bts.search.imports.mapping.ValueCollectionResult
import com.bts.search.imports.mapping.ValueTargetField
import com.fasterxml.jackson.annotation.JsonInclude

/**
 * `POST /api/v1/imports/{jobId}/mapping/values` 응답 바디.
 *
 * [com.bts.search.imports.mapping.ImportMappingService.collectValues]가 반환하는
 * [ValueCollectionResult]를 그대로 직렬화한다 — 저장 없이 수집·추천 결과만 노출한다.
 * [UserCollectionResponse]와 동형이다.
 *
 * @property fields [ValueTargetField]별 소스값+자동추천 목록.
 */
data class ValueCollectionResponse(
    val fields: List<FieldValueItem>,
) {
    /**
     * [ValueTargetField] 하나에 대한 소스값+자동추천 목록.
     *
     * @property targetField 값 매핑 대상 필드([ValueTargetField]).
     * @property values [targetField]에 등장한 소스값별 자동추천 목록.
     */
    data class FieldValueItem(
        val targetField: ValueTargetField,
        val values: List<ValueEntryItem>,
    )

    /**
     * [ValueCollectionEntry] 직렬화 형태.
     *
     * @property sourceValue Import 원본에 등장한 소스 값(정규화됨).
     * @property suggestedTargetValue 추천 BTS 대상 값. 실재하는 후보를 찾지 못하면 null.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    data class ValueEntryItem(
        val sourceValue: String,
        val suggestedTargetValue: String?,
    )

    companion object {
        /**
         * [ValueCollectionResult]를 [ValueCollectionResponse]로 변환한다.
         *
         * @param result [com.bts.search.imports.mapping.ImportMappingService.collectValues] 반환 결과.
         * @return 변환된 응답 DTO.
         */
        fun from(result: ValueCollectionResult): ValueCollectionResponse =
            ValueCollectionResponse(
                fields =
                    result.values.map { (targetField, entries) ->
                        FieldValueItem(targetField = targetField, values = entries.map { it.toItem() })
                    },
            )

        private fun ValueCollectionEntry.toItem(): ValueEntryItem =
            ValueEntryItem(
                sourceValue = sourceValue,
                suggestedTargetValue = suggestedTargetValue,
            )
    }
}
