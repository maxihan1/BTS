// BacklogApplicationService 단위 테스트 — 그룹핑·정렬·미할당 계산 RED 명세 (FR-BL-01/02 Task 3)

package com.bts.agileplanning.application

import com.bts.agileplanning.domain.Board
import com.bts.agileplanning.domain.BoardType
import com.bts.agileplanning.domain.Sprint
import com.bts.agileplanning.domain.SprintStatus
import com.bts.agileplanning.repository.BoardRepository
import com.bts.agileplanning.repository.SprintRepository
import com.bts.shared.board.BoardIssueLookupPort
import com.bts.shared.board.BoardIssuePage
import com.bts.shared.board.BoardIssueView
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
import java.time.Instant
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
 *
 * ### 보드 스코프 (FR-BD-04 D6 · PR ③ Task 3)
 * - BS-1: `board=A` 를 주면 A 보드의 스프린트만 돌아온다(B 보드 스프린트는 빠진다).
 * - BS-2(**E12**): 그때 B 보드 스프린트의 이슈는 **A 의 백로그 칸에 보인다**(J20 — 증발 금지).
 * - BS-3: `board` 미지정이면 기본 스크럼 보드로 폴백한다.
 * - BS-4(**E7**): 존재하지 않는 보드 UUID 는 404 다. 기본 보드로 조용히 폴백하지 않는다.
 * - BS-5(**E8**): 다른 프로젝트의 보드 UUID 도 404 다(403 아님 — 존재 probe 차단).
 */
class BacklogApplicationServiceTest {
    private val actorId: UUID = UUID.randomUUID()
    private val projectKey = "PROJ"

    /** 보드 A — 기본(가장 오래된) 스크럼 보드 역할. */
    private val boardAId: UUID = UUID.fromString("aaaaaaaa-1111-1111-1111-111111111111")

    /** 보드 B — 같은 프로젝트의 두 번째 스크럼 보드(ADR D6 다수 보드). */
    private val boardBId: UUID = UUID.fromString("bbbbbbbb-2222-2222-2222-222222222222")

    private val sprintAId: UUID = UUID.fromString("11111111-aaaa-aaaa-aaaa-111111111111")
    private val sprintBId: UUID = UUID.fromString("22222222-bbbb-bbbb-bbbb-222222222222")

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
        boardId: UUID = UUID.randomUUID(),
    ): Sprint =
        Sprint(
            id = id,
            projectKey = projectKey,
            boardId = boardId,
            name = "Sprint",
            goal = null,
            status = status,
            startDate = startDate,
            endDate = null,
            version = 0L,
        )

    private fun board(
        id: UUID,
        project: String = projectKey,
    ): Board =
        Board(
            id = id,
            projectKey = project,
            name = "보드 $id",
            columns = emptyList(),
            createdAt = Instant.EPOCH,
            updatedAt = Instant.EPOCH,
            boardType = BoardType.SCRUM,
        )

    private fun allowAllResolver(): IssuePermissionResolver =
        mockk<IssuePermissionResolver>().also {
            every { it.hasPermission(any(), any(), any()) } returns true
        }

    private fun denyResolver(): IssuePermissionResolver =
        mockk<IssuePermissionResolver>().also {
            every { it.hasPermission(any(), any(), any()) } returns false
        }

    /**
     * 경로 프로젝트([projectKey])에만 BROWSE 를 주는 resolver.
     *
     * E8 을 공허하지 않게 만든다 — 구현이 **보드가 속한 프로젝트**로 권한을 물으면
     * 다른 프로젝트("OTHER")에서 false 가 나와 403 이 되고, 404 단언이 그 자리를 잡는다.
     */
    private fun projectScopedResolver(): IssuePermissionResolver =
        mockk<IssuePermissionResolver>().also { resolver ->
            every { resolver.hasPermission(any(), any(), any()) } answers {
                val scope = thirdArg<IssueScope>()
                scope is IssueScope.Project && scope.key == projectKey
            }
        }

    /**
     * BoardRepository mock — 하나의 사실집합 위에 조회 창구를 함께 스텁한다.
     *
     * 구현이 `findAllByProjectKey` 를 쓰든 `findById` 를 쓰든 같은 답이 나오게 해
     * 테스트가 구현 세부를 지정하지 않도록 한다.
     *
     * @param projectBoards [projectKey] 소속 보드.
     * @param foreignBoards 다른 프로젝트 소속 보드 — **존재하지만** 이 프로젝트 스코프 밖이다(E8).
     * @param defaultScrumBoardId `board` 미지정 시 폴백할 기본 스크럼 보드.
     */
    private fun boardRepo(
        projectBoards: List<Board>,
        foreignBoards: List<Board> = emptyList(),
        defaultScrumBoardId: UUID? = projectBoards.firstOrNull()?.id,
    ): BoardRepository =
        mockk<BoardRepository>().also { repo ->
            val all = projectBoards + foreignBoards
            every { repo.findAllByProjectKey(any()) } answers {
                all.filter { it.projectKey == firstArg<String>() }
            }
            every { repo.findById(any()) } answers { all.find { it.id == firstArg<UUID>() } }
            every { repo.findScrumBoardIdByProject(projectKey) } returns defaultScrumBoardId
        }

    private fun makeService(
        resolver: IssuePermissionResolver = allowAllResolver(),
        repo: SprintRepository = mockk(relaxed = true),
        lookup: BoardIssueLookupPort = mockk(relaxed = true),
        // 기본은 「보드가 없는 프로젝트」다. relaxed mock 을 쓰면 findScrumBoardIdByProject 가
        // null 이 아니라 **mock UUID** 를 돌려줘 어떤 스프린트와도 안 맞고, 보드 축과 무관한
        // 기존 케이스(BL-*)가 조용히 갈린다.
        boards: BoardRepository = boardRepo(projectBoards = emptyList()),
    ): BacklogApplicationService = BacklogApplicationService(resolver, repo, lookup, boards)

    /**
     * 보드 A·B 에 스프린트가 하나씩 붙고 미할당 이슈가 하나 있는 2-보드 픽스처.
     *
     * - PROJ-1 — 어느 스프린트에도 없다.
     * - PROJ-2 — 보드 A 스프린트.
     * - PROJ-3 — 보드 B 스프린트.
     */
    private fun twoBoardService(
        boards: BoardRepository,
        resolver: IssuePermissionResolver = allowAllResolver(),
    ): BacklogApplicationService {
        val sprintA = sprint(id = sprintAId, status = SprintStatus.ACTIVE, boardId = boardAId)
        val sprintB = sprint(id = sprintBId, status = SprintStatus.ACTIVE, boardId = boardBId)
        val repo =
            mockk<SprintRepository>().also {
                every { it.findByProject(projectKey, null) } returns listOf(sprintA, sprintB)
                every { it.findIssueKeysByProject(projectKey) } returns
                    mapOf(sprintAId to listOf("PROJ-2"), sprintBId to listOf("PROJ-3"))
            }
        val lookup =
            mockk<BoardIssueLookupPort>().also {
                every { it.listVisibleIssuesByProject(projectKey, actorId) } returns
                    BoardIssuePage(
                        issues =
                            listOf(
                                issue("PROJ-1", rank = "a"),
                                issue("PROJ-2", rank = "b"),
                                issue("PROJ-3", rank = "c"),
                            ),
                        truncated = false,
                    )
            }
        return makeService(resolver = resolver, repo = repo, lookup = lookup, boards = boards)
    }

    // ── BL-1: BROWSE 권한 거부 → 403 ─────────────────────────────────────────

    @Test
    fun `BROWSE 권한이 없으면 403 ResponseStatusException 을 던진다`() {
        val service = makeService(resolver = denyResolver())
        assertThatThrownBy { service.getBacklog(actorId, projectKey, null) }
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

        val result = makeService(repo = repo, lookup = lookup).getBacklog(actorId, projectKey, null)

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

        val result = makeService(repo = repo, lookup = lookup).getBacklog(actorId, projectKey, null)

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

        val result = makeService(repo = repo, lookup = lookup).getBacklog(actorId, projectKey, null)

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

        val result = makeService(repo = repo, lookup = lookup).getBacklog(actorId, projectKey, null)

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

        val result = makeService(repo = repo, lookup = lookup).getBacklog(actorId, projectKey, null)

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

        val result = makeService(repo = repo, lookup = lookup).getBacklog(actorId, projectKey, null)

        // PROJ-1 은 미할당이므로 backlog 에
        assertThat(result.backlog.map { it.key }).containsExactly("PROJ-1")
        // 스프린트에는 가시 이슈가 없음(비가시 PROJ-SECRET 누출 0 단언)
        assertThat(result.sprints[0].issues).isEmpty()
    }

    // ── FR-UX-14 B2: 카드 밀도 3필드 ────────────────────────────────────────

    @Test
    fun `백로그 응답은 backlog 배열과 sprints 배열 양쪽에 유형 라벨 추정을 담는다`() {
        // 두 배열은 같은 from 을 거치므로 로직이 갈라질 수 없지만, 회귀 감시는 두 곳을 다 봐야
        // 한쪽이 조용히 빠지는 것을 잡는다. 헬퍼 issue() 는 typeKey 를 하드코딩하므로 쓰지 않는다.
        val sprintId = UUID.randomUUID()
        val s = sprint(id = sprintId, status = SprintStatus.ACTIVE)
        val backlogIssue =
            BoardIssueView(
                key = "PROJ-1",
                summary = "백로그 이슈",
                currentStateKey = "open",
                assigneeId = null,
                priority = 1,
                version = 0L,
                typeKey = "bug",
                labels = listOf("urgent"),
                originalEstimateSeconds = 3600,
            )
        val sprintIssue =
            BoardIssueView(
                key = "PROJ-2",
                summary = "스프린트 이슈",
                currentStateKey = "open",
                assigneeId = null,
                priority = 1,
                version = 0L,
                typeKey = "story",
            )

        val repo =
            mockk<SprintRepository>().also {
                every { it.findByProject(projectKey, null) } returns listOf(s)
                every { it.findIssueKeysByProject(projectKey) } returns mapOf(sprintId to listOf("PROJ-2"))
            }
        val lookup =
            mockk<BoardIssueLookupPort>().also {
                every { it.listVisibleIssuesByProject(projectKey, actorId) } returns
                    BoardIssuePage(issues = listOf(backlogIssue, sprintIssue), truncated = false)
            }

        val result = makeService(repo = repo, lookup = lookup).getBacklog(actorId, projectKey, null)

        val fromBacklog = result.backlog.single { it.key == "PROJ-1" }
        assertThat(fromBacklog.typeKey).isEqualTo("bug")
        assertThat(fromBacklog.labels).containsExactly("urgent")
        assertThat(fromBacklog.originalEstimateSeconds).isEqualTo(3600)

        val fromSprint = result.sprints.single().issues.single { it.key == "PROJ-2" }
        assertThat(fromSprint.typeKey).isEqualTo("story")
        assertThat(fromSprint.labels).isEmpty()
        assertThat(fromSprint.originalEstimateSeconds).isNull()
    }

    // ── BS-1: board=A → A 보드 스프린트만 ────────────────────────────────────

    @Test
    fun `board 를 지정하면 그 보드의 스프린트만 반환하고 다른 보드의 스프린트는 빠진다`() {
        val boards = boardRepo(listOf(board(boardAId), board(boardBId)))

        val result = twoBoardService(boards).getBacklog(actorId, projectKey, boardAId)

        assertThat(result.sprints.map { it.sprint.sprintId }).containsExactly(sprintAId)
        assertThat(result.sprints.single().issues.map { it.key }).containsExactly("PROJ-2")
    }

    // ── BS-2(E12): 다른 보드 스프린트의 이슈는 이 보드의 백로그 칸에 보인다 ──

    /**
     * **E12 — 이 task 의 핵심 판단.**
     *
     * 백로그 칸은 「**그 보드의** 스프린트에 없는 가시 이슈」다. 보드 B 스프린트의 PROJ-3 은
     * 보드 A 의 스프린트 칸에는 없지만 **백로그 칸에는 보여야 한다**(J20 — *"in each board, the
     * sprint content shown will be only the issues in the sprint that match the board's filter"*.
     * BTS 보드 필터는 `project_key` 고정이라 모든 보드의 필터가 사실상 같다).
     *
     * 대안(프로젝트 전체 스프린트를 제외)을 쓰면 PROJ-3 이 A 의 스프린트에도 백로그에도 없어
     * **화면에서 증발**한다 — 사용자가 이슈를 잃는다.
     */
    @Test
    fun `다른 보드 스프린트에 할당된 이슈는 이 보드의 백로그 칸에 보인다`() {
        val boards = boardRepo(listOf(board(boardAId), board(boardBId)))

        val result = twoBoardService(boards).getBacklog(actorId, projectKey, boardAId)

        // PROJ-3 은 보드 B 스프린트 소속이지만 보드 A 의 백로그 칸에 있어야 한다(증발 금지).
        assertThat(result.backlog.map { it.key }).containsExactly("PROJ-1", "PROJ-3")
    }

    // ── BS-3: board 미지정 → 기본 스크럼 보드 폴백 ───────────────────────────

    @Test
    fun `board 를 지정하지 않으면 기본 스크럼 보드로 폴백해 그 보드의 스프린트만 반환한다`() {
        // 기본 보드 = created_at ASC 첫 스크럼 보드 = 보드 A (findScrumBoardIdByProject 의 판단).
        val boards =
            boardRepo(
                projectBoards = listOf(board(boardAId), board(boardBId)),
                defaultScrumBoardId = boardAId,
            )

        val result = twoBoardService(boards).getBacklog(actorId, projectKey, null)

        verify(exactly = 1) { boards.findScrumBoardIdByProject(projectKey) }
        assertThat(result.sprints.map { it.sprint.sprintId }).containsExactly(sprintAId)
        assertThat(result.backlog.map { it.key }).containsExactly("PROJ-1", "PROJ-3")
    }

    // ── BS-4(E7): 존재하지 않는 보드 UUID → 404, 폴백하지 않는다 ────────────

    @Test
    fun `존재하지 않는 보드 UUID 를 주면 404 를 던지고 기본 보드로 폴백하지 않는다`() {
        val boards =
            boardRepo(
                projectBoards = listOf(board(boardAId), board(boardBId)),
                defaultScrumBoardId = boardAId,
            )
        val missing = UUID.fromString("cccccccc-3333-3333-3333-333333333333")

        assertThatThrownBy { twoBoardService(boards).getBacklog(actorId, projectKey, missing) }
            .isInstanceOf(ResponseStatusException::class.java)
            .satisfies({ ex ->
                val rse = ex as ResponseStatusException
                assertThat(rse.statusCode.value()).isEqualTo(HttpStatus.NOT_FOUND.value())
            })

        // 조용한 폴백 금지 — 기본 보드를 물어보지도 않아야 한다(잘못된 링크를 정상처럼 보이면
        // 사용자가 다른 보드를 보면서 그 사실을 모른다).
        verify(exactly = 0) { boards.findScrumBoardIdByProject(any()) }
    }

    // ── BS-5(E8): 다른 프로젝트의 보드 UUID → 404 (403 아님) ────────────────

    /**
     * 다른 프로젝트의 보드는 **404** 다. 403 이면 「그 UUID 는 존재한다」가 새어 나간다
     * (memory `permission-assert-before-existence-makes-403-lie`).
     *
     * resolver 는 경로 프로젝트에만 BROWSE 를 준다 — 구현이 보드 소속 프로젝트로 권한을 물으면
     * 403 이 나오므로 이 단언이 그 자리를 잡는다.
     */
    @Test
    fun `다른 프로젝트의 보드 UUID 를 주면 403 이 아니라 404 를 던진다`() {
        val foreignBoardId = UUID.fromString("dddddddd-4444-4444-4444-444444444444")
        val boards =
            boardRepo(
                projectBoards = listOf(board(boardAId)),
                foreignBoards = listOf(board(foreignBoardId, project = "OTHER")),
                defaultScrumBoardId = boardAId,
            )

        assertThatThrownBy {
            twoBoardService(boards, resolver = projectScopedResolver())
                .getBacklog(actorId, projectKey, foreignBoardId)
        }
            .isInstanceOf(ResponseStatusException::class.java)
            .satisfies({ ex ->
                val rse = ex as ResponseStatusException
                assertThat(rse.statusCode.value()).isEqualTo(HttpStatus.NOT_FOUND.value())
                assertThat(rse.statusCode.value()).isNotEqualTo(HttpStatus.FORBIDDEN.value())
            })
    }
}
