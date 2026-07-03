// Import 필드 매핑 검증(validate) 요청 DTO — fieldMappings 배열 (FR-IM-02 PR-A Task 8)

package com.bts.search.imports.web.dto

import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank

/**
 * `POST /api/v1/imports/{jobId}/mapping/validate` 요청 바디.
 *
 * API 계약(스펙 §API 인터페이스)은 `fieldMappings:[{sourceField,targetField}]` 배열 형태다 —
 * [com.bts.search.imports.mapping.ImportMappingService.validate]가 받는 `Map<String,String>`으로
 * [toFieldMappingsMap]에서 변환한다. 이 모듈은 Hibernate Validator 구현체를 포함하지 않으므로
 * ([com.bts.search.web.dto.ExportRequest] 선례와 동일 사유), 여기 Jakarta Validation 어노테이션은
 * 문서화·1차 방어 목적이다. 빈/blank 매핑은 시스템 오류가 아니라
 * [com.bts.search.imports.mapping.MappingValidator]가 처리하는 정상 비즈니스 검증 결과
 * (UNKNOWN_SOURCE/UNKNOWN_TARGET 등)이므로 컨트롤러가 별도 명시 검증을 두지 않는다.
 *
 * @property fieldMappings 소스 필드 → 대상 필드 매핑 목록. 생략 시 빈 목록(모두 미매핑으로 간주).
 */
data class MappingValidateRequest(
    @field:Valid
    val fieldMappings: List<FieldMappingEntry> = emptyList(),
) {
    /** [fieldMappings]를 서비스 계층이 받는 소스 필드→대상 필드 Map으로 변환한다. */
    fun toFieldMappingsMap(): Map<String, String> = fieldMappings.associate { it.sourceField to it.targetField }
}

/**
 * 소스 필드 → 대상 필드 매핑 항목 하나.
 *
 * [MappingValidateRequest]/[MappingConfirmRequest] 양쪽이 공유한다.
 *
 * @property sourceField 소스(CSV 헤더 또는 JSON canonical) 필드 이름.
 * @property targetField 대상 [com.bts.search.imports.mapping.TargetField.key] 또는
 *   [com.bts.search.imports.mapping.TargetField.IGNORE_KEY].
 */
data class FieldMappingEntry(
    @field:NotBlank(message = "sourceField는 필수입니다.")
    val sourceField: String = "",
    @field:NotBlank(message = "targetField는 필수입니다.")
    val targetField: String = "",
)
