// 프로젝트 멤버십 생명주기를 관리하는 서비스 — 가드·부트스트랩·마지막admin 보호 (FR-PM-01 Task 5)

package com.atlas.bts.identity.project

import com.atlas.bts.identity.audit.AuthAuditLog
import com.atlas.bts.identity.audit.AuthAuditLogService
import com.atlas.bts.identity.audit.AuthEventType
import com.atlas.bts.identity.user.UserRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * 프로젝트 멤버십 생명주기 서비스 (FR-PM-01 Task 5).
 *
 * ## 보안 책임
 *
 * - 존재 숨김(404): 비멤버에게 프로젝트 존재 여부를 노출하지 않는다 (B2/B3).
 * - 부트스트랩(B1): PAT 경유 최초 멤버 추가를 차단한다.
 * - 마지막 admin 보호(EC-1/EC-2b): advisory lock + countAdmins 검사로 admin 0명 상태를 방지한다.
 * - 동시성: 부트스트랩과 마지막 admin 조작은 `pg_advisory_xact_lock`으로 직렬화한다.
 *
 * ## 에러 평가 순서
 * 1. 프로젝트 존재 확인 → 2. actor 멤버십(가드) → 3. actor 역할(ADMIN 여부)
 * → 4. 대상 사용자 존재 확인 → 5. 비즈니스 규칙(중복/마지막admin)
 *
 * ## 서비스 전용 예외
 * 모두 이 파일 내 sealed class로 정의한다. HTTP 상태 코드 매핑은 컨트롤러 담당.
 *
 * @see docs/sdd/19-authentication.md §19.7 프로젝트 멤버십 보안
 * @see docs/decisions/2026-06-01-project-membership-model.md 부트스트랩/마지막admin ADR
 */
@Service
@Transactional(isolation = Isolation.READ_COMMITTED)
class ProjectMembershipService(
    private val membershipRepo: ProjectMembershipRepository,
    private val projectDirectory: ProjectDirectory,
    private val userRepository: UserRepository,
    private val auditLogService: AuthAuditLogService,
) {

    // ── 서비스 전용 예외 (sealed class) ─────────────────────────────────────

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

    // ── 공개 서비스 메서드 ───────────────────────────────────────────────────

    /**
     * 프로젝트에 멤버를 추가한다.
     *
     * 부트스트랩(멤버 0명): actor == target + JWT 전용, requestedRole 무관 PROJECT_ADMIN 강제.
     * 일반 초대(멤버 1+): actor는 PROJECT_ADMIN이어야 하며 target이 미멤버여야 한다.
     *
     * @param actorId 요청자 BTS 사용자 ID
     * @param isPat 요청이 PAT 인증 경유인지 여부
     * @param projectId 대상 프로젝트 ID
     * @param targetUserId 초대할 사용자 ID
     * @param requestedRole 요청된 역할 (부트스트랩 시 강제 PROJECT_ADMIN으로 대체됨)
     * @return 저장된 [ProjectMembership]
     * @throws ProjectNotFound 프로젝트 미존재 / 비멤버 존재숨김
     * @throws BootstrapRequiresJwt 부트스트랩 경로에서 PAT 사용
     * @throws NotProjectAdmin actor가 ADMIN이 아님
     * @throws AlreadyMember target이 이미 멤버
     * @throws UserNotFound targetUserId가 BTS에 없음
     */
    fun addMember(
        actorId: UUID,
        isPat: Boolean,
        projectId: UUID,
        targetUserId: UUID,
        requestedRole: ProjectRole,
    ): ProjectMembership {
        // 1. 프로젝트 존재 확인 — lock 불필요하므로 먼저 검사
        requireProjectExists(projectId)

        // 2. advisory lock 획득 — count 판단이 반드시 lock 이후여야 EC-1 race 차단
        membershipRepo.acquireProjectLock(projectId)

        // 3. lock 안에서 count 재조회 — lock 밖에서 읽은 값은 신뢰할 수 없음
        val memberCount = membershipRepo.countByProject(projectId)

        return if (memberCount == 0) {
            addMemberBootstrap(actorId, isPat, projectId, targetUserId)
        } else {
            addMemberNormal(actorId, projectId, targetUserId, requestedRole)
        }
    }

    /**
     * 프로젝트 멤버의 역할을 변경한다.
     *
     * actor는 PROJECT_ADMIN이어야 한다. 마지막 admin을 MEMBER로 강등하는 것을 차단한다.
     *
     * @param actorId 요청자 BTS 사용자 ID
     * @param projectId 대상 프로젝트 ID
     * @param targetUserId 역할을 변경할 사용자 ID
     * @param newRole 변경할 역할
     * @throws ProjectNotFound 프로젝트 미존재 / actor 비멤버
     * @throws NotProjectAdmin actor가 ADMIN이 아님
     * @throws MemberNotFound target이 멤버 아님
     * @throws LastAdminProtected 마지막 ADMIN 강등 시도
     */
    fun changeRole(
        actorId: UUID,
        projectId: UUID,
        targetUserId: UUID,
        newRole: ProjectRole,
    ): ProjectMembership {
        requireProjectExists(projectId)
        requireProjectAdmin(actorId, projectId)

        val target = membershipRepo.findByProjectAndUser(projectId, targetUserId)
            ?: throw MemberNotFound(projectId, targetUserId)

        if (target.role == ProjectRole.PROJECT_ADMIN && newRole == ProjectRole.MEMBER) {
            guardLastAdmin(projectId)
        }

        val updated = membershipRepo.updateRole(projectId, targetUserId, newRole)
            ?: throw MemberNotFound(projectId, targetUserId)

        emitAudit(actorId, AuthEventType.PROJECT_ROLE_CHANGED, projectId, targetUserId)
        return updated
    }

    /**
     * 프로젝트에서 멤버를 제거한다.
     *
     * actor는 PROJECT_ADMIN이어야 한다. 마지막 admin 제거를 차단한다.
     *
     * @throws ProjectNotFound 프로젝트 미존재 / actor 비멤버
     * @throws NotProjectAdmin actor가 ADMIN이 아님
     * @throws MemberNotFound target이 멤버 아님
     * @throws LastAdminProtected 마지막 ADMIN 제거 시도
     */
    fun removeMember(
        actorId: UUID,
        projectId: UUID,
        targetUserId: UUID,
    ) {
        requireProjectExists(projectId)
        requireProjectAdmin(actorId, projectId)

        val target = membershipRepo.findByProjectAndUser(projectId, targetUserId)
            ?: throw MemberNotFound(projectId, targetUserId)

        if (target.role == ProjectRole.PROJECT_ADMIN) {
            guardLastAdmin(projectId)
        }

        membershipRepo.deleteByProjectAndUser(projectId, targetUserId)
        emitAudit(actorId, AuthEventType.PROJECT_MEMBER_REMOVED, projectId, targetUserId)
    }

    /**
     * 프로젝트 멤버 목록을 반환한다.
     *
     * actor가 해당 프로젝트의 멤버여야 한다 (비멤버는 존재숨김 404).
     * 역할 권한 구분 없이 모든 멤버가 조회 가능하다.
     *
     * @param actorId 요청자 사용자 ID
     * @param projectId 대상 프로젝트 ID
     * @return 프로젝트 멤버 목록
     * @throws ProjectNotFound 프로젝트 미존재 / actor 비멤버
     */
    @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
    fun listMembers(actorId: UUID, projectId: UUID): List<ProjectMembership> {
        requireProjectExists(projectId)
        resolveMembershipOr404(actorId, projectId) // 비멤버 존재숨김
        return membershipRepo.listByProject(projectId)
    }

    // ── 내부 헬퍼 ────────────────────────────────────────────────────────────

    /**
     * 부트스트랩 경로 — 멤버 0명일 때만 호출.
     *
     * lock 획득 및 count 재조회는 호출자(addMember)에서 이미 완료된 상태.
     * 이 메서드는 lock 안에서 실행되므로 중복 acquireProjectLock 호출 금지.
     * 조건 위반 시 ProjectNotFound/BootstrapRequiresJwt를 던진다.
     * 조건 충족 시 requestedRole 무관 PROJECT_ADMIN 강제 저장.
     */
    private fun addMemberBootstrap(
        actorId: UUID,
        isPat: Boolean,
        projectId: UUID,
        targetUserId: UUID,
    ): ProjectMembership {
        // lock 및 count 재조회는 addMember에서 완료됨 — 여기서 재획득 금지 (EC-1)

        if (isPat) throw BootstrapRequiresJwt(projectId)

        // B2: target != actor → 비멤버 취급 → 존재숨김
        if (targetUserId != actorId) throw ProjectNotFound(projectId)

        val membership = ProjectMembership(
            projectId = projectId,
            userId    = actorId,
            role      = ProjectRole.PROJECT_ADMIN, // 항상 강제
            createdAt = java.time.Instant.now(),
            updatedAt = java.time.Instant.now(),
        )
        val saved = membershipRepo.save(membership)
        emitAudit(actorId, AuthEventType.PROJECT_MEMBER_ADDED, projectId, actorId)
        return saved
    }

    /**
     * 일반 초대 경로 — 멤버 1명 이상일 때 호출.
     *
     * actor 가드 → target 사용자 존재 확인 → 중복 확인 → 저장.
     */
    private fun addMemberNormal(
        actorId: UUID,
        projectId: UUID,
        targetUserId: UUID,
        requestedRole: ProjectRole,
    ): ProjectMembership {
        requireProjectAdmin(actorId, projectId)

        // FR-9: target 사용자 존재 확인
        userRepository.findById(targetUserId) ?: throw UserNotFound(targetUserId)

        // FR-10: 중복 멤버 확인
        val existing = membershipRepo.findByProjectAndUser(projectId, targetUserId)
        if (existing != null) throw AlreadyMember(projectId, targetUserId)

        val membership = ProjectMembership(
            projectId = projectId,
            userId    = targetUserId,
            role      = requestedRole,
            createdAt = java.time.Instant.now(),
            updatedAt = java.time.Instant.now(),
        )
        val saved = membershipRepo.save(membership)
        emitAudit(actorId, AuthEventType.PROJECT_MEMBER_ADDED, projectId, targetUserId)
        return saved
    }

    /**
     * 마지막 ADMIN 제거·강등을 방지한다.
     *
     * advisory lock으로 count→throw를 직렬화하여 동시 요청(EC-2b)을 차단한다.
     *
     * @throws LastAdminProtected admin이 1명 이하인 경우
     */
    private fun guardLastAdmin(projectId: UUID) {
        membershipRepo.acquireProjectLock(projectId)
        if (membershipRepo.countAdminsByProject(projectId) <= 1) {
            throw LastAdminProtected(projectId)
        }
    }

    /**
     * 프로젝트가 활성 상태로 존재하는지 확인한다.
     *
     * 미존재 시 [ProjectNotFound] 예외를 던진다.
     */
    private fun requireProjectExists(projectId: UUID) {
        if (!projectDirectory.exists(projectId)) throw ProjectNotFound(projectId)
    }

    /**
     * actor가 해당 프로젝트의 PROJECT_ADMIN인지 검증한다.
     *
     * 비멤버면 [ProjectNotFound](존재숨김), MEMBER 역할이면 [NotProjectAdmin].
     */
    private fun requireProjectAdmin(actorId: UUID, projectId: UUID) {
        val actorMembership = resolveMembershipOr404(actorId, projectId)
        if (actorMembership.role != ProjectRole.PROJECT_ADMIN) {
            throw NotProjectAdmin(projectId, actorId)
        }
    }

    /**
     * actor의 멤버십을 조회한다.
     *
     * 멤버십이 없으면 [ProjectNotFound]를 던진다 (B3 존재숨김).
     */
    private fun resolveMembershipOr404(actorId: UUID, projectId: UUID): ProjectMembership =
        membershipRepo.findByProjectAndUser(projectId, actorId)
            ?: throw ProjectNotFound(projectId)

    /**
     * 감사 로그 이벤트를 emit한다.
     *
     * providerId는 "project-membership"으로 고정한다.
     * metadata에 프로젝트/대상 사용자 ID를 포함한다.
     */
    private fun emitAudit(
        actorId: UUID,
        eventType: AuthEventType,
        projectId: UUID,
        targetUserId: UUID,
    ) {
        auditLogService.record(
            AuthAuditLog(
                userId      = actorId,
                eventType   = eventType,
                providerId  = "project-membership",
                metadata    = mapOf("projectId" to projectId.toString(), "targetUserId" to targetUserId.toString()),
            )
        )
    }
}
