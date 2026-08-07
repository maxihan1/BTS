// BoardController MockMvc HTTP 통합 테스트 — 보드 REST API 권한 게이트 + 에러 매핑 (FR-BD-01 Task 9, FR-BD-03 Task 5)

package com.bts.agileplanning.web

import com.bts.agileplanning.application.BoardApplicationService
import com.bts.agileplanning.application.BoardPlacementResult
import com.bts.agileplanning.domain.Board
import com.bts.agileplanning.domain.BoardColumn
import com.bts.agileplanning.domain.PlacedColumn
import com.bts.agileplanning.domain.QuickFilter
import com.bts.agileplanning.repository.BoardRepository
import com.bts.shared.board.BoardCardFilter
import com.bts.shared.board.BoardIssueView
import com.bts.shared.board.BoardTransitionResult
import com.bts.shared.permission.IssuePermission
import com.bts.shared.permission.IssuePermissionResolver
import com.bts.shared.permission.IssueScope
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.MediaType
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.junit.jupiter.SpringExtension
import org.springframework.test.context.web.WebAppConfiguration
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.servlet.config.annotation.EnableWebMvc
import java.time.Instant
import java.util.UUID

/**
 * BoardController MockMvc 슬라이스 HTTP 통합 테스트.
 *
 * [BoardApplicationService] / [BoardRepository] 는 MockK stub 으로 대체하고
 * [IssuePermissionResolver] 는 테스트별로 allow/deny stub 을 주입한다.
 * Spring Security 컨텍스트는 [SecurityContextHolder] 에 직접 UUID 기반 Authentication 을 주입한다.
 *
 * ### 검증 케이스
 * - CREATE-1. POST /api/v1/boards 정상 → 201 + DataResponse 봉투
 * - CREATE-2. POST CREATE 권한 미충족 → 403 + ACCESS_DENIED
 * - CREATE-3. POST projectKey 누락 → 400 VALIDATION_FAILED
 * - CREATE-4. POST 워크플로우 미할당(서비스 422) → 422
 * - CREATE-5. POST 비인증 → 401
 * - GET-1. GET /api/v1/boards/{id} 정상 → 200 + 컬럼+카드
 * - GET-2. GET BROWSE 권한 미충족 → 403
 * - GET-3. GET 보드 미존재 → 404
 * - GET-4. GET path id 가 UUID 형식 아님 → 400 (catch-all 이 500 으로 삼키지 않음)
 * - GET-QF1. GET /api/v1/boards/{id} 정상 → 응답에 quickFilters 목록 포함(FR-UX-01 Task 7)
 * - GET-QF2. GET /api/v1/boards/{id} 퀵필터 없음 → quickFilters 빈 배열
 * - LIST-1. GET /api/v1/boards?projectKey= 정상 → 200 + 배열
 * - LIST-2. GET 목록 BROWSE 권한 미충족 → 403
 * - MOVE-1. POST move 정상 → 200 + 전이 결과 + columnId echo
 * - MOVE-2. POST move 보드 미존재 → 404
 * - MOVE-3. POST move 버전 충돌(서비스 409) → 409
 * - MOVE-4. POST move 보드-이슈 정합 위반(서비스 400) → 400
 * - MOVE-5. POST move body 손상 → 400 (catch-all 이 500 으로 삼키지 않음)
 * - ORDER-1. 권한 거부 시 서비스(리소스 조회)가 호출되지 않는다(존재 probe 차단)
 * - WIP-S1. PATCH /boards/{id}/columns/{columnId} {wipLimit:5} → 200 + wipLimit=5. GET 영속 확인.
 * - WIP-S2. wipLimit=3 컬럼 + 카드 5건 → GET 응답 wipExceeded=true.
 * - WIP-S3. {wipLimit:null} → 200 + wipLimit 해제.
 * - WIP-S4. PATCH /boards/{id} {swimlaneField:"ASSIGNEE"} → 200 + GET swimlaneField="ASSIGNEE" echo.
 * - WIP-E1. {wipLimit:0} → 400, {wipLimit:-1} → 400.
 * - WIP-E3. {swimlaneField:"EPIC"} 또는 {swimlaneField:"foo"} → 400.
 * - WIP-E4. 타 보드 소속 columnId로 wipLimit PATCH → 404.
 * - WIP-E5. 미존재 boardId로 두 PATCH → 404.
 * - WIP-E6. CREATE 권한 미충족 → 403 (실제 403 단언, vacuous 금지).
 * - WIP-E7. 미인증 → 401.
 * - NULL-1. 카드 nullable 필드(originalEstimateSeconds/epicKey/rank)가 null 이어도
 *   응답 키 자체는 존재한다(프론트 Zod `.nullable()` 계약 가드, FR-UX-14 F14 후속).
 */
@ExtendWith(SpringExtension::class)
@ContextConfiguration(classes = [BoardControllerIntegrationTest.TestMvcConfig::class])
@WebAppConfiguration
@Suppress("LargeClass") // 보드 REST API 전 시나리오를 단일 슬라이스 테스트로 커버한다
class BoardControllerIntegrationTest {
    /**
     * 테스트 전용 Spring MVC 최소 컨텍스트.
     *
     * [BoardController], [BoardExceptionHandler] 와 MockK stub Bean 을 등록한다.
     * [IssuePermissionResolver] 는 변경 가능한 [PermissionGate] 로 감싸 테스트별 allow/deny 를 토글한다.
     */
    @Configuration
    @EnableWebMvc
    open class TestMvcConfig {
        @Bean
        open fun boardApplicationService(): BoardApplicationService = mockk(relaxed = true)

        @Bean
        open fun boardRepository(): BoardRepository = mockk(relaxed = true)

        @Bean
        open fun permissionGate(): PermissionGate = PermissionGate()

        @Bean
        open fun boardController(
            service: BoardApplicationService,
            repository: BoardRepository,
            gate: PermissionGate,
        ): BoardController = BoardController(service, repository, gate)

        @Bean
        open fun boardExceptionHandler(): BoardExceptionHandler = BoardExceptionHandler()
    }

    /**
     * 테스트별 allow/deny 토글 + 전달 인자 캡처가 가능한 [IssuePermissionResolver] stub.
     *
     * boolean allow-all 만으로는 컨트롤러가 잘못된 권한코드/scope 를 넘겨도 그린이 되어
     * 권한 게이트의 의미를 검증하지 못한다(sec codereview-fix P2). 모든 [hasPermission] 호출의
     * (actorId, permission, scope) 를 [calls] 에 기록해 테스트가 실제 전달 인자를 단언하도록 한다.
     */
    open class PermissionGate : IssuePermissionResolver {
        /** false 면 모든 권한 판정을 거부한다. */
        var allowAll: Boolean = true

        /** [hasPermission] 호출마다 전달된 (actorId, permission, scope) 를 순서대로 기록한다. */
        val calls: MutableList<Triple<UUID, IssuePermission, IssueScope>> = mutableListOf()

        override fun hasPermission(
            actorId: UUID,
            permission: IssuePermission,
            scope: IssueScope,
        ): Boolean {
            calls.add(Triple(actorId, permission, scope))
            return allowAll
        }
    }

    @Autowired
    lateinit var webApplicationContext: WebApplicationContext

    @Autowired
    lateinit var boardApplicationService: BoardApplicationService

    @Autowired
    lateinit var boardRepository: BoardRepository

    @Autowired
    lateinit var permissionGate: PermissionGate

    lateinit var mockMvc: MockMvc

    private val mapper: ObjectMapper = ObjectMapper().registerKotlinModule()
    private val actorId: UUID = UUID.fromString("11111111-1111-1111-1111-111111111111")

    @BeforeEach
    fun setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build()
        // 컨텍스트 캐시로 MockK mock 이 테스트 간 공유되므로 호출 기록을 리셋해 verify 누적을 끊는다.
        clearMocks(boardApplicationService, boardRepository)
        permissionGate.allowAll = true
        permissionGate.calls.clear()
        val auth =
            UsernamePasswordAuthenticationToken(
                actorId.toString(),
                null,
                listOf(SimpleGrantedAuthority("ROLE_USER")),
            )
        SecurityContextHolder.getContext().authentication = auth
    }

    private fun sampleBoard(
        boardId: UUID = UUID.randomUUID(),
        projectKey: String = "BTS",
    ): Board =
        Board(
            id = boardId,
            projectKey = projectKey,
            name = "BTS 개발 보드",
            columns =
                listOf(
                    BoardColumn(UUID.randomUUID(), "open", "열림", "TODO", 0),
                    BoardColumn(UUID.randomUUID(), "in-progress", "진행 중", "IN_PROGRESS", 1),
                    BoardColumn(UUID.randomUUID(), "closed", "완료", "DONE", 2),
                ),
            createdAt = Instant.parse("2026-06-20T00:00:00Z"),
            updatedAt = Instant.parse("2026-06-20T00:00:00Z"),
        )

    // ── CREATE-1. POST 정상 → 201 ──────────────────────────────────────────────

    @Test
    fun `POST boards 정상 입력이면 201 + DataResponse 봉투에 컬럼 포함`() {
        val board = sampleBoard()
        every { boardApplicationService.createBoard("BTS", "BTS 개발 보드") } returns board

        val body = mapOf("projectKey" to "BTS", "name" to "BTS 개발 보드")

        mockMvc.perform(
            post("/api/v1/boards")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.data.boardId").value(board.id.toString()))
            .andExpect(jsonPath("$.data.projectKey").value("BTS"))
            .andExpect(jsonPath("$.data.columns.length()").value(3))
            .andExpect(jsonPath("$.data.columns[0].stateKey").value("open"))

        // 권한 게이트가 CREATE + Project(요청 projectKey) 로 판정됐는지 검증 (sec P2)
        assertThat(permissionGate.calls)
            .containsExactly(Triple(actorId, IssuePermission.CREATE, IssueScope.Project("BTS")))
    }

    // ── CREATE-2. POST CREATE 권한 미충족 → 403 ────────────────────────────────

    @Test
    fun `POST boards CREATE 권한이 없으면 403`() {
        permissionGate.allowAll = false
        val body = mapOf("projectKey" to "BTS", "name" to "보드")

        mockMvc.perform(
            post("/api/v1/boards")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.errorCode").value("AGILE_ACCESS_DENIED"))

        // 권한 거부 시 보드 생성 서비스가 호출되지 않아야 한다
        verify(exactly = 0) { boardApplicationService.createBoard(any(), any()) }
    }

    // ── CREATE-3. POST projectKey 누락 → 400 ──────────────────────────────────

    @Test
    fun `POST boards projectKey 누락이면 400`() {
        val body = mapOf("name" to "보드")

        mockMvc.perform(
            post("/api/v1/boards")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errorCode").value("AGILE_VALIDATION_FAILED"))
    }

    // ── CREATE-4. POST 서비스 422 → 422 ───────────────────────────────────────

    @Test
    fun `POST boards 워크플로우 미할당이면 422`() {
        every { boardApplicationService.createBoard("BTS", "보드") } throws
            ResponseStatusException(
                org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY,
                "AGILE_BOARD_WORKFLOW_NOT_ASSIGNED",
            )

        val body = mapOf("projectKey" to "BTS", "name" to "보드")

        mockMvc.perform(
            post("/api/v1/boards")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isUnprocessableEntity)
    }

    // ── CREATE-5. POST 비인증 → 401 ───────────────────────────────────────────

    @Test
    fun `POST boards 비인증이면 401`() {
        SecurityContextHolder.clearContext()
        val body = mapOf("projectKey" to "BTS", "name" to "보드")

        mockMvc.perform(
            post("/api/v1/boards")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.errorCode").value("AGILE_UNAUTHENTICATED"))
    }

    // ── GET-1. GET /{id} 정상 → 200 + 컬럼+카드 ───────────────────────────────

    @Test
    fun `GET boards id 정상이면 200 + 컬럼과 카드 반환`() {
        val board = sampleBoard()
        every { boardRepository.findById(board.id) } returns board
        every { boardApplicationService.getBoard(board.id, actorId, BoardCardFilter.EMPTY) } returns
            BoardPlacementResult(
                columns =
                    listOf(
                        PlacedColumn(
                            column = board.columns[0],
                            cards =
                                listOf(
                                    BoardIssueView(
                                        key = "BTS-1",
                                        summary = "첫 이슈",
                                        currentStateKey = "open",
                                        assigneeId = null,
                                        priority = 1,
                                        version = 1L,
                                        // FR-UX-14 B2 — 카드 밀도 3필드가 JSON 까지 나가는지 확인하기 위한 값.
                                        typeKey = "bug",
                                        labels = listOf("urgent", "api"),
                                        originalEstimateSeconds = 3600,
                                    ),
                                ),
                        ),
                        PlacedColumn(column = board.columns[1], cards = emptyList()),
                        PlacedColumn(column = board.columns[2], cards = emptyList()),
                    ),
                truncated = false,
                unplacedCount = 0,
            )

        mockMvc.perform(get("/api/v1/boards/${board.id}").accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.boardId").value(board.id.toString()))
            .andExpect(jsonPath("$.data.projectKey").value("BTS"))
            .andExpect(jsonPath("$.data.columns.length()").value(3))
            .andExpect(jsonPath("$.data.columns[0].cards.length()").value(1))
            .andExpect(jsonPath("$.data.columns[0].cards[0].issueKey").value("BTS-1"))
            // priority 는 PRIORITY 스윔레인(FR-BD-03 D6) 그룹화 근거로 재노출 — Maxi 확정 (시드 priority=1)
            .andExpect(jsonPath("$.data.columns[0].cards[0].priority").value(1))
            // FR-UX-14 B2 — 카드 밀도 3필드. 위에서 issueKey 로 신원을 고정한 뒤 값을 본다
            // (인덱스만으로 지목하면 순서가 바뀔 때 엉뚱한 카드를 검증하고도 통과할 수 있다).
            .andExpect(jsonPath("$.data.columns[0].cards[0].typeKey").value("bug"))
            .andExpect(jsonPath("$.data.columns[0].cards[0].labels[0]").value("urgent"))
            .andExpect(jsonPath("$.data.columns[0].cards[0].labels[1]").value("api"))
            .andExpect(jsonPath("$.data.columns[0].cards[0].originalEstimateSeconds").value(3600))
            // truncated/unplacedCount 신호 필드 단언 (P2)
            .andExpect(jsonPath("$.data.truncated").value(false))
            .andExpect(jsonPath("$.data.unplacedCount").value(0))

        // 권한 게이트가 BROWSE + Project(보드 projectKey) 로 판정됐는지 검증 (sec P2)
        assertThat(permissionGate.calls)
            .containsExactly(Triple(actorId, IssuePermission.BROWSE, IssueScope.Project("BTS")))
    }

    @Test
    fun `GET boards id truncated=true 이면 응답에 truncated=true 가 포함된다`() {
        val board = sampleBoard()
        every { boardRepository.findById(board.id) } returns board
        every { boardApplicationService.getBoard(board.id, actorId, BoardCardFilter.EMPTY) } returns
            BoardPlacementResult(
                columns =
                    listOf(
                        PlacedColumn(column = board.columns[0], cards = emptyList()),
                        PlacedColumn(column = board.columns[1], cards = emptyList()),
                        PlacedColumn(column = board.columns[2], cards = emptyList()),
                    ),
                truncated = true,
                unplacedCount = 5,
            )

        mockMvc.perform(get("/api/v1/boards/${board.id}").accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.truncated").value(true))
            .andExpect(jsonPath("$.data.unplacedCount").value(5))
    }

    // ── GET-2. GET BROWSE 권한 미충족 → 403 ───────────────────────────────────

    @Test
    fun `GET boards id BROWSE 권한이 없으면 403`() {
        val board = sampleBoard()
        every { boardRepository.findById(board.id) } returns board
        permissionGate.allowAll = false

        mockMvc.perform(get("/api/v1/boards/${board.id}").accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.errorCode").value("AGILE_ACCESS_DENIED"))

        // 권한 거부 시 카드 배치(서비스 getBoard)가 호출되지 않아야 한다
        verify(exactly = 0) { boardApplicationService.getBoard(any(), any(), any()) }
    }

    // ── GET-3. GET 보드 미존재 → 404 ──────────────────────────────────────────

    @Test
    fun `GET boards id 보드 미존재면 404`() {
        val boardId = UUID.randomUUID()
        every { boardRepository.findById(boardId) } returns null

        mockMvc.perform(get("/api/v1/boards/$boardId").accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.errorCode").value("AGILE_BOARD_NOT_FOUND"))
    }

    // ── GET-4. GET path id 가 UUID 형식 아님 → 400 (catch-all 변질 차단) ─────────

    @Test
    fun `GET boards id 가 UUID 형식이 아니면 400`() {
        mockMvc.perform(get("/api/v1/boards/not-a-uuid").accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errorCode").value("AGILE_VALIDATION_FAILED"))
    }

    // ── GET-QF1. GET /{id} 정상이면 quickFilters 포함 (FR-UX-01 Task 7) ───────

    @Test
    fun `GET boards id 정상이면 응답에 quickFilters 목록이 포함된다`() {
        val board = sampleBoard()
        val quickFilters =
            listOf(
                QuickFilter(id = UUID.randomUUID(), boardId = board.id, name = "내 버그", query = "label=bug"),
                QuickFilter(id = UUID.randomUUID(), boardId = board.id, name = "긴급", query = "label=urgent"),
            )
        every { boardRepository.findById(board.id) } returns board
        every { boardApplicationService.getBoard(board.id, actorId, BoardCardFilter.EMPTY) } returns
            BoardPlacementResult(
                columns = board.columns.map { PlacedColumn(it, emptyList()) },
                truncated = false,
                unplacedCount = 0,
                quickFilters = quickFilters,
            )

        mockMvc.perform(get("/api/v1/boards/${board.id}").accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.quickFilters.length()").value(2))
            .andExpect(jsonPath("$.data.quickFilters[0].filterId").value(quickFilters[0].id.toString()))
            .andExpect(jsonPath("$.data.quickFilters[0].name").value("내 버그"))
            .andExpect(jsonPath("$.data.quickFilters[1].query").value("label=urgent"))
    }

    // ── GET-QF2. GET /{id} 퀵필터 없음 → quickFilters 빈 배열 ────────────────

    @Test
    fun `GET boards id 퀵필터가 없으면 quickFilters 는 빈 배열이다`() {
        val board = sampleBoard()
        every { boardRepository.findById(board.id) } returns board
        every { boardApplicationService.getBoard(board.id, actorId, BoardCardFilter.EMPTY) } returns
            BoardPlacementResult(
                columns = board.columns.map { PlacedColumn(it, emptyList()) },
                truncated = false,
                unplacedCount = 0,
            )

        mockMvc.perform(get("/api/v1/boards/${board.id}").accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.quickFilters").isArray)
            .andExpect(jsonPath("$.data.quickFilters.length()").value(0))
    }

    // ── LIST-1. GET ?projectKey= 정상 → 200 ───────────────────────────────────

    @Test
    fun `GET boards projectKey 목록이면 200 + 배열`() {
        every { boardApplicationService.listBoards("BTS") } returns listOf(sampleBoard(projectKey = "BTS"))

        mockMvc.perform(
            get("/api/v1/boards").param("projectKey", "BTS").accept(MediaType.APPLICATION_JSON),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data").isArray)
            .andExpect(jsonPath("$.data[0].projectKey").value("BTS"))

        verify { boardApplicationService.listBoards("BTS") }

        // 권한 게이트가 BROWSE + Project(요청 projectKey) 로 판정됐는지 검증 (sec P2)
        assertThat(permissionGate.calls)
            .containsExactly(Triple(actorId, IssuePermission.BROWSE, IssueScope.Project("BTS")))
    }

    // ── LIST-2. GET 목록 BROWSE 권한 미충족 → 403 ─────────────────────────────

    @Test
    fun `GET boards 목록 BROWSE 권한이 없으면 403`() {
        permissionGate.allowAll = false

        mockMvc.perform(
            get("/api/v1/boards").param("projectKey", "BTS").accept(MediaType.APPLICATION_JSON),
        )
            .andExpect(status().isForbidden)

        verify(exactly = 0) { boardApplicationService.listBoards(any()) }
    }

    // ── MOVE-1. POST move 정상 → 200 + columnId echo ──────────────────────────

    @Test
    fun `POST move 정상이면 200 + 전이 결과와 columnId echo`() {
        val board = sampleBoard()
        val toColumnId = board.columns[1].id
        every { boardRepository.findById(board.id) } returns board
        every {
            boardApplicationService.moveCard(
                boardId = board.id,
                issueKey = "BTS-1",
                actorUserId = actorId,
                toColumnId = toColumnId,
                expectedVersion = 3L,
                resolutionId = null,
            )
        } returns BoardTransitionResult(issueKey = "BTS-1", currentStateKey = "in-progress", version = 4L)

        val body = mapOf("toColumnId" to toColumnId.toString(), "expectedVersion" to 3)

        mockMvc.perform(
            post("/api/v1/boards/${board.id}/cards/BTS-1/move")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.issueKey").value("BTS-1"))
            .andExpect(jsonPath("$.data.currentStateKey").value("in-progress"))
            .andExpect(jsonPath("$.data.version").value(4))
            .andExpect(jsonPath("$.data.columnId").value(toColumnId.toString()))

        // move 의 보드 접근 게이트가 BROWSE + Project(보드 projectKey) 로 판정됐는지 검증 (sec P2).
        // 이동 자체의 TRANSITION 강제는 전이 포트(IssueTransitionAdapter)가 담당한다.
        assertThat(permissionGate.calls)
            .containsExactly(Triple(actorId, IssuePermission.BROWSE, IssueScope.Project("BTS")))
    }

    // ── MOVE-2. POST move 보드 미존재 → 404 ───────────────────────────────────

    @Test
    fun `POST move 보드 미존재면 404`() {
        val boardId = UUID.randomUUID()
        every { boardRepository.findById(boardId) } returns null

        val body = mapOf("toColumnId" to UUID.randomUUID().toString(), "expectedVersion" to 1)

        mockMvc.perform(
            post("/api/v1/boards/$boardId/cards/BTS-1/move")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.errorCode").value("AGILE_BOARD_NOT_FOUND"))
    }

    // ── MOVE-3. POST move 버전 충돌(서비스 409) → 409 ─────────────────────────

    @Test
    fun `POST move 버전 충돌이면 409`() {
        val board = sampleBoard()
        val toColumnId = board.columns[1].id
        every { boardRepository.findById(board.id) } returns board
        every {
            boardApplicationService.moveCard(any(), any(), any(), any(), any(), any())
        } throws
            ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT, "version conflict")

        val body = mapOf("toColumnId" to toColumnId.toString(), "expectedVersion" to 99)

        mockMvc.perform(
            post("/api/v1/boards/${board.id}/cards/BTS-1/move")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isConflict)
    }

    // ── MOVE-4. POST move 보드-이슈 정합 위반(서비스 400) → 400 ────────────────

    @Test
    fun `POST move 보드-이슈 정합 위반이면 400`() {
        val board = sampleBoard()
        val toColumnId = board.columns[1].id
        every { boardRepository.findById(board.id) } returns board
        every {
            boardApplicationService.moveCard(any(), any(), any(), any(), any(), any())
        } throws
            ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, "mismatch")

        val body = mapOf("toColumnId" to toColumnId.toString(), "expectedVersion" to 1)

        mockMvc.perform(
            post("/api/v1/boards/${board.id}/cards/OTHER-1/move")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isBadRequest)
    }

    // ── MOVE-5. POST move body 손상 → 400 (catch-all 변질 차단) ────────────────

    @Test
    fun `POST move 요청 본문이 손상이면 400`() {
        val board = sampleBoard()
        every { boardRepository.findById(board.id) } returns board

        mockMvc.perform(
            post("/api/v1/boards/${board.id}/cards/BTS-1/move")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{ not valid json"),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errorCode").value("AGILE_VALIDATION_FAILED"))
    }

    // ── ORDER-1. 권한 거부 시 리소스 조회(서비스)가 호출되지 않는다 ─────────────

    @Test
    fun `생성 권한 거부 시 actor 추출 후 권한 게이트에서 차단되어 서비스 미호출`() {
        permissionGate.allowAll = false
        val body = mapOf("projectKey" to "BTS", "name" to "보드")

        mockMvc.perform(
            post("/api/v1/boards")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isForbidden)

        verify(exactly = 0) { boardApplicationService.createBoard(any(), any()) }
    }

    // ── SCOPE-1. 권한 게이트가 올바른 permission/scope 로만 판정됨 (가짜그린 차단) ───

    /**
     * 일부러 검증 — 컨트롤러가 잘못된 권한코드/scope 를 넘기면 이 테스트가 실패해야 한다(sec P2).
     *
     * 보드 생성은 반드시 [IssuePermission.CREATE] + [IssueScope.Project] 여야 하며,
     * BROWSE 나 Issue scope 로 판정되면 회귀로 간주한다. 권한코드/scope 가 약화돼도
     * allow-all boolean stub 으로는 그린이 되던 갭을 막는다.
     */
    @Test
    fun `보드 생성 권한 판정은 BROWSE 나 Issue scope 가 아니라 CREATE + Project scope 여야 한다`() {
        val board = sampleBoard()
        every { boardApplicationService.createBoard("BTS", "보드") } returns board
        val body = mapOf("projectKey" to "BTS", "name" to "보드")

        mockMvc.perform(
            post("/api/v1/boards")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        ).andExpect(status().isCreated)

        assertThat(permissionGate.calls).hasSize(1)
        val (_, permission, scope) = permissionGate.calls.single()
        assertThat(permission).isEqualTo(IssuePermission.CREATE)
        assertThat(permission).isNotEqualTo(IssuePermission.BROWSE)
        assertThat(scope).isEqualTo(IssueScope.Project("BTS"))
        assertThat(scope).isNotInstanceOf(IssueScope.Issue::class.java)
    }

    // ── FILTER-1. 필터 파라미터 없음 → EMPTY 필터로 서비스 호출 (EC2 회귀) ──────

    @Test
    fun `FILTER-1 필터 파라미터가 없으면 EMPTY 필터로 서비스를 호출한다 (EC2 회귀)`() {
        val board = sampleBoard()
        every { boardRepository.findById(board.id) } returns board
        every { boardApplicationService.getBoard(board.id, actorId, BoardCardFilter.EMPTY) } returns
            BoardPlacementResult(
                columns = board.columns.map { PlacedColumn(it, emptyList()) },
                truncated = false,
                unplacedCount = 0,
            )

        mockMvc.perform(get("/api/v1/boards/${board.id}").accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk)

        verify { boardApplicationService.getBoard(board.id, actorId, BoardCardFilter.EMPTY) }
    }

    // ── FILTER-2. 파라미터 있음 → 파싱된 filter 로 서비스 호출, 캡처 단언 ─────

    @Test
    fun `FILTER-2 assignee·label·component 파라미터가 있으면 파싱된 filter 로 서비스를 호출한다`() {
        val board = sampleBoard()
        val assigneeUuid = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
        val componentUuid = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc")
        val expectedFilter =
            BoardCardFilter(
                assigneeIds = listOf(assigneeUuid),
                includeUnassigned = true,
                labels = listOf("bug"),
                componentIds = listOf(componentUuid),
            )

        every { boardRepository.findById(board.id) } returns board
        every { boardApplicationService.getBoard(board.id, actorId, expectedFilter) } returns
            BoardPlacementResult(
                columns = board.columns.map { PlacedColumn(it, emptyList()) },
                truncated = false,
                unplacedCount = 0,
            )

        mockMvc.perform(
            get("/api/v1/boards/${board.id}")
                .param("assignee", assigneeUuid.toString())
                .param("assignee", "unassigned")
                .param("label", "bug")
                .param("component", componentUuid.toString())
                .accept(MediaType.APPLICATION_JSON),
        ).andExpect(status().isOk)

        verify { boardApplicationService.getBoard(board.id, actorId, expectedFilter) }
    }

    // ── FILTER-3. 형식오류 assignee → 400 ────────────────────────────────────

    @Test
    fun `FILTER-3 assignee 파라미터가 UUID 형식이 아니면 400`() {
        val board = sampleBoard()
        every { boardRepository.findById(board.id) } returns board

        mockMvc.perform(
            get("/api/v1/boards/${board.id}")
                .param("assignee", "not-a-uuid")
                .accept(MediaType.APPLICATION_JSON),
        ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errorCode").value("AGILE_VALIDATION_FAILED"))
    }

    // ── FILTER-4. 형식오류 component → 400 ───────────────────────────────────

    @Test
    fun `FILTER-4 component 파라미터가 UUID 형식이 아니면 400`() {
        val board = sampleBoard()
        every { boardRepository.findById(board.id) } returns board

        mockMvc.perform(
            get("/api/v1/boards/${board.id}")
                .param("component", "foo")
                .accept(MediaType.APPLICATION_JSON),
        ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errorCode").value("AGILE_VALIDATION_FAILED"))
    }

    // ── WIP-S1. PATCH columns wipLimit=5 → 200, GET 영속 확인 ─────────────────

    @Test
    fun `WIP-S1 PATCH wipLimit=5 이면 200 + 응답 wipLimit=5, 이후 GET에서도 반영된다`() {
        val board = sampleBoard()
        val col = board.columns[1]
        val updatedCol = col.copy(wipLimit = 5)
        val updatedBoard = board.copy(columns = listOf(board.columns[0], updatedCol, board.columns[2]))

        every { boardRepository.findById(board.id) } returns board
        every {
            boardApplicationService.updateColumnWipLimit(board.id, col.id, 5)
        } returns updatedCol

        val body = mapOf("wipLimit" to 5)

        mockMvc.perform(
            patch("/api/v1/boards/${board.id}/columns/${col.id}")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.columnId").value(col.id.toString()))
            .andExpect(jsonPath("$.data.wipLimit").value(5))

        // CREATE 권한으로 판정됐는지 확인
        assertThat(permissionGate.calls)
            .containsExactly(Triple(actorId, IssuePermission.CREATE, IssueScope.Project("BTS")))

        // GET에서도 반영 확인 — GET은 별도 stub 경로
        every { boardApplicationService.getBoard(board.id, actorId, BoardCardFilter.EMPTY) } returns
            BoardPlacementResult(
                columns = updatedBoard.columns.map { PlacedColumn(it, emptyList()) },
                truncated = false,
                unplacedCount = 0,
            )
        every { boardRepository.findById(board.id) } returns updatedBoard

        mockMvc.perform(get("/api/v1/boards/${board.id}").accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.columns[1].wipLimit").value(5))
    }

    // ── WIP-S2. wipLimit=3, 카드 5건 → wipExceeded=true ──────────────────────

    @Test
    fun `WIP-S2 wipLimit=3인 컬럼에 카드 5건이면 GET 응답 wipExceeded=true`() {
        val board =
            Board(
                id = UUID.randomUUID(),
                projectKey = "BTS",
                name = "BTS 보드",
                columns =
                    listOf(
                        BoardColumn(UUID.randomUUID(), "open", "열림", "TODO", 0),
                        BoardColumn(UUID.randomUUID(), "in-progress", "진행 중", "IN_PROGRESS", 1, wipLimit = 3),
                        BoardColumn(UUID.randomUUID(), "closed", "완료", "DONE", 2),
                    ),
                createdAt = Instant.parse("2026-06-22T00:00:00Z"),
                updatedAt = Instant.parse("2026-06-22T00:00:00Z"),
            )
        val cards =
            (1..5).map { i ->
                BoardIssueView(
                    key = "BTS-$i",
                    summary = "이슈 $i",
                    currentStateKey = "in-progress",
                    assigneeId = null,
                    priority = i,
                    version = 1L,
                    typeKey = "task",
                )
            }

        every { boardRepository.findById(board.id) } returns board
        every { boardApplicationService.getBoard(board.id, actorId, BoardCardFilter.EMPTY) } returns
            BoardPlacementResult(
                columns =
                    listOf(
                        PlacedColumn(board.columns[0], emptyList()),
                        PlacedColumn(board.columns[1], cards),
                        PlacedColumn(board.columns[2], emptyList()),
                    ),
                truncated = false,
                unplacedCount = 0,
            )

        mockMvc.perform(get("/api/v1/boards/${board.id}").accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.columns[1].wipLimit").value(3))
            .andExpect(jsonPath("$.data.columns[1].wipExceeded").value(true))
            // FR-UX-14 B2 — 라벨 없는 카드는 null 이 아니라 **빈 배열**로 직렬화된다(FR8).
            // 프론트가 곧바로 순회하므로 null 이면 화면이 터진다. 신원을 먼저 고정하고 본다.
            .andExpect(jsonPath("$.data.columns[1].cards[0].issueKey").value("BTS-1"))
            .andExpect(jsonPath("$.data.columns[1].cards[0].labels").isArray)
            .andExpect(jsonPath("$.data.columns[1].cards[0].labels.length()").value(0))
    }

    // ── WIP-S3. {wipLimit:null} → 200 + 해제 ─────────────────────────────────

    @Test
    fun `WIP-S3 wipLimit=null 이면 200 + wipLimit 해제`() {
        val board = sampleBoard()
        val col = board.columns[0].copy(wipLimit = 3)
        val boardWithWip = board.copy(columns = listOf(col, board.columns[1], board.columns[2]))
        val releasedCol = col.copy(wipLimit = null)

        every { boardRepository.findById(boardWithWip.id) } returns boardWithWip
        every {
            boardApplicationService.updateColumnWipLimit(boardWithWip.id, col.id, null)
        } returns releasedCol

        val body = mapOf("wipLimit" to null)

        mockMvc.perform(
            patch("/api/v1/boards/${boardWithWip.id}/columns/${col.id}")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.wipLimit").doesNotExist())
    }

    // ── WIP-S4. PATCH swimlaneField=ASSIGNEE → 200 + echo ────────────────────

    @Test
    fun `WIP-S4 PATCH swimlaneField=ASSIGNEE 이면 200 + GET 응답에서 swimlaneField=ASSIGNEE 가 반환된다`() {
        val board = sampleBoard()
        val updatedBoard =
            board.copy(swimlaneField = com.bts.agileplanning.domain.SwimlaneField.ASSIGNEE)

        every { boardRepository.findById(board.id) } returns board
        every {
            boardApplicationService.updateSwimlaneField(board.id, "ASSIGNEE")
        } returns updatedBoard

        val body = mapOf("swimlaneField" to "ASSIGNEE")

        mockMvc.perform(
            patch("/api/v1/boards/${board.id}")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.boardId").value(board.id.toString()))
            .andExpect(jsonPath("$.data.swimlaneField").value("ASSIGNEE"))

        // CREATE 권한으로 판정됐는지 확인
        assertThat(permissionGate.calls)
            .containsExactly(Triple(actorId, IssuePermission.CREATE, IssueScope.Project("BTS")))
    }

    // ── WIP-E1. {wipLimit:0} → 400, {wipLimit:-1} → 400 ─────────────────────

    @Test
    fun `WIP-E1 wipLimit=0 이면 400`() {
        val board = sampleBoard()
        every { boardRepository.findById(board.id) } returns board

        val body = mapOf("wipLimit" to 0)

        mockMvc.perform(
            patch("/api/v1/boards/${board.id}/columns/${board.columns[0].id}")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errorCode").value("AGILE_VALIDATION_FAILED"))
    }

    @Test
    fun `WIP-E1 wipLimit=-1 이면 400`() {
        val board = sampleBoard()
        every { boardRepository.findById(board.id) } returns board

        val body = mapOf("wipLimit" to -1)

        mockMvc.perform(
            patch("/api/v1/boards/${board.id}/columns/${board.columns[0].id}")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errorCode").value("AGILE_VALIDATION_FAILED"))
    }

    // ── WIP-E3. 유효하지 않은 swimlaneField → 400 ────────────────────────────

    @Test
    fun `WIP-E3 swimlaneField=EPIC 이면 서비스가 400 을 던지고 응답도 400`() {
        val board = sampleBoard()
        every { boardRepository.findById(board.id) } returns board
        every {
            boardApplicationService.updateSwimlaneField(board.id, "EPIC")
        } throws ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, "알 수 없는 swimlaneField")

        val body = mapOf("swimlaneField" to "EPIC")

        mockMvc.perform(
            patch("/api/v1/boards/${board.id}")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `WIP-E3 swimlaneField=foo 이면 서비스가 400 을 던지고 응답도 400`() {
        val board = sampleBoard()
        every { boardRepository.findById(board.id) } returns board
        every {
            boardApplicationService.updateSwimlaneField(board.id, "foo")
        } throws ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, "알 수 없는 swimlaneField")

        val body = mapOf("swimlaneField" to "foo")

        mockMvc.perform(
            patch("/api/v1/boards/${board.id}")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isBadRequest)
    }

    // ── WIP-E4. 타 보드 소속 columnId → 404 ──────────────────────────────────

    @Test
    fun `WIP-E4 타 보드 소속 columnId로 wipLimit PATCH 이면 404`() {
        val board = sampleBoard()
        val otherColumnId = UUID.randomUUID()

        every { boardRepository.findById(board.id) } returns board
        every {
            boardApplicationService.updateColumnWipLimit(board.id, otherColumnId, 3)
        } throws ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "column not found")

        val body = mapOf("wipLimit" to 3)

        mockMvc.perform(
            patch("/api/v1/boards/${board.id}/columns/$otherColumnId")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.errorCode").value("AGILE_BOARD_NOT_FOUND"))
    }

    // ── WIP-E5. 미존재 boardId → 404 ─────────────────────────────────────────

    @Test
    fun `WIP-E5 미존재 boardId로 wipLimit PATCH 이면 404`() {
        val boardId = UUID.randomUUID()
        val columnId = UUID.randomUUID()
        every { boardRepository.findById(boardId) } returns null

        val body = mapOf("wipLimit" to 3)

        mockMvc.perform(
            patch("/api/v1/boards/$boardId/columns/$columnId")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.errorCode").value("AGILE_BOARD_NOT_FOUND"))
    }

    @Test
    fun `WIP-E5 미존재 boardId로 swimlaneField PATCH 이면 404`() {
        val boardId = UUID.randomUUID()
        every { boardRepository.findById(boardId) } returns null

        val body = mapOf("swimlaneField" to "ASSIGNEE")

        mockMvc.perform(
            patch("/api/v1/boards/$boardId")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.errorCode").value("AGILE_BOARD_NOT_FOUND"))
    }

    // ── WIP-E6. CREATE 권한 미충족 → 403 (실제 status 단언, vacuous 금지) ────────

    @Test
    fun `WIP-E6 CREATE 권한 없으면 wipLimit PATCH 시 403 반환되고 서비스 미호출`() {
        val board = sampleBoard()
        every { boardRepository.findById(board.id) } returns board
        // 권한 stub 을 false 로 명시 설정
        permissionGate.allowAll = false

        val body = mapOf("wipLimit" to 5)

        mockMvc.perform(
            patch("/api/v1/boards/${board.id}/columns/${board.columns[0].id}")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            // 실제 403 응답을 단언 (vacuous 금지)
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.errorCode").value("AGILE_ACCESS_DENIED"))

        // 권한 거부 시 서비스가 호출되지 않아야 한다
        verify(exactly = 0) { boardApplicationService.updateColumnWipLimit(any(), any(), any()) }
    }

    @Test
    fun `WIP-E6 CREATE 권한 없으면 swimlaneField PATCH 시 403 반환되고 서비스 미호출`() {
        val board = sampleBoard()
        every { boardRepository.findById(board.id) } returns board
        // 권한 stub 을 false 로 명시 설정
        permissionGate.allowAll = false

        val body = mapOf("swimlaneField" to "ASSIGNEE")

        mockMvc.perform(
            patch("/api/v1/boards/${board.id}")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            // 실제 403 응답을 단언 (vacuous 금지)
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.errorCode").value("AGILE_ACCESS_DENIED"))

        verify(exactly = 0) { boardApplicationService.updateSwimlaneField(any(), any()) }
    }

    // ── WIP-E7. 미인증 → 401 ─────────────────────────────────────────────────

    @Test
    fun `WIP-E7 미인증이면 wipLimit PATCH 시 401`() {
        SecurityContextHolder.clearContext()
        val board = sampleBoard()

        val body = mapOf("wipLimit" to 5)

        mockMvc.perform(
            patch("/api/v1/boards/${board.id}/columns/${board.columns[0].id}")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.errorCode").value("AGILE_UNAUTHENTICATED"))
    }

    @Test
    fun `WIP-E7 미인증이면 swimlaneField PATCH 시 401`() {
        SecurityContextHolder.clearContext()
        val board = sampleBoard()

        val body = mapOf("swimlaneField" to "ASSIGNEE")

        mockMvc.perform(
            patch("/api/v1/boards/${board.id}")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.errorCode").value("AGILE_UNAUTHENTICATED"))
    }

    // ── NULL-1. 카드 nullable 필드가 null 이어도 키는 존재한다 (계약 가드) ────────

    /**
     * 프론트 Zod 스키마(`apps/web/src/api/boards.ts`)가 `originalEstimateSeconds` 를
     * `z.number().int().nullable()` 로 선언한다 — `.nullish()` 가 아니라 **키가 반드시
     * 존재**해야 하고 값만 null 일 수 있다는 계약이다. `agile-planning` 모듈에는
     * `@JsonInclude` 관용구가 0건이라 Jackson 기본값(null 도 키와 함께 직렬화)에
     * 기대고 있는데, 누군가 이 DTO 에 `@JsonInclude(NON_NULL)` 을 붙이면(다른 3개
     * 모듈이 이미 이 관용구를 쓴다) 키 자체가 사라져 프론트가 파싱 단계에서 깨진다.
     * 이 회귀를 값이 아니라 **키의 존재**로 단언해 막는다.
     *
     * `jsonPath(...).value(null)` 은 키가 아예 없어도 통과할 수 있어 가드가 공허해진다
     * (JsonPath 읽기가 실패해도 Spring 매처가 null 을 돌려주기 때문). 대신
     * `hasJsonPath()` 를 쓴다 — Spring `JsonPathExpectationsHelper.hasJsonPath()` 는
     * 내부적으로 JsonPath 읽기가 `PathNotFoundException` 을 던지는지로만 판정하므로,
     * 값이 null 이어도 키가 존재하면 통과하고 키 자체가 없으면 실패한다
     * (바이트코드 실측 + `@JsonInclude(NON_NULL)` 임시 부여 실험으로 확인 — FR-UX-14 F14 후속).
     * `epicKey`·`rank` 는 프론트가 이미 `.nullish()` 로 방어하지만 같은 DTO 의 nullable
     * 필드라 정보성으로 함께 못박는다.
     */
    @Test
    fun `GET boards id 카드 nullable 필드가 null 이어도 originalEstimateSeconds epicKey rank 키는 존재한다`() {
        val board = sampleBoard()
        every { boardRepository.findById(board.id) } returns board
        every { boardApplicationService.getBoard(board.id, actorId, BoardCardFilter.EMPTY) } returns
            BoardPlacementResult(
                columns =
                    listOf(
                        PlacedColumn(
                            column = board.columns[0],
                            cards =
                                listOf(
                                    BoardIssueView(
                                        key = "BTS-1",
                                        summary = "널 필드 이슈",
                                        currentStateKey = "open",
                                        assigneeId = null,
                                        priority = 1,
                                        version = 1L,
                                        typeKey = "task",
                                        epicKey = null,
                                        rank = null,
                                        originalEstimateSeconds = null,
                                    ),
                                ),
                        ),
                        PlacedColumn(column = board.columns[1], cards = emptyList()),
                        PlacedColumn(column = board.columns[2], cards = emptyList()),
                    ),
                truncated = false,
                unplacedCount = 0,
            )

        mockMvc.perform(get("/api/v1/boards/${board.id}").accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk)
            // 신원을 먼저 고정한 뒤 nullable 필드 키 존재를 본다
            .andExpect(jsonPath("$.data.columns[0].cards[0].issueKey").value("BTS-1"))
            .andExpect(jsonPath("$.data.columns[0].cards[0].originalEstimateSeconds").hasJsonPath())
            .andExpect(jsonPath("$.data.columns[0].cards[0].epicKey").hasJsonPath())
            .andExpect(jsonPath("$.data.columns[0].cards[0].rank").hasJsonPath())
    }
}
