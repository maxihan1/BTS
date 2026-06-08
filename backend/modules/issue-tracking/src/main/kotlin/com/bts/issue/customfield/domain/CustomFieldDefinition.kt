// 커스텀 필드 정의 Aggregate Root — 프로젝트별 필드 스키마를 나타내는 불변 도메인 객체
package com.bts.issue.customfield.domain

import java.util.UUID

/** key 가 URL-safe 소문자(영문자 시작, 영문 소문자·숫자·언더스코어만 허용)임을 검증하는 정규식. */
private val KEY_PATTERN: Regex = Regex("^[a-z][a-z0-9_]*$")

/** 필드 name 의 최대 허용 글자 수. */
internal const val MAX_FIELD_NAME_LENGTH = 255

/** key 의 최대 허용 글자 수. */
internal const val MAX_KEY_LENGTH = 64

/**
 * 커스텀 필드 정의 Aggregate Root.
 *
 * 프로젝트별로 이슈에 추가할 수 있는 커스텀 필드의 스키마를 정의한다.
 * 직접 생성자 대신 [CustomFieldDefinition.create] factory 를 통해 불변식을 검증하고 인스턴스를 얻는다.
 *
 * 불변식.
 * - [key] 는 URL-safe 소문자(`[a-z][a-z0-9_]*`), 최대 [MAX_KEY_LENGTH]자. 생성 후 불변.
 * - [name] 은 trim 후 빈 문자열 불가, 최대 [MAX_FIELD_NAME_LENGTH]자.
 * - [fieldType] 이 [FieldType.isSelectType] = true 이면 [options] 이 하나 이상 있어야 한다.
 * - [fieldType] 은 생성 후 불변.
 *
 * @property id DB PK. DB 저장 전에는 null.
 * @property projectId 이 필드 정의가 속한 프로젝트 UUID.
 * @property key 필드 식별자. URL-safe 소문자. JSONB 키로도 사용.
 * @property name 사용자에게 노출되는 필드 이름.
 * @property fieldType 필드 데이터 타입. 생성 후 변경 불가.
 * @property required 이슈 저장 시 이 필드 입력이 필수인지 여부.
 * @property displayOrder 이슈 폼 내 표시 순서. 낮을수록 먼저 표시.
 * @property options 선택형 타입([FieldType.isSelectType] = true)의 선택지 목록. 비선택형이면 빈 리스트.
 */
data class CustomFieldDefinition(
    val id: UUID?,
    val projectId: UUID,
    val key: String,
    val name: String,
    val fieldType: FieldType,
    val required: Boolean,
    val displayOrder: Int,
    val options: List<CustomFieldOption>,
) {
    companion object {
        /**
         * 새 커스텀 필드 정의를 생성한다.
         *
         * 불변식 위반 시 [InvalidFieldDefinitionException] 을 던진다.
         *
         * @param projectId 이 필드 정의가 속한 프로젝트 UUID.
         * @param key 필드 식별자. `[a-z][a-z0-9_]*` 패턴, 최대 [MAX_KEY_LENGTH]자.
         * @param name 사용자에게 노출되는 필드 이름. trim 후 빈 문자열 불가, 최대 [MAX_FIELD_NAME_LENGTH]자.
         * @param fieldType 필드 데이터 타입.
         * @param required 필수 여부. 기본값 false.
         * @param displayOrder 표시 순서. 기본값 0.
         * @param options 선택지 목록. 선택형 타입이면 반드시 1건 이상. 기본값 빈 리스트.
         * @return 생성된 [CustomFieldDefinition] 인스턴스.
         * @throws InvalidFieldDefinitionException 불변식 위반 시.
         */
        @Suppress("LongParameterList") // Aggregate factory — 7개 필드 모두 도메인 불변식에 해당, 분리 불가
        fun create(
            projectId: UUID,
            key: String,
            name: String,
            fieldType: FieldType,
            required: Boolean = false,
            displayOrder: Int = 0,
            options: List<CustomFieldOption> = emptyList(),
        ): CustomFieldDefinition {
            validateKey(key)
            validateName(name)
            validateOptions(fieldType, options)
            return CustomFieldDefinition(
                id = null,
                projectId = projectId,
                key = key,
                name = name.trim(),
                fieldType = fieldType,
                required = required,
                displayOrder = displayOrder,
                options = options,
            )
        }
    }
}

/**
 * [CustomFieldDefinition.key] 불변식 검증.
 *
 * - 빈 문자열이면 [InvalidFieldDefinitionException] 을 던진다.
 * - [MAX_KEY_LENGTH] 초과이면 [InvalidFieldDefinitionException] 을 던진다.
 * - [KEY_PATTERN] 불일치이면 [InvalidFieldDefinitionException] 을 던진다.
 *
 * @param key 검증할 key 값.
 * @throws InvalidFieldDefinitionException 불변식 위반 시.
 */
@Suppress("ThrowsCount") // key 검증 3단계(empty → length → pattern) — 단계별 명확한 오류 메시지를 위해 분리 유지
private fun validateKey(key: String) {
    if (key.isEmpty()) {
        throw InvalidFieldDefinitionException("key must not be blank")
    }
    if (key.length > MAX_KEY_LENGTH) {
        throw InvalidFieldDefinitionException(
            "key must be $MAX_KEY_LENGTH characters or fewer, but was ${key.length}",
        )
    }
    if (!KEY_PATTERN.matches(key)) {
        throw InvalidFieldDefinitionException(
            "key must match pattern [a-z][a-z0-9_]* (URL-safe lowercase), but was '$key'",
        )
    }
}

/**
 * [CustomFieldDefinition.name] 불변식 검증.
 *
 * - trim 후 빈 문자열이면 [InvalidFieldDefinitionException] 을 던진다.
 * - trim 후 [MAX_FIELD_NAME_LENGTH] 초과이면 [InvalidFieldDefinitionException] 을 던진다.
 *
 * @param name 검증할 name 값.
 * @throws InvalidFieldDefinitionException 불변식 위반 시.
 */
private fun validateName(name: String) {
    val trimmed = name.trim()
    if (trimmed.isEmpty()) {
        throw InvalidFieldDefinitionException("name must not be blank")
    }
    if (trimmed.length > MAX_FIELD_NAME_LENGTH) {
        throw InvalidFieldDefinitionException(
            "name must be $MAX_FIELD_NAME_LENGTH characters or fewer, but was ${trimmed.length}",
        )
    }
}

/**
 * 선택형 타입의 옵션 존재 불변식 검증.
 *
 * [FieldType.isSelectType] = true 인 타입에 [options] 이 비어 있으면
 * [InvalidFieldDefinitionException] 을 던진다.
 *
 * @param fieldType 필드 타입.
 * @param options 선택지 목록.
 * @throws InvalidFieldDefinitionException 선택형 타입에 옵션이 없을 때.
 */
private fun validateOptions(
    fieldType: FieldType,
    options: List<CustomFieldOption>,
) {
    if (fieldType.isSelectType && options.isEmpty()) {
        throw InvalidFieldDefinitionException(
            "fieldType $fieldType requires at least one option",
        )
    }
}
