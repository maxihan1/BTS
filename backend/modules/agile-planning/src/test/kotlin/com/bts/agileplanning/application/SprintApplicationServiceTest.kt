// SprintApplicationService MockK 단위 테스트 — 권한 판정 + 도메인 전이 + 이슈 할당/해제 RED 명세 (FR-BL-02 Task 4)

package com.bts.agileplanning.application

import com.bts.agileplanning.domain.InvalidSprintTransitionException
import com.bts.agileplanning.domain.Sprint
import com.bts.agileplanning.domain.SprintStatus
import com.bts.agileplanning.repository.SprintRepository
import com.bts.shared.board.BoardIssueLookupPort
import com.bts.shared.permission.IssuePermission
import com.bts.shared.permission.IssuePermissionResolver
import com.bts.shared.permission.IssueScope
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import java.time.LocalDate
import java.util.UUID

/**
 * SprintApplicationService 단위 테스트.
 *
 * cross-BC 포트(IssuePermissionResolver, BoardIssueLookupPort)와 SprintRepository 를
 * MockK 로 교체해 독립적으로 동작을 검증한다.
 * 권한 판정 순서(actor -> sprint 조회 -> 권한 -> 동작)와 fail-closed 를 집중 검증한다.
 */
class SprintApplicationServiceTest {
    private val actorId: UUID = UUID.randomUUID()
    private val sprintId: UUID = UUID.randomUUID()
    private val projectKey = "ATLAS"

    private val plannedSprint =
        Sprint(
            id = sprintId,
            projectKey = projectKey,
            name = "Sprint 1",
            goal = null,
            status = SprintStatus.PLANNED,
            startDate = LocalDate.of(2026, 7, 1),
            endDate = LocalDate.of(2026, 7, 14),
            version = 0L,
        )

    private val activeSprint = plannedSprint.copy(status = SprintStatus.ACTIVE, version = 1L)
    private val completedSprint = plannedSprint.copy(status = SprintStatus.COMPLETED, version = 2L)

    /** 기본 allow-all resolver — 원하는 곳에서만 false 세팅 */
    private fun allowAllResolver(): IssuePermissionResolver =
        mockk<IssuePermissionResolver>().also {
            every { it.hasPermission(any(), any(), any()) } returns true
        }

    /** 지정 권한에 대해 false 반환, 나머지 true */
    private fun denyResolver(
        actor: UUID = actorId,
        permission: IssuePermission,
        scope: IssueScope,
    ): IssuePermissionResolver =
        mockk<IssuePermissionResolver>().also {
            every { it.hasPermission(any(), any(), any()) } returns true
            every { it.hasPermission(actor, permission, scope) } returns false
        }

    private fun makeService(
        resolver: IssuePermissionResolver = allowAllResolver(),
        repo: SprintRepository = mockk(relaxed = true),
        lookupPort: BoardIssueLookupPort = mockk(relaxed = true),
    ): SprintApplicationService = SprintApplicationService(resolver, repo, lookupPort)

    // ── create ────────────────────────────────────────────────────────────────

    @Test
    fun `create CREATE 권한이 있으면 repo에 sprint를 삽입하고 반환한다`() {
        val repo = mockk<SprintRepository>()
        every { repo.insert(any()) } answers { firstArg() }

        val result =
            makeService(repo = repo).create(
                actorId = actorId,
                projectKey = projectKey,
                name = "Sprint 1",
                goal = null,
                startDate = null,
                endDate = null,
            )

        assertThat(result.projectKey).isEqualTo(projectKey)
        assertThat(result.name).isEqualTo("Sprint 1")
        assertThat(result.status).isEqualTo(SprintStatus.PLANNED)
        verify(exactly = 1) { repo.insert(any()) }
    }

    @Test
    fun `create CREATE 권한 없으면 403을 던지고 repo를 호출하지 않는다`() {
        val repo = mockk<SprintRepository>()
        val resolver = denyResolver(actorId, IssuePermission.CREATE, IssueScope.Project(projectKey))

        assertThatThrownBy {
            makeService(resolver = resolver, repo = repo).create(
                actorId = actorId,
                projectKey = projectKey,
                name = "Sprint X",
                goal = null,
                startDate = null,
                endDate = null,
            )
        }.isInstanceOf(ResponseStatusException::class.java)
            .extracting("statusCode.value")
            .isEqualTo(403)

        verify(exactly = 0) { repo.insert(any()) }
    }

    // ── update ────────────────────────────────────────────────────────────────

    @Test
    fun `update sprint 조회로 projectKey를 확보한 뒤 CREATE 권한을 검증한다`() {
        val repo =
            mockk<SprintRepository>().also {
                every { it.findById(sprintId) } returns plannedSprint
                every { it.updateMeta(any(), any(), any(), any(), any(), any()) } returns
                    plannedSprint.copy(name = "새 이름")
            }

        val result =
            makeService(repo = repo).update(
                actorId = actorId,
                sprintId = sprintId,
                name = "새 이름",
                goal = null,
                startDate = null,
                endDate = null,
                version = 0L,
            )

        assertThat(result.name).isEqualTo("새 이름")
    }

    @Test
    fun `update sprint 미존재면 404를 던진다`() {
        val repo =
            mockk<SprintRepository>().also {
                every { it.findById(sprintId) } returns null
            }

        assertThatThrownBy {
            makeService(repo = repo).update(
                actorId = actorId,
                sprintId = sprintId,
                name = "이름",
                goal = null,
                startDate = null,
                endDate = null,
                version = 0L,
            )
        }.isInstanceOf(SprintNotFoundException::class.java)
    }

    @Test
    fun `update CREATE 권한 없으면 403을 던진다`() {
        val repo =
            mockk<SprintRepository>().also {
                every { it.findById(sprintId) } returns plannedSprint
            }
        val resolver = denyResolver(actorId, IssuePermission.CREATE, IssueScope.Project(projectKey))

        assertThatThrownBy {
            makeService(resolver = resolver, repo = repo).update(
                actorId = actorId,
                sprintId = sprintId,
                name = "이름",
                goal = null,
                startDate = null,
                endDate = null,
                version = 0L,
            )
        }.isInstanceOf(ResponseStatusException::class.java)
            .extracting("statusCode.value")
            .isEqualTo(403)
    }

    @Test
    fun `update repo가 null 반환하면 OCC 충돌로 404를 던진다`() {
        val repo =
            mockk<SprintRepository>().also {
                every { it.findById(sprintId) } returns plannedSprint
                every { it.updateMeta(any(), any(), any(), any(), any(), any()) } returns null
            }

        assertThatThrownBy {
            makeService(repo = repo).update(
                actorId = actorId,
                sprintId = sprintId,
                name = "이름",
                goal = null,
                startDate = null,
                endDate = null,
                version = 99L,
            )
        }.isInstanceOf(SprintNotFoundException::class.java)
    }

    // ── softDelete ────────────────────────────────────────────────────────────

    @Test
    fun `softDelete sprint 조회 후 CREATE 권한 확인하고 repo softDelete 호출한다`() {
        val repo =
            mockk<SprintRepository>().also {
                every { it.findById(sprintId) } returns plannedSprint
                every { it.softDelete(sprintId) } returns Unit
            }

        makeService(repo = repo).softDelete(actorId, sprintId)

        verify(exactly = 1) { repo.softDelete(sprintId) }
    }

    @Test
    fun `softDelete sprint 미존재면 404를 던진다`() {
        val repo =
            mockk<SprintRepository>().also {
                every { it.findById(sprintId) } returns null
            }

        assertThatThrownBy {
            makeService(repo = repo).softDelete(actorId, sprintId)
        }.isInstanceOf(SprintNotFoundException::class.java)
    }

    @Test
    fun `softDelete CREATE 권한 없으면 403을 던진다`() {
        val repo =
            mockk<SprintRepository>().also {
                every { it.findById(sprintId) } returns plannedSprint
            }
        val resolver = denyResolver(actorId, IssuePermission.CREATE, IssueScope.Project(projectKey))

        assertThatThrownBy {
            makeService(resolver = resolver, repo = repo).softDelete(actorId, sprintId)
        }.isInstanceOf(ResponseStatusException::class.java)
            .extracting("statusCode.value")
            .isEqualTo(403)
    }

    // ── list ──────────────────────────────────────────────────────────────────

    @Test
    fun `list BROWSE 권한이 있으면 프로젝트의 스프린트 목록을 반환한다`() {
        val repo =
            mockk<SprintRepository>().also {
                every { it.findByProject(projectKey, null) } returns listOf(plannedSprint, activeSprint)
            }

        val result = makeService(repo = repo).list(actorId, projectKey, statusFilter = null)

        assertThat(result).hasSize(2)
    }

    @Test
    fun `list BROWSE 권한 없으면 403을 던진다`() {
        val resolver = denyResolver(actorId, IssuePermission.BROWSE, IssueScope.Project(projectKey))

        assertThatThrownBy {
            makeService(resolver = resolver).list(actorId, projectKey, statusFilter = null)
        }.isInstanceOf(ResponseStatusException::class.java)
            .extracting("statusCode.value")
            .isEqualTo(403)
    }

    @Test
    fun `list statusFilter가 있으면 해당 상태만 반환한다`() {
        val repo =
            mockk<SprintRepository>().also {
                every { it.findByProject(projectKey, SprintStatus.ACTIVE) } returns listOf(activeSprint)
            }

        val result = makeService(repo = repo).list(actorId, projectKey, SprintStatus.ACTIVE)

        assertThat(result).hasSize(1)
        assertThat(result.first().status).isEqualTo(SprintStatus.ACTIVE)
    }

    // ── get ───────────────────────────────────────────────────────────────────

    @Test
    fun `get BROWSE 권한이 있으면 sprint 단건을 반환한다`() {
        val repo =
            mockk<SprintRepository>().also {
                every { it.findById(sprintId) } returns plannedSprint
            }

        val result = makeService(repo = repo).get(actorId, sprintId)

        assertThat(result.id).isEqualTo(sprintId)
    }

    @Test
    fun `get sprint 미존재면 404를 던진다`() {
        val repo =
            mockk<SprintRepository>().also {
                every { it.findById(sprintId) } returns null
            }

        assertThatThrownBy {
            makeService(repo = repo).get(actorId, sprintId)
        }.isInstanceOf(SprintNotFoundException::class.java)
    }

    @Test
    fun `get BROWSE 권한 없으면 403을 던진다`() {
        val repo =
            mockk<SprintRepository>().also {
                every { it.findById(sprintId) } returns plannedSprint
            }
        val resolver = denyResolver(actorId, IssuePermission.BROWSE, IssueScope.Project(projectKey))

        assertThatThrownBy {
            makeService(resolver = resolver, repo = repo).get(actorId, sprintId)
        }.isInstanceOf(ResponseStatusException::class.java)
            .extracting("statusCode.value")
            .isEqualTo(403)
    }

    // ── start ─────────────────────────────────────────────────────────────────

    @Test
    fun `start PLANNED 스프린트는 ACTIVE로 전이되고 영속된다`() {
        val repo =
            mockk<SprintRepository>().also {
                every { it.findById(sprintId) } returns plannedSprint
                every { it.updateStatus(sprintId, SprintStatus.ACTIVE, 0L) } returns activeSprint
            }

        val result = makeService(repo = repo).start(actorId, sprintId)

        assertThat(result.status).isEqualTo(SprintStatus.ACTIVE)
        verify(exactly = 1) { repo.updateStatus(sprintId, SprintStatus.ACTIVE, 0L) }
    }

    @Test
    fun `start sprint 미존재면 404를 던진다`() {
        val repo =
            mockk<SprintRepository>().also {
                every { it.findById(sprintId) } returns null
            }

        assertThatThrownBy {
            makeService(repo = repo).start(actorId, sprintId)
        }.isInstanceOf(SprintNotFoundException::class.java)
    }

    @Test
    fun `start CREATE 권한 없으면 403을 던진다`() {
        val repo =
            mockk<SprintRepository>().also {
                every { it.findById(sprintId) } returns plannedSprint
            }
        val resolver = denyResolver(actorId, IssuePermission.CREATE, IssueScope.Project(projectKey))

        assertThatThrownBy {
            makeService(resolver = resolver, repo = repo).start(actorId, sprintId)
        }.isInstanceOf(ResponseStatusException::class.java)
            .extracting("statusCode.value")
            .isEqualTo(403)
    }

    @Test
    fun `start ACTIVE 스프린트 start 시도는 InvalidSprintTransitionException을 던진다`() {
        val repo =
            mockk<SprintRepository>().also {
                every { it.findById(sprintId) } returns activeSprint
            }

        assertThatThrownBy {
            makeService(repo = repo).start(actorId, sprintId)
        }.isInstanceOf(InvalidSprintTransitionException::class.java)
    }

    @Test
    fun `start COMPLETED 스프린트 start 시도는 InvalidSprintTransitionException을 던진다`() {
        val repo =
            mockk<SprintRepository>().also {
                every { it.findById(sprintId) } returns completedSprint
            }

        assertThatThrownBy {
            makeService(repo = repo).start(actorId, sprintId)
        }.isInstanceOf(InvalidSprintTransitionException::class.java)
    }

    // ── complete ──────────────────────────────────────────────────────────────

    @Test
    fun `complete ACTIVE 스프린트는 COMPLETED로 전이되고 영속된다`() {
        val repo =
            mockk<SprintRepository>().also {
                every { it.findById(sprintId) } returns activeSprint
                every { it.updateStatus(sprintId, SprintStatus.COMPLETED, 1L) } returns completedSprint
            }

        val result = makeService(repo = repo).complete(actorId, sprintId)

        assertThat(result.status).isEqualTo(SprintStatus.COMPLETED)
        verify(exactly = 1) { repo.updateStatus(sprintId, SprintStatus.COMPLETED, 1L) }
    }

    @Test
    fun `complete sprint 미존재면 404를 던진다`() {
        val repo =
            mockk<SprintRepository>().also {
                every { it.findById(sprintId) } returns null
            }

        assertThatThrownBy {
            makeService(repo = repo).complete(actorId, sprintId)
        }.isInstanceOf(SprintNotFoundException::class.java)
    }

    @Test
    fun `complete CREATE 권한 없으면 403을 던진다`() {
        val repo =
            mockk<SprintRepository>().also {
                every { it.findById(sprintId) } returns activeSprint
            }
        val resolver = denyResolver(actorId, IssuePermission.CREATE, IssueScope.Project(projectKey))

        assertThatThrownBy {
            makeService(resolver = resolver, repo = repo).complete(actorId, sprintId)
        }.isInstanceOf(ResponseStatusException::class.java)
            .extracting("statusCode.value")
            .isEqualTo(403)
    }

    @Test
    fun `complete PLANNED 스프린트 complete 시도는 InvalidSprintTransitionException을 던진다`() {
        val repo =
            mockk<SprintRepository>().also {
                every { it.findById(sprintId) } returns plannedSprint
            }

        assertThatThrownBy {
            makeService(repo = repo).complete(actorId, sprintId)
        }.isInstanceOf(InvalidSprintTransitionException::class.java)
    }

    // ── assignIssue ───────────────────────────────────────────────────────────

    @Test
    fun `assignIssue UPDATE 권한이 있고 이슈가 가시적이면 할당에 성공한다`() {
        val issueKey = "ATLAS-1"
        val repo =
            mockk<SprintRepository>().also {
                every { it.findById(sprintId) } returns plannedSprint
                every { it.assignIssue(sprintId, issueKey) } returns 1
            }
        val lookupPort =
            mockk<BoardIssueLookupPort>().also {
                every { it.isVisibleIssue(projectKey, issueKey, actorId) } returns true
            }

        makeService(repo = repo, lookupPort = lookupPort).assignIssue(actorId, sprintId, issueKey)

        verify(exactly = 1) { repo.assignIssue(sprintId, issueKey) }
    }

    @Test
    fun `assignIssue sprint 미존재면 404를 던진다`() {
        val repo =
            mockk<SprintRepository>().also {
                every { it.findById(sprintId) } returns null
            }

        assertThatThrownBy {
            makeService(repo = repo).assignIssue(actorId, sprintId, "ATLAS-1")
        }.isInstanceOf(SprintNotFoundException::class.java)
    }

    @Test
    fun `assignIssue UPDATE 권한 없으면 403을 던진다`() {
        val repo =
            mockk<SprintRepository>().also {
                every { it.findById(sprintId) } returns plannedSprint
            }
        val resolver = denyResolver(actorId, IssuePermission.UPDATE, IssueScope.Project(projectKey))

        assertThatThrownBy {
            makeService(resolver = resolver, repo = repo).assignIssue(actorId, sprintId, "ATLAS-1")
        }.isInstanceOf(ResponseStatusException::class.java)
            .extracting("statusCode.value")
            .isEqualTo(403)
    }

    @Test
    fun `assignIssue 이슈가 가시적이지 않으면 404를 던진다 (probe 차단)`() {
        val issueKey = "ATLAS-99"
        val repo =
            mockk<SprintRepository>().also {
                every { it.findById(sprintId) } returns plannedSprint
            }
        val lookupPort =
            mockk<BoardIssueLookupPort>().also {
                every { it.isVisibleIssue(projectKey, issueKey, actorId) } returns false
            }

        assertThatThrownBy {
            makeService(repo = repo, lookupPort = lookupPort).assignIssue(actorId, sprintId, issueKey)
        }.isInstanceOf(ResponseStatusException::class.java)
            .extracting("statusCode.value")
            .isEqualTo(404)
    }

    @Test
    fun `assignIssue isVisibleIssue가 false(포트 미등록 기본값)이면 할당을 거부한다 (fail-closed)`() {
        val issueKey = "ATLAS-1"
        val repo =
            mockk<SprintRepository>().also {
                every { it.findById(sprintId) } returns plannedSprint
            }
        // default impl returns false (fail-closed)
        val lookupPort = object : BoardIssueLookupPort {}

        assertThatThrownBy {
            makeService(repo = repo, lookupPort = lookupPort).assignIssue(actorId, sprintId, issueKey)
        }.isInstanceOf(ResponseStatusException::class.java)
            .extracting("statusCode.value")
            .isEqualTo(404)
    }

    @Test
    fun `assignIssue COMPLETED 스프린트에 할당하면 409를 던진다 (E5)`() {
        val issueKey = "ATLAS-1"
        val repo =
            mockk<SprintRepository>().also {
                every { it.findById(sprintId) } returns completedSprint
                every { it.assignIssue(sprintId, issueKey) } returns 0
            }
        val lookupPort =
            mockk<BoardIssueLookupPort>().also {
                every { it.isVisibleIssue(projectKey, issueKey, actorId) } returns true
            }

        assertThatThrownBy {
            makeService(repo = repo, lookupPort = lookupPort).assignIssue(actorId, sprintId, issueKey)
        }.isInstanceOf(ResponseStatusException::class.java)
            .extracting("statusCode.value")
            .isEqualTo(409)
    }

    // ── unassignIssue ─────────────────────────────────────────────────────────

    @Test
    fun `unassignIssue UPDATE 권한이 있으면 repo unassignIssue를 호출한다`() {
        val issueKey = "ATLAS-1"
        val repo =
            mockk<SprintRepository>().also {
                every { it.findById(sprintId) } returns plannedSprint
                every { it.unassignIssue(sprintId, issueKey) } returns Unit
            }

        makeService(repo = repo).unassignIssue(actorId, sprintId, issueKey)

        verify(exactly = 1) { repo.unassignIssue(sprintId, issueKey) }
    }

    @Test
    fun `unassignIssue sprint 미존재면 404를 던진다`() {
        val repo =
            mockk<SprintRepository>().also {
                every { it.findById(sprintId) } returns null
            }

        assertThatThrownBy {
            makeService(repo = repo).unassignIssue(actorId, sprintId, "ATLAS-1")
        }.isInstanceOf(SprintNotFoundException::class.java)
    }

    @Test
    fun `unassignIssue UPDATE 권한 없으면 403을 던진다`() {
        val repo =
            mockk<SprintRepository>().also {
                every { it.findById(sprintId) } returns plannedSprint
            }
        val resolver = denyResolver(actorId, IssuePermission.UPDATE, IssueScope.Project(projectKey))

        assertThatThrownBy {
            makeService(resolver = resolver, repo = repo).unassignIssue(actorId, sprintId, "ATLAS-1")
        }.isInstanceOf(ResponseStatusException::class.java)
            .extracting("statusCode.value")
            .isEqualTo(403)
    }

    // ── 권한 판정 순서 보장 ────────────────────────────────────────────────────

    @Test
    fun `get 권한 판정은 sprint조회로 얻은 projectKey로 수행한다 (요청 파라미터 신뢰 금지)`() {
        val differentProjectKey = "OTHER"
        val sprintWithDifferentProject = plannedSprint.copy(projectKey = differentProjectKey)
        val repo =
            mockk<SprintRepository>().also {
                every { it.findById(sprintId) } returns sprintWithDifferentProject
            }
        val resolver =
            mockk<IssuePermissionResolver>().also {
                every { it.hasPermission(actorId, IssuePermission.BROWSE, IssueScope.Project(differentProjectKey)) } returns true
                every { it.hasPermission(actorId, IssuePermission.BROWSE, IssueScope.Project(projectKey)) } returns false
            }

        // sprint.projectKey = OTHER 기준으로 권한 판정해야 통과
        val result = makeService(resolver = resolver, repo = repo).get(actorId, sprintId)
        assertThat(result.projectKey).isEqualTo(differentProjectKey)
    }

    @Test
    fun `create만 body projectKey로 권한을 판정한다 (리소스 부재 상황)`() {
        val repo = mockk<SprintRepository>()
        every { repo.insert(any()) } answers { firstArg() }
        val resolver = allowAllResolver()

        makeService(resolver = resolver, repo = repo).create(
            actorId = actorId,
            projectKey = projectKey,
            name = "Sprint 1",
            goal = null,
            startDate = null,
            endDate = null,
        )

        // create는 사전에 sprint가 없어 body projectKey 로 권한 판정
        verify { resolver.hasPermission(actorId, IssuePermission.CREATE, IssueScope.Project(projectKey)) }
    }

    // ── 응답 statusCode 세부 검증 ─────────────────────────────────────────────

    @Test
    fun `SprintNotFoundException은 HttpStatus 404를 반환한다`() {
        val ex = SprintNotFoundException()
        assertThat((ex as ResponseStatusException).statusCode.value()).isEqualTo(HttpStatus.NOT_FOUND.value())
    }
}
