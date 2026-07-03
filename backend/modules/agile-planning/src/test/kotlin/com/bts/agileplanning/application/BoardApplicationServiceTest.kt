// BoardApplicationService 통합 테스트 (Testcontainers) — 보드 생성/조회/이동 시나리오 RED 명세 (FR-BD-01 Task 8)

package com.bts.agileplanning.application

import com.bts.agileplanning.AgilePlanningTestBootApplication
import com.bts.agileplanning.AgilePlanningTestcontainersConfig
import com.bts.agileplanning.domain.Board
import com.bts.agileplanning.domain.BoardColumn
import com.bts.agileplanning.domain.QuickFilter
import com.bts.agileplanning.domain.SwimlaneField
import com.bts.agileplanning.repository.BoardQuickFilterRepository
import com.bts.agileplanning.repository.BoardRepository
import com.bts.shared.board.BoardCardFilter
import com.bts.shared.board.BoardIssueLookupPort
import com.bts.shared.board.BoardIssuePage
import com.bts.shared.board.BoardIssueView
import com.bts.shared.board.BoardTransitionCommand
import com.bts.shared.board.BoardTransitionResult
import com.bts.shared.board.IssueTransitionPort
import com.bts.shared.workflow.ProjectKey
import com.bts.shared.workflow.WorkflowStateCatalog
import com.bts.shared.workflow.WorkflowStateView
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.util.UUID

/**
 * [BoardApplicationService] 통합 테스트 — Testcontainers PostgreSQL 사용.
 *
 * 각 테스트가 [BoardRepository] 는 Spring 컨텍스트에서 주입받고,
 * cross-BC 포트([WorkflowStateCatalog] / [BoardIssueLookupPort] / [IssueTransitionPort])는
 * MockK 로 교체해 독립적으로 주입한다.
 *
 * 검증 시나리오.
 * - (a) 보드 생성 시 WorkflowStateCatalog 조회 → 컬럼 시드 + boards/board_columns 영속
 * - (b) E1: 스킴 미할당(빈 상태 목록) → 422 UnprocessableEntity
 * - (c) 보드 조회 시 BoardIssueLookupPort 카드 배치
 * - (d) 카드 이동 = toColumnId → state_key 도출 후 IssueTransitionPort 위임
 * - (e) E3: 같은 컬럼으로 이동 → no-op 200
 * - (f) E8: 보드-이슈 프로젝트 정합 위반 → 거부
 * - (g) 보드 조회 시 BoardQuickFilterRepository 결과가 quickFilters 로 포함(FR-UX-01 Task 7)
 */
@SpringBootTest(
    classes = [AgilePlanningTestBootApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
)
@Import(AgilePlanningTestcontainersConfig::class)
class BoardApplicationServiceTest {
    @Autowired
    private lateinit var boardRepository: BoardRepository

    companion object {
        /** 테스트용 상태 목록 — 3개 컬럼(TODO·IN_PROGRESS·DONE). */
        val DEFAULT_STATES =
            listOf(
                WorkflowStateView(key = "open", name = "열림", isDone = false, category = "TODO", displayOrder = 0),
                WorkflowStateView(
                    key = "in-progress",
                    name = "진행 중",
                    isDone = false,
                    category = "IN_PROGRESS",
                    displayOrder = 1,
                ),
                WorkflowStateView(key = "closed", name = "완료", isDone = true, category = "DONE", displayOrder = 2),
            )
    }

    /**
     * 테스트마다 독립 [BoardApplicationService] 인스턴스를 생성해 cross-BC 포트를 MockK 로 교체한다.
     *
     * [BoardRepository] 는 Testcontainers DB 에 실제로 접근하는 Spring Bean 을 공유하여
     * DB 영속 동작을 검증한다.
     */
    private fun serviceWith(
        catalog: WorkflowStateCatalog = mockk(relaxed = true),
        lookup: BoardIssueLookupPort = mockk(relaxed = true),
        transition: IssueTransitionPort = mockk(relaxed = true),
        repo: BoardRepository = boardRepository,
        quickFilterRepo: BoardQuickFilterRepository = mockk(relaxed = true),
    ): BoardApplicationService =
        BoardApplicationService(
            workflowStateCatalog = catalog,
            boardIssueLookupPort = lookup,
            issueTransitionPort = transition,
            boardRepository = repo,
            boardQuickFilterRepository = quickFilterRepo,
        )

    // ── (a) 보드 생성 시 컬럼 시드 + 영속 ────────────────────────────────────────

    @Test
    fun `보드 생성 시 WorkflowStateCatalog 조회 후 컬럼이 3개 시드되고 DB 에 영속된다`() {
        val catalog = mockk<WorkflowStateCatalog>()
        every { catalog.listStates(ProjectKey.of("BTS"), null) } returns DEFAULT_STATES

        val board = serviceWith(catalog = catalog).createBoard(projectKey = "BTS", name = "BTS 보드")

        assertThat(board.columns).hasSize(3)
        assertThat(board.columns.map { it.stateKey })
            .containsExactly("open", "in-progress", "closed")
        assertThat(board.columns.map { it.category })
            .containsExactly("TODO", "IN_PROGRESS", "DONE")

        // DB 에 실제로 영속됐는지 재조회로 확인
        val reloaded = boardRepository.findById(board.id)
        assertThat(reloaded).isNotNull()
        assertThat(reloaded!!.columns).hasSize(3)
    }

    // ── (b) E1: 스킴 미할당 → 422 ───────────────────────────────────────────────

    @Test
    fun `E1 워크플로우 스킴 미할당 프로젝트는 보드 생성 시 422 를 던진다`() {
        val catalog = mockk<WorkflowStateCatalog>()
        every { catalog.listStates(ProjectKey.of("BTS"), null) } returns emptyList()

        assertThatThrownBy { serviceWith(catalog = catalog).createBoard(projectKey = "BTS", name = "빈 보드") }
            .isInstanceOf(ResponseStatusException::class.java)
            .extracting("statusCode.value")
            .isEqualTo(422)
    }

    // ── (c) 보드 조회 시 카드 배치 ───────────────────────────────────────────────

    @Test
    fun `보드 조회 시 BoardIssueLookupPort 결과가 state_key 기준으로 컬럼에 배치된다`() {
        val catalog = mockk<WorkflowStateCatalog>()
        every { catalog.listStates(ProjectKey.of("PROJ"), null) } returns DEFAULT_STATES
        val board = serviceWith(catalog = catalog).createBoard("PROJ", "조회 테스트 보드")

        val viewerId = UUID.randomUUID()
        val issues =
            listOf(
                BoardIssueView(
                    key = "PROJ-1",
                    summary = "첫 이슈",
                    currentStateKey = "open",
                    assigneeId = null,
                    priority = 2,
                    version = 1L,
                ),
                BoardIssueView(
                    key = "PROJ-2",
                    summary = "두 번째 이슈",
                    currentStateKey = "in-progress",
                    assigneeId = null,
                    priority = 1,
                    version = 1L,
                ),
            )

        // CONCERN-1: 3-인자 메서드를 override 해 filter 를 캡처해야 2-인자 default 위임으로 filter 드롭이 안 생긴다.
        val lookup =
            object : BoardIssueLookupPort {
                override fun listVisibleIssuesByProject(
                    projectKey: String,
                    viewerUserId: UUID,
                    filter: BoardCardFilter,
                ): BoardIssuePage = BoardIssuePage(issues = issues, truncated = false)
            }

        val result = serviceWith(lookup = lookup).getBoard(boardId = board.id, viewerUserId = viewerId)

        val openPlaced = result.columns.first { it.column.stateKey == "open" }
        assertThat(openPlaced.cards).hasSize(1)
        assertThat(openPlaced.cards.first().key).isEqualTo("PROJ-1")

        val inProgressPlaced = result.columns.first { it.column.stateKey == "in-progress" }
        assertThat(inProgressPlaced.cards).hasSize(1)
        assertThat(inProgressPlaced.cards.first().key).isEqualTo("PROJ-2")
    }

    @Test
    fun `보드 조회 시 LIMIT 초과면 truncated=true 가 반환된다`() {
        val catalog = mockk<WorkflowStateCatalog>()
        every { catalog.listStates(ProjectKey.of("TRNC"), null) } returns DEFAULT_STATES
        val board = serviceWith(catalog = catalog).createBoard("TRNC", "truncated 테스트 보드")

        val viewerId = UUID.randomUUID()
        val lookup =
            object : BoardIssueLookupPort {
                override fun listVisibleIssuesByProject(
                    projectKey: String,
                    viewerUserId: UUID,
                    filter: BoardCardFilter,
                ): BoardIssuePage = BoardIssuePage(issues = emptyList(), truncated = true)
            }

        val result = serviceWith(lookup = lookup).getBoard(boardId = board.id, viewerUserId = viewerId)

        assertThat(result.truncated).isTrue()
    }

    @Test
    fun `보드 조회 시 미매핑 상태 이슈가 있으면 unplacedCount 가 0보다 크다`() {
        val catalog = mockk<WorkflowStateCatalog>()
        every { catalog.listStates(ProjectKey.of("UNPL"), null) } returns DEFAULT_STATES
        val board = serviceWith(catalog = catalog).createBoard("UNPL", "unplaced 테스트 보드")

        val viewerId = UUID.randomUUID()
        // 미매핑 상태 이슈 포함
        val issues =
            listOf(
                BoardIssueView("UNPL-1", "미매핑 이슈", "ghost-state", null, 1, 1L),
                BoardIssueView("UNPL-2", "정상 이슈", "open", null, 2, 1L),
            )
        val lookup =
            object : BoardIssueLookupPort {
                override fun listVisibleIssuesByProject(
                    projectKey: String,
                    viewerUserId: UUID,
                    filter: BoardCardFilter,
                ): BoardIssuePage = BoardIssuePage(issues = issues, truncated = false)
            }

        val result = serviceWith(lookup = lookup).getBoard(boardId = board.id, viewerUserId = viewerId)

        assertThat(result.unplacedCount).isEqualTo(1)
    }

    // ── (c-filter) CONCERN-1: filter 캡처 — 3-인자 override 필수 ──────────────

    @Test
    fun `getBoard filter 인자가 실제로 BoardIssueLookupPort 3-인자 메서드로 전달된다`() {
        val catalog = mockk<WorkflowStateCatalog>()
        every { catalog.listStates(ProjectKey.of("FILT"), null) } returns DEFAULT_STATES
        val board = serviceWith(catalog = catalog).createBoard("FILT", "filter 캡처 테스트 보드")

        val viewerId = UUID.randomUUID()
        val uuid1 = UUID.randomUUID()
        val expectedFilter =
            BoardCardFilter(
                assigneeIds = listOf(uuid1),
                includeUnassigned = true,
                labels = listOf("bug"),
                componentIds = emptyList(),
            )
        var capturedFilter: BoardCardFilter? = null

        val lookup =
            object : BoardIssueLookupPort {
                override fun listVisibleIssuesByProject(
                    projectKey: String,
                    viewerUserId: UUID,
                    filter: BoardCardFilter,
                ): BoardIssuePage {
                    capturedFilter = filter
                    return BoardIssuePage(issues = emptyList(), truncated = false)
                }
            }

        serviceWith(lookup = lookup).getBoard(boardId = board.id, viewerUserId = viewerId, filter = expectedFilter)

        assertThat(capturedFilter).isEqualTo(expectedFilter)
    }

    @Test
    fun `getBoard 필터 없이 호출하면 BoardCardFilter_EMPTY 가 전달된다`() {
        val catalog = mockk<WorkflowStateCatalog>()
        every { catalog.listStates(ProjectKey.of("NOFLT"), null) } returns DEFAULT_STATES
        val board = serviceWith(catalog = catalog).createBoard("NOFLT", "무필터 보드")

        val viewerId = UUID.randomUUID()
        var capturedFilter: BoardCardFilter? = null

        val lookup =
            object : BoardIssueLookupPort {
                override fun listVisibleIssuesByProject(
                    projectKey: String,
                    viewerUserId: UUID,
                    filter: BoardCardFilter,
                ): BoardIssuePage {
                    capturedFilter = filter
                    return BoardIssuePage(issues = emptyList(), truncated = false)
                }
            }

        // 2-인자 기존 API 호출 → default filter = EMPTY
        serviceWith(lookup = lookup).getBoard(boardId = board.id, viewerUserId = viewerId)

        assertThat(capturedFilter).isEqualTo(BoardCardFilter.EMPTY)
    }

    // ── (d) 카드 이동 = IssueTransitionPort 위임 ─────────────────────────────────

    @Test
    fun `카드 이동 시 대상 컬럼의 state_key 로 IssueTransitionPort 에 위임한다`() {
        val catalog = mockk<WorkflowStateCatalog>()
        every { catalog.listStates(ProjectKey.of("CARD"), null) } returns DEFAULT_STATES
        val board = serviceWith(catalog = catalog).createBoard("CARD", "이동 테스트 보드")

        val inProgressColumn = board.columns.first { it.stateKey == "in-progress" }
        val cmdSlot = slot<BoardTransitionCommand>()
        val transition = mockk<IssueTransitionPort>()
        every { transition.transition(capture(cmdSlot)) } returns
            BoardTransitionResult(issueKey = "CARD-1", currentStateKey = "in-progress", version = 2L)

        val actorUserId = UUID.randomUUID()
        val result =
            serviceWith(transition = transition)
                .moveCard(
                    boardId = board.id,
                    issueKey = "CARD-1",
                    actorUserId = actorUserId,
                    toColumnId = inProgressColumn.id,
                    expectedVersion = 1L,
                    resolutionId = null,
                )

        assertThat(cmdSlot.captured.actorUserId).isEqualTo(actorUserId)
        assertThat(cmdSlot.captured.toStateKey).isEqualTo("in-progress")
        assertThat(cmdSlot.captured.issueKey).isEqualTo("CARD-1")
        assertThat(cmdSlot.captured.expectedVersion).isEqualTo(1L)
        assertThat(result.currentStateKey).isEqualTo("in-progress")
    }

    // ── (e) E8: 보드-이슈 프로젝트 정합 ────────────────────────────────────────────

    @Test
    fun `E8 issueKey 가 보드의 projectKey 소속이 아니면 거부된다`() {
        val catalog = mockk<WorkflowStateCatalog>()
        every { catalog.listStates(ProjectKey.of("BTS"), null) } returns DEFAULT_STATES
        val board = serviceWith(catalog = catalog).createBoard("BTS", "정합 테스트 보드")

        val anyColumn = board.columns.first()

        // BTS 보드에 OTHER 프로젝트 이슈 이동 시도
        assertThatThrownBy {
            serviceWith().moveCard(
                boardId = board.id,
                issueKey = "OTHER-1",
                actorUserId = UUID.randomUUID(),
                toColumnId = anyColumn.id,
                expectedVersion = 1L,
                resolutionId = null,
            )
        }.isInstanceOf(ResponseStatusException::class.java)
    }

    @Test
    fun `E8 하이픈 없는 issueKey 는 BTS 보드에서 거부된다`() {
        val catalog = mockk<WorkflowStateCatalog>()
        every { catalog.listStates(ProjectKey.of("BTS"), null) } returns DEFAULT_STATES
        val board = serviceWith(catalog = catalog).createBoard("BTS", "형식 검증 보드")

        val anyColumn = board.columns.first()

        // "BTS" — 하이픈 없는 키: prefix = "BTS" 이지만 NUMBER 부분 없음
        assertThatThrownBy {
            serviceWith().moveCard(
                boardId = board.id,
                issueKey = "BTS",
                actorUserId = UUID.randomUUID(),
                toColumnId = anyColumn.id,
                expectedVersion = 1L,
                resolutionId = null,
            )
        }.isInstanceOf(ResponseStatusException::class.java)
    }

    @Test
    fun `E8 prefix는 같지만 PROJECT_NUMBER 형식이 아닌 이슈키는 거부된다`() {
        val catalog = mockk<WorkflowStateCatalog>()
        every { catalog.listStates(ProjectKey.of("BTS"), null) } returns DEFAULT_STATES
        val board = serviceWith(catalog = catalog).createBoard("BTS", "형식 검증 보드2")

        val anyColumn = board.columns.first()

        // "BTS-abc" — prefix 는 BTS 이지만 NUMBER 가 숫자가 아님
        assertThatThrownBy {
            serviceWith().moveCard(
                boardId = board.id,
                issueKey = "BTS-abc",
                actorUserId = UUID.randomUUID(),
                toColumnId = anyColumn.id,
                expectedVersion = 1L,
                resolutionId = null,
            )
        }.isInstanceOf(ResponseStatusException::class.java)
    }

    @Test
    fun `E8 BTSX-1 은 BTS 보드에서 거부된다 (prefix 충돌 방지)`() {
        val catalog = mockk<WorkflowStateCatalog>()
        every { catalog.listStates(ProjectKey.of("BTS"), null) } returns DEFAULT_STATES
        val board = serviceWith(catalog = catalog).createBoard("BTS", "prefix 충돌 테스트 보드")

        val anyColumn = board.columns.first()

        // "BTSX-1" — substringBefore("-") = "BTSX" != "BTS" 이지만
        // startsWith("BTS-") 로 정확하게 차단하는지 확인
        assertThatThrownBy {
            serviceWith().moveCard(
                boardId = board.id,
                issueKey = "BTSX-1",
                actorUserId = UUID.randomUUID(),
                toColumnId = anyColumn.id,
                expectedVersion = 1L,
                resolutionId = null,
            )
        }.isInstanceOf(ResponseStatusException::class.java)
    }

    // ── (g) 보드 조회 시 quickFilters 포함 (FR-UX-01 Task 7) ─────────────────────

    @Test
    fun `보드 조회 시 BoardQuickFilterRepository 결과가 quickFilters 로 포함된다`() {
        val catalog = mockk<WorkflowStateCatalog>()
        every { catalog.listStates(ProjectKey.of("QF"), null) } returns DEFAULT_STATES
        val board = serviceWith(catalog = catalog).createBoard("QF", "퀵필터 테스트 보드")

        val viewerId = UUID.randomUUID()
        val expectedFilters =
            listOf(
                QuickFilter(id = UUID.randomUUID(), boardId = board.id, name = "내 버그", query = "label=bug"),
                QuickFilter(id = UUID.randomUUID(), boardId = board.id, name = "긴급", query = "label=urgent"),
            )
        val quickFilterRepo = mockk<BoardQuickFilterRepository>()
        every { quickFilterRepo.findByBoardId(board.id) } returns expectedFilters

        val result =
            serviceWith(quickFilterRepo = quickFilterRepo).getBoard(boardId = board.id, viewerUserId = viewerId)

        assertThat(result.quickFilters).isEqualTo(expectedFilters)
    }

    @Test
    fun `보드 조회 시 퀵필터가 없으면 quickFilters 는 빈 목록이다`() {
        val catalog = mockk<WorkflowStateCatalog>()
        every { catalog.listStates(ProjectKey.of("QF2"), null) } returns DEFAULT_STATES
        val board = serviceWith(catalog = catalog).createBoard("QF2", "퀵필터 없음 보드")

        val viewerId = UUID.randomUUID()
        val quickFilterRepo = mockk<BoardQuickFilterRepository>()
        every { quickFilterRepo.findByBoardId(board.id) } returns emptyList()

        val result =
            serviceWith(quickFilterRepo = quickFilterRepo).getBoard(boardId = board.id, viewerUserId = viewerId)

        assertThat(result.quickFilters).isEmpty()
    }

    // ── 보드 목록 조회 ────────────────────────────────────────────────────────────

    @Test
    fun `listBoards 는 projectKey 로 활성 보드 목록을 반환한다`() {
        val catalog = mockk<WorkflowStateCatalog>()
        every { catalog.listStates(ProjectKey.of("LIST"), null) } returns DEFAULT_STATES
        val service = serviceWith(catalog = catalog)

        service.createBoard("LIST", "목록 보드 1")
        service.createBoard("LIST", "목록 보드 2")

        val boards = service.listBoards("LIST")

        assertThat(boards).hasSizeGreaterThanOrEqualTo(2)
        assertThat(boards.map { it.name }).contains("목록 보드 1", "목록 보드 2")
    }

    // ── updateSwimlaneField / updateColumnWipLimit 단위 테스트 (mockk repo) ─────

    @Test
    fun `updateSwimlaneField ASSIGNEE 유효값이면 repo 가 SwimlaneField_ASSIGNEE 로 호출되고 갱신된 보드를 반환한다`() {
        val boardId = UUID.randomUUID()
        val expectedBoard =
            Board(
                id = boardId,
                projectKey = "TST",
                name = "테스트 보드",
                columns = emptyList(),
                createdAt = Instant.now(),
                updatedAt = Instant.now(),
                swimlaneField = SwimlaneField.ASSIGNEE,
            )
        val repo = mockk<BoardRepository>()
        every { repo.updateSwimlaneField(boardId, SwimlaneField.ASSIGNEE) } returns expectedBoard

        val result = serviceWith(repo = repo).updateSwimlaneField(boardId, "ASSIGNEE")

        assertThat(result).isEqualTo(expectedBoard)
        verify(exactly = 1) { repo.updateSwimlaneField(boardId, SwimlaneField.ASSIGNEE) }
    }

    @Test
    fun `updateSwimlaneField EPIC 은 유효값이므로 repo 가 SwimlaneField_EPIC 으로 호출된다 (FR-EP-01 활성화)`() {
        val boardId = UUID.randomUUID()
        val expectedBoard =
            Board(
                id = boardId,
                projectKey = "TST",
                name = "에픽 스윔레인 보드",
                columns = emptyList(),
                createdAt = Instant.now(),
                updatedAt = Instant.now(),
                swimlaneField = SwimlaneField.EPIC,
            )
        val repo = mockk<BoardRepository>()
        every { repo.updateSwimlaneField(boardId, SwimlaneField.EPIC) } returns expectedBoard

        val result = serviceWith(repo = repo).updateSwimlaneField(boardId, "EPIC")

        assertThat(result).isEqualTo(expectedBoard)
        verify(exactly = 1) { repo.updateSwimlaneField(boardId, SwimlaneField.EPIC) }
    }

    @Test
    fun `updateSwimlaneField 알 수 없는 값 foo 는 400 을 던지고 repo 를 호출하지 않는다`() {
        val repo = mockk<BoardRepository>()

        assertThatThrownBy { serviceWith(repo = repo).updateSwimlaneField(UUID.randomUUID(), "foo") }
            .isInstanceOf(ResponseStatusException::class.java)
            .extracting("statusCode.value")
            .isEqualTo(400)

        verify(exactly = 0) { repo.updateSwimlaneField(any(), any()) }
    }

    @Test
    fun `updateSwimlaneField repo 가 null 반환하면 404 를 던진다`() {
        val repo = mockk<BoardRepository>()
        every { repo.updateSwimlaneField(any(), any()) } returns null

        assertThatThrownBy { serviceWith(repo = repo).updateSwimlaneField(UUID.randomUUID(), "NONE") }
            .isInstanceOf(ResponseStatusException::class.java)
            .extracting("statusCode.value")
            .isEqualTo(404)
    }

    @Test
    fun `updateColumnWipLimit 유효 호출이면 repo 결과를 그대로 반환한다`() {
        val boardId = UUID.randomUUID()
        val columnId = UUID.randomUUID()
        val expectedColumn =
            BoardColumn(
                id = columnId,
                stateKey = "open",
                name = "열림",
                category = "TODO",
                displayOrder = 0,
                wipLimit = 5,
            )
        val repo = mockk<BoardRepository>()
        every { repo.updateColumnWipLimit(boardId, columnId, 5) } returns expectedColumn

        val result = serviceWith(repo = repo).updateColumnWipLimit(boardId, columnId, 5)

        assertThat(result).isEqualTo(expectedColumn)
    }

    @Test
    fun `updateColumnWipLimit repo 가 null 반환하면 404 를 던진다`() {
        val repo = mockk<BoardRepository>()
        every { repo.updateColumnWipLimit(any(), any(), any()) } returns null

        assertThatThrownBy {
            serviceWith(repo = repo).updateColumnWipLimit(UUID.randomUUID(), UUID.randomUUID(), 3)
        }
            .isInstanceOf(ResponseStatusException::class.java)
            .extracting("statusCode.value")
            .isEqualTo(404)
    }
}
