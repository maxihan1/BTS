// ProjectSecuritySchemeService 단위 테스트 — 프로젝트 스킴 적용·해제·조회 + PROJECT_ADMIN 가드 (FR-PM-06 PR-A Task 6)

package com.atlas.bts.identity.issuesecurity

import com.atlas.bts.identity.project.ProjectDirectory
import com.atlas.bts.identity.project.ProjectMembership
import com.atlas.bts.identity.project.ProjectMembershipRepository
import com.atlas.bts.identity.project.ProjectRole
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/**
 * ProjectSecuritySchemeService 단위 테스트 (FR-PM-06 PR-A Task 6).
 *
 * MockK 기반 순수 단위 테스트 — [ProjectSecuritySchemeRepository] / [ProjectDirectory] /
 * [ProjectMembershipRepository] / [IssueSecuritySchemeRepository] 모두 mock.
 *
 * ## 검증 순서 (각 작업 공통)
 * 1. 프로젝트 키 → id 해석. 없으면 [ProjectNotFoundException] (404 의미).
 * 2. actor 가 그 프로젝트 PROJECT_ADMIN 아니면 [ProjectSchemeAccessDeniedException] (403 의미).
 * 3. assign 만: 스킴 실재([IssueSecuritySchemeRepository.findById]) 확인. 없으면 [SchemeNotFoundException].
 *
 * ## 테스트 시나리오
 * - assign: 정상 위임 / 프로젝트 없음→ProjectNotFound / 비-PROJECT_ADMIN→AccessDenied /
 *   멤버십 없음→AccessDenied / 스킴 없음→SchemeNotFound / 가드 우선순위(키해석<권한<스킴).
 * - unassign: 정상 위임 / 프로젝트 없음 / 권한 거부.
 * - findByProject: 정상 반환(null 포함) / 프로젝트 없음 / 권한 거부.
 * - Annotation 회귀 가드: @Service + @Transactional.
 */
class ProjectSecuritySchemeServiceTest {
    private lateinit var projectSchemeRepo: ProjectSecuritySchemeRepository
    private lateinit var projectDirectory: ProjectDirectory
    private lateinit var membershipRepo: ProjectMembershipRepository
    private lateinit var schemeRepo: IssueSecuritySchemeRepository
    private lateinit var service: ProjectSecuritySchemeService

    private val projectKey = "ATLAS"
    private val projectId = UUID.fromString("11111111-1111-4111-8111-111111111111")
    private val schemeId = UUID.fromString("22222222-2222-4222-8222-222222222222")
    private val adminId = UUID.fromString("33333333-3333-4333-8333-333333333333")
    private val memberId = UUID.fromString("44444444-4444-4444-8444-444444444444")
    private val outsiderId = UUID.fromString("55555555-5555-4555-8555-555555555555")

    @BeforeEach
    fun setUp() {
        projectSchemeRepo = mockk(relaxed = true)
        projectDirectory = mockk()
        membershipRepo = mockk()
        schemeRepo = mockk()
        service = ProjectSecuritySchemeService(projectSchemeRepo, projectDirectory, membershipRepo, schemeRepo)
    }

    private fun membership(role: ProjectRole): ProjectMembership =
        ProjectMembership(
            projectId = projectId,
            userId = adminId,
            role = role,
            createdAt = Instant.parse("2026-06-06T00:00:00Z"),
            updatedAt = Instant.parse("2026-06-06T00:00:00Z"),
        )

    private fun persistedSchemeDetail(): IssueSecuritySchemeDetail =
        IssueSecuritySchemeDetail(
            scheme =
                IssueSecurityScheme(
                    id = schemeId,
                    name = "기밀 스킴",
                    description = null,
                    createdAt = Instant.parse("2026-06-06T00:00:00Z"),
                    updatedAt = Instant.parse("2026-06-06T00:00:00Z"),
                ),
            levels = emptyList(),
        )

    // ── assign ──────────────────────────────────────────────────────────────────

    @Test
    fun `assign — PROJECT_ADMIN 이고 스킴 실재하면 영속 위임`() {
        every { projectDirectory.resolveKeyToId(projectKey) } returns projectId
        every { membershipRepo.findByProjectAndUser(projectId, adminId) } returns membership(ProjectRole.PROJECT_ADMIN)
        every { schemeRepo.findById(schemeId) } returns persistedSchemeDetail()

        service.assign(projectKey, schemeId, adminId)

        verify(exactly = 1) { projectSchemeRepo.assign(projectId, schemeId) }
    }

    @Test
    fun `assign — 없는 프로젝트면 ProjectNotFound`() {
        every { projectDirectory.resolveKeyToId(projectKey) } returns null

        assertThatThrownBy { service.assign(projectKey, schemeId, adminId) }
            .isInstanceOf(ProjectNotFoundException::class.java)

        verify(exactly = 0) { projectSchemeRepo.assign(any(), any()) }
    }

    @Test
    fun `assign — MEMBER 역할이면 AccessDenied`() {
        every { projectDirectory.resolveKeyToId(projectKey) } returns projectId
        every { membershipRepo.findByProjectAndUser(projectId, memberId) } returns membership(ProjectRole.MEMBER)

        assertThatThrownBy { service.assign(projectKey, schemeId, memberId) }
            .isInstanceOf(ProjectSchemeAccessDeniedException::class.java)

        verify(exactly = 0) { projectSchemeRepo.assign(any(), any()) }
    }

    @Test
    fun `assign — 멤버십 없으면 AccessDenied`() {
        every { projectDirectory.resolveKeyToId(projectKey) } returns projectId
        every { membershipRepo.findByProjectAndUser(projectId, outsiderId) } returns null

        assertThatThrownBy { service.assign(projectKey, schemeId, outsiderId) }
            .isInstanceOf(ProjectSchemeAccessDeniedException::class.java)

        verify(exactly = 0) { projectSchemeRepo.assign(any(), any()) }
    }

    @Test
    fun `assign — 없는 스킴이면 SchemeNotFound`() {
        every { projectDirectory.resolveKeyToId(projectKey) } returns projectId
        every { membershipRepo.findByProjectAndUser(projectId, adminId) } returns membership(ProjectRole.PROJECT_ADMIN)
        every { schemeRepo.findById(schemeId) } returns null

        assertThatThrownBy { service.assign(projectKey, schemeId, adminId) }
            .isInstanceOf(SchemeNotFoundException::class.java)

        verify(exactly = 0) { projectSchemeRepo.assign(any(), any()) }
    }

    @Test
    fun `assign — 권한 거부가 스킴 조회보다 먼저`() {
        every { projectDirectory.resolveKeyToId(projectKey) } returns projectId
        every { membershipRepo.findByProjectAndUser(projectId, memberId) } returns membership(ProjectRole.MEMBER)

        assertThatThrownBy { service.assign(projectKey, schemeId, memberId) }
            .isInstanceOf(ProjectSchemeAccessDeniedException::class.java)

        verify(exactly = 0) { schemeRepo.findById(any()) }
    }

    // ── unassign ────────────────────────────────────────────────────────────────

    @Test
    fun `unassign — PROJECT_ADMIN 이면 영속 위임`() {
        every { projectDirectory.resolveKeyToId(projectKey) } returns projectId
        every { membershipRepo.findByProjectAndUser(projectId, adminId) } returns membership(ProjectRole.PROJECT_ADMIN)

        service.unassign(projectKey, adminId)

        verify(exactly = 1) { projectSchemeRepo.unassign(projectId) }
    }

    @Test
    fun `unassign — 없는 프로젝트면 ProjectNotFound`() {
        every { projectDirectory.resolveKeyToId(projectKey) } returns null

        assertThatThrownBy { service.unassign(projectKey, adminId) }
            .isInstanceOf(ProjectNotFoundException::class.java)

        verify(exactly = 0) { projectSchemeRepo.unassign(any()) }
    }

    @Test
    fun `unassign — 비-PROJECT_ADMIN 이면 AccessDenied`() {
        every { projectDirectory.resolveKeyToId(projectKey) } returns projectId
        every { membershipRepo.findByProjectAndUser(projectId, memberId) } returns membership(ProjectRole.MEMBER)

        assertThatThrownBy { service.unassign(projectKey, memberId) }
            .isInstanceOf(ProjectSchemeAccessDeniedException::class.java)

        verify(exactly = 0) { projectSchemeRepo.unassign(any()) }
    }

    // ── findByProject ─────────────────────────────────────────────────────────────

    @Test
    fun `findByProject — PROJECT_ADMIN 이면 적용된 schemeId 반환`() {
        every { projectDirectory.resolveKeyToId(projectKey) } returns projectId
        every { membershipRepo.findByProjectAndUser(projectId, adminId) } returns membership(ProjectRole.PROJECT_ADMIN)
        every { projectSchemeRepo.findByProject(projectId) } returns schemeId

        assertThat(service.findByProject(projectKey, adminId)).isEqualTo(schemeId)
    }

    @Test
    fun `findByProject — 미적용이면 null 반환`() {
        every { projectDirectory.resolveKeyToId(projectKey) } returns projectId
        every { membershipRepo.findByProjectAndUser(projectId, adminId) } returns membership(ProjectRole.PROJECT_ADMIN)
        every { projectSchemeRepo.findByProject(projectId) } returns null

        assertThat(service.findByProject(projectKey, adminId)).isNull()
    }

    @Test
    fun `findByProject — 없는 프로젝트면 ProjectNotFound`() {
        every { projectDirectory.resolveKeyToId(projectKey) } returns null

        assertThatThrownBy { service.findByProject(projectKey, adminId) }
            .isInstanceOf(ProjectNotFoundException::class.java)
    }

    @Test
    fun `findByProject — 비-PROJECT_ADMIN 이면 AccessDenied`() {
        every { projectDirectory.resolveKeyToId(projectKey) } returns projectId
        every { membershipRepo.findByProjectAndUser(projectId, memberId) } returns membership(ProjectRole.MEMBER)

        assertThatThrownBy { service.findByProject(projectKey, memberId) }
            .isInstanceOf(ProjectSchemeAccessDeniedException::class.java)
    }

    // ── listLevelsByProject (BE-2, PROJECT_ADMIN 불요) ──────────────────────────────

    private fun persistedLevel(): IssueSecurityLevel =
        IssueSecurityLevel(
            id = UUID.fromString("66666666-6666-4666-8666-666666666666"),
            schemeId = schemeId,
            name = "임원만",
            description = "임원 전용 등급",
            isDefault = true,
            createdAt = Instant.parse("2026-06-06T00:00:00Z"),
        )

    @Test
    fun `listLevelsByProject — 적용 스킴 등급 목록 반환, PROJECT_ADMIN 가드 미호출`() {
        every { projectDirectory.resolveKeyToId(projectKey) } returns projectId
        every { projectSchemeRepo.findByProject(projectId) } returns schemeId
        every { schemeRepo.listLevels(schemeId) } returns listOf(persistedLevel())

        assertThat(service.listLevelsByProject(projectKey)).containsExactly(persistedLevel())

        // 드롭다운 조회는 인증만 요구 — 멤버십(PROJECT_ADMIN) 조회를 하지 않는다.
        verify(exactly = 0) { membershipRepo.findByProjectAndUser(any(), any()) }
    }

    @Test
    fun `listLevelsByProject — 미적용 프로젝트면 빈 목록 (404 아님)`() {
        every { projectDirectory.resolveKeyToId(projectKey) } returns projectId
        every { projectSchemeRepo.findByProject(projectId) } returns null

        assertThat(service.listLevelsByProject(projectKey)).isEmpty()

        verify(exactly = 0) { schemeRepo.listLevels(any()) }
    }

    @Test
    fun `listLevelsByProject — 없는 프로젝트면 ProjectNotFound`() {
        every { projectDirectory.resolveKeyToId(projectKey) } returns null

        assertThatThrownBy { service.listLevelsByProject(projectKey) }
            .isInstanceOf(ProjectNotFoundException::class.java)
    }

    // ── Annotation 회귀 가드 ────────────────────────────────────────────────────────

    @Test
    fun `클래스에 @Service 와 @Transactional 이 선언돼 있다`() {
        val clazz = ProjectSecuritySchemeService::class.java
        assertThat(clazz.isAnnotationPresent(Service::class.java)).isTrue()
        assertThat(clazz.isAnnotationPresent(Transactional::class.java)).isTrue()
    }
}
