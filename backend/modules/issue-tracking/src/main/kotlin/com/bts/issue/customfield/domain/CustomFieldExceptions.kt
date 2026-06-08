// 커스텀 필드 BC 도메인 예외 계층 — sealed 베이스 + 4 서브클래스
package com.bts.issue.customfield.domain

import java.util.UUID

/**
 * 커스텀 필드 BC 에서 발생하는 모든 도메인 예외의 베이스.
 *
 * sealed 로 선언하여 when 식에서 컴파일러가 완전성(exhaustiveness)을 보장한다.
 * RuntimeException 을 상속하므로 Spring @Transactional 롤백 트리거 대상이다.
 */
sealed class CustomFieldDomainException(message: String) : RuntimeException(message)

/**
 * 같은 프로젝트 내에서 동일한 key 의 활성 필드 정의가 이미 존재할 때.
 *
 * 부분 유니크 인덱스(`ux_custom_field_definitions_project_key_active`)가 23505(unique_violation)를
 * 던지면 ApplicationService 에서 이 예외로 변환한다.
 *
 * @param key 중복이 발생한 필드 key.
 */
class DuplicateCustomFieldKeyException(key: String) :
    CustomFieldDomainException("Custom field key already exists in this project: $key")

/**
 * 지정한 fieldId 에 해당하는 활성 필드 정의가 존재하지 않을 때.
 *
 * @param fieldId 조회 대상 필드 정의 UUID.
 */
class CustomFieldNotFoundException(fieldId: UUID) :
    CustomFieldDomainException("Custom field definition not found: $fieldId")

/**
 * 필드 정의 불변식 위반 시 — key 형식 오류, name 공백, 선택형 옵션 누락 등.
 *
 * HTTP 422 매핑은 예외 핸들러에서 처리한다.
 *
 * @param reason 위반 내용 상세 메시지.
 */
class InvalidFieldDefinitionException(reason: String) :
    CustomFieldDomainException("Invalid field definition: $reason")

/**
 * 생성 후 불변인 속성(fieldType, key)을 변경하려 할 때.
 *
 * HTTP 422 매핑은 예외 핸들러에서 처리한다.
 *
 * @param field 변경을 시도한 속성 이름.
 */
class ImmutableFieldTypeChangeException(field: String) :
    CustomFieldDomainException("Field '$field' is immutable after creation")
