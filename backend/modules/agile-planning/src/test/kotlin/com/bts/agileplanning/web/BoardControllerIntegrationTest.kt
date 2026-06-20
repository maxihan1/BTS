// BoardController MockMvc HTTP 통합 테스트 — 보드 REST API 권한 게이트 + 에러 매핑 (FR-BD-01 Task 9)

package com.bts.agileplanning.web

import com.bts.agileplanning.application.BoardApplicationService
import com.bts.agileplanning.domain.Board
import com.bts.agileplanning.domain.BoardColumn
import com.bts.agileplanning.domain.PlacedColumn
import com.bts.agileplanning.repository.BoardRepository
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
 * - LIST-1. GET /api/v1/boards?projectKey= 정상 → 200 + 배열
 * - LIST-2. GET 목록 BROWSE 권한 미충족 → 403
 * - MOVE-1. POST move 정상 → 200 + 전이 결과 + columnId echo
 * - MOVE-2. POST move 보드 미존재 → 404
 * - MOVE-3. POST move 버전 충돌(서비스 409) → 409
 * - MOVE-4. POST move 보드-이슈 정합 위반(서비스 400) → 400
 * - MOVE-5. POST move body 손상 → 400 (catch-all 이 500 으로 삼키지 않음)
 * - ORDER-1. 권한 거부 시 서비스(리소스 조회)가 호출되지 않는다(존재 probe 차단)
 */
@ExtendWith(SpringExtension::class)
@ContextConfiguration(classes = [BoardControllerIntegrationTest.TestMvcConfig::class])
@WebAppConfiguration
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

    /** 테스트별 allow/deny 토글이 가능한 [IssuePermissionResolver] stub. */
    open class PermissionGate : IssuePermissionResolver {
        /** false 면 모든 권한 판정을 거부한다. */
        var allowAll: Boolean = true

        override fun hasPermission(
            actorId: UUID,
            permission: IssuePermission,
            scope: IssueScope,
        ): Boolean = allowAll
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
        every { boardApplicationService.getBoard(board.id, actorId) } returns
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
                            ),
                        ),
                ),
                PlacedColumn(column = board.columns[1], cards = emptyList()),
                PlacedColumn(column = board.columns[2], cards = emptyList()),
            )

        mockMvc.perform(get("/api/v1/boards/${board.id}").accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.boardId").value(board.id.toString()))
            .andExpect(jsonPath("$.data.projectKey").value("BTS"))
            .andExpect(jsonPath("$.data.columns.length()").value(3))
            .andExpect(jsonPath("$.data.columns[0].cards.length()").value(1))
            .andExpect(jsonPath("$.data.columns[0].cards[0].issueKey").value("BTS-1"))
            // NIT: priority 는 정렬 내부용 → 응답 카드 DTO 에서 제외
            .andExpect(jsonPath("$.data.columns[0].cards[0].priority").doesNotExist())
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
        verify(exactly = 0) { boardApplicationService.getBoard(any(), any()) }
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
}
