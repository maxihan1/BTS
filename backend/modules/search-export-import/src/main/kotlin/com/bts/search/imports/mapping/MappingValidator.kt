// FR-IM-02 CSV Import 필드 매핑 순수 검증기 — summary 필수·중복 target·미지 target/source·미매핑 source warning (Task 5)

package com.bts.search.imports.mapping

/**
 * [MappingValidator.validate] 의 결과.
 *
 * @property valid [errors] 가 비어 있을 때만 true. [warnings] 는 valid 여부에 영향을 주지 않는다 —
 *   사용자에게 참고 정보로만 노출한다.
 * @property errors 이 매핑으로는 import 를 진행할 수 없게 만드는 문제 목록.
 * @property warnings import 진행에는 지장이 없으나 사용자가 인지하면 좋은 참고 사항 목록.
 */
data class MappingValidationResult(
    val valid: Boolean,
    val errors: List<MappingIssue>,
    val warnings: List<MappingIssue>,
)

/**
 * 매핑 검증에서 발견된 개별 이슈(error 또는 warning) 하나.
 *
 * @property code [MappingValidator] 의 코드 상수 중 하나(예: [MappingValidator.SUMMARY_NOT_MAPPED]).
 * @property message 사용자에게 노출 가능한 한국어 설명.
 * @property field 이슈와 연관된 필드 이름(소스 필드명 또는 대상 target 키). 필드를 특정할 수 없는
 *   전역 이슈([MappingValidator.SUMMARY_NOT_MAPPED])는 null.
 */
data class MappingIssue(
    val code: String,
    val message: String,
    val field: String? = null,
)

/**
 * CSV import 필드 매핑(소스 CSV 헤더 → BTS 대상 필드)을 검증하는 순수 함수 검증기.
 *
 * DB·파일 I/O 에 의존하지 않는다 — analyze 단계(예: [com.bts.search.imports.parse.ImportRowParser])가
 * 이미 감지한 소스 필드 목록과 사용자가 제안한 매핑만으로 검증을 완결한다. JSON import 는 canonical
 * 스키마라 필드 매핑 자체가 없으므로 이 검증기의 대상이 아니다 — CSV/JSON 형식 분기는 상위 서비스
 * (Task 7)의 책임이다.
 */
object MappingValidator {
    /**
     * summary(제목) 대상에 매핑된 소스 필드가 하나도 없음.
     *
     * summary 는 [TargetField.SUMMARY] 로 required=true 다 — summary 없이는 이슈 행 자체를 만들 수
     * 없으므로 warning 이 아닌 error 다.
     */
    const val SUMMARY_NOT_MAPPED = "SUMMARY_NOT_MAPPED"

    /**
     * 둘 이상의 소스 필드가 같은 대상 필드([TargetField.IGNORE_KEY] 제외)를 가리킴.
     *
     * import 시 어느 소스 값이 최종 반영될지 모호해지므로 사용자가 매핑을 하나로 정리하도록 error 로
     * 막는다. IGNORE 는 "매핑하지 않음"이라는 동일한 의미가 여럿 겹쳐도 모호함이 없으므로 집계에서
     * 제외한다.
     */
    const val DUPLICATE_TARGET = "DUPLICATE_TARGET"

    /**
     * [TargetField] 카탈로그에도 [TargetField.IGNORE_KEY] 에도 속하지 않는 대상 키.
     *
     * 프론트가 보낸 payload 가 카탈로그와 어긋난 상태(오래된 캐시, 오타 등)를 나타내므로 error 다.
     */
    const val UNKNOWN_TARGET = "UNKNOWN_TARGET"

    /**
     * analyze 단계가 감지한 소스 필드 목록에 없는 소스 필드를 매핑에 포함함.
     *
     * 존재하지 않는 CSV 헤더를 매핑하려는 시도이므로 error 다 — 조용히 무시하면 사용자가 매핑이
     * 반영됐다고 오인할 수 있다.
     */
    const val UNKNOWN_SOURCE = "UNKNOWN_SOURCE"

    /**
     * 감지된 소스 필드이지만 매핑에서 빠졌거나 IGNORE 로 지정되어 import 시 무시됨.
     *
     * 데이터 유실이 아니라 사용자의 의도된(또는 무의식적인) 선택일 수 있으므로 error 가 아닌 warning
     * 이다 — import 를 막지 않고 참고 정보로만 노출한다.
     */
    const val SOURCE_FIELD_IGNORED = "SOURCE_FIELD_IGNORED"

    /**
     * 필드 매핑을 검증한다.
     *
     * @param sourceFields analyze 단계가 감지한 소스 필드(CSV 헤더) 목록.
     * @param fieldMappings 사용자가 제안한 소스 필드 이름 → 대상 필드 키(또는 [TargetField.IGNORE_KEY])
     *   매핑.
     * @return 검증 결과. [MappingValidationResult.valid] 는 errors 가 비어 있을 때만 true.
     */
    fun validate(
        sourceFields: List<String>,
        fieldMappings: Map<String, String>,
    ): MappingValidationResult {
        val errors = mutableListOf<MappingIssue>()
        errors += findUnknownSourceIssues(sourceFields, fieldMappings)
        errors += findUnknownTargetIssues(fieldMappings)
        errors += findDuplicateTargetIssues(fieldMappings)
        if (!isSummaryMapped(fieldMappings)) {
            errors += MappingIssue(SUMMARY_NOT_MAPPED, "summary(제목) 대상에 매핑된 소스 필드가 없습니다.")
        }

        val warnings = findIgnoredSourceFieldWarnings(sourceFields, fieldMappings)

        return MappingValidationResult(valid = errors.isEmpty(), errors = errors, warnings = warnings)
    }

    /** 소스 필드 목록에 없는 키를 매핑에 사용한 경우를 찾는다([UNKNOWN_SOURCE]). */
    private fun findUnknownSourceIssues(
        sourceFields: List<String>,
        fieldMappings: Map<String, String>,
    ): List<MappingIssue> {
        val knownSourceFields = sourceFields.toSet()
        return fieldMappings.keys
            .filter { sourceField -> sourceField !in knownSourceFields }
            .map { sourceField ->
                MappingIssue(UNKNOWN_SOURCE, "감지되지 않은 소스 필드입니다: $sourceField", sourceField)
            }
    }

    /** 카탈로그·IGNORE 밖의 대상 키를 찾는다([UNKNOWN_TARGET]). */
    private fun findUnknownTargetIssues(fieldMappings: Map<String, String>): List<MappingIssue> =
        fieldMappings.entries
            .filter { (_, targetKey) -> !TargetField.isIgnoreKey(targetKey) && TargetField.fromKey(targetKey) == null }
            .map { (sourceField, targetKey) ->
                MappingIssue(UNKNOWN_TARGET, "알 수 없는 대상 필드 키입니다: $targetKey", sourceField)
            }

    /** IGNORE 를 제외한 대상 키가 둘 이상의 소스 필드에 중복 지정된 경우를 찾는다([DUPLICATE_TARGET]). */
    private fun findDuplicateTargetIssues(fieldMappings: Map<String, String>): List<MappingIssue> =
        fieldMappings.entries
            .filter { (_, targetKey) -> !TargetField.isIgnoreKey(targetKey) }
            .groupBy({ entry -> entry.value }, { entry -> entry.key })
            .filterValues { sourceFields -> sourceFields.size > 1 }
            .map { (targetKey, sourceFields) ->
                val sourceFieldList = sourceFields.joinToString()
                MappingIssue(
                    DUPLICATE_TARGET,
                    "여러 소스 필드가 같은 대상에 매핑되었습니다: $targetKey ($sourceFieldList)",
                    targetKey,
                )
            }

    /** summary 대상으로 해석되는 매핑이 하나라도 있는지 확인한다. */
    private fun isSummaryMapped(fieldMappings: Map<String, String>): Boolean =
        fieldMappings.values.any { targetKey -> TargetField.fromKey(targetKey) == TargetField.SUMMARY }

    /** 감지됐으나 미매핑 또는 IGNORE 인 소스 필드를 찾는다([SOURCE_FIELD_IGNORED] warning). */
    private fun findIgnoredSourceFieldWarnings(
        sourceFields: List<String>,
        fieldMappings: Map<String, String>,
    ): List<MappingIssue> =
        sourceFields
            .filter { sourceField -> isUnmappedOrIgnored(fieldMappings[sourceField]) }
            .map { sourceField ->
                MappingIssue(SOURCE_FIELD_IGNORED, "이 소스 필드는 매핑되지 않아 import 시 무시됩니다: $sourceField", sourceField)
            }

    private fun isUnmappedOrIgnored(targetKey: String?): Boolean =
        targetKey == null || TargetField.isIgnoreKey(targetKey)
}
