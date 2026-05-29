// IssueType 도메인 예외 계층 — sealed 베이스 + 6 서브클래스

package com.bts.issue.type.domain

import com.bts.shared.issue.IssueTypeId
import com.bts.shared.issue.IssueTypeKey

/**
 * issue-tracking BC 의 IssueType 도메인에서 발생하는 모든 예외의 베이스.
 *
 * sealed 로 선언하여 when 식에서 컴파일러가 완전성(exhaustiveness)을 보장한다.
 * RuntimeException 을 상속하므로 Spring @Transactional 롤백 트리거 대상이다.
 *
 * [com.bts.issue.domain.IssueDomainException] 과 별개로 선언하여
 * IssueType 전용 계층을 독립적으로 유지한다.
 */
sealed class IssueTypeDomainException(message: String) : RuntimeException(message)

/**
 * 표준(standard) 이슈 타입을 변경/삭제하려 할 때.
 *
 * 표준 5종(epic/story/task/subtask/bug)은 불변이다.
 *
 * @param typeId 대상 이슈 타입 ID. [key] 가 null 일 때 사용.
 * @param key 대상 이슈 타입 키. [typeId] 가 null 일 때 사용.
 */
class IssueTypeStandardImmutableException(
    val typeId: IssueTypeId?,
    val key: IssueTypeKey?,
) : IssueTypeDomainException(
        buildString {
            append("Standard IssueType is immutable:")
            if (typeId != null) append(" id=${typeId.value}")
            if (key != null) append(" key=${key.value}")
        },
    )

/**
 * 이미 존재하는 key 로 IssueType 을 생성하려 할 때.
 *
 * @param key 중복된 이슈 타입 키
 */
class IssueTypeKeyDuplicateException(val key: IssueTypeKey) :
    IssueTypeDomainException("IssueType key already exists: ${key.value}")

/**
 * 허용되지 않는 형식의 key 를 사용하려 할 때.
 *
 * 키는 URL-safe 소문자 슬러그 형식이어야 한다.
 *
 * @param key 유효하지 않은 이슈 타입 키
 */
class IssueTypeKeyInvalidException(val key: IssueTypeKey) :
    IssueTypeDomainException("IssueType key is invalid: ${key.value}")

/**
 * 사용 중인 이슈 타입을 삭제하려 할 때.
 *
 * @param usageCount 이 타입을 참조하는 이슈 수
 * @param schemeMappingCount 이 타입을 참조하는 스킴 매핑 수
 */
class IssueTypeInUseException(
    val usageCount: Long,
    val schemeMappingCount: Long,
) : IssueTypeDomainException(
        "IssueType is in use: usageCount=$usageCount, schemeMappingCount=$schemeMappingCount",
    )

/**
 * 이슈 타입 삭제 시 재배정 대상 타입이 유효하지 않을 때.
 *
 * @param targetId 재배정 대상으로 지정된 이슈 타입 ID
 * @param reason 유효하지 않은 이유
 */
class IssueTypeReassignTargetInvalidException(
    val targetId: IssueTypeId,
    val reason: String,
) : IssueTypeDomainException(
        "IssueType reassign target is invalid: targetId=${targetId.value}, reason=$reason",
    )

/**
 * 지정한 ID 에 해당하는 이슈 타입을 찾을 수 없을 때.
 *
 * @param id 조회 대상 이슈 타입 ID
 */
class IssueTypeNotFoundException(val id: IssueTypeId) :
    IssueTypeDomainException("IssueType not found: id=${id.value}")
