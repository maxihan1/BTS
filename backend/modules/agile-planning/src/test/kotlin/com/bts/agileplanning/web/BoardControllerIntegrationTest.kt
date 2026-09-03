// BoardController MockMvc HTTP 통합 테스트 — 보드 REST API 권한 게이트 + 에러 매핑 (FR-BD-01 Task 9, FR-BD-03 Task 5)

package com.bts.agileplanning.web

import com.bts.agileplanning.application.BoardApplicationService
import com.bts.agileplanning.application.BoardCardMoveResult
import com.bts.agileplanning.application.BoardPlacementResult
import com.bts.agileplanning.application.BoardStateNotMappedException
import com.bts.agileplanning.application.ColumnStateAmbiguousException
import com.bts.agileplanning.application.DuplicateStateKeysException
import com.bts.agileplanning.application.MoveTargetAmbiguousException
import com.bts.agileplanning.application.StateAlreadyMappedException
import com.bts.agileplanning.domain.Board
import com.bts.agileplanning.domain.BoardColumn
import com.bts.agileplanning.domain.BoardNameInvalidException
import com.bts.agileplanning.domain.BoardType
import com.bts.agileplanning.domain.PlacedColumn
import com.bts.agileplanning.domain.QuickFilter
import com.bts.agileplanning.repository.BoardRepository
import com.bts.shared.board.BoardCardFilter
import com.bts.shared.board.BoardIssueView
import com.bts.shared.board.BoardTransitionResult
import com.bts.shared.permission.IssuePermission
import com.bts.shared.permission.IssuePermissionResolver
import com.bts.shared.permission.IssueScope
import com.bts.shared.workflow.WorkflowStateView
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.openapitools.jackson.nullable.JsonNullableModule
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.dao.CannotAcquireLockException
import org.springframework.http.MediaType
import org.springframework.http.converter.HttpMessageConverter
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.junit.jupiter.SpringExtension
import org.springframework.test.context.web.WebAppConfiguration
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.servlet.config.annotation.EnableWebMvc
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
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
 * - CREATE-6. POST boardType=SCRUM → 201 + 응답 boardType=SCRUM (FR-BD-04 D4)
 * - CREATE-7. POST boardType 미지정 → KANBAN (기존 호출자 무변경)
 * - CREATE-8. POST boardType 허용값 밖 → 400 AGILE_BOARD_TYPE_INVALID
 * - GET-1. GET /api/v1/boards/{id} 정상 → 200 + 컬럼+카드
 * - GET-2. GET BROWSE 권한 미충족 → 403
 * - GET-3. GET 보드 미존재 → 404
 * - GET-4. GET path id 가 UUID 형식 아님 → 400 (catch-all 이 500 으로 삼키지 않음)
 * - GET-QF1. GET /api/v1/boards/{id} 정상 → 응답에 quickFilters 목록 포함(FR-UX-01 Task 7)
 * - GET-QF2. GET /api/v1/boards/{id} 퀵필터 없음 → quickFilters 빈 배열
 * - LIST-1. GET /api/v1/boards?projectKey= 정상 → 200 + 배열
 * - LIST-2. GET 목록 BROWSE 권한 미충족 → 403
 * - LIST-3. GET 목록 각 항목에 boardType — SCRUM/KANBAN 이 각각 판정을 진다 (FR-BD-04 D6)
 * - MOVE-1. POST move 정상 → 200 + 전환 결과 + columnId echo
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
 * - PATCH-N1. PATCH /boards/{id} {name:"버그 보드"} → swimlaneField 없이 200 + name echo.
 * - PATCH-N2. {name:"   "} → 400. 컨트롤러가 아니라 도메인 Board.init require 가 거부한다
 *   (서비스 호출까지 도달했는지 verify 로 못박는다 — 컨트롤러 선차단 우회 금지).
 * - PATCH-N3. {name:null} → 400 (present-null). 서비스 미호출.
 * - PATCH-N4. {} → 400. 최소 1필드 규칙 — 계약 완화 방지 앵커.
 * - PATCH-N5. {name, swimlaneField} 동시 전송 → 200 + 두 변경 모두 반영.
 * - PATCH-N7. {name 유효, swimlaneField 무효} → 400 + 서비스 위임 1회(원자성 — 두 트랜잭션 분할 금지).
 * - ERR-1. 이름 불변식과 무관한 IllegalArgumentException 하위(NumberFormatException) → 500.
 * - DEL-1. DELETE /boards/{id} → 204 + SOFT_DELETE 권한 판정 + 서비스 위임.
 * - DEL-2. DELETE SOFT_DELETE 미보유 → 403 + 서비스 미호출.
 * - DEL-3. DELETE 미존재 보드 → 404. 권한 판정에 도달하지 않는다(존재 검사가 먼저).
 * - DEL-4. DELETE 미인증 → 401 + 보드 조회 자체가 없다(존재 probe 차단).
 * - CANDEL-1. GET /boards/{id} 응답 canDelete 가 SOFT_DELETE 보유/미보유로 갈린다.
 * - LOCK-1. 보드 경로에서 advisory lock 예산 초과 → 503 AGILE_UNAVAILABLE (스펙 E8 · 부채 166 ②).
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
    open class TestMvcConfig : WebMvcConfigurer {
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

        override fun extendMessageConverters(converters: MutableList<HttpMessageConverter<*>>) {
            // @EnableWebMvc 슬라이스는 Boot 자동 구성을 우회하므로 JacksonNullableConfiguration Bean 이
            // ObjectMapper 에 반영되지 않는다. 등록하지 않으면 JsonNullable 의 부재와 명시 null 이
            // 같은 값으로 역직렬화돼 3-state 가 무너진다(SprintControllerTest 동일 패턴).
            converters
                .filterIsInstance<MappingJackson2HttpMessageConverter>()
                .forEach { it.objectMapper.registerModule(JsonNullableModule()) }
        }
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

        /**
         * 여기에 담긴 권한코드만 골라서 거부한다.
         *
         * 한 요청이 서로 다른 권한을 2회 판정하는 경로(GET 상세 = BROWSE + SOFT_DELETE)가 생기면서
         * [allowAll] 단일 토글로는 "BROWSE 는 되고 SOFT_DELETE 는 안 되는" 상태를 표현할 수 없게 됐다.
         */
        val denied: MutableSet<IssuePermission> = mutableSetOf()

        /** [hasPermission] 호출마다 전달된 (actorId, permission, scope) 를 순서대로 기록한다. */
        val calls: MutableList<Triple<UUID, IssuePermission, IssueScope>> = mutableListOf()

        override fun hasPermission(
            actorId: UUID,
            permission: IssuePermission,
            scope: IssueScope,
        ): Boolean {
            calls.add(Triple(actorId, permission, scope))
            return allowAll && permission !in denied
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
        permissionGate.denied.clear()
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
        boardType: BoardType = BoardType.KANBAN,
    ): Board =
        Board(
            id = boardId,
            projectKey = projectKey,
            name = "BTS 개발 보드",
            boardType = boardType,
            columns =
                listOf(
                    BoardColumn(UUID.randomUUID(), listOf("open"), "열림", "TODO", 0),
                    BoardColumn(UUID.randomUUID(), listOf("in-progress"), "진행 중", "IN_PROGRESS", 1),
                    BoardColumn(UUID.randomUUID(), listOf("closed"), "완료", "DONE", 2),
                ),
            createdAt = Instant.parse("2026-06-20T00:00:00Z"),
            updatedAt = Instant.parse("2026-06-20T00:00:00Z"),
        )

    // ── CREATE-1. POST 정상 → 201 ──────────────────────────────────────────────

    @Test
    fun `POST boards 정상 입력이면 201 + DataResponse 봉투에 컬럼 포함`() {
        val board = sampleBoard()
        every { boardApplicationService.createBoard("BTS", "BTS 개발 보드", BoardType.KANBAN) } returns board
        // 상태의 이름·카테고리는 워크플로우 카탈로그에서만 온다(R11). 컨트롤러가 이 조회를
        // 빠뜨리면 응답의 `name` 이 키로 떨어지므로, stub 을 걸어 배선을 실측한다.
        every { boardApplicationService.listWorkflowStates("BTS") } returns
            listOf(
                WorkflowStateView(key = "open", name = "열림", isDone = false, category = "TODO", displayOrder = 0),
            )

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
            // 1:1 시절의 `stateKey` 자리다 — 이제 배열이고 상태별 표시 정보를 함께 낸다(R11).
            .andExpect(jsonPath("$.data.columns[0].states.length()").value(1))
            .andExpect(jsonPath("$.data.columns[0].states[0].key").value("open"))
            .andExpect(jsonPath("$.data.columns[0].states[0].name").value("열림"))
            .andExpect(jsonPath("$.data.columns[0].states[0].category").value("TODO"))
            // 카탈로그에 없는 키는 드롭하지 않고 키를 이름으로 쓴다 — 매핑이 남았다는 사실이 보여야 한다.
            .andExpect(jsonPath("$.data.columns[1].states[0].name").value("in-progress"))

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
        verify(exactly = 0) { boardApplicationService.createBoard(any(), any(), any()) }
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
        every { boardApplicationService.createBoard("BTS", "보드", BoardType.KANBAN) } throws
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

    // ── CREATE-6. POST boardType=SCRUM → 201 + 응답 boardType ────────────────

    @Test
    fun `POST boards boardType 이 SCRUM 이면 그대로 전달되고 응답에 SCRUM 이 실린다`() {
        val board = sampleBoard(boardType = BoardType.SCRUM)
        every { boardApplicationService.createBoard("BTS", "스크럼 보드", BoardType.SCRUM) } returns board

        val body = mapOf("projectKey" to "BTS", "name" to "스크럼 보드", "boardType" to "SCRUM")

        mockMvc.perform(
            post("/api/v1/boards")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.data.boardType").value("SCRUM"))

        verify(exactly = 1) { boardApplicationService.createBoard("BTS", "스크럼 보드", BoardType.SCRUM) }
    }

    // ── CREATE-7. POST boardType 미지정 → KANBAN (기존 호출자 무변경) ─────────

    @Test
    fun `POST boards boardType 을 안 보내면 KANBAN 으로 생성된다`() {
        val board = sampleBoard(boardType = BoardType.KANBAN)
        every { boardApplicationService.createBoard("BTS", "보드", BoardType.KANBAN) } returns board

        // 기존 E2E board-manage.spec.ts S1 이 이 형태로 부른다 — 필드가 늘어도 깨지면 안 된다.
        val body = mapOf("projectKey" to "BTS", "name" to "보드")

        mockMvc.perform(
            post("/api/v1/boards")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.data.boardType").value("KANBAN"))

        verify(exactly = 1) { boardApplicationService.createBoard("BTS", "보드", BoardType.KANBAN) }
    }

    // ── CREATE-8. POST boardType 허용값 밖 → 400 ─────────────────────────────

    @Test
    fun `POST boards boardType 이 허용값 밖이면 400 AGILE_BOARD_TYPE_INVALID`() {
        val body = mapOf("projectKey" to "BTS", "name" to "보드", "boardType" to "GANTT")

        mockMvc.perform(
            post("/api/v1/boards")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errorCode").value("AGILE_BOARD_TYPE_INVALID"))

        // 허용값 밖은 서비스에 닿기 전에 걸러야 한다 — 보드가 만들어지면 안 된다.
        verify(exactly = 0) { boardApplicationService.createBoard(any(), any(), any()) }
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

        // 권한 게이트가 BROWSE + Project(보드 projectKey) 로 판정됐는지 검증 (sec P2).
        // canDelete 산출(FR-BD-01-2b)로 SOFT_DELETE 판정이 1회 뒤따른다 — 순서까지 못박는다.
        assertThat(permissionGate.calls)
            .containsExactly(
                Triple(actorId, IssuePermission.BROWSE, IssueScope.Project("BTS")),
                Triple(actorId, IssuePermission.SOFT_DELETE, IssueScope.Project("BTS")),
            )
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

    // ── LIST-3. GET 목록 각 항목에 boardType (FR-BD-04 · FR-5) ────────────────

    @Test
    fun `GET boards 목록의 각 항목에 보드 종류가 실린다`() {
        val scrumBoard = sampleBoard(projectKey = "BTS", boardType = BoardType.SCRUM)
        val kanbanBoard = sampleBoard(projectKey = "BTS", boardType = BoardType.KANBAN)
        every { boardApplicationService.listBoards("BTS") } returns listOf(scrumBoard, kanbanBoard)

        mockMvc.perform(
            get("/api/v1/boards").param("projectKey", "BTS").accept(MediaType.APPLICATION_JSON),
        )
            .andExpect(status().isOk)
            // 두 종류가 각각 판정을 진다 — 한쪽만 단언하면 상수 하드코딩이 통과한다.
            // boardId 를 함께 못박아 어느 보드의 종류인지가 뒤바뀌어도 드러나게 한다.
            .andExpect(jsonPath("$.data[0].boardId").value(scrumBoard.id.toString()))
            .andExpect(jsonPath("$.data[0].boardType").value("SCRUM"))
            .andExpect(jsonPath("$.data[1].boardId").value(kanbanBoard.id.toString()))
            .andExpect(jsonPath("$.data[1].boardType").value("KANBAN"))
    }

    // ── MOVE-1. POST move 정상 → 200 + columnId echo ──────────────────────────

    @Test
    fun `POST move 정상이면 200 + 전환 결과와 columnId echo`() {
        val board = sampleBoard()
        val toColumnId = board.columns[1].id
        every { boardRepository.findById(board.id) } returns board
        every {
            boardApplicationService.moveCard(
                boardId = board.id,
                issueKey = "BTS-1",
                actorUserId = actorId,
                toColumnId = toColumnId,
                toStateKey = null,
                expectedVersion = 3L,
                resolutionId = null,
            )
        } returns
            BoardCardMoveResult(
                transition = BoardTransitionResult(issueKey = "BTS-1", currentStateKey = "in-progress", version = 4L),
                columnId = toColumnId,
                stateKey = "in-progress",
            )

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
        // 이동 자체의 TRANSITION 강제는 전환 포트(IssueTransitionAdapter)가 담당한다.
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
            boardApplicationService.moveCard(any(), any(), any(), any(), any(), any(), any())
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
            boardApplicationService.moveCard(any(), any(), any(), any(), any(), any(), any())
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

    // ── MOVE-4b. toStateKey 수용 + 하위 호환 응답 계약 (R6 · R7 · E4) ──────────

    @Test
    fun `POST move 가 toStateKey 를 받으면 200 이고 그 상태를 담은 컬럼을 echo 한다`() {
        // R6 — 지라는 컬럼 안의 각 상태를 드롭존으로 그린다(J3·J4). 요청이 상태를 지목한다.
        val board = sampleBoard()
        val merged = board.columns[1]
        every { boardRepository.findById(board.id) } returns board
        every {
            boardApplicationService.moveCard(
                boardId = board.id,
                issueKey = "BTS-1",
                actorUserId = actorId,
                toColumnId = null,
                toStateKey = "in-progress",
                expectedVersion = 3L,
                resolutionId = null,
            )
        } returns
            BoardCardMoveResult(
                transition = BoardTransitionResult(issueKey = "BTS-1", currentStateKey = "in-progress", version = 4L),
                columnId = merged.id,
                stateKey = "in-progress",
            )

        val body = mapOf("toStateKey" to "in-progress", "expectedVersion" to 3)

        mockMvc.perform(
            post("/api/v1/boards/${board.id}/cards/BTS-1/move")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.currentStateKey").value("in-progress"))
            // echo 는 **서비스가 해석한** 컬럼이다. 요청이 컬럼을 안 줬으므로 서버가 되돌려 준다.
            .andExpect(jsonPath("$.data.columnId").value(merged.id.toString()))
    }

    @Test
    fun `POST move 의 toStateKey 가 보드에 매핑 안 됐으면 404 AGILE_BOARD_STATE_NOT_MAPPED`() {
        // E4 — 보드 미존재(AGILE_BOARD_NOT_FOUND)와 코드를 나눠야 UI 가 「컬럼에 상태를 추가하세요」를
        //      띄울 수 있다. 둘 다 404 라 상태 코드만으로는 구분이 안 된다.
        val board = sampleBoard()
        every { boardRepository.findById(board.id) } returns board
        every {
            boardApplicationService.moveCard(any(), any(), any(), any(), any(), any(), any())
        } throws BoardStateNotMappedException("blocked")

        val body = mapOf("toStateKey" to "blocked", "expectedVersion" to 1)

        mockMvc.perform(
            post("/api/v1/boards/${board.id}/cards/BTS-1/move")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.errorCode").value("AGILE_BOARD_STATE_NOT_MAPPED"))
    }

    @Test
    fun `POST move 의 toColumnId 가 상태 2개 이상 컬럼이면 400 AGILE_COLUMN_STATE_AMBIGUOUS`() {
        // R7 — 하위 호환 경로의 막다른 골목이다. 프론트가 toStateKey 로 옮겨 가야 한다는 신호.
        val board = sampleBoard()
        every { boardRepository.findById(board.id) } returns board
        every {
            boardApplicationService.moveCard(any(), any(), any(), any(), any(), any(), any())
        } throws ColumnStateAmbiguousException(board.columns[1].id, 2)

        val body = mapOf("toColumnId" to board.columns[1].id.toString(), "expectedVersion" to 1)

        mockMvc.perform(
            post("/api/v1/boards/${board.id}/cards/BTS-1/move")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errorCode").value("AGILE_COLUMN_STATE_AMBIGUOUS"))
    }

    @Test
    fun `POST move 가 toColumnId 도 toStateKey 도 없으면 400`() {
        // R7 — 「둘 중 정확히 하나」는 @NotNull 로 표현할 수 없어 서비스가 진다.
        //      이 판정을 컨트롤러에도 두면 규칙이 두 곳이 되고, 언젠가 갈린다.
        val board = sampleBoard()
        every { boardRepository.findById(board.id) } returns board
        every {
            boardApplicationService.moveCard(any(), any(), any(), any(), any(), any(), any())
        } throws MoveTargetAmbiguousException("toColumnId 와 toStateKey 중 정확히 하나를 보내야 합니다.")

        val body = mapOf("expectedVersion" to 1)

        mockMvc.perform(
            post("/api/v1/boards/${board.id}/cards/BTS-1/move")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errorCode").value("AGILE_VALIDATION_FAILED"))
    }

    // ── COLUMN-1~4. 컬럼 관리 3종 (R9 · R10 · E7) ──────────────────────────────

    @Test
    fun `POST columns 는 201 과 생성된 컬럼을 내고 CREATE 권한으로 게이트된다`() {
        val board = sampleBoard()
        val created = BoardColumn(UUID.randomUUID(), emptyList(), "대기", "TODO", 3)
        every { boardRepository.findById(board.id) } returns board
        every { boardApplicationService.createColumn(board.id, "대기", emptyList(), null) } returns created
        every { boardApplicationService.listWorkflowStates("BTS") } returns emptyList()

        val body = mapOf("name" to "대기", "stateKeys" to emptyList<String>())

        mockMvc.perform(
            post("/api/v1/boards/${board.id}/columns")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.data.columnId").value(created.id.toString()))
            .andExpect(jsonPath("$.data.states").isEmpty)

        // 컬럼 관리는 기존 PATCH columns/{columnId} 의 게이트를 승계한다 — CREATE on 보드 프로젝트.
        assertThat(permissionGate.calls)
            .containsExactly(Triple(actorId, IssuePermission.CREATE, IssueScope.Project("BTS")))
    }

    @Test
    fun `PUT states 가 다른 컬럼이 쓰는 상태를 받으면 409 AGILE_STATE_ALREADY_MAPPED`() {
        // E7 — 어느 컬럼이 쓰고 있는지 detail 에 실어야 사용자가 풀 방법을 찾는다.
        val board = sampleBoard()
        val owner = board.columns[2]
        every { boardRepository.findById(board.id) } returns board
        every {
            boardApplicationService.replaceColumnStates(any(), any(), any())
        } throws StateAlreadyMappedException(stateKey = "closed", ownerColumnId = owner.id)

        val body = mapOf("stateKeys" to listOf("open", "closed"))

        mockMvc.perform(
            put("/api/v1/boards/${board.id}/columns/${board.columns[0].id}/states")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.errorCode").value("AGILE_STATE_ALREADY_MAPPED"))
    }

    @Test
    fun `PUT states 의 stateKeys 에 중복이 있으면 400 AGILE_VALIDATION_FAILED`() {
        // E9 — 요청 모양의 오류다. 경합(409)과 코드를 나눈다.
        val board = sampleBoard()
        every { boardRepository.findById(board.id) } returns board
        every {
            boardApplicationService.replaceColumnStates(any(), any(), any())
        } throws DuplicateStateKeysException(listOf("open"))

        val body = mapOf("stateKeys" to listOf("open", "open"))

        mockMvc.perform(
            put("/api/v1/boards/${board.id}/columns/${board.columns[0].id}/states")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errorCode").value("AGILE_VALIDATION_FAILED"))
    }

    @Test
    fun `DELETE columns 는 204 를 내고 본문이 없다`() {
        // R10 — 담긴 상태는 미매핑으로 돌아가고 이슈는 그대로다. 돌려줄 표현이 없으므로 204 다.
        val board = sampleBoard()
        every { boardRepository.findById(board.id) } returns board
        every { boardApplicationService.deleteColumn(board.id, board.columns[0].id) } returns Unit

        mockMvc.perform(delete("/api/v1/boards/${board.id}/columns/${board.columns[0].id}"))
            .andExpect(status().isNoContent)

        verify(exactly = 1) { boardApplicationService.deleteColumn(board.id, board.columns[0].id) }
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

        verify(exactly = 0) { boardApplicationService.createBoard(any(), any(), any()) }
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
        every { boardApplicationService.createBoard("BTS", "보드", BoardType.KANBAN) } returns board
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
                        BoardColumn(UUID.randomUUID(), listOf("open"), "열림", "TODO", 0),
                        BoardColumn(UUID.randomUUID(), listOf("in-progress"), "진행 중", "IN_PROGRESS", 1, wipLimit = 3),
                        BoardColumn(UUID.randomUUID(), listOf("closed"), "완료", "DONE", 2),
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
            boardApplicationService.updateBoard(board.id, null, "ASSIGNEE")
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
            boardApplicationService.updateBoard(board.id, null, "EPIC")
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
            boardApplicationService.updateBoard(board.id, null, "foo")
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

        verify(exactly = 0) { boardApplicationService.updateBoard(any(), any(), any()) }
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

    // ── PATCH-N1~N6. PATCH /{id} 부분 갱신 (FR-BD-01-2a) ──────────────────────

    @Test
    fun `PATCH-N1 name 만 보내면 swimlaneField 없이 200 이고 이름이 갱신된다`() {
        val board = sampleBoard()
        val renamed = board.copy(name = "버그 보드")
        every { boardRepository.findById(board.id) } returns board
        every { boardApplicationService.updateBoard(board.id, "버그 보드", null) } returns renamed

        val body = mapOf("name" to "버그 보드")

        mockMvc.perform(
            patch("/api/v1/boards/${board.id}")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.boardId").value(board.id.toString()))
            .andExpect(jsonPath("$.data.name").value("버그 보드"))

        // swimlaneField 는 미전송이므로 null 로 전달돼 건드려지지 않는다(부분 갱신의 정의).
        verify(exactly = 1) { boardApplicationService.updateBoard(board.id, "버그 보드", null) }
        assertThat(permissionGate.calls)
            .containsExactly(Triple(actorId, IssuePermission.CREATE, IssueScope.Project("BTS")))
    }

    /**
     * 공백 이름 거부는 [com.bts.agileplanning.domain.Board] 의 init require 가 지는 책임이다.
     *
     * 컨트롤러가 공백을 미리 막으면 상태 코드는 같아도 도메인 불변식이 dead code 가 되고
     * 「도달 불가 조건을 지키는 테스트 = 가짜 그린」 양식이 된다. 그래서 상태 코드뿐 아니라
     * **요청이 서비스까지 도달했는지**를 함께 못박는다 — 선차단 우회를 이 verify 가 금지한다.
     * 서비스가 실제로 [IllegalArgumentException] 을 던진다는 계약은 `BoardApplicationServiceTest`
     * 의 `updateName 이 공백 이름을 IllegalArgumentException 으로 거부한다` 가 따로 고정하고 있다.
     */
    @Test
    fun `PATCH-N2 name 이 공백뿐이면 400 이고 도메인 검증까지 도달한다`() {
        val board = sampleBoard()
        every { boardRepository.findById(board.id) } returns board
        every { boardApplicationService.updateBoard(board.id, "   ", null) } throws
            BoardNameInvalidException()

        val body = mapOf("name" to "   ")

        mockMvc.perform(
            patch("/api/v1/boards/${board.id}")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errorCode").value("AGILE_VALIDATION_FAILED"))

        verify(exactly = 1) { boardApplicationService.updateBoard(board.id, "   ", null) }
    }

    @Test
    fun `PATCH-N3 name 이 명시 null 이면 400 이고 서비스는 호출되지 않는다`() {
        val board = sampleBoard()
        every { boardRepository.findById(board.id) } returns board

        // present-null. presence 만 보고 통과시키면 null 이 그대로 흘러 500 이 된다.
        mockMvc.perform(
            patch("/api/v1/boards/${board.id}")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":null}"""),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errorCode").value("AGILE_VALIDATION_FAILED"))

        verify(exactly = 0) { boardApplicationService.updateBoard(any(), any(), any()) }
    }

    /**
     * 빈 바디 `{}` 는 400 이다 — 계약 완화 방지 앵커.
     *
     * 부분 갱신으로 넓히면서 `@NotBlank` 가 사라지므로, 「최소 1필드」 규칙이 없으면
     * 아무것도 바꾸지 않는 요청이 조용히 200 을 받게 된다. 기존 400 을 그 규칙으로 승계한다.
     */
    @Test
    fun `PATCH-N4 바디가 빈 객체면 400 이고 어떤 갱신도 일어나지 않는다`() {
        val board = sampleBoard()
        every { boardRepository.findById(board.id) } returns board

        mockMvc.perform(
            patch("/api/v1/boards/${board.id}")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errorCode").value("AGILE_VALIDATION_FAILED"))

        verify(exactly = 0) { boardApplicationService.updateBoard(any(), any(), any()) }
    }

    @Test
    fun `PATCH-N5 name 과 swimlaneField 를 함께 보내면 200 이고 둘 다 반영된다`() {
        val board = sampleBoard()
        val renamed = board.copy(name = "버그 보드")
        val both = renamed.copy(swimlaneField = com.bts.agileplanning.domain.SwimlaneField.ASSIGNEE)
        every { boardRepository.findById(board.id) } returns board
        every { boardApplicationService.updateBoard(board.id, "버그 보드", "ASSIGNEE") } returns both

        val body = mapOf("name" to "버그 보드", "swimlaneField" to "ASSIGNEE")

        mockMvc.perform(
            patch("/api/v1/boards/${board.id}")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.name").value("버그 보드"))
            .andExpect(jsonPath("$.data.swimlaneField").value("ASSIGNEE"))

        verify(exactly = 1) { boardApplicationService.updateBoard(board.id, "버그 보드", "ASSIGNEE") }
    }

    @Test
    fun `PATCH-N6 swimlaneField 가 명시 null 이면 400 이다`() {
        val board = sampleBoard()
        every { boardRepository.findById(board.id) } returns board

        mockMvc.perform(
            patch("/api/v1/boards/${board.id}")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"swimlaneField":null}"""),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errorCode").value("AGILE_VALIDATION_FAILED"))

        verify(exactly = 0) { boardApplicationService.updateBoard(any(), any(), any()) }
    }

    /**
     * 두 필드 동시 전송은 **서비스 한 번**으로 위임돼야 한다 — 원자성의 컨트롤러측 계약.
     *
     * 이전 구현은 `updateName` 을 호출해 커밋한 뒤 `updateSwimlaneField` 를 불렀고 둘 다 각자
     * `@Transactional` 이라 두 트랜잭션으로 갈렸다. 그래서 뒤쪽이 400 을 던지면 클라이언트는 400 을
     * 받는데 `boards.name` 은 이미 새 이름으로 바뀌어 있었다. 위임이 1회라는 이 단언이 그 분할을
     * 금지한다 — 실제로 이름이 옛 값으로 남는지는 `BoardApplicationServiceTest` 가 실물 DB 로 확인한다.
     */
    @Test
    fun `PATCH-N7 무효 swimlaneField 와 함께 온 이름 변경은 400 이고 서비스 위임은 1회다`() {
        val board = sampleBoard()
        every { boardRepository.findById(board.id) } returns board
        every { boardApplicationService.updateBoard(board.id, "새 이름", "BOGUS") } throws
            ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, "알 수 없는 swimlaneField")

        mockMvc.perform(
            patch("/api/v1/boards/${board.id}")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"새 이름","swimlaneField":"BOGUS"}"""),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errorCode").value("AGILE_VALIDATION_FAILED"))

        verify(exactly = 1) { boardApplicationService.updateBoard(board.id, "새 이름", "BOGUS") }
    }

    // ── ERR-1. IllegalArgumentException 400 매핑 범위 가드 ─────────────────────

    /**
     * 보드 이름 불변식과 무관한 [IllegalArgumentException] 하위는 400 이 아니라 500 이어야 한다.
     *
     * 핸들러가 [IllegalArgumentException] 전체를 400 으로 매핑하면 두 컨트롤러 호출 사슬 어디에서
     * 터지든 내부 `require`/`check` 버그가 400 으로 나가 5xx 경보에서 사라진다.
     * [NumberFormatException] 은 그 하위 타입 중 가장 흔한 대표라 범위 축소의 판별자로 쓴다 —
     * 이 단언이 400 으로 되돌아가면 매핑이 다시 넓어졌다는 뜻이다.
     */
    @Test
    fun `ERR-1 이름 불변식과 무관한 IllegalArgumentException 하위는 500 이다`() {
        every { boardApplicationService.listBoards("BTS") } throws
            NumberFormatException("For input string: \"보드\"")

        mockMvc.perform(
            get("/api/v1/boards").param("projectKey", "BTS").accept(MediaType.APPLICATION_JSON),
        )
            .andExpect(status().isInternalServerError)
            .andExpect(jsonPath("$.errorCode").value("AGILE_INTERNAL_ERROR"))
    }

    // ── DEL-1~4. DELETE /{id} 소프트 삭제 (FR-BD-01-2b) ───────────────────────

    /**
     * 삭제 계약은 204 · 권한코드 · 서비스 위임 세 가지다.
     *
     * 「목록에서 사라진다」를 이 슬라이스에서 재려던 단언은 지웠다 — 서비스가 mock 이라
     * 직전에 세운 `listBoards → emptyList()` 를 되읽는 자기 확인이었고, 지워도 잃는 것이 없었다.
     * 그 계약은 `BoardRepositoryTest` 의
     * `softDelete 가 deleted_at 을 채우고 이후 findById 가 null 이다` 가 실물 DB 로 고정한다.
     */
    @Test
    fun `DEL-1 DELETE 는 204 이고 SOFT_DELETE 권한으로 서비스에 위임한다`() {
        val board = sampleBoard()
        every { boardRepository.findById(board.id) } returns board

        mockMvc.perform(delete("/api/v1/boards/${board.id}"))
            .andExpect(status().isNoContent)

        verify(exactly = 1) { boardApplicationService.softDelete(board.id) }
        // 권한코드가 CREATE 가 아니라 SOFT_DELETE 여야 한다(plan 의 의도적 편차 X3).
        assertThat(permissionGate.calls)
            .containsExactly(Triple(actorId, IssuePermission.SOFT_DELETE, IssueScope.Project("BTS")))
    }

    @Test
    fun `DEL-2 DELETE 는 SOFT_DELETE 미보유 시 403 이고 서비스는 호출되지 않는다`() {
        val board = sampleBoard()
        every { boardRepository.findById(board.id) } returns board
        permissionGate.denied.add(IssuePermission.SOFT_DELETE)

        mockMvc.perform(delete("/api/v1/boards/${board.id}"))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.errorCode").value("AGILE_ACCESS_DENIED"))

        verify(exactly = 0) { boardApplicationService.softDelete(any()) }
    }

    /**
     * 미존재 보드는 404 이며 권한 판정에는 도달하지 않는다.
     *
     * `permissionGate.calls` 가 비어 있다는 단언이 **존재 검사 → 권한 판정** 순서를 못박는 장치다.
     * 순서가 뒤집히면 권한 미보유자에게 403 이 돌아가 「그 보드는 존재한다」를 누설하는데,
     * 로컬 개발자는 항상 권한을 가지므로 이 뒤집힘이 눈에 보이지 않는다.
     */
    @Test
    fun `DEL-3 DELETE 는 미존재 보드에 404 이고 권한 판정에 도달하지 않는다`() {
        val boardId = UUID.randomUUID()
        every { boardRepository.findById(boardId) } returns null

        mockMvc.perform(delete("/api/v1/boards/$boardId"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.errorCode").value("AGILE_BOARD_NOT_FOUND"))

        assertThat(permissionGate.calls).isEmpty()
        verify(exactly = 0) { boardApplicationService.softDelete(any()) }
    }

    @Test
    fun `DEL-4 DELETE 는 미인증이면 401 이고 보드 존재 여부를 노출하지 않는다`() {
        SecurityContextHolder.clearContext()
        val board = sampleBoard()
        every { boardRepository.findById(board.id) } returns board

        mockMvc.perform(delete("/api/v1/boards/${board.id}"))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.errorCode").value("AGILE_UNAUTHENTICATED"))

        // 존재하는 보드를 넘겨도 조회 자체가 없어야 한다 — 미인증자의 존재 probe 차단.
        verify(exactly = 0) { boardRepository.findById(any()) }
        verify(exactly = 0) { boardApplicationService.softDelete(any()) }
    }

    // ── CANDEL-1. GET /{id} 응답의 canDelete (FR-BD-01-2d) ────────────────────

    @Test
    fun `CANDEL-1 GET 응답 canDelete 는 SOFT_DELETE 보유자에게 true 다`() {
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
            .andExpect(jsonPath("$.data.canDelete").value(true))
    }

    @Test
    fun `CANDEL-1 GET 응답 canDelete 는 SOFT_DELETE 미보유자에게 false 다`() {
        val board = sampleBoard()
        every { boardRepository.findById(board.id) } returns board
        every { boardApplicationService.getBoard(board.id, actorId, BoardCardFilter.EMPTY) } returns
            BoardPlacementResult(
                columns = board.columns.map { PlacedColumn(it, emptyList()) },
                truncated = false,
                unplacedCount = 0,
            )
        // BROWSE 는 통과시키고 SOFT_DELETE 만 거부한다 — 조회는 되고 삭제만 막히는 상태.
        permissionGate.denied.add(IssuePermission.SOFT_DELETE)

        mockMvc.perform(get("/api/v1/boards/${board.id}").accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.canDelete").value(false))
    }

    // ── LOCK-1. 보드 경로 advisory lock 예산(200ms) 초과 → 503 (스펙 E8) ──────
    //
    // ★ 형제 락 `scrum-board:<projectKey>` 는 [BoardApplicationService.ensureScrumBoard] 가 잡는다.
    // 그 메서드는 **보드 서비스의 public 메서드**라 보드 컨트롤러 경로에서도 예외가 이 advice 로
    // 올라온다. [SprintExceptionHandler] 한쪽에만 매핑을 걸면 이쪽은 500 을 낸다(스펙 E8 · Sanity G3).
    //
    // [CannotAcquireLockException] 은 ResponseStatusException 상속이 **아니라** 상태 전파 핸들러가
    // 잡지 못하고 catch-all 이 500 AGILE_INTERNAL_ERROR 로 삼킨다. 타입은 추측이 아니라
    // `AdvisoryLockBudget.kt` KDoc 의 실측(2026-09-03 · `55P03` → `JooqExceptionTranslator`)이 정본이다.
    //
    // 보안 — 락 키에 `projectKey` 가 들어간다. 응답 detail 에 그것도 SQL 도 나오면 안 된다.

    @Test
    fun `보드 경로에서 락 타임아웃이면 503 AGILE_UNAVAILABLE 을 반환한다`() {
        every { boardApplicationService.createBoard("BTS", "BTS 스크럼 보드", BoardType.SCRUM) } throws
            CannotAcquireLockException(
                "jOOQ; SQL [SELECT pg_advisory_xact_lock(hashtextextended(?, 0))]; " +
                    "ERROR: canceling statement due to lock timeout [scrum-board:BTS]",
            )

        val body = mapOf("projectKey" to "BTS", "name" to "BTS 스크럼 보드", "boardType" to "SCRUM")

        mockMvc.perform(
            post("/api/v1/boards")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isServiceUnavailable)
            .andExpect(jsonPath("$.errorCode").value("AGILE_UNAVAILABLE"))
            .andExpect(jsonPath("$.detail").value(not(containsString("scrum-board"))))
            .andExpect(jsonPath("$.detail").value(not(containsString("BTS"))))
    }
}
