// 워크플로우 스킴 도메인 예외 sealed 계층 — 7 sub-class + RFC 7807 errorCode 매핑

package com.bts.workflow.scheme.exception

/**
 * 워크플로우 스킴(WorkflowScheme) BC 내 모든 도메인 예외의 봉인 기반 클래스.
 *
 * T28 WorkflowSchemeExceptionHandler가 이 타입을 catch하여 RFC 7807 ProblemDetail 응답으로 변환한다.
 * 에러 코드 prefix는 프로젝트 규약(`SCHEME_` / `MAPPING_` / `WORKFLOW_SCHEME_`)을 따른다.
 *
 * ## spec §4.5 ProblemDetail errorCode 매핑
 *
 * | 예외 클래스                          | errorCode                        | HTTP 상태 |
 * |--------------------------------------|----------------------------------|-----------|
 * | [WorkflowSchemeNotFoundException]    | `SCHEME_NOT_FOUND`               | 404       |
 * | [SchemeInUseException]               | `SCHEME_IN_USE`                  | 409       |
 * | [SchemeStandardNotDeletableException]| `SCHEME_STANDARD_NOT_DELETABLE`  | 409       |
 * | [SchemeStandardFieldLockedException] | `SCHEME_STANDARD_FIELD_LOCKED`   | 409       |
 * | [MappingDuplicateException]          | `MAPPING_DUPLICATE`              | 409       |
 * | [MappingDefaultDuplicateException]   | `MAPPING_DEFAULT_DUPLICATE`      | 409       |
 * | [WorkflowSchemeNoDefaultException]   | `WORKFLOW_SCHEME_NO_DEFAULT`     | 422       |
 */
sealed class WorkflowSchemeDomainException(message: String) : RuntimeException(message)

/**
 * 요청한 키에 해당하는 워크플로우 스킴이 존재하지 않을 때 던지는 예외.
 *
 * errorCode: `SCHEME_NOT_FOUND`
 *
 * @param key 조회를 시도한 스킴 키. 예: "DEFAULT".
 */
class WorkflowSchemeNotFoundException(
    val key: String,
) : WorkflowSchemeDomainException("WorkflowScheme not found: $key")

/**
 * 하나 이상의 프로젝트가 사용 중인 스킴을 삭제하려 할 때 던지는 예외.
 *
 * errorCode: `SCHEME_IN_USE`
 *
 * @param usedByProjects 해당 스킴을 참조하는 프로젝트 ID 목록.
 */
class SchemeInUseException(
    val usedByProjects: List<Long>,
) : WorkflowSchemeDomainException("Scheme in use by ${usedByProjects.size} projects")

/**
 * 표준(standard) 스킴을 삭제하려 할 때 던지는 예외.
 *
 * 표준 스킴은 시스템 기본값으로, 삭제 불가 정책이 도메인 규칙으로 고정되어 있다.
 * errorCode: `SCHEME_STANDARD_NOT_DELETABLE`
 *
 * @param key 삭제 시도된 표준 스킴의 키. 예: "DEFAULT".
 */
class SchemeStandardNotDeletableException(
    val key: String,
) : WorkflowSchemeDomainException("Standard scheme not deletable: $key")

/**
 * 표준 스킴의 잠긴 필드를 변경하려 할 때 던지는 예외.
 *
 * D11 결정 — `key` / `name` / `description` / `is_default` 4 필드는 표준 스킴에서 변경 불가.
 * errorCode: `SCHEME_STANDARD_FIELD_LOCKED`
 *
 * @param key 변경 시도된 표준 스킴의 키.
 * @param field 잠긴 필드 이름. 예: "name", "is_default".
 */
class SchemeStandardFieldLockedException(
    val key: String,
    val field: String,
) : WorkflowSchemeDomainException("Standard scheme field locked: $key.$field")

/**
 * 동일한 스킴 + 이슈 타입 조합의 매핑이 이미 존재할 때 던지는 예외.
 *
 * errorCode: `MAPPING_DUPLICATE`
 *
 * @param schemeKey 중복이 발생한 스킴 키.
 * @param issueTypeKey 중복이 발생한 이슈 타입 키.
 */
class MappingDuplicateException(
    val schemeKey: String,
    val issueTypeKey: String,
) : WorkflowSchemeDomainException("Mapping duplicate: $schemeKey/$issueTypeKey")

/**
 * 스킴 내에 기본(default) 매핑이 이미 존재하는데 또 다른 기본 매핑을 추가하려 할 때 던지는 예외.
 *
 * 스킴 당 기본 매핑은 정확히 1개여야 한다는 도메인 불변식을 보호한다.
 * errorCode: `MAPPING_DEFAULT_DUPLICATE`
 *
 * @param schemeKey 기본 매핑 중복이 발생한 스킴 키.
 */
class MappingDefaultDuplicateException(
    val schemeKey: String,
) : WorkflowSchemeDomainException("Default mapping duplicate for: $schemeKey")

/**
 * 스킴에 기본(default) 매핑이 없을 때 던지는 예외.
 *
 * 유효한 스킴은 반드시 하나의 기본 매핑을 가져야 한다는 도메인 불변식을 보호한다.
 * errorCode: `WORKFLOW_SCHEME_NO_DEFAULT`
 *
 * @param schemeKey 기본 매핑이 없는 스킴 키.
 */
class WorkflowSchemeNoDefaultException(
    val schemeKey: String,
) : WorkflowSchemeDomainException("No default mapping for: $schemeKey")
