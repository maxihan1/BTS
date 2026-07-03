// Import 필드 매핑 검증(validate) 응답 DTO — valid/errors/warnings (FR-IM-02 PR-A Task 8)

package com.bts.search.imports.web.dto

import com.bts.search.imports.mapping.MappingIssue
import com.bts.search.imports.mapping.MappingValidationResult
import com.fasterxml.jackson.annotation.JsonInclude

/**
 * `POST /api/v1/imports/{jobId}/mapping/validate` 응답 DTO.
 *
 * [com.bts.search.imports.mapping.ImportMappingService.validate]가 반환하는
 * [MappingValidationResult]를 그대로 직렬화한다 — 저장 없이 검증 결과만 노출한다.
 *
 * @property valid [errors]가 비어 있을 때만 true.
 * @property errors 이 매핑으로는 import를 진행할 수 없게 만드는 문제 목록.
 * @property warnings import 진행에는 지장이 없으나 참고할 사항 목록.
 */
data class MappingValidationResponse(
    val valid: Boolean,
    val errors: List<MappingIssueItem>,
    val warnings: List<MappingIssueItem>,
) {
    /**
     * [MappingIssue] 직렬화 형태.
     *
     * @property field 이슈와 연관된 필드 이름. 전역 이슈
     *   ([com.bts.search.imports.mapping.MappingValidator.SUMMARY_NOT_MAPPED])는 null이라 직렬화에서 제외한다.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    data class MappingIssueItem(
        val code: String,
        val message: String,
        val field: String?,
    )

    companion object {
        /**
         * [MappingValidationResult]를 [MappingValidationResponse]로 변환한다.
         *
         * @param result [com.bts.search.imports.mapping.ImportMappingService.validate] 반환 결과.
         * @return 변환된 응답 DTO.
         */
        fun from(result: MappingValidationResult): MappingValidationResponse =
            MappingValidationResponse(
                valid = result.valid,
                errors = result.errors.map { it.toItem() },
                warnings = result.warnings.map { it.toItem() },
            )

        private fun MappingIssue.toItem(): MappingIssueItem {
            return MappingIssueItem(code = code, message = message, field = field)
        }
    }
}
