// 커스텀 필드 값의 타입별 유효성을 검사하는 도메인 검증기
package com.bts.issue.customfield.domain

/**
 * 이슈에 저장되는 커스텀 필드 값의 타입 · 형식 · 선택지 유효성을 검사하는 도메인 예외.
 *
 * HTTP 422 매핑은 예외 핸들러에서 처리한다.
 *
 * @param fieldKey 검증에 실패한 필드 key.
 * @param reason 위반 상세 메시지.
 */
class CustomFieldValidationException(fieldKey: String, reason: String) :
    CustomFieldDomainException("Custom field validation failed for '$fieldKey': $reason")

// ── 타입별 제약 상수 ──────────────────────────────────────────────────────────

private const val SHORT_TEXT_MAX_LENGTH = 255
private const val LONG_TEXT_MAX_LENGTH = 32_768

/** DATE 형식 검증 정규식. YYYY-MM-DD 패턴만 허용. */
private val DATE_PATTERN: Regex = Regex("""^\d{4}-\d{2}-\d{2}$""")

/**
 * DATETIME 형식 검증 정규식.
 *
 * ISO 8601 instant / offset 형식을 허용한다.
 * 예) `2024-03-15T10:30:00Z`, `2024-03-15T10:30:00+09:00`.
 */
private val DATETIME_PATTERN: Regex =
    Regex("""^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?(Z|[+-]\d{2}:\d{2})$""")

/** URL 형식 검증 정규식. http / https 스킴만 허용. */
private val URL_PATTERN: Regex = Regex("""^https?://.+""")

/**
 * 커스텀 필드 값 검증기.
 *
 * [validate] 함수 하나를 공개 API 로 제공하며, Spring 빈 없이도 단위 테스트에서 직접 인스턴스화할 수 있다.
 * ApplicationService 에서 이슈 생성/수정 시 호출한다.
 */
class CustomFieldValueValidator {

    /**
     * 정의 목록([definitions])과 입력값 맵([values])을 검증한다.
     *
     * 검증 순서.
     * 1. [values] 안의 키가 모두 [definitions] 에 존재하는지 확인 (E1 미정의 키 거부).
     * 2. required 정의 중 값이 없거나 null 인 경우 거부 (E2).
     * 3. 값이 존재하는 각 필드에 대해 타입별 형식/선택지 검증 수행 (E3/E4).
     *
     * @param definitions 프로젝트에 활성 상태로 등록된 커스텀 필드 정의 목록.
     * @param values 이슈에 저장할 커스텀 필드 값 맵. 키는 [CustomFieldDefinition.key].
     * @throws CustomFieldValidationException 검증 위반이 발생한 첫 번째 필드에서 즉시 던진다.
     */
    fun validate(
        definitions: List<CustomFieldDefinition>,
        values: Map<String, Any?>,
    ) {
        val definedKeys = definitions.associateBy { it.key }
        checkNoUndefinedKeys(values.keys, definedKeys.keys)
        checkRequiredFields(definitions, values)
        for (definition in definitions) {
            val value = values[definition.key] ?: continue
            validateFieldValue(definition, value)
        }
    }

    /** E1: 미정의 키 거부. values 안에 definitions 에 없는 키가 있으면 즉시 예외를 던진다. */
    private fun checkNoUndefinedKeys(
        valueKeys: Set<String>,
        definedKeys: Set<String>,
    ) {
        val undefinedKeys = valueKeys - definedKeys
        if (undefinedKeys.isNotEmpty()) {
            val key = undefinedKeys.first()
            throw CustomFieldValidationException(key, "field is not defined in this project")
        }
    }

    /** E2: required 필드 누락/null 거부. */
    private fun checkRequiredFields(
        definitions: List<CustomFieldDefinition>,
        values: Map<String, Any?>,
    ) {
        for (definition in definitions) {
            if (!definition.required) continue
            val value = values[definition.key]
            if (value == null) {
                throw CustomFieldValidationException(definition.key, "field is required but missing or null")
            }
        }
    }

    /** 단일 필드의 타입별 검증 분기. when 에 else 미사용 — 신규 FieldType 추가 시 컴파일 에러로 감지. */
    private fun validateFieldValue(
        definition: CustomFieldDefinition,
        value: Any,
    ) {
        when (definition.fieldType) {
            FieldType.SHORT_TEXT -> validateShortText(definition.key, value)
            FieldType.LONG_TEXT -> validateLongText(definition.key, value)
            FieldType.NUMBER -> validateNumber(definition.key, value)
            FieldType.DATE -> validateDate(definition.key, value)
            FieldType.DATETIME -> validateDatetime(definition.key, value)
            FieldType.SINGLE_SELECT -> validateSingleSelect(definition.key, value, definition.options)
            FieldType.MULTI_SELECT -> validateMultiSelect(definition.key, value, definition.options)
            FieldType.CHECKBOX -> validateCheckbox(definition.key, value)
            FieldType.RADIO -> validateRadio(definition.key, value, definition.options)
            FieldType.URL -> validateUrl(definition.key, value)
        }
    }

    private fun validateShortText(key: String, value: Any) {
        val str = value as? String
            ?: throw CustomFieldValidationException(key, "expected String but got ${value::class.simpleName}")
        if (str.length > SHORT_TEXT_MAX_LENGTH) {
            throw CustomFieldValidationException(
                key,
                "SHORT_TEXT must be $SHORT_TEXT_MAX_LENGTH characters or fewer, but was ${str.length}",
            )
        }
    }

    private fun validateLongText(key: String, value: Any) {
        val str = value as? String
            ?: throw CustomFieldValidationException(key, "expected String but got ${value::class.simpleName}")
        if (str.length > LONG_TEXT_MAX_LENGTH) {
            throw CustomFieldValidationException(
                key,
                "LONG_TEXT must be $LONG_TEXT_MAX_LENGTH characters or fewer, but was ${str.length}",
            )
        }
    }

    private fun validateNumber(key: String, value: Any) {
        val number = value as? Number
            ?: throw CustomFieldValidationException(key, "expected Number but got ${value::class.simpleName}")
        val double = number.toDouble()
        if (!double.isFinite()) {
            throw CustomFieldValidationException(key, "NUMBER must be a finite value, but was $double")
        }
    }

    private fun validateDate(key: String, value: Any) {
        val str = value as? String
            ?: throw CustomFieldValidationException(key, "expected String (YYYY-MM-DD) but got ${value::class.simpleName}")
        if (!DATE_PATTERN.matches(str)) {
            throw CustomFieldValidationException(key, "DATE must match YYYY-MM-DD format, but was '$str'")
        }
    }

    private fun validateDatetime(key: String, value: Any) {
        val str = value as? String
            ?: throw CustomFieldValidationException(key, "expected String (ISO 8601) but got ${value::class.simpleName}")
        if (!DATETIME_PATTERN.matches(str)) {
            throw CustomFieldValidationException(key, "DATETIME must be ISO 8601 format, but was '$str'")
        }
    }

    private fun validateSingleSelect(key: String, value: Any, options: List<CustomFieldOption>) {
        val str = value as? String
            ?: throw CustomFieldValidationException(key, "expected String but got ${value::class.simpleName}")
        val validValues = options.map { it.value }.toSet()
        if (str !in validValues) {
            throw CustomFieldValidationException(key, "value '$str' is not in defined options $validValues")
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun validateMultiSelect(key: String, value: Any, options: List<CustomFieldOption>) {
        val list = value as? List<*>
            ?: throw CustomFieldValidationException(key, "expected List but got ${value::class.simpleName}")
        val validValues = options.map { it.value }.toSet()
        for (item in list) {
            val str = item as? String
                ?: throw CustomFieldValidationException(key, "MULTI_SELECT list items must be String, but found ${item?.let { it::class.simpleName }}")
            if (str !in validValues) {
                throw CustomFieldValidationException(key, "value '$str' is not in defined options $validValues")
            }
        }
    }

    private fun validateCheckbox(key: String, value: Any) {
        if (value !is Boolean) {
            throw CustomFieldValidationException(key, "expected Boolean but got ${value::class.simpleName}")
        }
    }

    private fun validateRadio(key: String, value: Any, options: List<CustomFieldOption>) {
        val str = value as? String
            ?: throw CustomFieldValidationException(key, "expected String but got ${value::class.simpleName}")
        val validValues = options.map { it.value }.toSet()
        if (str !in validValues) {
            throw CustomFieldValidationException(key, "value '$str' is not in defined options $validValues")
        }
    }

    private fun validateUrl(key: String, value: Any) {
        val str = value as? String
            ?: throw CustomFieldValidationException(key, "expected String but got ${value::class.simpleName}")
        if (!URL_PATTERN.matches(str)) {
            throw CustomFieldValidationException(key, "URL must start with http:// or https://, but was '$str'")
        }
    }
}
