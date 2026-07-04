// Import 필드 매핑 확정(confirm) 요청 DTO — fieldMappings 배열 + dryRun + userMappings/valueMappings (FR-IM-02 PR-A/B/C)

package com.bts.search.imports.web.dto

import com.bts.search.imports.mapping.ValueTargetField
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/**
 * `POST /api/v1/imports/{jobId}/mapping` 요청 바디.
 *
 * [MappingValidateRequest]와 동일한 `fieldMappings` 배열 계약에 optional [dryRun]을 더한다 —
 * [com.bts.search.imports.mapping.ImportMappingService.confirm] KDoc §dryRun 참조. 이 값은 confirm
 * 시점에 최종 확정되어 `import_jobs.dry_run` 컬럼에 영속된다(analyze 단계 job의 dryRun은 항상 false).
 *
 * @property fieldMappings 소스 필드 → 대상 필드 매핑 목록. 생략 시 빈 목록.
 * @property dryRun 검증 전용 실행 여부. 기본값 false.
 * @property userMappings 확정할 사용자 매핑 목록. 생략 시 빈 목록 — 사용자 매핑 없이 confirm 하는
 *   기존 호출부와 하위호환([com.bts.search.imports.mapping.ImportMappingService.confirm] KDoc
 *   §사용자 매핑 검증 참조).
 * @property valueMappings 확정할 값 매핑(상태/유형/우선순위) 목록. 생략 시 빈 목록 — 값 매핑 없이
 *   confirm 하는 기존 호출부와 하위호환([com.bts.search.imports.mapping.ImportMappingService.confirm]
 *   KDoc §값 매핑 검증 참조).
 */
data class MappingConfirmRequest(
    @field:Valid
    val fieldMappings: List<FieldMappingEntry> = emptyList(),
    val dryRun: Boolean = false,
    @field:Valid
    val userMappings: List<UserMappingEntry> = emptyList(),
    @field:Valid
    val valueMappings: List<ValueMappingEntry> = emptyList(),
) {
    /** [fieldMappings]를 서비스 계층이 받는 소스 필드→대상 필드 Map으로 변환한다. */
    fun toFieldMappingsMap(): Map<String, String> = fieldMappings.associate { it.sourceField to it.targetField }

    /**
     * [userMappings]를 서비스 계층이 받는 `sourceIdentifier to targetUserId?` 목록으로 변환한다.
     *
     * Map 이 아닌 List 를 그대로 사용한다 — Map 으로 변환하면 정규화 전 원본이 다르더라도 정규화 후
     * 겹치는 중복 소스 식별자가 여기서 먼저 붕괴되어, 서비스 계층의
     * [com.bts.search.imports.mapping.ImportUserMappingInvalidException.DUPLICATE_SOURCE_IDENTIFIER]
     * 검증이 무력화된다.
     */
    fun toUserMappingPairs(): List<Pair<String, UUID?>> = userMappings.map { it.sourceIdentifier to it.targetUserId }

    /**
     * [valueMappings]를 서비스 계층이 받는 `(대상 필드, 소스 값, 대상 값)` 목록으로 변환한다.
     *
     * [ValueMappingEntry.targetField] 문자열을 [ValueTargetField.valueOf] 로 변환한다 — 알 수 없는
     * 이름이면 서비스 계층 검증([com.bts.search.imports.mapping.ImportValueMappingInvalidException],
     * 422)에 도달하기 전에 [ResponseStatusException](400)으로 즉시 거절한다(서비스 미호출).
     *
     * @throws ResponseStatusException(400) [ValueMappingEntry.targetField] 가 [ValueTargetField] 의
     *   유효한 상수명이 아닌 경우.
     */
    fun toValueMappingTriples(): List<Triple<ValueTargetField, String, String>> =
        valueMappings.map { entry ->
            val targetField =
                try {
                    ValueTargetField.valueOf(entry.targetField)
                } catch (e: IllegalArgumentException) {
                    throw ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "알 수 없는 값매핑 대상 필드입니다: ${entry.targetField}",
                        e,
                    )
                }
            Triple(targetField, entry.sourceValue, entry.targetValue)
        }
}

/**
 * 사용자 매핑 항목 하나 — 소스 작성자 식별자 → 대상 BTS 사용자 UUID(옵션) 매핑.
 *
 * @property sourceIdentifier 정규화 대상 소스 작성자 식별자(이메일).
 * @property targetUserId 매핑할 대상 사용자 UUID. null 이면 미매핑(폴백) 의도.
 */
data class UserMappingEntry(
    @field:NotBlank(message = "sourceIdentifier는 필수입니다.")
    val sourceIdentifier: String = "",
    val targetUserId: UUID? = null,
)

/**
 * 값 매핑 항목 하나 — 대상 필드(상태/유형/우선순위) + 소스 값 → 대상 값 매핑.
 *
 * [targetField] 는 [ValueTargetField] 상수명 문자열이다(예: `"STATUS"`) — [MappingConfirmRequest
 * .toValueMappingTriples] 가 [ValueTargetField.valueOf] 로 변환하며, 알 수 없는 이름은 400 이다.
 *
 * @property targetField [ValueTargetField] 상수명 문자열.
 * @property sourceValue Import 원본에 등장한 소스 값(상태/유형/우선순위 이름).
 * @property targetValue 매핑할 BTS 대상 값.
 */
data class ValueMappingEntry(
    @field:NotBlank(message = "targetField는 필수입니다.")
    val targetField: String = "",
    @field:NotBlank(message = "sourceValue는 필수입니다.")
    val sourceValue: String = "",
    @field:NotBlank(message = "targetValue는 필수입니다.")
    val targetValue: String = "",
)
