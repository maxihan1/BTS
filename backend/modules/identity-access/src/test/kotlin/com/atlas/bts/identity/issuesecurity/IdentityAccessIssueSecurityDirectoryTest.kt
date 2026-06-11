// IdentityAccessIssueSecurityDirectory 단위테스트 — accessibleLevels 멤버조회 배치화(N+1 회피) 검증 (FR-PM-06 PR-B C2)

package com.atlas.bts.identity.issuesecurity

import com.atlas.bts.identity.group.UserGroupRepository
import com.atlas.bts.identity.project.ProjectDirectory
import com.atlas.bts.identity.project.ProjectMembership
import com.atlas.bts.identity.project.ProjectMembershipRepository
import com.atlas.bts.identity.project.ProjectRole
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.time.Instant
import java.util.UUID

/**
 * [IdentityAccessIssueSecurityDirectory] 단위테스트 (FR-PM-06 PR-B C2).
 *
 * ## 목적
 * accessibleLevels 가 등급 수 N 에 비례해 [IssueSecuritySchemeRepository.listMembers] 를
 * 호출하던 N+1 을 단일 배치 조회([IssueSecuritySchemeRepository.listMembersByScheme])로
 * 바꿨음을 mockk verify 로 잠근다(쿼리 수 회귀 방지). 등급집합 정확성의 ground-truth 는
 * [IdentityAccessIssueSecurityDirectoryIntegrationTest](실 DB)이며, 여기서는 호출 횟수만 검증한다.
 */
class IdentityAccessIssueSecurityDirectoryTest {
    private val projectDirectory: ProjectDirectory = mockk()
    private val projectSecuritySchemeRepo: ProjectSecuritySchemeRepository = mockk()
    private val schemeRepo: IssueSecuritySchemeRepository = mockk()
    private val membershipRepo: ProjectMembershipRepository = mockk()
    private val userGroupRepo: UserGroupRepository = mockk()
    private val jdbc: NamedParameterJdbcTemplate = mockk()

    private val directory =
        IdentityAccessIssueSecurityDirectory(
            projectDirectory,
            projectSecuritySchemeRepo,
            schemeRepo,
            membershipRepo,
            userGroupRepo,
            jdbc,
        )

    private val actorId = UUID.fromString("00000000-aaaa-0000-0000-000000000001")
    private val projectId = UUID.fromString("eeeeeeee-0000-0000-0000-000000000001")
    private val schemeId = UUID.fromString("11111111-0000-0000-0000-000000000001")
    private val projectKey = "SECDIR"

    @Test
    fun `accessibleLevels는 등급 수와 무관하게 멤버를 단일 배치쿼리로만 조회한다 (N+1 회피)`() {
        val levelIds = (1..5).map { UUID.fromString("22222222-0000-0000-0000-00000000000$it") }
        every { projectDirectory.resolveKeyToId(projectKey) } returns projectId
        every { projectSecuritySchemeRepo.findByProject(projectId) } returns schemeId
        every { membershipRepo.findByProjectAndUser(projectId, actorId) } returns membership(actorId)
        every { schemeRepo.listLevels(schemeId) } returns levelIds.map { level(it) }
        // actor 가 첫 등급의 USER 멤버. 나머지 등급은 actor 비충족 멤버.
        every { schemeRepo.listMembersByScheme(schemeId) } returns
            listOf(member(levelIds[0], MemberType.USER, actorId.toString())) +
            levelIds.drop(1).map { member(it, MemberType.USER, UUID.randomUUID().toString()) }
        every { schemeRepo.listLevelIdsByMemberType(schemeId, MemberType.REPORTER) } returns emptySet()
        every { schemeRepo.listLevelIdsByMemberType(schemeId, MemberType.ASSIGNEE) } returns emptySet()

        val access = directory.accessibleLevels(actorId, projectKey)

        assertThat(access.staticLevelIds).containsExactly(levelIds[0])
        // 핵심: 등급이 5개여도 멤버 배치쿼리는 정확히 1회. listMembers(등급별)는 0회.
        verify(exactly = 1) { schemeRepo.listMembersByScheme(schemeId) }
        verify(exactly = 0) { schemeRepo.listMembers(any()) }
    }

    private fun level(id: UUID) =
        IssueSecurityLevel(
            id = id,
            schemeId = schemeId,
            name = "lvl-$id",
            description = null,
            isDefault = false,
            createdAt = Instant.now(),
        )

    private fun member(
        levelId: UUID,
        type: MemberType,
        value: String?,
    ) = SecurityLevelMember(
        id = UUID.randomUUID(),
        levelId = levelId,
        memberType = type,
        memberValue = value,
        createdAt = Instant.now(),
    )

    private fun membership(userId: UUID) =
        ProjectMembership(
            projectId = projectId,
            userId = userId,
            role = ProjectRole.MEMBER,
            createdAt = Instant.now(),
            updatedAt = Instant.now(),
        )
}
