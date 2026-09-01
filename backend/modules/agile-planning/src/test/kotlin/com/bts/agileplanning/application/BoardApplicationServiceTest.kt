// BoardApplicationService 통합 테스트 (Testcontainers) — 보드 생성/조회/이동 시나리오 RED 명세 (FR-BD-01 Task 8)

package com.bts.agileplanning.application

import com.bts.agileplanning.AgilePlanningTestBootApplication
import com.bts.agileplanning.AgilePlanningTestcontainersConfig
import com.bts.agileplanning.domain.Board
import com.bts.agileplanning.domain.BoardColumn
import com.bts.agileplanning.domain.BoardNameInvalidException
import com.bts.agileplanning.domain.BoardType
import com.bts.agileplanning.domain.QuickFilter
import com.bts.agileplanning.domain.Sprint
import com.bts.agileplanning.domain.SprintStatus
import com.bts.agileplanning.domain.SwimlaneField
import com.bts.agileplanning.repository.BoardQuickFilterRepository
import com.bts.agileplanning.repository.BoardRepository
import com.bts.agileplanning.repository.SprintRepository
import com.bts.agileplanning.web.BoardNotFoundException
import com.bts.agileplanning.web.dto.BoardCardResponse
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
import java.time.LocalDate
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
 * - (h) BoardCardResponse 가 BoardIssueView.rank 를 그대로 노출(FR-UX-06 PR21 Task 1)
 * - (i) updateBoard / softDelete 의 공백 이름 거부 + 미존재 보드 404 승격(FR-BD-01-2 Task 2)
 * - (j) PATCH 원자성 — 이름 유효 + swimlaneField 무효면 400 이고 이름이 옛 값으로 남는다(리뷰 지적 1)
 */
@SpringBootTest(
    classes = [AgilePlanningTestBootApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
)
@Import(AgilePlanningTestcontainersConfig::class)
// 서비스 전 시나리오를 단일 클래스로 커버한다 — 형제 SprintApplicationServiceTest 와 같은 결정.
@Suppress("LargeClass")
class BoardApplicationServiceTest {
    @Autowired
    private lateinit var boardRepository: BoardRepository

    /** 스크럼 보드 분기 검증용 — 활성 스프린트와 이슈 할당을 실제 DB 에 심는다(FR-BD-04 D4). */
    @Autowired
    private lateinit var sprintRepository: SprintRepository

    /**
     * Spring 이 프록시한 [BoardApplicationService] 빈.
     *
     * [serviceWith] 가 만드는 인스턴스는 생성자 직접 호출이라 `@Transactional` AOP 가 걸리지 않는다.
     * 트랜잭션 경계가 걸린 상태의 동작을 봐야 하는 테스트만 이 빈을 쓴다.
     */
    @Autowired
    private lateinit var transactionalBoardService: BoardApplicationService

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
    @Suppress("LongParameterList") // 서비스 생성자 의존 수와 1:1 — 줄이면 어느 포트를 바꿨는지 흐려진다
    private fun serviceWith(
        catalog: WorkflowStateCatalog = mockk(relaxed = true),
        lookup: BoardIssueLookupPort = mockk(relaxed = true),
        transition: IssueTransitionPort = mockk(relaxed = true),
        repo: BoardRepository = boardRepository,
        quickFilterRepo: BoardQuickFilterRepository = mockk(relaxed = true),
        sprintRepo: SprintRepository = sprintRepository,
    ): BoardApplicationService =
        BoardApplicationService(
            workflowStateCatalog = catalog,
            boardIssueLookupPort = lookup,
            issueTransitionPort = transition,
            boardRepository = repo,
            boardQuickFilterRepository = quickFilterRepo,
            sprintRepository = sprintRepo,
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
                    typeKey = "task",
                ),
                BoardIssueView(
                    key = "PROJ-2",
                    summary = "두 번째 이슈",
                    currentStateKey = "in-progress",
                    assigneeId = null,
                    priority = 1,
                    version = 1L,
                    typeKey = "task",
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

    // ── 스크럼 보드 분기 (FR-BD-04 D4) ──────────────────────────────────────────

    /** 보드 카드용 최소 이슈 뷰. */
    private fun issueView(
        key: String,
        stateKey: String,
    ): BoardIssueView =
        BoardIssueView(
            key = key,
            summary = "$key 요약",
            currentStateKey = stateKey,
            assigneeId = null,
            priority = 2,
            version = 1L,
            typeKey = "task",
        )

    /** 그 보드에 ACTIVE 스프린트를 심고 이슈를 할당한다. */
    private fun seedActiveSprint(
        boardId: UUID,
        projectKey: String,
        issueKeys: List<String>,
    ): Sprint {
        val sprint =
            sprintRepository.insert(
                Sprint(
                    id = UUID.randomUUID(),
                    projectKey = projectKey,
                    boardId = boardId,
                    name = "Sprint 1",
                    goal = null,
                    status = SprintStatus.ACTIVE,
                    startDate = LocalDate.of(2026, 9, 1),
                    endDate = LocalDate.of(2026, 9, 14),
                    version = 0L,
                ),
            )
        issueKeys.forEach { sprintRepository.assignIssue(sprint.id, it) }
        return sprint
    }

    /** 스프린트 안 3건 + 밖 5건 = 8건. 앞 3건이 스프린트 소속이다. */
    private fun eightIssues(projectKey: String): List<BoardIssueView> =
        (1..8).map { n -> issueView("$projectKey-$n", if (n <= 3) "open" else "in-progress") }

    private fun lookupOf(issues: List<BoardIssueView>): BoardIssueLookupPort =
        object : BoardIssueLookupPort {
            override fun listVisibleIssuesByProject(
                projectKey: String,
                viewerUserId: UUID,
                filter: BoardCardFilter,
            ): BoardIssuePage = BoardIssuePage(issues = issues, truncated = false)
        }

    @Test
    fun `스크럼 보드는 활성 스프린트에 속한 이슈만 배치한다`() {
        val catalog = mockk<WorkflowStateCatalog>()
        every { catalog.listStates(ProjectKey.of("SCRM"), null) } returns DEFAULT_STATES
        val board = serviceWith(catalog = catalog).createBoard("SCRM", "스크럼 보드", BoardType.SCRUM)
        seedActiveSprint(board.id, "SCRM", listOf("SCRM-1", "SCRM-2", "SCRM-3"))

        val result =
            serviceWith(catalog = catalog, lookup = lookupOf(eightIssues("SCRM")))
                .getBoard(boardId = board.id, viewerUserId = UUID.randomUUID())

        assertThat(result.columns.sumOf { it.cards.size }).isEqualTo(3)
        assertThat(result.columns.flatMap { it.cards }.map { it.key })
            .containsExactlyInAnyOrder("SCRM-1", "SCRM-2", "SCRM-3")
    }

    @Test
    fun `칸반 보드는 같은 보드에 활성 스프린트가 있어도 이슈 전량을 배치한다`() {
        // NFR-1 회귀 0. 칸반의 의미는 「프로젝트 이슈 전량」이고 이 PR 이 그것을 바꾸지 않는다.
        val catalog = mockk<WorkflowStateCatalog>()
        every { catalog.listStates(ProjectKey.of("KNBN"), null) } returns DEFAULT_STATES
        val board = serviceWith(catalog = catalog).createBoard("KNBN", "칸반 보드")
        seedActiveSprint(board.id, "KNBN", listOf("KNBN-1"))

        val result =
            serviceWith(catalog = catalog, lookup = lookupOf(eightIssues("KNBN")))
                .getBoard(boardId = board.id, viewerUserId = UUID.randomUUID())

        assertThat(result.columns.sumOf { it.cards.size }).isEqualTo(8)
    }

    @Test
    fun `스크럼 보드에 활성 스프린트가 없으면 카드가 0건이고 activeSprint 가 null 이다`() {
        val catalog = mockk<WorkflowStateCatalog>()
        every { catalog.listStates(ProjectKey.of("NOSP"), null) } returns DEFAULT_STATES
        val board = serviceWith(catalog = catalog).createBoard("NOSP", "빈 스크럼 보드", BoardType.SCRUM)

        val result =
            serviceWith(catalog = catalog, lookup = lookupOf(eightIssues("NOSP")))
                .getBoard(boardId = board.id, viewerUserId = UUID.randomUUID())

        assertThat(result.activeSprint).isNull()
        assertThat(result.columns).hasSize(3)
        assertThat(result.columns.sumOf { it.cards.size }).isEqualTo(0)
    }

    @Test
    fun `스크럼 보드 응답의 activeSprint 는 그 보드의 활성 스프린트다`() {
        val catalog = mockk<WorkflowStateCatalog>()
        every { catalog.listStates(ProjectKey.of("ACTS"), null) } returns DEFAULT_STATES
        val board = serviceWith(catalog = catalog).createBoard("ACTS", "스크럼 보드", BoardType.SCRUM)
        val sprint = seedActiveSprint(board.id, "ACTS", listOf("ACTS-1"))

        val result =
            serviceWith(catalog = catalog, lookup = lookupOf(eightIssues("ACTS")))
                .getBoard(boardId = board.id, viewerUserId = UUID.randomUUID())

        assertThat(result.activeSprint?.id).isEqualTo(sprint.id)
        assertThat(result.activeSprint?.name).isEqualTo("Sprint 1")
    }

    @Test
    fun `칸반 보드는 활성 스프린트가 있어도 activeSprint 가 null 이다`() {
        val catalog = mockk<WorkflowStateCatalog>()
        every { catalog.listStates(ProjectKey.of("KBNU"), null) } returns DEFAULT_STATES
        val board = serviceWith(catalog = catalog).createBoard("KBNU", "칸반 보드")
        seedActiveSprint(board.id, "KBNU", listOf("KBNU-1"))

        val result =
            serviceWith(catalog = catalog, lookup = lookupOf(eightIssues("KBNU")))
                .getBoard(boardId = board.id, viewerUserId = UUID.randomUUID())

        assertThat(result.activeSprint).isNull()
    }

    @Test
    fun `컬럼이 0개인 보드는 조회 시 컬럼이 시드되고 영속된다`() {
        val catalog = mockk<WorkflowStateCatalog>()
        every { catalog.listStates(ProjectKey.of("HEAL"), null) } returns DEFAULT_STATES
        // ensureScrumBoard 는 일부러 컬럼 0개로 만든다. V506 백필도 복제할 보드가 없으면 같은 상태를 남긴다.
        val boardId = serviceWith(catalog = catalog).ensureScrumBoard("HEAL")
        assertThat(boardRepository.findById(boardId)!!.columns).isEmpty()

        val result =
            serviceWith(catalog = catalog, lookup = lookupOf(emptyList()))
                .getBoard(boardId = boardId, viewerUserId = UUID.randomUUID())

        assertThat(result.columns).hasSize(3)
        // ★ 영속이 핵심이다. 매 조회마다 다시 시드하면 컬럼 UUID 가 흔들려 카드 이동(toColumnId)이 깨진다.
        assertThat(boardRepository.findById(boardId)!!.columns.map { it.stateKey })
            .containsExactly("open", "in-progress", "closed")
    }

    @Test
    fun `자가 치유는 실제 트랜잭션 경계 안에서도 성공한다`() {
        // ★ 이 테스트만이 getBoard 의 @Transactional 이 readOnly 가 아님을 지킨다.
        // serviceWith 인스턴스는 생성자 직접 호출이라 AOP 가 없어, readOnly 가 되살아나도
        // 다른 자가 치유 테스트는 전부 통과한다(가짜 그린). 여기서는 프록시된 빈을 써서
        // 진짜 read-only 커넥션 위에서 시드 INSERT 를 시도한다.
        AgilePlanningTestcontainersConfig.EmptyWorkflowStateCatalogStub.states = DEFAULT_STATES
        try {
            val boardId = transactionalBoardService.ensureScrumBoard("TXHEAL")
            assertThat(boardRepository.findById(boardId)!!.columns).isEmpty()

            transactionalBoardService.getBoard(boardId = boardId, viewerUserId = UUID.randomUUID())

            assertThat(boardRepository.findById(boardId)!!.columns.map { it.stateKey })
                .containsExactly("open", "in-progress", "closed")
        } finally {
            AgilePlanningTestcontainersConfig.EmptyWorkflowStateCatalogStub.states = emptyList()
        }
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
                BoardIssueView("UNPL-1", "미매핑 이슈", "ghost-state", null, 1, 1L, "task"),
                BoardIssueView("UNPL-2", "정상 이슈", "open", null, 2, 1L, "task"),
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

    // ── updateBoard / updateColumnWipLimit 단위 테스트 (mockk repo) ────────────

    @Test
    fun `updateBoard 가 swimlaneField 만 받으면 repo 가 SwimlaneField_ASSIGNEE 로 호출된다`() {
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
        every { repo.findById(boardId) } returns activeBoard(boardId)
        every { repo.updateSwimlaneField(boardId, SwimlaneField.ASSIGNEE) } returns expectedBoard

        val result = serviceWith(repo = repo).updateBoard(boardId, name = null, swimlaneField = "ASSIGNEE")

        assertThat(result).isEqualTo(expectedBoard)
        verify(exactly = 1) { repo.updateSwimlaneField(boardId, SwimlaneField.ASSIGNEE) }
        // name 미전송이므로 이름 갱신은 일어나지 않는다(부분 갱신의 정의).
        verify(exactly = 0) { repo.updateName(any(), any()) }
    }

    @Test
    fun `updateBoard 의 swimlaneField EPIC 은 유효값이므로 repo 가 SwimlaneField_EPIC 으로 호출된다 (FR-EP-01)`() {
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
        every { repo.findById(boardId) } returns activeBoard(boardId)
        every { repo.updateSwimlaneField(boardId, SwimlaneField.EPIC) } returns expectedBoard

        val result = serviceWith(repo = repo).updateBoard(boardId, name = null, swimlaneField = "EPIC")

        assertThat(result).isEqualTo(expectedBoard)
        verify(exactly = 1) { repo.updateSwimlaneField(boardId, SwimlaneField.EPIC) }
    }

    @Test
    fun `updateBoard 가 알 수 없는 swimlaneField 값을 받으면 400 이고 어떤 쓰기도 하지 않는다`() {
        val boardId = UUID.randomUUID()
        val repo = mockk<BoardRepository>()
        every { repo.findById(boardId) } returns activeBoard(boardId)

        assertThatThrownBy { serviceWith(repo = repo).updateBoard(boardId, name = "새 이름", swimlaneField = "foo") }
            .isInstanceOf(ResponseStatusException::class.java)
            .extracting("statusCode.value")
            .isEqualTo(400)

        // 이름이 함께 왔어도 쓰기는 0건이어야 한다 — 검증이 모든 쓰기보다 앞선다(원자성).
        verify(exactly = 0) { repo.updateSwimlaneField(any(), any()) }
        verify(exactly = 0) { repo.updateName(any(), any()) }
    }

    @Test
    fun `updateBoard 의 swimlaneField 갱신이 0행이면 BoardNotFoundException 을 던진다`() {
        val boardId = UUID.randomUUID()
        val repo = mockk<BoardRepository>()
        every { repo.findById(boardId) } returns activeBoard(boardId)
        // 조회와 갱신 사이에 다른 트랜잭션이 soft-delete 한 경우 repo 가 null 을 돌려준다(TOCTOU).
        every { repo.updateSwimlaneField(boardId, any()) } returns null

        assertThatThrownBy { serviceWith(repo = repo).updateBoard(boardId, name = null, swimlaneField = "NONE") }
            .isInstanceOf(BoardNotFoundException::class.java)
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

    // ── (i) updateBoard / softDelete (FR-BD-01-2 Task 2) ───────────────────────

    /** updateBoard / softDelete 단위 테스트용 활성 보드 픽스처. */
    private fun activeBoard(boardId: UUID): Board =
        Board(
            id = boardId,
            projectKey = "BDCRUD",
            name = "기존 보드 이름",
            columns = emptyList(),
            createdAt = Instant.now(),
            updatedAt = Instant.now(),
        )

    @Test
    fun `updateBoard 가 공백 이름을 BoardNameInvalidException 으로 거부한다`() {
        val boardId = UUID.randomUUID()
        val repo = mockk<BoardRepository>()
        every { repo.findById(boardId) } returns activeBoard(boardId)

        // 이름 있는 도메인 예외여야 한다. 맨 IllegalArgumentException 이면 핸들러가 계층 전체를
        // 400 으로 삼키게 되어(리뷰 지적 3) 내부 require 버그가 5xx 경보에서 사라진다.
        assertThatThrownBy { serviceWith(repo = repo).updateBoard(boardId, name = "   ", swimlaneField = null) }
            .isInstanceOf(BoardNameInvalidException::class.java)

        // 공백 이름이 DB 까지 내려가지 않는다 — 도메인 불변식이 쓰기보다 앞선다.
        verify(exactly = 0) { repo.updateName(any(), any()) }
    }

    @Test
    fun `updateBoard 가 미존재 보드에 BoardNotFoundException 을 던진다`() {
        val repo = mockk<BoardRepository>()
        every { repo.findById(any()) } returns null

        assertThatThrownBy { serviceWith(repo = repo).updateBoard(UUID.randomUUID(), "새 보드 이름", null) }
            .isInstanceOf(BoardNotFoundException::class.java)

        verify(exactly = 0) { repo.updateName(any(), any()) }
    }

    @Test
    fun `updateBoard 가 name 만 받으면 repo 갱신 결과를 그대로 반환한다`() {
        val boardId = UUID.randomUUID()
        val renamed = activeBoard(boardId).copy(name = "새 보드 이름")
        val repo = mockk<BoardRepository>()
        every { repo.findById(boardId) } returns activeBoard(boardId)
        every { repo.updateName(boardId, "새 보드 이름") } returns renamed

        val result = serviceWith(repo = repo).updateBoard(boardId, name = "새 보드 이름", swimlaneField = null)

        assertThat(result).isEqualTo(renamed)
        verify(exactly = 1) { repo.updateName(boardId, "새 보드 이름") }
        verify(exactly = 0) { repo.updateSwimlaneField(any(), any()) }
    }

    @Test
    fun `updateBoard 는 조회 후 갱신 전에 보드가 사라지면 BoardNotFoundException 을 던진다`() {
        val boardId = UUID.randomUUID()
        val repo = mockk<BoardRepository>()
        every { repo.findById(boardId) } returns activeBoard(boardId)
        // 다른 트랜잭션이 그 사이에 soft-delete 한 경우 repo 가 null 을 돌려준다.
        every { repo.updateName(boardId, any()) } returns null

        assertThatThrownBy { serviceWith(repo = repo).updateBoard(boardId, "새 보드 이름", null) }
            .isInstanceOf(BoardNotFoundException::class.java)
    }

    /**
     * 두 필드를 함께 받으면 한 호출 안에서 이름 → 스윔레인 순으로 쓰고 마지막 결과를 돌려준다.
     *
     * 두 쓰기가 한 메서드(= 한 트랜잭션) 안에 있다는 것이 원자성의 구조적 근거다.
     * 컨트롤러가 서비스를 두 번 부르던 이전 구조에서는 이 단언이 성립할 수 없었다.
     */
    @Test
    fun `updateBoard 가 두 필드를 함께 받으면 한 호출에서 이름과 스윔레인을 모두 갱신한다`() {
        val boardId = UUID.randomUUID()
        val renamed = activeBoard(boardId).copy(name = "새 보드 이름")
        val both = renamed.copy(swimlaneField = SwimlaneField.ASSIGNEE)
        val repo = mockk<BoardRepository>()
        every { repo.findById(boardId) } returns activeBoard(boardId)
        every { repo.updateName(boardId, "새 보드 이름") } returns renamed
        every { repo.updateSwimlaneField(boardId, SwimlaneField.ASSIGNEE) } returns both

        val result = serviceWith(repo = repo).updateBoard(boardId, "새 보드 이름", "ASSIGNEE")

        assertThat(result).isEqualTo(both)
        verify(exactly = 1) { repo.updateName(boardId, "새 보드 이름") }
        verify(exactly = 1) { repo.updateSwimlaneField(boardId, SwimlaneField.ASSIGNEE) }
    }

    @Test
    fun `softDelete 가 미존재 보드에 BoardNotFoundException 을 던진다`() {
        val repo = mockk<BoardRepository>()
        every { repo.softDelete(any()) } returns false

        assertThatThrownBy { serviceWith(repo = repo).softDelete(UUID.randomUUID()) }
            .isInstanceOf(BoardNotFoundException::class.java)
    }

    @Test
    fun `softDelete 가 활성 보드를 삭제하면 예외 없이 repo softDelete 를 호출한다`() {
        val boardId = UUID.randomUUID()
        val repo = mockk<BoardRepository>()
        every { repo.softDelete(boardId) } returns true

        serviceWith(repo = repo).softDelete(boardId)

        verify(exactly = 1) { repo.softDelete(boardId) }
    }

    // ── (h) BoardCardResponse.rank 노출 (FR-UX-06 PR21 Task 1) ──────────────────

    @Test
    fun `rank 가 부여된 BoardIssueView 는 BoardCardResponse 에 그 rank 그대로 노출된다`() {
        val view =
            BoardIssueView(
                key = "RANK-1",
                summary = "rank 부여 이슈",
                currentStateKey = "open",
                assigneeId = null,
                priority = 1,
                version = 0L,
                typeKey = "task",
                rank = "0|hzzzzz:",
            )

        val response = BoardCardResponse.from(view)

        assertThat(response.rank).isEqualTo("0|hzzzzz:")
    }

    @Test
    fun `rank 가 미부여인 BoardIssueView 는 BoardCardResponse rank 도 null 이다`() {
        val view =
            BoardIssueView(
                key = "RANK-2",
                summary = "rank 미부여 이슈",
                currentStateKey = "open",
                assigneeId = null,
                priority = 1,
                version = 0L,
                typeKey = "task",
            )

        val response = BoardCardResponse.from(view)

        assertThat(response.rank).isNull()
    }

    // ── (j) PATCH 원자성 — 두 필드 갱신은 한 트랜잭션이다 (리뷰 지적 1) ──────────

    /**
     * 이름이 유효해도 스윔레인 값이 무효면 400 이고 `boards.name` 은 옛 값 그대로여야 한다.
     *
     * 이전 구조는 컨트롤러가 `updateName` → `updateSwimlaneField` 를 순차 호출했고 각각이
     * `@Transactional` 이라 두 트랜잭션으로 갈렸다. 그래서 앞의 이름 갱신이 **커밋된 뒤** 뒤쪽이
     * 400 을 던져, 클라이언트는 400 을 받는데 보드 이름은 이미 바뀌어 있는 상태가 남았다.
     *
     * 실물 DB 로 재조회해 「옛 이름」을 확인하는 것이 이 테스트의 판별자다. mock repo 로는
     * 커밋 여부를 볼 수 없어 같은 결함이 초록으로 지나간다.
     */
    @Test
    fun `updateBoard 는 swimlaneField 가 무효면 400 이고 이름을 옛 값으로 남긴다`() {
        val saved =
            boardRepository.insert(
                Board(
                    id = UUID.randomUUID(),
                    projectKey = "ATOMIC",
                    name = "원래 이름",
                    columns = emptyList(),
                    createdAt = Instant.now(),
                    updatedAt = Instant.now(),
                ),
            )

        assertThatThrownBy { transactionalBoardService.updateBoard(saved.id, "새 이름", "BOGUS") }
            .isInstanceOf(ResponseStatusException::class.java)
            .extracting("statusCode.value")
            .isEqualTo(400)

        assertThat(boardRepository.findById(saved.id)?.name).isEqualTo("원래 이름")
    }
}
