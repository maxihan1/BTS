// UserGroupService 가 던지는 도메인 예외 계층 (FR-PM-09 Task 4)

package com.atlas.bts.identity.group

import java.util.UUID

/** 사용자 그룹 서비스의 모든 도메인 예외 기반 타입. HTTP 상태 매핑은 컨트롤러 담당. */
sealed class UserGroupException(message: String) : RuntimeException(message)

/** 대상 그룹이 존재하지 않음 (→ 404). */
class UserGroupNotFoundException(groupId: UUID) :
    UserGroupException("user group not found: $groupId")

/** 그룹 이름이 이미 다른 그룹과 충돌함 (→ 409). */
class UserGroupNameConflictException(name: String) :
    UserGroupException("user group name already exists: $name")

/** 멤버로 추가할 사용자 ID가 BTS 시스템에 존재하지 않음 (→ 404). */
class UserNotFoundException(userId: UUID) :
    UserGroupException("user not found: $userId")
