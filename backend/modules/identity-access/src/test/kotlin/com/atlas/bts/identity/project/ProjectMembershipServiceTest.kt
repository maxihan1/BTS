// ProjectMembershipService 단위 테스트 — 부트스트랩/가드/마지막admin/동시성 로직 (FR-PM-01 Task 5)

package com.atlas.bts.identity.project

import com.atlas.bts.identity.audit.AuthAuditLog
import com.atlas.bts.identity.audit.AuthAuditLogService
import com.atlas.bts.identity.audit.AuthEventType
import com.atlas.bts.identity.user.UserRepository
import com.atlas.bts.identity.user.User
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import io.mockk.verify
import io.mockk.verifyOrder
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/**
 * ProjectMembershipService 단위 테스트 (FR-PM-01 Task 5).
 *
 * MockK 기반 순수 단위 테스트 — repository/ProjectDirectory/UserRepository/audit 모두 mock.
 *
 * ## 테스트 시나리오
 *
 * - S1  부트스트랩(멤버 0명): 자기자신+JWT → PROJECT_ADMIN 강제 저장 + audit emit
 * - S1b 부트스트랩: role=MEMBER 요청도 PROJECT_ADMIN 강제 (EC-4)
 * - S1c 부트스트랩: isPat=true → BootstrapRequiresJwt
 * - S1d 부트스트랩: targetUserId != actorId → ProjectNotFound (B2 존재숨김)
 * - S2  ADMIN 초대: 기존 ADMIN이 새 멤버 초대
 * - S3  비ADMIN 초대 시도 → NotProjectAdmin
 * - S3b 비멤버 초대 시도 → ProjectNotFound (B3 존재숨김)
 * - S4  역할 변경: ADMIN이 MEMBER → ADMIN 변경
 * - S5  멤버 제거: ADMIN이 일반 MEMBER 제거
 * - S6  마지막 ADMIN 제거 시도 → LastAdminProtected
 * - S8  마지막 ADMIN 강등 시도 → LastAdminProtected
 * - FR8 프로젝트 미존재 → ProjectNotFound
 * - FR9 존재하지 않는 targetUserId 초대 → UserNotFound
 * - FR10 이미 멤버인 사용자 추가 → AlreadyMember
 * - FR11 audit emit 검증 (addMember/changeRole/removeMember)
 * - Annotation 회귀 가드: @Service + @Transactional
 */
class ProjectMembershipServiceTest {

    // ── 픽스처 ────────────────────────────────────────────────────────────────

    private val projectId = UUID.fromString("11111111-1111-1111-1111-111111111111")
    private val actorId   = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
    private val targetId  = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb")
    private val fixedNow  = Instant.parse("2026-06-01T00:00:00Z")

    private lateinit var membershipRepo: ProjectMembershipRepository
    private lateinit var projectDirectory: ProjectDirectory
    private lateinit var userRepository: UserRepository
    private lateinit var auditLogService: AuthAuditLogService
    private lateinit var service: ProjectMembershipService

    @BeforeEach
    fun setUp() {
        membershipRepo   = mockk()
        projectDirectory = mockk()
        userRepository   = mockk()
        auditLogService  = mockk()
        service = ProjectMembershipService(
            membershipRepo,
            projectDirectory,
            userRepository,
            auditLogService,
        )
    }

    // ── 헬퍼 ──────────────────────────────────────────────────────────────────

    private fun membership(userId: UUID, role: ProjectRole) = ProjectMembership(
        projectId = projectId,
        userId    = userId,
        role      = role,
        createdAt = fixedNow,
        updatedAt = fixedNow,
    )

    private fun stubProjectExists()   { every { projectDirectory.exists(projectId) } returns true }
    private fun stubProjectMissing()  { every { projectDirectory.exists(projectId) } returns false }
    private fun stubAuditRecord()     { every { auditLogService.record(any()) } just runs }
    private fun stubAcquireLock()     { every { membershipRepo.acquireProjectLock(projectId) } just runs }

    private fun stubUser(userId: UUID) {
        every { userRepository.findById(userId) } returns User(
            id = userId, username = "user-${userId.toString().take(4)}",
            email = null, displayName = "User", createdAt = fixedNow, updatedAt = fixedNow,
        )
    }

    // ── S1: 부트스트랩 정상 흐름 ─────────────────────────────────────────────

    @Test
    fun `S1 부트스트랩 — 멤버 0명, 자기자신, JWT → PROJECT_ADMIN 강제 저장 + audit emit`() {
        stubProjectExists()
        stubAcquireLock()
        stubAuditRecord()
        stubUser(actorId)
        every { membershipRepo.countByProject(projectId) } returns 0
        val saved = membership(actorId, ProjectRole.PROJECT_ADMIN)
        every { membershipRepo.save(match { it.role == ProjectRole.PROJECT_ADMIN }) } returns saved

        val result = service.addMember(
            actorId       = actorId,
            isPat         = false,
            projectId     = projectId,
            targetUserId  = actorId,
            requestedRole = ProjectRole.MEMBER, // MEMBER 요청해도 강제 ADMIN
        )

        assertThat(result.role).isEqualTo(ProjectRole.PROJECT_ADMIN)
        verify(exactly = 1) { membershipRepo.save(match { it.role == ProjectRole.PROJECT_ADMIN }) }
        verify(exactly = 1) { auditLogService.record(match { it.eventType == AuthEventType.PROJECT_MEMBER_ADDED }) }
    }

    @Test
    fun `S1b 부트스트랩 EC-4 — requestedRole=MEMBER여도 PROJECT_ADMIN 강제`() {
        stubProjectExists()
        stubAcquireLock()
        stubAuditRecord()
        stubUser(actorId)
        every { membershipRepo.countByProject(projectId) } returns 0
        val saved = membership(actorId, ProjectRole.PROJECT_ADMIN)
        every { membershipRepo.save(match { it.role == ProjectRole.PROJECT_ADMIN }) } returns saved

        val result = service.addMember(actorId, false, projectId, actorId, ProjectRole.MEMBER)

        assertThat(result.role).isEqualTo(ProjectRole.PROJECT_ADMIN)
    }

    @Test
    fun `S1c 부트스트랩 — isPat=true → BootstrapRequiresJwt`() {
        stubProjectExists()
        stubAcquireLock()
        every { membershipRepo.countByProject(projectId) } returns 0

        assertThatThrownBy {
            service.addMember(actorId, true, projectId, actorId, ProjectRole.PROJECT_ADMIN)
        }.isInstanceOf(ProjectMembershipService.BootstrapRequiresJwt::class.java)
    }

    @Test
    fun `S1d 부트스트랩 — targetUserId != actorId → ProjectNotFound (B2 존재숨김)`() {
        stubProjectExists()
        stubAcquireLock()
        every { membershipRepo.countByProject(projectId) } returns 0

        assertThatThrownBy {
            service.addMember(actorId, false, projectId, targetId, ProjectRole.PROJECT_ADMIN)
        }.isInstanceOf(ProjectMembershipService.ProjectNotFound::class.java)
    }

    // ── S2: ADMIN이 다른 사용자 초대 ─────────────────────────────────────────

    @Test
    fun `S2 ADMIN 초대 — 기존 멤버 1명, actor=ADMIN, 새 타깃 초대`() {
        stubProjectExists()
        stubAcquireLock()
        stubAuditRecord()
        stubUser(targetId)
        every { membershipRepo.countByProject(projectId) } returns 1
        every { membershipRepo.findByProjectAndUser(projectId, actorId) } returns
            membership(actorId, ProjectRole.PROJECT_ADMIN)
        every { membershipRepo.findByProjectAndUser(projectId, targetId) } returns null
        val saved = membership(targetId, ProjectRole.MEMBER)
        every { membershipRepo.save(any()) } returns saved

        val result = service.addMember(actorId, false, projectId, targetId, ProjectRole.MEMBER)

        assertThat(result.userId).isEqualTo(targetId)
        verify(exactly = 1) {
            auditLogService.record(match { it.eventType == AuthEventType.PROJECT_MEMBER_ADDED })
        }
    }

    // ── S3: 권한 가드 ─────────────────────────────────────────────────────────

    @Test
    fun `S3 비ADMIN 멤버가 초대 시도 → NotProjectAdmin`() {
        stubProjectExists()
        stubAcquireLock()
        every { membershipRepo.countByProject(projectId) } returns 2
        every { membershipRepo.findByProjectAndUser(projectId, actorId) } returns
            membership(actorId, ProjectRole.MEMBER)

        assertThatThrownBy {
            service.addMember(actorId, false, projectId, targetId, ProjectRole.MEMBER)
        }.isInstanceOf(ProjectMembershipService.NotProjectAdmin::class.java)
    }

    @Test
    fun `S3b 비멤버가 초대 시도 → ProjectNotFound (B3 존재숨김)`() {
        stubProjectExists()
        stubAcquireLock()
        every { membershipRepo.countByProject(projectId) } returns 1
        every { membershipRepo.findByProjectAndUser(projectId, actorId) } returns null

        assertThatThrownBy {
            service.addMember(actorId, false, projectId, targetId, ProjectRole.MEMBER)
        }.isInstanceOf(ProjectMembershipService.ProjectNotFound::class.java)
    }

    // ── S4: 역할 변경 ─────────────────────────────────────────────────────────

    @Test
    fun `S4 역할 변경 — ADMIN이 MEMBER를 ADMIN으로 변경`() {
        stubProjectExists()
        stubAuditRecord()
        every { membershipRepo.countByProject(projectId) } returns 2
        every { membershipRepo.findByProjectAndUser(projectId, actorId) } returns
            membership(actorId, ProjectRole.PROJECT_ADMIN)
        every { membershipRepo.findByProjectAndUser(projectId, targetId) } returns
            membership(targetId, ProjectRole.MEMBER)
        val updated = membership(targetId, ProjectRole.PROJECT_ADMIN)
        every { membershipRepo.updateRole(projectId, targetId, ProjectRole.PROJECT_ADMIN) } returns updated

        val result = service.changeRole(actorId, projectId, targetId, ProjectRole.PROJECT_ADMIN)

        assertThat(result.role).isEqualTo(ProjectRole.PROJECT_ADMIN)
        verify(exactly = 1) {
            auditLogService.record(match { it.eventType == AuthEventType.PROJECT_ROLE_CHANGED })
        }
    }

    @Test
    fun `S4b changeRole 대상이 멤버 아님 → MemberNotFound`() {
        stubProjectExists()
        every { membershipRepo.countByProject(projectId) } returns 1
        every { membershipRepo.findByProjectAndUser(projectId, actorId) } returns
            membership(actorId, ProjectRole.PROJECT_ADMIN)
        every { membershipRepo.findByProjectAndUser(projectId, targetId) } returns null

        assertThatThrownBy {
            service.changeRole(actorId, projectId, targetId, ProjectRole.MEMBER)
        }.isInstanceOf(ProjectMembershipService.MemberNotFound::class.java)
    }

    // ── S5: 멤버 제거 ─────────────────────────────────────────────────────────

    @Test
    fun `S5 멤버 제거 — ADMIN이 일반 MEMBER 제거`() {
        stubProjectExists()
        stubAuditRecord()
        every { membershipRepo.countByProject(projectId) } returns 2
        every { membershipRepo.findByProjectAndUser(projectId, actorId) } returns
            membership(actorId, ProjectRole.PROJECT_ADMIN)
        every { membershipRepo.findByProjectAndUser(projectId, targetId) } returns
            membership(targetId, ProjectRole.MEMBER)
        every { membershipRepo.countAdminsByProject(projectId) } returns 1
        every { membershipRepo.deleteByProjectAndUser(projectId, targetId) } returns true

        service.removeMember(actorId, projectId, targetId)

        verify(exactly = 1) { membershipRepo.deleteByProjectAndUser(projectId, targetId) }
        verify(exactly = 1) {
            auditLogService.record(match { it.eventType == AuthEventType.PROJECT_MEMBER_REMOVED })
        }
    }

    @Test
    fun `S5b removeMember 대상이 멤버 아님 → MemberNotFound`() {
        stubProjectExists()
        every { membershipRepo.countByProject(projectId) } returns 1
        every { membershipRepo.findByProjectAndUser(projectId, actorId) } returns
            membership(actorId, ProjectRole.PROJECT_ADMIN)
        every { membershipRepo.findByProjectAndUser(projectId, targetId) } returns null

        assertThatThrownBy {
            service.removeMember(actorId, projectId, targetId)
        }.isInstanceOf(ProjectMembershipService.MemberNotFound::class.java)
    }

    // ── S6/S8: 마지막 ADMIN 보호 ─────────────────────────────────────────────

    @Test
    fun `S6 마지막 ADMIN 제거 시도 → LastAdminProtected`() {
        stubProjectExists()
        stubAcquireLock()
        every { membershipRepo.countByProject(projectId) } returns 1
        every { membershipRepo.findByProjectAndUser(projectId, actorId) } returns
            membership(actorId, ProjectRole.PROJECT_ADMIN)
        every { membershipRepo.countAdminsByProject(projectId) } returns 1

        assertThatThrownBy {
            service.removeMember(actorId, projectId, actorId)
        }.isInstanceOf(ProjectMembershipService.LastAdminProtected::class.java)
    }

    @Test
    fun `S8 마지막 ADMIN 강등 시도 — ADMIN→MEMBER 변경 → LastAdminProtected`() {
        stubProjectExists()
        stubAcquireLock()
        every { membershipRepo.countByProject(projectId) } returns 2
        every { membershipRepo.findByProjectAndUser(projectId, actorId) } returns
            membership(actorId, ProjectRole.PROJECT_ADMIN)
        every { membershipRepo.countAdminsByProject(projectId) } returns 1

        assertThatThrownBy {
            service.changeRole(actorId, projectId, actorId, ProjectRole.MEMBER)
        }.isInstanceOf(ProjectMembershipService.LastAdminProtected::class.java)
    }

    // ── FR8: 프로젝트 미존재 ──────────────────────────────────────────────────

    @Test
    fun `FR8 프로젝트 미존재 — addMember → ProjectNotFound`() {
        stubProjectMissing()

        assertThatThrownBy {
            service.addMember(actorId, false, projectId, targetId, ProjectRole.MEMBER)
        }.isInstanceOf(ProjectMembershipService.ProjectNotFound::class.java)
    }

    @Test
    fun `FR8 프로젝트 미존재 — changeRole → ProjectNotFound`() {
        stubProjectMissing()

        assertThatThrownBy {
            service.changeRole(actorId, projectId, targetId, ProjectRole.MEMBER)
        }.isInstanceOf(ProjectMembershipService.ProjectNotFound::class.java)
    }

    @Test
    fun `FR8 프로젝트 미존재 — removeMember → ProjectNotFound`() {
        stubProjectMissing()

        assertThatThrownBy {
            service.removeMember(actorId, projectId, targetId)
        }.isInstanceOf(ProjectMembershipService.ProjectNotFound::class.java)
    }

    @Test
    fun `FR8 프로젝트 미존재 — listMembers → ProjectNotFound`() {
        stubProjectMissing()

        assertThatThrownBy {
            service.listMembers(actorId, projectId)
        }.isInstanceOf(ProjectMembershipService.ProjectNotFound::class.java)
    }

    // ── FR9: 초대 대상 사용자 미존재 ─────────────────────────────────────────

    @Test
    fun `FR9 존재하지 않는 targetUserId 초대 → UserNotFound`() {
        stubProjectExists()
        stubAcquireLock()
        every { membershipRepo.countByProject(projectId) } returns 1
        every { membershipRepo.findByProjectAndUser(projectId, actorId) } returns
            membership(actorId, ProjectRole.PROJECT_ADMIN)
        every { membershipRepo.findByProjectAndUser(projectId, targetId) } returns null
        every { userRepository.findById(targetId) } returns null

        assertThatThrownBy {
            service.addMember(actorId, false, projectId, targetId, ProjectRole.MEMBER)
        }.isInstanceOf(ProjectMembershipService.UserNotFound::class.java)
    }

    // ── FR10: 중복 멤버 추가 ─────────────────────────────────────────────────

    @Test
    fun `FR10 이미 멤버인 사용자 추가 → AlreadyMember`() {
        stubProjectExists()
        stubAcquireLock()
        stubUser(targetId)
        every { membershipRepo.countByProject(projectId) } returns 2
        every { membershipRepo.findByProjectAndUser(projectId, actorId) } returns
            membership(actorId, ProjectRole.PROJECT_ADMIN)
        every { membershipRepo.findByProjectAndUser(projectId, targetId) } returns
            membership(targetId, ProjectRole.MEMBER)

        assertThatThrownBy {
            service.addMember(actorId, false, projectId, targetId, ProjectRole.MEMBER)
        }.isInstanceOf(ProjectMembershipService.AlreadyMember::class.java)
    }

    // ── listMembers ──────────────────────────────────────────────────────────

    @Test
    fun `listMembers — 프로젝트 멤버 목록 반환`() {
        stubProjectExists()
        every { membershipRepo.countByProject(projectId) } returns 2
        every { membershipRepo.findByProjectAndUser(projectId, actorId) } returns
            membership(actorId, ProjectRole.PROJECT_ADMIN)
        val members = listOf(
            membership(actorId, ProjectRole.PROJECT_ADMIN),
            membership(targetId, ProjectRole.MEMBER),
        )
        every { membershipRepo.listByProject(projectId) } returns members

        val result = service.listMembers(actorId, projectId)

        assertThat(result).hasSize(2)
    }

    @Test
    fun `listMembers — 비멤버 actor → ProjectNotFound (B3 존재숨김)`() {
        stubProjectExists()
        every { membershipRepo.countByProject(projectId) } returns 1
        every { membershipRepo.findByProjectAndUser(projectId, actorId) } returns null

        assertThatThrownBy {
            service.listMembers(actorId, projectId)
        }.isInstanceOf(ProjectMembershipService.ProjectNotFound::class.java)
    }

    // ── EC-1 동시성 가드: lock 획득 순서 검증 ────────────────────────────────

    /**
     * EC-1 회귀 가드: acquireProjectLock이 countByProject보다 먼저 호출되어야 한다.
     *
     * lock 밖에서 count를 읽으면 두 요청이 동시에 count==0을 읽어 둘 다 부트스트랩
     * 경로로 진입하는 TOCTOU race가 발생한다. verifyOrder로 순서를 고정한다.
     */
    @Test
    fun `EC-1 회귀 가드 — acquireProjectLock이 countByProject보다 먼저 호출됨`() {
        stubProjectExists()
        stubAcquireLock()
        stubAuditRecord()
        every { membershipRepo.countByProject(projectId) } returns 0
        val saved = membership(actorId, ProjectRole.PROJECT_ADMIN)
        every { membershipRepo.save(any()) } returns saved

        service.addMember(actorId, false, projectId, actorId, ProjectRole.PROJECT_ADMIN)

        verifyOrder {
            membershipRepo.acquireProjectLock(projectId)
            membershipRepo.countByProject(projectId)
        }
    }

    /**
     * EC-1 회귀 가드 — 일반 초대(count>0) 경로에서도 acquireProjectLock이 먼저 호출됨.
     */
    @Test
    fun `EC-1 회귀 가드 — 일반 초대 경로에서도 acquireProjectLock이 countByProject보다 먼저 호출됨`() {
        stubProjectExists()
        stubAcquireLock()
        stubAuditRecord()
        stubUser(targetId)
        every { membershipRepo.countByProject(projectId) } returns 1
        every { membershipRepo.findByProjectAndUser(projectId, actorId) } returns
            membership(actorId, ProjectRole.PROJECT_ADMIN)
        every { membershipRepo.findByProjectAndUser(projectId, targetId) } returns null
        val saved = membership(targetId, ProjectRole.MEMBER)
        every { membershipRepo.save(any()) } returns saved

        service.addMember(actorId, false, projectId, targetId, ProjectRole.MEMBER)

        verifyOrder {
            membershipRepo.acquireProjectLock(projectId)
            membershipRepo.countByProject(projectId)
        }
    }

    // ── Annotation 회귀 가드 ─────────────────────────────────────────────────

    @Test
    fun `@Service 어노테이션이 ProjectMembershipService에 부착됨`() {
        assertThat(ProjectMembershipService::class.java.isAnnotationPresent(Service::class.java)).isTrue()
    }

    @Test
    fun `@Transactional 어노테이션이 ProjectMembershipService에 부착됨`() {
        assertThat(ProjectMembershipService::class.java.isAnnotationPresent(Transactional::class.java)).isTrue()
    }
}
