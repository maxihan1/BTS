// SprintVelocityService MockK 단위 테스트 — 권한 판정 + limit 클램프 + 벨로시티 조립 RED 명세 (FR-RP-02 Task 4)

package com.bts.agileplanning.application

import com.bts.agileplanning.domain.Sprint
import com.bts.agileplanning.domain.SprintStatus
import com.bts.agileplanning.repository.SprintRepository
import com.bts.shared.permission.IssuePermission
import com.bts.shared.permission.IssuePermissionResolver
import com.bts.shared.permission.IssueScope
import com.bts.shared.velocity.SprintVelocityLookupPort
import com.bts.shared.velocity.VelocityContribution
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.web.server.ResponseStatusException
import java.time.LocalDate
import java.util.UUID

/**
 * SprintVelocityService 단위 테스트.
 *
 * cross-BC 포트([SprintVelocityLookupPort], [IssuePermissionResolver])와 [SprintRepository] 를
 * MockK 로 교체해 독립적으로 검증한다. relaxed mock 과 `any()` 남용을 피하고 명시 스텁 +
 * verify 로 인자를 검증한다(가짜그린 방지).
 */
class SprintVelocityServiceTest {
    private val actorId: UUID = UUID.randomUUID()
    private val projectKey = "ATLAS"

    /** 기본 allow-all resolver — 원하는 테스트에서만 false 세팅. */
    private fun allowAllResolver(): IssuePermissionResolver =
        mockk<IssuePermissionResolver>().also {
            every { it.hasPermission(any(), any(), any()) } returns true
        }

    private fun makeService(
        velocityPort: SprintVelocityLookupPort = mockk(),
        sprintRepository: SprintRepository = mockk(),
        permissionResolver: IssuePermissionResolver = allowAllResolver(),
    ): SprintVelocityService = SprintVelocityService(velocityPort, sprintRepository, permissionResolver)

    /** created_at 오름차순을 흉내 낸 COMPLETED 스프린트 N개를 생성한다. */
    private fun completedSprint(
        index: Int,
    ): Sprint =
        Sprint(
            id = UUID.randomUUID(),
            projectKey = projectKey,
            name = "Sprint $index",
            goal = null,
            status = SprintStatus.COMPLETED,
            startDate = LocalDate.of(2026, 1, index),
            endDate = LocalDate.of(2026, 1, index + 5),
            version = 0L,
        )

    @Test
    fun `BROWSE 권한이 없으면 403을 던진다`() {
        val resolver =
            mockk<IssuePermissionResolver>().also {
                every {
                    it.hasPermission(actorId, IssuePermission.BROWSE, IssueScope.Project(projectKey))
                } returns false
            }

        assertThatThrownBy {
            makeService(permissionResolver = resolver).getVelocity(actorId, projectKey, limit = 5)
        }.isInstanceOf(ResponseStatusException::class.java)
            .extracting("statusCode.value")
            .isEqualTo(403)
    }

    @Test
    fun `COMPLETED 스프린트 중 최근 limit개만 오름차순으로 선택한다`() {
        val sprints = (1..5).map { completedSprint(it) }
        val repo =
            mockk<SprintRepository>().also {
                every { it.findByProject(projectKey, SprintStatus.COMPLETED) } returns sprints
                every { it.findIssueKeysByProject(projectKey) } returns emptyMap()
            }
        val port =
            mockk<SprintVelocityLookupPort>().also {
                every { it.fetchVelocitySource(emptyMap(), projectKey, actorId) } returns emptyMap()
            }

        val result = makeService(velocityPort = port, sprintRepository = repo).getVelocity(actorId, projectKey, limit = 3)

        assertThat(result.points.map { it.sprintId })
            .containsExactly(sprints[2].id, sprints[3].id, sprints[4].id)
        assertThat(result.points.map { it.name })
            .containsExactly("Sprint 3", "Sprint 4", "Sprint 5")
    }

    @Test
    fun `limit 0 은 1로 클램프된다`() {
        val sprints = (1..5).map { completedSprint(it) }
        val repo =
            mockk<SprintRepository>().also {
                every { it.findByProject(projectKey, SprintStatus.COMPLETED) } returns sprints
                every { it.findIssueKeysByProject(projectKey) } returns emptyMap()
            }
        val port =
            mockk<SprintVelocityLookupPort>().also {
                every { it.fetchVelocitySource(emptyMap(), projectKey, actorId) } returns emptyMap()
            }

        val result = makeService(velocityPort = port, sprintRepository = repo).getVelocity(actorId, projectKey, limit = 0)

        assertThat(result.points).hasSize(1)
        assertThat(result.points.single().sprintId).isEqualTo(sprints[4].id)
    }

    @Test
    fun `limit 음수는 1로 클램프된다`() {
        val sprints = (1..5).map { completedSprint(it) }
        val repo =
            mockk<SprintRepository>().also {
                every { it.findByProject(projectKey, SprintStatus.COMPLETED) } returns sprints
                every { it.findIssueKeysByProject(projectKey) } returns emptyMap()
            }
        val port =
            mockk<SprintVelocityLookupPort>().also {
                every { it.fetchVelocitySource(emptyMap(), projectKey, actorId) } returns emptyMap()
            }

        val result = makeService(velocityPort = port, sprintRepository = repo).getVelocity(actorId, projectKey, limit = -5)

        assertThat(result.points).hasSize(1)
        assertThat(result.points.single().sprintId).isEqualTo(sprints[4].id)
    }

    @Test
    fun `limit 100은 50으로 클램프된다`() {
        val sprints = (1..60).map { completedSprint(it) }
        val repo =
            mockk<SprintRepository>().also {
                every { it.findByProject(projectKey, SprintStatus.COMPLETED) } returns sprints
                every { it.findIssueKeysByProject(projectKey) } returns emptyMap()
            }
        val port =
            mockk<SprintVelocityLookupPort>().also {
                every { it.fetchVelocitySource(emptyMap(), projectKey, actorId) } returns emptyMap()
            }

        val result = makeService(velocityPort = port, sprintRepository = repo).getVelocity(actorId, projectKey, limit = 100)

        assertThat(result.points).hasSize(50)
        // 클램프 후에도 오름차순 유지 — 마지막 50개(11..60)가 선택된다.
        assertThat(result.points.first().sprintId).isEqualTo(sprints[10].id)
        assertThat(result.points.last().sprintId).isEqualTo(sprints[59].id)
    }

    @Test
    fun `포트가 일부 스프린트를 반환하지 않으면 커밋 완료 시간을 0으로 기본 처리한다`() {
        val sprint1 = completedSprint(1)
        val sprint2 = completedSprint(2)
        val sprints = listOf(sprint1, sprint2)

        val repo =
            mockk<SprintRepository>().also {
                every { it.findByProject(projectKey, SprintStatus.COMPLETED) } returns sprints
                every { it.findIssueKeysByProject(projectKey) } returns
                    mapOf(
                        sprint1.id to listOf("ATLAS-1", "ATLAS-2"),
                        sprint2.id to listOf("ATLAS-3"),
                    )
            }
        val expectedIssueKeysBySprint =
            mapOf(
                sprint1.id to setOf("ATLAS-1", "ATLAS-2"),
                sprint2.id to setOf("ATLAS-3"),
            )
        val port =
            mockk<SprintVelocityLookupPort>().also {
                every {
                    it.fetchVelocitySource(expectedIssueKeysBySprint, projectKey, actorId)
                } returns
                    mapOf(
                        // sprint2.id 는 의도적으로 미반환 — E2 기본값(0,0) 검증 대상.
                        sprint1.id to VelocityContribution(commitmentSeconds = 7_200L, completedSeconds = 3_600L),
                    )
            }

        val result = makeService(velocityPort = port, sprintRepository = repo).getVelocity(actorId, projectKey, limit = 10)

        val point1 = result.points.first { it.sprintId == sprint1.id }
        val point2 = result.points.first { it.sprintId == sprint2.id }
        assertThat(point1.commitmentSeconds).isEqualTo(7_200L)
        assertThat(point1.completedSeconds).isEqualTo(3_600L)
        assertThat(point2.commitmentSeconds).isEqualTo(0L)
        assertThat(point2.completedSeconds).isEqualTo(0L)

        verify(exactly = 1) { port.fetchVelocitySource(expectedIssueKeysBySprint, projectKey, actorId) }
    }

    @Test
    fun `결과 평균은 SprintVelocityResult of 로 계산된 floor 나눗셈 값이다`() {
        val sprint1 = completedSprint(1)
        val sprint2 = completedSprint(2)
        val sprint3 = completedSprint(3)
        val sprints = listOf(sprint1, sprint2, sprint3)

        val repo =
            mockk<SprintRepository>().also {
                every { it.findByProject(projectKey, SprintStatus.COMPLETED) } returns sprints
                every { it.findIssueKeysByProject(projectKey) } returns
                    mapOf(
                        sprint1.id to listOf("ATLAS-1"),
                        sprint2.id to listOf("ATLAS-2"),
                        sprint3.id to listOf("ATLAS-3"),
                    )
            }
        val expectedIssueKeysBySprint =
            mapOf(
                sprint1.id to setOf("ATLAS-1"),
                sprint2.id to setOf("ATLAS-2"),
                sprint3.id to setOf("ATLAS-3"),
            )
        val port =
            mockk<SprintVelocityLookupPort>().also {
                every {
                    it.fetchVelocitySource(expectedIssueKeysBySprint, projectKey, actorId)
                } returns
                    mapOf(
                        sprint1.id to VelocityContribution(commitmentSeconds = 100L, completedSeconds = 90L),
                        sprint2.id to VelocityContribution(commitmentSeconds = 150L, completedSeconds = 60L),
                        sprint3.id to VelocityContribution(commitmentSeconds = 151L, completedSeconds = 30L),
                    )
            }

        val result = makeService(velocityPort = port, sprintRepository = repo).getVelocity(actorId, projectKey, limit = 10)

        // (100+150+151)/3 = 133 (floor), (90+60+30)/3 = 60
        assertThat(result.projectKey).isEqualTo(projectKey)
        assertThat(result.averageCommitmentSeconds).isEqualTo(133L)
        assertThat(result.averageCompletedSeconds).isEqualTo(60L)
    }
}
