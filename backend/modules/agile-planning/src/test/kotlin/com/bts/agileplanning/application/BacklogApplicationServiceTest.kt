// BacklogApplicationService 단위 테스트 — 그룹핑·정렬·미할당 계산 RED 명세 (FR-BL-01/02 Task 3)

package com.bts.agileplanning.application

import com.bts.agileplanning.domain.Sprint
import com.bts.agileplanning.domain.SprintStatus
import com.bts.agileplanning.repository.SprintRepository
import com.bts.shared.board.BoardIssueLookupPort
import com.bts.shared.board.BoardIssuePage
import com.bts.shared.board.BoardIssueView
import com.bts.shared.permission.IssuePermissionResolver
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import java.time.LocalDate
import java.util.UUID

/**
 * BacklogApplicationService 단위 테스트.
 *
 * cross-BC 포트(IssuePermissionResolver, BoardIssueLookupPort)와 SprintRepository 를
 * MockK 로 교체해 그룹핑·정렬·미할당 계산 로직을 독립 검증한다.
 *
 * ### 검증 케이스
 * - BL-1: BROWSE 권한 없으면 403 을 던진다.
 * - BL-2: 미할당 이슈가 backlog 에, 스프린트 할당 이슈가 해당 스프린트에 그룹핑된다.
 * - BL-3: 백로그 이슈는 rank ASC NULLS LAST → key ASC 보조 정렬이다.
 * - BL-4: 스프린트는 status(ACTIVE→PLANNED→COMPLETED) 그다음 startDate ASC NULLS LAST 정렬이다.
 * - BL-5: truncated 플래그가 그대로 전파된다.
 * - BL-6: 이슈가 없으면 backlog=[], sprints=[] 를 반환한다(vacuous 금지 — 빈 결과 단언).
 */
class BacklogApplicationServiceTest {
    private val actorId: UUID = UUID.randomUUID()
    private val projectKey = "PROJ"

    private fun issue(
        key: String,
        rank: String? = null,
        priority: Int = 3,
    ): BoardIssueView =
        BoardIssueView(
            key = key,
            summary = "요약 $key",
            currentStateKey = "open",
            assigneeId = null,
            priority = priority,
            version = 0L,
            typeKey = "task",
            rank = rank,
        )

    private fun sprint(
        id: UUID = UUID.randomUUID(),
        status: SprintStatus = SprintStatus.PLANNED,
        startDate: LocalDate? = null,
    ): Sprint =
        Sprint(
            id = id,
            projectKey = projectKey,
            name = "Sprint",
            goal = null,
            status = status,
            startDate = startDate,
            endDate = null,
            version = 0L,
        )

    private fun allowAllResolver(): IssuePermissionResolver =
        mockk<IssuePermissionResolver>().also {
            every { it.hasPermission(any(), any(), any()) } returns true
        }

    private fun denyResolver(): IssuePermissionResolver =
        mockk<IssuePermissionResolver>().also {
            every { it.hasPermission(any(), any(), any()) } returns false
        }

    private fun makeService(
        resolver: IssuePermissionResolver = allowAllResolver(),
        repo: SprintRepository = mockk(relaxed = true),
        lookup: BoardIssueLookupPort = mockk(relaxed = true),
    ): BacklogApplicationService = BacklogApplicationService(resolver, repo, lookup)

    // ── BL-1: BROWSE 권한 거부 → 403 ─────────────────────────────────────────

    @Test
    fun `BROWSE 권한이 없으면 403 ResponseStatusException 을 던진다`() {
        val service = makeService(resolver = denyResolver())
        assertThatThrownBy { service.getBacklog(actorId, projectKey) }
            .isInstanceOf(ResponseStatusException::class.java)
            .satisfies({ ex ->
                val rse = ex as ResponseStatusException
                assertThat(rse.statusCode.value()).isEqualTo(HttpStatus.FORBIDDEN.value())
            })
    }

    // ── BL-2: 그룹핑 정확성 ──────────────────────────────────────────────────

    @Test
    fun `미할당 이슈는 backlog 에, 스프린트 할당 이슈는 해당 스프린트에 정확히 그룹핑된다`() {
        val sprintId = UUID.randomUUID()
        val s = sprint(id = sprintId, status = SprintStatus.ACTIVE)
        val i1 = issue("PROJ-1", rank = "m") // 미할당 → backlog
        val i2 = issue("PROJ-2", rank = "n") // 스프린트 할당

        val repo =
            mockk<SprintRepository>().also {
                every { it.findByProject(projectKey, null) } returns listOf(s)
                every { it.findIssueKeysByProject(projectKey) } returns mapOf(sprintId to listOf("PROJ-2"))
            }
        val lookup =
            mockk<BoardIssueLookupPort>().also {
                every { it.listVisibleIssuesByProject(projectKey, actorId) } returns
                    BoardIssuePage(issues = listOf(i1, i2), truncated = false)
            }

        val result = makeService(repo = repo, lookup = lookup).getBacklog(actorId, projectKey)

        assertThat(result.backlog.map { it.key }).containsExactly("PROJ-1")
        assertThat(result.sprints).hasSize(1)
        assertThat(result.sprints[0].issues.map { it.key }).containsExactly("PROJ-2")
    }

    // ── BL-3: 백로그 이슈 rank ASC NULLS LAST → key ASC 보조 정렬 ──────────

    @Test
    fun `백로그 이슈는 rank ASC NULLS LAST 그다음 key ASC 로 정렬된다`() {
        val repo =
            mockk<SprintRepository>().also {
                every { it.findByProject(projectKey, null) } returns emptyList()
                every { it.findIssueKeysByProject(projectKey) } returns emptyMap()
            }
        // rank: "a" < "z" < null
        val issues =
            listOf(
                issue("PROJ-3", rank = null),
                issue("PROJ-1", rank = "a"),
                issue("PROJ-2", rank = "z"),
            )
        val lookup =
            mockk<BoardIssueLookupPort>().also {
                every { it.listVisibleIssuesByProject(projectKey, actorId) } returns
                    BoardIssuePage(issues = issues, truncated = false)
            }

        val result = makeService(repo = repo, lookup = lookup).getBacklog(actorId, projectKey)

        assertThat(result.backlog.map { it.key }).containsExactly("PROJ-1", "PROJ-2", "PROJ-3")
    }

    // ── BL-4: 스프린트 순서 ACTIVE → PLANNED → COMPLETED 그다음 startDate ──

    @Test
    fun `스프린트는 status ACTIVE then PLANNED then COMPLETED 그다음 startDate ASC NULLS LAST 로 정렬된다`() {
        val id1 = UUID.randomUUID()
        val id2 = UUID.randomUUID()
        val id3 = UUID.randomUUID()
        val id4 = UUID.randomUUID()
        val completedSprint = sprint(id = id1, status = SprintStatus.COMPLETED, startDate = LocalDate.of(2026, 1, 1))
        val plannedSprint = sprint(id = id2, status = SprintStatus.PLANNED, startDate = LocalDate.of(2026, 3, 1))
        val activeSprint = sprint(id = id3, status = SprintStatus.ACTIVE, startDate = LocalDate.of(2026, 2, 1))
        val plannedNoDate = sprint(id = id4, status = SprintStatus.PLANNED, startDate = null)

        val repo =
            mockk<SprintRepository>().also {
                every { it.findByProject(projectKey, null) } returns
                    listOf(completedSprint, plannedSprint, activeSprint, plannedNoDate)
                every { it.findIssueKeysByProject(projectKey) } returns emptyMap()
            }
        val lookup =
            mockk<BoardIssueLookupPort>().also {
                every { it.listVisibleIssuesByProject(projectKey, actorId) } returns
                    BoardIssuePage(issues = emptyList(), truncated = false)
            }

        val result = makeService(repo = repo, lookup = lookup).getBacklog(actorId, projectKey)

        val sprintIds = result.sprints.map { it.sprint.sprintId }
        assertThat(sprintIds).containsExactly(id3, id2, id4, id1)
    }

    // ── BL-5: truncated 전파 ─────────────────────────────────────────────────

    @Test
    fun `BoardIssuePage 의 truncated 플래그가 결과에 그대로 전파된다`() {
        val repo =
            mockk<SprintRepository>().also {
                every { it.findByProject(projectKey, null) } returns emptyList()
                every { it.findIssueKeysByProject(projectKey) } returns emptyMap()
            }
        val lookup =
            mockk<BoardIssueLookupPort>().also {
                every { it.listVisibleIssuesByProject(projectKey, actorId) } returns
                    BoardIssuePage(issues = listOf(issue("PROJ-1")), truncated = true)
            }

        val result = makeService(repo = repo, lookup = lookup).getBacklog(actorId, projectKey)

        assertThat(result.truncated).isTrue()
    }

    // ── BL-6: 빈 결과 단언(vacuous 방지) ────────────────────────────────────

    @Test
    fun `이슈와 스프린트가 없으면 backlog 빈 목록 sprints 빈 목록 truncated false 를 반환한다`() {
        val repo =
            mockk<SprintRepository>().also {
                every { it.findByProject(projectKey, null) } returns emptyList()
                every { it.findIssueKeysByProject(projectKey) } returns emptyMap()
            }
        val lookup =
            mockk<BoardIssueLookupPort>().also {
                every { it.listVisibleIssuesByProject(projectKey, actorId) } returns
                    BoardIssuePage(issues = emptyList(), truncated = false)
            }

        val result = makeService(repo = repo, lookup = lookup).getBacklog(actorId, projectKey)

        assertThat(result.backlog).isEmpty()
        assertThat(result.sprints).isEmpty()
        assertThat(result.truncated).isFalse()
    }

    // ── BL-7: 가시성 음성 — viewer 가 볼 수 없는 이슈는 결과에 없다 ─────────

    @Test
    fun `listVisibleIssuesByProject 결과에 없는 이슈는 backlog 와 스프린트 어디에도 나타나지 않는다`() {
        val sprintId = UUID.randomUUID()
        val s = sprint(id = sprintId, status = SprintStatus.ACTIVE)
        // 보안등급 이슈("PROJ-SECRET")는 lookup 결과에 포함되지 않음
        val visibleIssue = issue("PROJ-1")

        val repo =
            mockk<SprintRepository>().also {
                every { it.findByProject(projectKey, null) } returns listOf(s)
                // sprint_issues 에는 비가시 이슈가 있다
                every { it.findIssueKeysByProject(projectKey) } returns
                    mapOf(sprintId to listOf("PROJ-SECRET"))
            }
        val lookup =
            mockk<BoardIssueLookupPort>().also {
                every { it.listVisibleIssuesByProject(projectKey, actorId) } returns
                    BoardIssuePage(issues = listOf(visibleIssue), truncated = false)
            }

        val result = makeService(repo = repo, lookup = lookup).getBacklog(actorId, projectKey)

        // PROJ-1 은 미할당이므로 backlog 에
        assertThat(result.backlog.map { it.key }).containsExactly("PROJ-1")
        // 스프린트에는 가시 이슈가 없음(비가시 PROJ-SECRET 누출 0 단언)
        assertThat(result.sprints[0].issues).isEmpty()
    }
}
