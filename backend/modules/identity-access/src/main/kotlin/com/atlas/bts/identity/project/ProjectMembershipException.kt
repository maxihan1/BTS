// 프로젝트 멤버십 서비스가 던지는 도메인 예외 계층

package com.atlas.bts.identity.project

import java.util.UUID

/** 프로젝트 멤버십 서비스의 모든 도메인 예외 기반 타입. */
sealed class ProjectMembershipException(message: String) : RuntimeException(message)

/** 프로젝트가 존재하지 않거나 actor가 해당 프로젝트의 멤버가 아님 (존재숨김 404). */
class ProjectNotFound(projectId: UUID) :
    ProjectMembershipException("project not found: $projectId")

/** 부트스트랩 경로에서 PAT으로 시도함 — JWT 전용 경로 (→ 403 또는 400). */
class BootstrapRequiresJwt(projectId: UUID) :
    ProjectMembershipException("bootstrap requires JWT session, not PAT: project=$projectId")

/** actor가 해당 프로젝트의 PROJECT_ADMIN이 아님 (→ 403). */
class NotProjectAdmin(projectId: UUID, actorId: UUID) :
    ProjectMembershipException("actor $actorId is not PROJECT_ADMIN in project $projectId")

/** 마지막 admin 제거·강등 시도 (→ 409). */
class LastAdminProtected(projectId: UUID) :
    ProjectMembershipException("cannot remove or demote the last PROJECT_ADMIN in project $projectId")

/** 이미 멤버인 사용자를 추가 시도 (→ 409). */
class AlreadyMember(projectId: UUID, userId: UUID) :
    ProjectMembershipException("user $userId is already a member of project $projectId")

/** 변경/제거 대상 사용자가 프로젝트 멤버가 아님 (→ 404). */
class MemberNotFound(projectId: UUID, userId: UUID) :
    ProjectMembershipException("user $userId is not a member of project $projectId")

/** 초대 대상 사용자 ID가 BTS 시스템에 존재하지 않음 (→ 404). */
class UserNotFound(userId: UUID) :
    ProjectMembershipException("user not found: $userId")
