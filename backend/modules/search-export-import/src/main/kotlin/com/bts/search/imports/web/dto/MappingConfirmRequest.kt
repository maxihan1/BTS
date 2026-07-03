// Import 필드 매핑 확정(confirm) 요청 DTO — fieldMappings 배열 + optional dryRun (FR-IM-02 PR-A Task 8)

package com.bts.search.imports.web.dto

import jakarta.validation.Valid

/**
 * `POST /api/v1/imports/{jobId}/mapping` 요청 바디.
 *
 * [MappingValidateRequest]와 동일한 `fieldMappings` 배열 계약에 optional [dryRun]을 더한다 —
 * [com.bts.search.imports.mapping.ImportMappingService.confirm] KDoc §dryRun 참조. 이 값은 confirm
 * 시점에 최종 확정되어 `import_jobs.dry_run` 컬럼에 영속된다(analyze 단계 job의 dryRun은 항상 false).
 *
 * @property fieldMappings 소스 필드 → 대상 필드 매핑 목록. 생략 시 빈 목록.
 * @property dryRun 검증 전용 실행 여부. 기본값 false.
 */
data class MappingConfirmRequest(
    @field:Valid
    val fieldMappings: List<FieldMappingEntry> = emptyList(),
    val dryRun: Boolean = false,
) {
    /** [fieldMappings]를 서비스 계층이 받는 소스 필드→대상 필드 Map으로 변환한다. */
    fun toFieldMappingsMap(): Map<String, String> = fieldMappings.associate { it.sourceField to it.targetField }
}
