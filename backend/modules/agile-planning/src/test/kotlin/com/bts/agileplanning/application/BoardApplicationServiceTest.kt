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
import com.bts.agileplanning.repository.BoardColumnStateRepository
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
import io.mockk.CapturingSlot
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
import org.springframework.dao.DuplicateKeyException
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

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

    /** 컬럼에 상태를 둘 이상 매핑해 1:N 경로를 만드는 데 쓴다(R6·R7). */
    @Autowired
    private lateinit var columnStateRepository: BoardColumnStateRepository

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

        /** 보드 시드 **뒤에** 워크플로우에 추가된 상태 — 어느 컬럼에도 매핑되지 않는다(R8). */
        val BLOCKED_STATE =
            WorkflowStateView(key = "blocked", name = "차단됨", isDone = false, category = "TODO", displayOrder = 3)
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
        // 기본은 실물 — X1 제약과 CASCADE 가 판정에 들어와야 한다. 경합 변환(B1) 판정만 mock 을 넣는다.
        columnStateRepo: BoardColumnStateRepository = columnStateRepository,
    ): BoardApplicationService =
        BoardApplicationService(
            workflowStateCatalog = catalog,
            boardIssueLookupPort = lookup,
            issueTransitionPort = transition,
            boardRepository = repo,
            boardQuickFilterRepository = quickFilterRepo,
            sprintRepository = sprintRepo,
            columnStates = columnStateRepo,
        )

    // ── (a) 보드 생성 시 컬럼 시드 + 영속 ────────────────────────────────────────

    @Test
    fun `보드 생성 시 WorkflowStateCatalog 조회 후 컬럼이 3개 시드되고 DB 에 영속된다`() {
        val catalog = mockk<WorkflowStateCatalog>()
        every { catalog.listStates(ProjectKey.of("BTS"), null) } returns DEFAULT_STATES

        val board = serviceWith(catalog = catalog).createBoard(projectKey = "BTS", name = "BTS 보드")

        assertThat(board.columns).hasSize(3)
        assertThat(board.columns.map { it.legacyStateKey })
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

        val openPlaced = result.columns.first { it.column.legacyStateKey == "open" }
        assertThat(openPlaced.cards).hasSize(1)
        assertThat(openPlaced.cards.first().key).isEqualTo("PROJ-1")

        val inProgressPlaced = result.columns.first { it.column.legacyStateKey == "in-progress" }
        assertThat(inProgressPlaced.cards).hasSize(1)
        assertThat(inProgressPlaced.cards.first().key).isEqualTo("PROJ-2")
    }

    // ── (c-2) 미매핑 상태 목록 (R8 · J2 · G3) ───────────────────────────────────

    @Test
    fun `보드 조회가 어느 컬럼에도 없는 상태를 unmappedStates 로 낸다`() {
        val catalog = mockk<WorkflowStateCatalog>()
        every { catalog.listStates(ProjectKey.of("UNMAP"), null) } returns DEFAULT_STATES
        val service = serviceWith(catalog = catalog)
        val board = service.createBoard("UNMAP", "미매핑 보드")

        // 보드를 만든 뒤 워크플로우에 상태가 하나 늘었다 — 그것을 담은 컬럼은 아직 없다.
        // 이 상태의 이슈는 오늘 unplacedCount 로만 세어져 「왜 빠졌는지」를 알 수 없다(E2).
        every { catalog.listStates(ProjectKey.of("UNMAP"), null) } returns DEFAULT_STATES + BLOCKED_STATE

        val result = service.getBoard(boardId = board.id, viewerUserId = UUID.randomUUID())

        assertThat(result.unmappedStates.map { it.key }).containsExactly("blocked")
        assertThat(result.unmappedStates.map { it.name }).containsExactly("차단됨")
    }

    @Test
    fun `모든 상태가 매핑됐으면 unmappedStates 가 빈 배열이다`() {
        val catalog = mockk<WorkflowStateCatalog>()
        every { catalog.listStates(ProjectKey.of("MAPPED"), null) } returns DEFAULT_STATES
        val service = serviceWith(catalog = catalog)
        val board = service.createBoard("MAPPED", "전량 매핑 보드")

        val result = service.getBoard(boardId = board.id, viewerUserId = UUID.randomUUID())

        assertThat(result.unmappedStates).isEmpty()
    }

    @Test
    fun `unmappedStates 기준이 listStates projectKey null 이다`() {
        // G3 — `createBoard` 의 시드와 **같은 호출**이어야 한다. `issueTypeKey` 로 가르면 시드에는
        // 있는데 미매핑 목록에는 없는(또는 그 반대) 상태가 생겨 두 목록이 서로를 배신한다.
        // strict mock 이라 다른 인자 조합으로 불리면 그 자체로 실패한다 — 이 verify 는 「불렸다」를 잰다.
        val catalog = mockk<WorkflowStateCatalog>()
        every { catalog.listStates(ProjectKey.of("GTHREE"), null) } returns DEFAULT_STATES
        val service = serviceWith(catalog = catalog)
        val board = service.createBoard("GTHREE", "G3 보드")

        service.getBoard(boardId = board.id, viewerUserId = UUID.randomUUID())

        verify(atLeast = 2) { catalog.listStates(ProjectKey.of("GTHREE"), null) }
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
        // 컬럼 0개 보드를 직접 심는다. V506 백필이 복제할 칸반을 못 찾았을 때와 같은 상태다.
        // ★ ensureScrumBoard 로 만들지 않는 이유 — 그쪽은 advisory lock(MANDATORY)이 필요해 트랜잭션
        // 경계를 끌고 오는데, 이 테스트의 관심사는 락이 아니라 **치유**다. 픽스처를 분리해 둘을 섞지 않는다.
        val boardId = UUID.randomUUID()
        boardRepository.insert(
            Board(
                id = boardId,
                projectKey = "HEAL",
                name = "HEAL 스크럼 보드",
                boardType = BoardType.SCRUM,
                columns = emptyList(),
                createdAt = Instant.now(),
                updatedAt = Instant.now(),
            ),
        )
        assertThat(boardRepository.findById(boardId)!!.columns).isEmpty()

        val result =
            serviceWith(catalog = catalog, lookup = lookupOf(emptyList()))
                .getBoard(boardId = boardId, viewerUserId = UUID.randomUUID())

        assertThat(result.columns).hasSize(3)
        // ★ 영속이 핵심이다. 매 조회마다 다시 시드하면 컬럼 UUID 가 흔들려 카드 이동(toColumnId)이 깨진다.
        assertThat(boardRepository.findById(boardId)!!.columns.map { it.legacyStateKey })
            .containsExactly("open", "in-progress", "closed")
    }

    @Test
    fun `ensureScrumBoard 는 두 번 불러도 같은 보드를 준다`() {
        // ★ 재사용 조기반환(findScrumBoardIdByProject?.let { return it })을 지우면 스프린트를 만들 때마다
        // 스크럼 보드가 하나씩 쌓여 보드 스위처(#416 으로 상시 노출)에 유령 보드가 늘어난다.
        // 그 조기반환을 지켜 주는 유일한 테스트다(리뷰 지적 C1).
        // ★ 프록시된 빈이어야 한다. acquireProjectScrumBoardLock 이 MANDATORY 라 트랜잭션 없이는 거부된다 —
        // serviceWith() 인스턴스로는 애초에 실행되지 않는다(그 배치가 조용히 통과하던 것이 리뷰 지적이었다).
        val first = transactionalBoardService.ensureScrumBoard("IDEM")
        val second = transactionalBoardService.ensureScrumBoard("IDEM")

        assertThat(second).isEqualTo(first)
        assertThat(boardRepository.findAllByProjectKey("IDEM")).hasSize(1)
    }

    @Test
    fun `동시 ensureScrumBoard 후에도 프로젝트의 스크럼 보드는 1개다`() {
        // ★ V506 ④ 가 「(project_key, SCRUM) 이 유일하다」는 전제 위에 서 있는데, 마이그레이션 이후
        // 그 유일성을 지키는 것은 이 메서드뿐이다. read-then-insert 라 잠금이 없으면 동시 요청 2건이
        // 보드를 2개 만들고, 이후 조회는 created_at 오래된 쪽만 집어 늦은 보드의 스프린트가 갈린다.
        // UNIQUE 인덱스로 막지 않는 이유 — ADR D6 이 다수 보드를 지원하므로 스키마로 1개를 못박을 수 없다.
        // ★ 프록시된 빈이어야 한다. advisory lock 은 트랜잭션 종료 시 풀리므로 트랜잭션 없이는 무의미하다.
        // ★ 정렬 장치가 없으면 두 작업이 겹칠 보장이 없다 — 순차 실행돼도 통과해 「락이 동작한다」와
        // 「애초에 안 겹쳤다」를 구별하지 못한다(리뷰 지적). 두 스레드가 진입한 것을 확인한 뒤 동시에 푼다.
        val entered = CountDownLatch(2)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        val futures =
            (1..2).map {
                executor.submit<Result<UUID>> {
                    entered.countDown()
                    start.await()
                    runCatching { transactionalBoardService.ensureScrumBoard("RACE") }
                }
            }
        assertThat(entered.await(10, TimeUnit.SECONDS))
            .`as`("두 스레드가 시작하지 못했다 — 경쟁이 재현되지 않았다")
            .isTrue()
        start.countDown()
        executor.shutdown()
        val results = futures.map { it.get() }

        // ★ 「1건 이상」이 아니라 **둘 다** 성공해야 한다. 늦은 쪽이 예외로 죽으면 사용자는 500 을 본다 —
        // 느슨한 단언은 그 회귀를 눈감는다.
        assertThat(results.count { it.isSuccess })
            .`as`("두 호출 중 실패가 있다 — 늦은 요청이 500 을 받는다")
            .isEqualTo(2)

        val boards = boardRepository.findAllByProjectKey("RACE")
        assertThat(boards)
            .`as`("동시 ensureScrumBoard 후 보드가 %d 개다 — 프로젝트당 스크럼 보드는 1개여야 한다", boards.size)
            .hasSize(1)
        assertThat(results.mapNotNull { it.getOrNull() }.distinct())
            .`as`("두 호출이 서로 다른 보드 id 를 받았다 — 늦은 쪽 스프린트가 갈린다")
            .hasSize(1)
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

            assertThat(boardRepository.findById(boardId)!!.columns.map { it.legacyStateKey })
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

        val inProgressColumn = board.columns.first { it.legacyStateKey == "in-progress" }
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
        assertThat(result.transition.currentStateKey).isEqualTo("in-progress")
    }

    // ── (d-2) toStateKey 수용 + toColumnId 하위 호환 (R6 · R7 · E3 · E4) ─────────

    /**
     * 컬럼에 상태 둘을 매핑한 보드를 만든다 — 1:N 경로를 타려면 이 상태가 필요하다.
     *
     * `in-progress` 컬럼이 `in-progress` 와 `closed` 를 함께 담는다. `closed` 를 원래 갖고 있던
     * 컬럼에서 먼저 떼야 X1(한 상태는 한 컬럼에만)을 어기지 않는다.
     */
    private fun boardWithMergedColumn(projectKey: String): Board {
        val catalog = mockk<WorkflowStateCatalog>()
        every { catalog.listStates(ProjectKey.of(projectKey), null) } returns DEFAULT_STATES
        val board = serviceWith(catalog = catalog).createBoard(projectKey, "1:N 이동 보드")

        val closedColumn = board.columns.first { it.legacyStateKey == "closed" }
        val inProgressColumn = board.columns.first { it.legacyStateKey == "in-progress" }
        columnStateRepository.replaceStates(board.id, closedColumn.id, emptyList())
        columnStateRepository.replaceStates(board.id, inProgressColumn.id, listOf("in-progress", "closed"))

        return requireNotNull(boardRepository.findById(board.id))
    }

    private fun capturingTransition(cmdSlot: CapturingSlot<BoardTransitionCommand>): IssueTransitionPort {
        val transition = mockk<IssueTransitionPort>()
        every { transition.transition(capture(cmdSlot)) } answers
            {
                BoardTransitionResult(
                    issueKey = cmdSlot.captured.issueKey,
                    currentStateKey = cmdSlot.captured.toStateKey,
                    version = cmdSlot.captured.expectedVersion + 1,
                )
            }
        return transition
    }

    @Test
    fun `toStateKey 로 옮기면 그 상태로 전환된다`() {
        // R6 — 지라는 컬럼 안의 각 상태를 드롭존으로 그린다(J3·J4). 클라이언트가 상태를 고르고
        //      서버는 그것이 이 보드에 매핑됐는지만 본다. 서버가 상태를 추론하지 않는다.
        val board = boardWithMergedColumn("MOVEA")
        val cmdSlot = slot<BoardTransitionCommand>()

        val result =
            serviceWith(transition = capturingTransition(cmdSlot)).moveCard(
                boardId = board.id,
                issueKey = "MOVEA-1",
                actorUserId = UUID.randomUUID(),
                toStateKey = "closed",
                expectedVersion = 1L,
                resolutionId = null,
            )

        assertThat(cmdSlot.captured.toStateKey).isEqualTo("closed")
        assertThat(result.transition.currentStateKey).isEqualTo("closed")
        // echo 되는 컬럼은 그 상태를 **담은** 컬럼이다. 병합된 in-progress 컬럼이어야 한다.
        assertThat(result.columnId)
            .isEqualTo(board.columns.first { it.stateKeys.contains("in-progress") }.id)
    }

    @Test
    fun `같은 컬럼 안 다른 상태로도 옮길 수 있다`() {
        // E3 — 1:N 이후에만 존재하는 조합이다. 컬럼은 그대로인데 상태만 바뀐다.
        val board = boardWithMergedColumn("MOVEB")
        val merged = board.columns.first { it.stateKeys.contains("in-progress") }
        val cmdSlot = slot<BoardTransitionCommand>()

        val result =
            serviceWith(transition = capturingTransition(cmdSlot)).moveCard(
                boardId = board.id,
                issueKey = "MOVEB-1",
                actorUserId = UUID.randomUUID(),
                toStateKey = "closed",
                expectedVersion = 1L,
                resolutionId = null,
            )

        assertThat(result.columnId).isEqualTo(merged.id)
        assertThat(cmdSlot.captured.toStateKey).isEqualTo("closed")
    }

    @Test
    fun `toStateKey 가 그 보드의 어느 컬럼에도 없으면 404`() {
        // E4 — 워크플로우에는 있으나 이 보드가 안 담은 상태다(미매핑 · R8 이 목록으로 알려주는 그것).
        val board = boardWithMergedColumn("MOVEC")

        assertThatThrownBy {
            serviceWith().moveCard(
                boardId = board.id,
                issueKey = "MOVEC-1",
                actorUserId = UUID.randomUUID(),
                toStateKey = "blocked",
                expectedVersion = 1L,
                resolutionId = null,
            )
        }
            .isInstanceOf(BoardStateNotMappedException::class.java)
    }

    @Test
    fun `toColumnId 만 보내고 그 컬럼의 상태가 1개면 성공한다`() {
        // R7 — 오늘의 프론트가 보내는 형태다. 상태가 유일하면 해석에 모호함이 없다.
        val catalog = mockk<WorkflowStateCatalog>()
        every { catalog.listStates(ProjectKey.of("MOVED"), null) } returns DEFAULT_STATES
        val board = serviceWith(catalog = catalog).createBoard("MOVED", "하위 호환 보드")
        val cmdSlot = slot<BoardTransitionCommand>()

        val result =
            serviceWith(transition = capturingTransition(cmdSlot)).moveCard(
                boardId = board.id,
                issueKey = "MOVED-1",
                actorUserId = UUID.randomUUID(),
                toColumnId = board.columns.first { it.legacyStateKey == "closed" }.id,
                expectedVersion = 1L,
                resolutionId = null,
            )

        assertThat(cmdSlot.captured.toStateKey).isEqualTo("closed")
        assertThat(result.transition.currentStateKey).isEqualTo("closed")
    }

    @Test
    fun `toColumnId 만 보내고 그 컬럼의 상태가 2개 이상이면 400`() {
        // R7 — 서버가 둘 중 하나를 고르면 사용자가 의도하지 않은 전환이 조용히 일어난다.
        //      「어느 상태로 가라」는 클라이언트만 안다.
        val board = boardWithMergedColumn("MOVEE")
        val merged = board.columns.first { it.stateKeys.size >= 2 }

        assertThatThrownBy {
            serviceWith().moveCard(
                boardId = board.id,
                issueKey = "MOVEE-1",
                actorUserId = UUID.randomUUID(),
                toColumnId = merged.id,
                expectedVersion = 1L,
                resolutionId = null,
            )
        }
            .isInstanceOf(ColumnStateAmbiguousException::class.java)
    }

    @Test
    fun `toStateKey 와 toColumnId 가 둘 다 없거나 둘 다 있으면 400`() {
        // R7 — `@NotNull` 로는 「둘 중 정확히 하나」를 표현할 수 없어 서비스가 진다.
        val board = boardWithMergedColumn("MOVEF")
        val anyColumn = board.columns.first()

        assertThatThrownBy {
            serviceWith().moveCard(
                boardId = board.id,
                issueKey = "MOVEF-1",
                actorUserId = UUID.randomUUID(),
                expectedVersion = 1L,
                resolutionId = null,
            )
        }
            .isInstanceOf(MoveTargetAmbiguousException::class.java)

        assertThatThrownBy {
            serviceWith().moveCard(
                boardId = board.id,
                issueKey = "MOVEF-1",
                actorUserId = UUID.randomUUID(),
                toColumnId = anyColumn.id,
                toStateKey = "closed",
                expectedVersion = 1L,
                resolutionId = null,
            )
        }
            .isInstanceOf(MoveTargetAmbiguousException::class.java)
    }

    // ── (d-3) 컬럼 관리 3종 — 생성 · 상태 교체 · 삭제 (R9 · R10 · E7~E9 · J5) ────

    @Test
    fun `컬럼을 상태 0개로 만들 수 있다`() {
        // R9·E1 — 지라는 컬럼을 먼저 만들고 Unmapped 패널에서 상태를 끌어다 놓는다(J2).
        //         그 중간 상태가 「상태 0개 컬럼」이고, V508 의 DROP NOT NULL 이 그것을 표현한다(G1).
        val board = boardWithMergedColumn("COLA")

        val created = serviceWith().createColumn(board.id, name = "대기", stateKeys = emptyList())

        assertThat(created.stateKeys).isEmpty()
        assertThat(created.category).isEqualTo("TODO")
        val reloaded = requireNotNull(boardRepository.findById(board.id))
        assertThat(reloaded.columns.map { it.id }).contains(created.id)
        assertThat(reloaded.columns.last().id).isEqualTo(created.id)
    }

    @Test
    fun `컬럼의 상태 집합을 통째로 교체한다`() {
        // R9 — 추가·제거를 각각 두면 「지금 이 컬럼의 상태 집합」이 클라이언트와 서버에서 갈린다.
        val board = boardWithMergedColumn("COLB")
        val target = board.columns.first { it.stateKeys == listOf("open") }

        serviceWith().replaceColumnStates(board.id, target.id, listOf("open", "blocked"))

        val reloaded = requireNotNull(boardRepository.findById(board.id))
        assertThat(reloaded.columns.first { it.id == target.id }.stateKeys)
            .containsExactly("open", "blocked")
    }

    @Test
    fun `다른 컬럼이 쓰는 상태를 넣으면 409 이고 어느 컬럼인지 알려준다`() {
        // E7·X1 — 「어느 컬럼이 쓰고 있는지」를 안 알려주면 사용자가 풀 방법을 못 찾는다.
        val board = boardWithMergedColumn("COLC")
        val merged = board.columns.first { it.stateKeys.size >= 2 }
        val other = board.columns.first { it.stateKeys == listOf("open") }

        val thrown =
            runCatching {
                serviceWith().replaceColumnStates(board.id, other.id, listOf("open", "closed"))
            }.exceptionOrNull()

        assertThat(thrown).isInstanceOf(StateAlreadyMappedException::class.java)
        assertThat((thrown as StateAlreadyMappedException).stateKey).isEqualTo("closed")
        assertThat(thrown.ownerColumnId).isEqualTo(merged.id)
        // 원래 매핑은 그대로다 — 거부가 부분 적용을 남기지 않는다.
        val reloaded = requireNotNull(boardRepository.findById(board.id))
        assertThat(reloaded.columns.first { it.id == other.id }.stateKeys).containsExactly("open")
    }

    @Test
    fun `stateKeys 에 중복이 있으면 400`() {
        // E9 — DB 는 UNIQUE(column_id, state_key) 로 막지만, 요청 자체의 모양 오류라
        //      409(경합)가 아니라 400(잘못된 요청)이어야 한다.
        val board = boardWithMergedColumn("COLD")
        val target = board.columns.first { it.stateKeys == listOf("open") }

        assertThatThrownBy {
            serviceWith().replaceColumnStates(board.id, target.id, listOf("open", "open"))
        }
            .isInstanceOf(DuplicateStateKeysException::class.java)
    }

    @Test
    fun `컬럼을 지우면 그 상태가 미매핑으로 돌아가고 이슈는 그대로다`() {
        // R10·J5 — 지라도 컬럼을 지우면 그 상태들이 Unmapped 패널로 돌아간다. 이슈는 손대지 않는다.
        val catalog = mockk<WorkflowStateCatalog>()
        every { catalog.listStates(ProjectKey.of("COLE"), null) } returns DEFAULT_STATES
        val service = serviceWith(catalog = catalog)
        val board = service.createBoard("COLE", "삭제 테스트 보드")
        val victim = board.columns.first { it.legacyStateKey == "closed" }

        service.deleteColumn(board.id, victim.id, UUID.randomUUID())

        val result = service.getBoard(board.id, UUID.randomUUID())
        assertThat(result.columns.map { it.column.id }).doesNotContain(victim.id)
        assertThat(result.unmappedStates.map { it.key }).containsExactly("closed")
    }

    @Test
    fun `마지막 컬럼도 지울 수 있다`() {
        // E8 — 컬럼 0개 보드는 healColumnsIfEmpty 가 다시 채운다. 삭제를 막을 이유가 없다.
        val catalog = mockk<WorkflowStateCatalog>()
        every { catalog.listStates(ProjectKey.of("COLF"), null) } returns DEFAULT_STATES
        val service = serviceWith(catalog = catalog)
        val board = service.createBoard("COLF", "전량 삭제 보드")

        board.columns.forEach { service.deleteColumn(board.id, it.id, UUID.randomUUID()) }

        assertThat(requireNotNull(boardRepository.findById(board.id)).columns).isEmpty()
    }

    // ── 게이트 2 BLOCKER 2건 (B1 경합 변환 · B2 ceo-3) ──────────────────────────

    @Test
    fun `사전 검사를 통과한 뒤 UNIQUE 에 걸리면 409 로 바뀐다`() {
        // B1 — 사전 검사(requireAssignableStates)는 사용자에게 「어느 컬럼이 쓰는지」를 주려고 있는 것이지
        //      제약을 대신하는 것이 아니다. 미매핑 상태 하나를 두 관리자가 서로 다른 컬럼에 동시에
        //      끌어다 놓으면 둘 다 「주인 없음」을 보고 통과하고, INSERT 하나가 UNIQUE 에 걸린다.
        //      그때 500 이 나가면 안 된다 — 같은 BC 의 BoardQuickFilterService 가 이미 그렇게 한다.
        val board = boardWithMergedColumn("RACEA")
        val target = board.columns.first { it.stateKeys == listOf("open") }

        val racing = mockk<BoardColumnStateRepository>()
        every { racing.findStateKeysByBoard(any()) } returns emptyMap()
        every { racing.replaceStates(any(), any(), any()) } throws
            DuplicateKeyException("duplicate key value violates unique constraint")

        assertThatThrownBy {
            serviceWith(columnStateRepo = racing)
                .replaceColumnStates(board.id, target.id, listOf("blocked"))
        }
            .isInstanceOf(StateAlreadyMappedException::class.java)
    }

    @Test
    fun `jOOQ 가 직접 던지는 제약 위반도 409 로 바뀐다`() {
        // B1 — Spring PersistenceExceptionTranslator 가 개입하지 않으면 jOOQ 가 자기 예외를 던진다.
        //      선례 BoardQuickFilterService.tryPersist 가 두 경로를 모두 잡는다.
        val board = boardWithMergedColumn("RACEB")
        val target = board.columns.first { it.stateKeys == listOf("open") }

        val racing = mockk<BoardColumnStateRepository>()
        every { racing.findStateKeysByBoard(any()) } returns emptyMap()
        every { racing.replaceStates(any(), any(), any()) } throws
            org.jooq.exception.IntegrityConstraintViolationException("uq violation")

        assertThatThrownBy {
            serviceWith(columnStateRepo = racing)
                .replaceColumnStates(board.id, target.id, listOf("blocked"))
        }
            .isInstanceOf(StateAlreadyMappedException::class.java)
    }

    @Test
    fun `컬럼 삭제가 사라지는 카드 수를 돌려준다`() {
        // B2 · R10(ceo-3) — 이슈는 안 건드리지만 **사용자가 보기엔 카드가 증발한다.**
        //      몇 장인지 모르면 되돌릴 판단을 할 수 없다. 되돌리려면 컬럼을 다시 만들고
        //      상태를 다시 매핑해야 한다.
        val catalog = mockk<WorkflowStateCatalog>()
        every { catalog.listStates(ProjectKey.of("BLAST"), null) } returns DEFAULT_STATES
        val board = serviceWith(catalog = catalog).createBoard("BLAST", "폭발 반경 보드")
        val victim = board.columns.first { it.legacyStateKey == "in-progress" }

        // in-progress 2장 · open 1장. 지우는 컬럼의 카드만 세어야 한다.
        val lookup =
            mockk<BoardIssueLookupPort>().also {
                every { it.listVisibleIssuesByProject(any(), any(), any()) } returns
                    BoardIssuePage(
                        issues =
                            listOf(
                                issueView("BLAST-1", "in-progress"),
                                issueView("BLAST-2", "in-progress"),
                                issueView("BLAST-3", "open"),
                            ),
                        truncated = false,
                    )
            }

        val removed =
            serviceWith(catalog = catalog, lookup = lookup)
                .deleteColumn(board.id, victim.id, UUID.randomUUID())

        assertThat(removed).isEqualTo(2)
    }

    @Test
    fun `상태 0개 컬럼을 지우면 사라지는 카드가 0 이다`() {
        // 매핑을 옮기는 중간 창의 컬럼이다(E1). 담긴 카드가 없으니 폭발 반경도 0 이다.
        val board = boardWithMergedColumn("BLASTZ")
        val empty = serviceWith().createColumn(board.id, name = "빈 컬럼", stateKeys = emptyList())

        val removed = serviceWith().deleteColumn(board.id, empty.id, UUID.randomUUID())

        assertThat(removed).isZero()
    }

    @Test
    fun `없는 컬럼을 지우면 404`() {
        val board = boardWithMergedColumn("COLG")

        assertThatThrownBy { serviceWith().deleteColumn(board.id, UUID.randomUUID(), UUID.randomUUID()) }
            .isInstanceOf(ResponseStatusException::class.java)
            .extracting("statusCode.value")
            .isEqualTo(404)
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
                stateKeys = listOf("open"),
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

    // ── (PR ④) 스프린트 술어를 LIMIT **앞**으로 — 포트에 issueKeys 를 실어 보낸다 ─────
    //
    // 결함. `listVisibleIssuesByProject` 가 `created_at DESC` 로 BOARD_CARD_FETCH_LIMIT+1 건을
    //       **먼저 자르고** 스프린트 필터가 그 뒤에 왔다. 활성 스프린트 이슈가 오래됐으면
    //       경고 없이 증발한다 — 사용자에게는 정상 시작한 스프린트가 빈 보드다.
    //
    // 여기서 재는 것은 **포트에 무엇을 넘겼는가** 하나다. 「LIMIT 앞에서 실제로 걸리는가」는
    // SQL 계층 책임이라 `BoardIssueLookupAdapterTest.S13` 이 Testcontainers 로 잰다.

    /** 포트 호출을 기록하는 스텁 — 넘어온 filter 전량과 호출 횟수를 남긴다. */
    private class RecordingLookup(
        private val issues: List<BoardIssueView> = emptyList(),
    ) : BoardIssueLookupPort {
        val filters = mutableListOf<BoardCardFilter>()

        override fun listVisibleIssuesByProject(
            projectKey: String,
            viewerUserId: UUID,
            filter: BoardCardFilter,
        ): BoardIssuePage {
            filters += filter
            return BoardIssuePage(issues = issues, truncated = false)
        }
    }

    @Test
    fun `스크럼 보드는 활성 스프린트 이슈 키를 BoardCardFilter_issueKeys 로 포트에 내려보낸다`() {
        val catalog = mockk<WorkflowStateCatalog>()
        every { catalog.listStates(ProjectKey.of("KEYS"), null) } returns DEFAULT_STATES
        val board = serviceWith(catalog = catalog).createBoard("KEYS", "스크럼 보드", BoardType.SCRUM)
        seedActiveSprint(board.id, "KEYS", listOf("KEYS-1", "KEYS-2"))
        val lookup = RecordingLookup(eightIssues("KEYS"))

        serviceWith(catalog = catalog, lookup = lookup)
            .getBoard(boardId = board.id, viewerUserId = UUID.randomUUID())

        assertThat(lookup.filters).hasSize(1)
        assertThat(lookup.filters[0].issueKeys).containsExactlyInAnyOrder("KEYS-1", "KEYS-2")
    }

    @Test
    fun `스크럼 보드의 issueKeys 는 호출자가 준 필터를 덮어쓰지 않고 함께 실린다`() {
        // 퀵필터·담당자 필터와 스프린트 술어는 AND 로 결합돼야 한다. copy 대신 새 VO 를 만들면
        // 호출자 필터가 조용히 사라지고 「필터를 걸었는데 안 걸린다」가 된다.
        val catalog = mockk<WorkflowStateCatalog>()
        every { catalog.listStates(ProjectKey.of("BOTH"), null) } returns DEFAULT_STATES
        val board = serviceWith(catalog = catalog).createBoard("BOTH", "스크럼 보드", BoardType.SCRUM)
        seedActiveSprint(board.id, "BOTH", listOf("BOTH-1"))
        val lookup = RecordingLookup(eightIssues("BOTH"))
        val callerFilter = BoardCardFilter(labels = listOf("bug"), includeUnassigned = true)

        serviceWith(catalog = catalog, lookup = lookup)
            .getBoard(boardId = board.id, viewerUserId = UUID.randomUUID(), filter = callerFilter)

        assertThat(lookup.filters[0].labels).containsExactly("bug")
        assertThat(lookup.filters[0].includeUnassigned).isTrue()
        assertThat(lookup.filters[0].issueKeys).containsExactly("BOTH-1")
    }

    @Test
    fun `칸반 보드는 issueKeys 를 비운 채 포트를 호출한다`() {
        // NFR-1. 칸반의 의미는 「프로젝트 이슈 전량」이고 이 PR 이 그것을 바꾸지 않는다.
        val catalog = mockk<WorkflowStateCatalog>()
        every { catalog.listStates(ProjectKey.of("KBFL"), null) } returns DEFAULT_STATES
        val board = serviceWith(catalog = catalog).createBoard("KBFL", "칸반 보드")
        seedActiveSprint(board.id, "KBFL", listOf("KBFL-1"))
        val lookup = RecordingLookup(eightIssues("KBFL"))

        serviceWith(catalog = catalog, lookup = lookup)
            .getBoard(boardId = board.id, viewerUserId = UUID.randomUUID())

        assertThat(lookup.filters).hasSize(1)
        assertThat(lookup.filters[0].issueKeys).isEmpty()
    }

    @Test
    fun `스크럼 보드에 활성 스프린트가 없으면 포트를 아예 호출하지 않는다`() {
        // 🛑 `issueKeys = emptyList()` 는 VO 규약상 **무필터**다. 그대로 넘기면 프로젝트 이슈를
        //    전량 조회하고 truncated 까지 참으로 올라온다 — 빈 스크럼 보드에 「일부가 누락됐다」가
        //    뜬다. 그 분기는 호출부가 단락시켜야 한다.
        val catalog = mockk<WorkflowStateCatalog>()
        every { catalog.listStates(ProjectKey.of("NOSP"), null) } returns DEFAULT_STATES
        val board = serviceWith(catalog = catalog).createBoard("NOSP", "스크럼 보드", BoardType.SCRUM)
        val lookup = RecordingLookup(eightIssues("NOSP"))

        val result =
            serviceWith(catalog = catalog, lookup = lookup)
                .getBoard(boardId = board.id, viewerUserId = UUID.randomUUID())

        assertThat(lookup.filters).isEmpty()
        assertThat(result.columns.sumOf { it.cards.size }).isEqualTo(0)
        assertThat(result.truncated).isFalse()
    }

    @Test
    fun `활성 스프린트에 이슈가 0건이면 포트를 아예 호출하지 않는다`() {
        // 위와 같은 이유. 「활성 스프린트 없음」과 「활성 스프린트가 비었음」은 같은 분기다.
        val catalog = mockk<WorkflowStateCatalog>()
        every { catalog.listStates(ProjectKey.of("EMSP"), null) } returns DEFAULT_STATES
        val board = serviceWith(catalog = catalog).createBoard("EMSP", "스크럼 보드", BoardType.SCRUM)
        val sprint = seedActiveSprint(board.id, "EMSP", emptyList())
        val lookup = RecordingLookup(eightIssues("EMSP"))

        val result =
            serviceWith(catalog = catalog, lookup = lookup)
                .getBoard(boardId = board.id, viewerUserId = UUID.randomUUID())

        assertThat(lookup.filters).isEmpty()
        assertThat(result.columns.sumOf { it.cards.size }).isEqualTo(0)
        assertThat(result.truncated).isFalse()
        // 활성 스프린트 자체는 여전히 응답에 실린다 — 빈 상태 문구가 E1/E2 를 구분하는 근거다.
        assertThat(result.activeSprint?.id).isEqualTo(sprint.id)
    }
}
