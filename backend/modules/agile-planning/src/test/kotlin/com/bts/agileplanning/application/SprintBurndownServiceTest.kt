// SprintBurndownService MockK 단위 테스트 — 권한 판정 + 기간 검증 + 계산기 위임 RED 명세 (FR-RP-01 Task 4)

package com.bts.agileplanning.application

import com.bts.agileplanning.domain.Sprint
import com.bts.agileplanning.domain.SprintStatus
import com.bts.agileplanning.repository.SprintRepository
import com.bts.shared.burndown.BurndownSource
import com.bts.shared.burndown.SprintBurndownLookupPort
import com.bts.shared.burndown.WorklogContribution
import com.bts.shared.permission.IssuePermission
import com.bts.shared.permission.IssuePermissionResolver
import com.bts.shared.permission.IssueScope
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.web.server.ResponseStatusException
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/**
 * SprintBurndownService 단위 테스트.
 *
 * cross-BC 포트(IssuePermissionResolver, SprintBurndownLookupPort)와 SprintRepository 를
 * MockK 로 교체해 독립적으로 검증한다. "오늘" 날짜는 고정 Clock 으로 주입해 결정성을 확보한다
 * (AuthController time-bomb 회귀 학습).
 */
class SprintBurndownServiceTest {
    private val actorId: UUID = UUID.randomUUID()
    private val sprintId: UUID = UUID.randomUUID()
    private val projectKey = "ATLAS"

    /** 2026-07-02 UTC 로 고정된 "오늘" — S1 시나리오의 2일차 조회를 재현한다. */
    private val fixedClock: Clock = Clock.fixed(Instant.parse("2026-07-02T00:00:00Z"), ZoneOffset.UTC)

    private val sprintWithDates =
        Sprint(
            id = sprintId,
            projectKey = projectKey,
            name = "Sprint 1",
            goal = null,
            status = SprintStatus.ACTIVE,
            startDate = LocalDate.of(2026, 7, 1),
            endDate = LocalDate.of(2026, 7, 14),
            version = 0L,
        )

    /** 기본 allow-all resolver — 원하는 테스트에서만 false 세팅. */
    private fun allowAllResolver(): IssuePermissionResolver =
        mockk<IssuePermissionResolver>().also {
            every { it.hasPermission(any(), any(), any()) } returns true
        }

    private fun makeService(
        sprintRepository: SprintRepository = mockk(),
        permissionResolver: IssuePermissionResolver = allowAllResolver(),
        burndownPort: SprintBurndownLookupPort = mockk(),
        clock: Clock = fixedClock,
    ): SprintBurndownService = SprintBurndownService(sprintRepository, permissionResolver, burndownPort, clock)

    @Test
    fun `BROWSE 권한이 없으면 403을 던진다`() {
        val repo = mockk<SprintRepository>()
        every { repo.findById(sprintId) } returns sprintWithDates
        val resolver =
            mockk<IssuePermissionResolver>().also {
                every {
                    it.hasPermission(actorId, IssuePermission.BROWSE, IssueScope.Project(projectKey))
                } returns false
            }

        assertThatThrownBy {
            makeService(sprintRepository = repo, permissionResolver = resolver)
                .getBurndown(actorId, sprintId)
        }.isInstanceOf(ResponseStatusException::class.java)
            .extracting("statusCode.value")
            .isEqualTo(403)
    }

    @Test
    fun `스프린트가 존재하지 않으면 404를 던진다`() {
        val repo = mockk<SprintRepository>()
        every { repo.findById(sprintId) } returns null

        assertThatThrownBy {
            makeService(sprintRepository = repo).getBurndown(actorId, sprintId)
        }.isInstanceOf(SprintNotFoundException::class.java)
    }

    @Test
    fun `start 또는 end 가 null 이면 422 SprintDatesRequired를 던진다`() {
        val repo = mockk<SprintRepository>()
        every { repo.findById(sprintId) } returns sprintWithDates.copy(endDate = null)

        assertThatThrownBy {
            makeService(sprintRepository = repo).getBurndown(actorId, sprintId)
        }.isInstanceOf(SprintDatesRequiredException::class.java)
    }

    @Test
    fun `해피 패스 - 포트 데이터를 계산기에 위임해 시계열을 반환한다`() {
        val repo = mockk<SprintRepository>()
        every { repo.findById(sprintId) } returns sprintWithDates
        every { repo.findIssueKeys(sprintId) } returns listOf("ATLAS-1", "ATLAS-2")

        val port =
            mockk<SprintBurndownLookupPort>().also {
                every { it.fetchBurndownSource(setOf("ATLAS-1", "ATLAS-2")) } returns
                    BurndownSource(
                        totalOriginalEstimateSeconds = 57_600,
                        worklogEntries = listOf(WorklogContribution(LocalDate.of(2026, 7, 1), 21_600)),
                    )
            }

        val result =
            makeService(sprintRepository = repo, burndownPort = port).getBurndown(actorId, sprintId)

        assertThat(result.sprintId).isEqualTo(sprintId)
        assertThat(result.projectKey).isEqualTo(projectKey)
        assertThat(result.status).isEqualTo(SprintStatus.ACTIVE)
        assertThat(result.startDate).isEqualTo(LocalDate.of(2026, 7, 1))
        assertThat(result.endDate).isEqualTo(LocalDate.of(2026, 7, 14))
        assertThat(result.totalScopeSeconds).isEqualTo(57_600)
        assertThat(result.points).isNotEmpty

        val firstPoint = result.points.first()
        assertThat(firstPoint.date).isEqualTo(LocalDate.of(2026, 7, 1))
        assertThat(firstPoint.idealSeconds).isEqualTo(57_600)
        assertThat(firstPoint.remainingSeconds).isEqualTo(36_000)
        assertThat(firstPoint.completedSeconds).isEqualTo(21_600)
    }
}
