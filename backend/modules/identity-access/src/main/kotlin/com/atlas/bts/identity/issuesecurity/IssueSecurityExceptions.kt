// IssueSecuritySchemeService 가 던지는 도메인 예외 계층 (FR-PM-06 PR-A Task 5)

package com.atlas.bts.identity.issuesecurity

import java.util.UUID

/**
 * 이슈 보안 스킴/등급/멤버 서비스의 모든 도메인 예외 기반 타입.
 *
 * HTTP 상태 매핑은 컨트롤러(Task 7/8)가 담당한다 — NotFound→404, NameConflict→409, 검증 위반→400.
 */
sealed class IssueSecurityException(message: String) : RuntimeException(message)

/** 대상 보안 스킴이 존재하지 않음 (→ 404). */
class SchemeNotFoundException(schemeId: UUID) :
    IssueSecurityException("issue security scheme not found: $schemeId")

/** 보안 스킴 이름이 이미 다른 스킴과 충돌함 (→ 409). */
class SchemeNameConflictException(name: String) :
    IssueSecurityException("issue security scheme name already exists: $name")

/** 대상 보안 등급이 존재하지 않음 (→ 404). */
class LevelNotFoundException(levelId: UUID) :
    IssueSecurityException("issue security level not found: $levelId")

/** 보안 등급 이름이 같은 스킴 내 다른 등급과 충돌함 (→ 409). */
class LevelNameConflictException(name: String) :
    IssueSecurityException("issue security level name already exists in scheme: $name")

/** 멤버로 추가할 사용자([MemberType.USER] memberValue)가 BTS 에 존재하지 않음 (→ 404). */
class IssueSecurityUserNotFoundException(userId: UUID) :
    IssueSecurityException("user not found: $userId")

/** 멤버로 추가할 그룹([MemberType.GROUP] memberValue)이 BTS 에 존재하지 않음 (→ 404). */
class IssueSecurityGroupNotFoundException(groupId: UUID) :
    IssueSecurityException("user group not found: $groupId")
