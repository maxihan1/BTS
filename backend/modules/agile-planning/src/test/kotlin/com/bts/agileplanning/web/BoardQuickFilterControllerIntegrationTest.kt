// BoardQuickFilterController MockMvc HTTP 통합 테스트 — 퀵필터 CRUD 권한 게이트 + 예외핸들러 스코프 (FR-UX-01 Task 6)

package com.bts.agileplanning.web

import com.bts.agileplanning.application.BoardQuickFilterService
import com.bts.agileplanning.application.QuickFilterEmptyQueryException
import com.bts.agileplanning.application.QuickFilterLimitExceededException
import com.bts.agileplanning.application.QuickFilterNameConflictException
import com.bts.agileplanning.application.QuickFilterNotFoundException
import com.bts.agileplanning.domain.Board
import com.bts.agileplanning.domain.BoardColumn
import com.bts.agileplanning.domain.QuickFilter
import com.bts.agileplanning.repository.BoardRepository
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import org.springframework.web.servlet.config.annotation.EnableWebMvc
import java.time.Instant
import java.util.UUID

/**
 * BoardQuickFilterController MockMvc 슬라이스 HTTP 통합 테스트.
 *
 * [BoardQuickFilterService] / [BoardRepository] 는 MockK stub 으로 대체하고
 * [IssuePermissionResolver] 는 [BoardControllerIntegrationTest.PermissionGate] 와 동일한 패턴의 테스트 전용
 * [PermissionGate] 로 allow/deny 를 토글한다.
 *
 * [BoardExceptionHandler] 의 `assignableTypes` 가 [BoardQuickFilterController] 도 포함하는지(리뷰 BLOCKER-B/C)
 * 여기서 401/403/404/409/400 매핑을 검증한다 — 신규 컨트롤러의 예외가 catch-all 로 500 변질되지 않아야 한다
 * (memory: catch-all-exceptionhandler-swallows-responsestatusexception).
 *
 * ### 검증 케이스
 * - CREATE-1. POST 정상 → 201 + Location + DataResponse 봉투
 * - CREATE-2. POST CREATE 권한 미충족 → 403, 서비스 미호출
 * - CREATE-3. POST 미인증 → 401
 * - CREATE-4. POST 보드 미존재 → 404
 * - CREATE-5. POST 이름 중복(서비스 409) → 409 + OCC 문구와 구분되는 전용 메시지
 * - CREATE-6. POST 빈 문자열 query(DTO NotBlank) → 400
 * - CREATE-7. POST 파싱은 되지만 조건 0개인 query(서비스 400, EC1) → 400
 * - CREATE-8. POST name blank → 400
 * - CREATE-9. POST name 51자 → 400
 * - CREATE-10. POST 보드당 20건 초과(서비스 409, EC3) → 409
 * - UPDATE-1. PATCH 정상 → 200 + DataResponse 봉투
 * - UPDATE-2. PATCH CREATE 권한 미충족 → 403
 * - UPDATE-3. PATCH 미인증 → 401
 * - UPDATE-4. PATCH 보드 미존재 → 404
 * - UPDATE-5. PATCH filterId 타 보드 소속(서비스 404, EC5) → 404
 * - UPDATE-6. PATCH 이름 중복 → 409
 * - DELETE-1. DELETE 정상 → 204
 * - DELETE-2. DELETE CREATE 권한 미충족 → 403
 * - DELETE-3. DELETE 미인증 → 401
 * - DELETE-4. DELETE 보드 미존재 → 404
 * - DELETE-5. DELETE filterId 타 보드 소속(서비스 404, EC5) → 404
 * - ORDER-1. 권한 거부 시 actor 추출 후 게이트에서 차단되어 서비스 미호출(존재 probe 차단)
 * - SCOPE-1. 권한 게이트가 CREATE + Project scope 로만 판정된다(가짜그린 차단)
 */
@ExtendWith(SpringExtension::class)
@ContextConfiguration(classes = [BoardQuickFilterControllerIntegrationTest.TestMvcConfig::class])
@WebAppConfiguration
class BoardQuickFilterControllerIntegrationTest {
    /**
     * 테스트 전용 Spring MVC 최소 컨텍스트.
     *
     * [BoardQuickFilterController], [BoardExceptionHandler] 와 MockK stub Bean 을 등록한다.
     */
    @Configuration
    @EnableWebMvc
    open class TestMvcConfig {
        @Bean
        open fun boardQuickFilterService(): BoardQuickFilterService = mockk(relaxed = true)

        @Bean
        open fun boardRepository(): BoardRepository = mockk(relaxed = true)

        @Bean
        open fun permissionGate(): PermissionGate = PermissionGate()

        @Bean
        open fun boardQuickFilterController(
            service: BoardQuickFilterService,
            repository: BoardRepository,
            gate: PermissionGate,
        ): BoardQuickFilterController = BoardQuickFilterController(service, repository, gate)

        @Bean
        open fun boardExceptionHandler(): BoardExceptionHandler = BoardExceptionHandler()
    }

    /** 테스트별 allow/deny 토글 + 전달 인자 캡처가 가능한 [IssuePermissionResolver] stub. */
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
    lateinit var boardQuickFilterService: BoardQuickFilterService

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
        clearMocks(boardQuickFilterService, boardRepository)
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
            columns = listOf(BoardColumn(UUID.randomUUID(), "open", "열림", "TODO", 0)),
            createdAt = Instant.parse("2026-06-20T00:00:00Z"),
            updatedAt = Instant.parse("2026-06-20T00:00:00Z"),
        )

    private fun sampleQuickFilter(
        boardId: UUID,
        filterId: UUID = UUID.randomUUID(),
        name: String = "내 버그",
        query: String = "label=bug",
    ): QuickFilter = QuickFilter(id = filterId, boardId = boardId, name = name, query = query)

    // ── CREATE-1. POST 정상 → 201 ──────────────────────────────────────────────

    @Test
    fun `POST quick-filters 정상 입력이면 201 + Location + DataResponse 봉투`() {
        val board = sampleBoard()
        val quickFilter = sampleQuickFilter(board.id, name = "내 버그", query = "label=bug")
        every { boardRepository.findById(board.id) } returns board
        every { boardQuickFilterService.create(board.id, "내 버그", "label=bug") } returns quickFilter

        val body = mapOf("name" to "내 버그", "query" to "label=bug")

        mockMvc.perform(
            post("/api/v1/boards/${board.id}/quick-filters")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isCreated)
            .andExpect(header().string("Location", "/api/v1/boards/${board.id}/quick-filters/${quickFilter.id}"))
            .andExpect(jsonPath("$.data.filterId").value(quickFilter.id.toString()))
            .andExpect(jsonPath("$.data.name").value("내 버그"))
            .andExpect(jsonPath("$.data.query").value("label=bug"))

        assertThat(permissionGate.calls)
            .containsExactly(Triple(actorId, IssuePermission.CREATE, IssueScope.Project("BTS")))
    }

    // ── CREATE-2. POST CREATE 권한 미충족 → 403 ────────────────────────────────

    @Test
    fun `POST quick-filters CREATE 권한이 없으면 403이고 서비스가 호출되지 않는다`() {
        val board = sampleBoard()
        every { boardRepository.findById(board.id) } returns board
        permissionGate.allowAll = false

        val body = mapOf("name" to "내 버그", "query" to "label=bug")

        mockMvc.perform(
            post("/api/v1/boards/${board.id}/quick-filters")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.errorCode").value("AGILE_ACCESS_DENIED"))

        verify(exactly = 0) { boardQuickFilterService.create(any(), any(), any()) }
    }

    // ── CREATE-3. POST 미인증 → 401 ───────────────────────────────────────────

    @Test
    fun `POST quick-filters 비인증이면 401`() {
        SecurityContextHolder.clearContext()
        val boardId = UUID.randomUUID()
        val body = mapOf("name" to "내 버그", "query" to "label=bug")

        mockMvc.perform(
            post("/api/v1/boards/$boardId/quick-filters")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.errorCode").value("AGILE_UNAUTHENTICATED"))

        verify(exactly = 0) { boardQuickFilterService.create(any(), any(), any()) }
    }

    // ── CREATE-4. POST 보드 미존재 → 404 ──────────────────────────────────────

    @Test
    fun `POST quick-filters 보드 미존재면 404`() {
        val boardId = UUID.randomUUID()
        every { boardRepository.findById(boardId) } returns null
        val body = mapOf("name" to "내 버그", "query" to "label=bug")

        mockMvc.perform(
            post("/api/v1/boards/$boardId/quick-filters")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.errorCode").value("AGILE_BOARD_NOT_FOUND"))

        verify(exactly = 0) { boardQuickFilterService.create(any(), any(), any()) }
    }

    // ── CREATE-5. POST 이름 중복 → 409 + 전용 메시지 ──────────────────────────

    @Test
    fun `POST quick-filters 이름 중복이면 409이고 OCC 문구와 구분되는 전용 메시지를 반환한다`() {
        val board = sampleBoard()
        every { boardRepository.findById(board.id) } returns board
        every { boardQuickFilterService.create(board.id, "내 버그", "label=bug") } throws
            QuickFilterNameConflictException()

        val body = mapOf("name" to "내 버그", "query" to "label=bug")

        mockMvc.perform(
            post("/api/v1/boards/${board.id}/quick-filters")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.detail").value("같은 이름의 퀵필터가 이미 있습니다."))
    }

    // ── CREATE-6. POST 빈 문자열 query(DTO NotBlank) → 400 ────────────────────

    @Test
    fun `POST quick-filters query가 빈 문자열이면 400`() {
        val board = sampleBoard()
        every { boardRepository.findById(board.id) } returns board
        val body = mapOf("name" to "내 버그", "query" to "")

        mockMvc.perform(
            post("/api/v1/boards/${board.id}/quick-filters")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errorCode").value("AGILE_VALIDATION_FAILED"))

        verify(exactly = 0) { boardQuickFilterService.create(any(), any(), any()) }
    }

    // ── CREATE-7. POST 조건 0개인 query(서비스 400, EC1) → 400 ────────────────

    @Test
    fun `POST quick-filters 서비스가 빈 필터 조건 예외를 던지면 400`() {
        val board = sampleBoard()
        every { boardRepository.findById(board.id) } returns board
        every { boardQuickFilterService.create(board.id, "내 버그", "foo=bar") } throws
            QuickFilterEmptyQueryException()
        val body = mapOf("name" to "내 버그", "query" to "foo=bar")

        mockMvc.perform(
            post("/api/v1/boards/${board.id}/quick-filters")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isBadRequest)
    }

    // ── CREATE-8. POST name blank → 400 ───────────────────────────────────────

    @Test
    fun `POST quick-filters name이 blank면 400`() {
        val board = sampleBoard()
        every { boardRepository.findById(board.id) } returns board
        val body = mapOf("name" to "  ", "query" to "label=bug")

        mockMvc.perform(
            post("/api/v1/boards/${board.id}/quick-filters")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errorCode").value("AGILE_VALIDATION_FAILED"))

        verify(exactly = 0) { boardQuickFilterService.create(any(), any(), any()) }
    }

    // ── CREATE-9. POST name 51자 → 400 ────────────────────────────────────────

    @Test
    fun `POST quick-filters name이 51자면 400`() {
        val board = sampleBoard()
        every { boardRepository.findById(board.id) } returns board
        val body = mapOf("name" to "a".repeat(51), "query" to "label=bug")

        mockMvc.perform(
            post("/api/v1/boards/${board.id}/quick-filters")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errorCode").value("AGILE_VALIDATION_FAILED"))
    }

    // ── CREATE-10. POST 보드당 20건 초과(서비스 409, EC3) → 409 ───────────────

    @Test
    fun `POST quick-filters 보드당 20건 초과면 409`() {
        val board = sampleBoard()
        every { boardRepository.findById(board.id) } returns board
        every { boardQuickFilterService.create(board.id, "내 버그", "label=bug") } throws
            QuickFilterLimitExceededException()
        val body = mapOf("name" to "내 버그", "query" to "label=bug")

        mockMvc.perform(
            post("/api/v1/boards/${board.id}/quick-filters")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isConflict)
    }

    // ── UPDATE-1. PATCH 정상 → 200 ────────────────────────────────────────────

    @Test
    fun `PATCH quick-filters 정상이면 200 + DataResponse 봉투`() {
        val board = sampleBoard()
        val quickFilter = sampleQuickFilter(board.id, name = "긴급 버그", query = "label=bug")
        every { boardRepository.findById(board.id) } returns board
        every {
            boardQuickFilterService.update(board.id, quickFilter.id, "긴급 버그", "label=bug")
        } returns quickFilter
        val body = mapOf("name" to "긴급 버그", "query" to "label=bug")

        mockMvc.perform(
            patch("/api/v1/boards/${board.id}/quick-filters/${quickFilter.id}")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.filterId").value(quickFilter.id.toString()))
            .andExpect(jsonPath("$.data.name").value("긴급 버그"))

        assertThat(permissionGate.calls)
            .containsExactly(Triple(actorId, IssuePermission.CREATE, IssueScope.Project("BTS")))
    }

    // ── UPDATE-2. PATCH CREATE 권한 미충족 → 403 ──────────────────────────────

    @Test
    fun `PATCH quick-filters CREATE 권한이 없으면 403`() {
        val board = sampleBoard()
        val filterId = UUID.randomUUID()
        every { boardRepository.findById(board.id) } returns board
        permissionGate.allowAll = false
        val body = mapOf("name" to "긴급 버그", "query" to "label=bug")

        mockMvc.perform(
            patch("/api/v1/boards/${board.id}/quick-filters/$filterId")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isForbidden)

        verify(exactly = 0) { boardQuickFilterService.update(any(), any(), any(), any()) }
    }

    // ── UPDATE-3. PATCH 미인증 → 401 ──────────────────────────────────────────

    @Test
    fun `PATCH quick-filters 비인증이면 401`() {
        SecurityContextHolder.clearContext()
        val boardId = UUID.randomUUID()
        val filterId = UUID.randomUUID()
        val body = mapOf("name" to "긴급 버그", "query" to "label=bug")

        mockMvc.perform(
            patch("/api/v1/boards/$boardId/quick-filters/$filterId")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isUnauthorized)
    }

    // ── UPDATE-4. PATCH 보드 미존재 → 404 ─────────────────────────────────────

    @Test
    fun `PATCH quick-filters 보드 미존재면 404`() {
        val boardId = UUID.randomUUID()
        val filterId = UUID.randomUUID()
        every { boardRepository.findById(boardId) } returns null
        val body = mapOf("name" to "긴급 버그", "query" to "label=bug")

        mockMvc.perform(
            patch("/api/v1/boards/$boardId/quick-filters/$filterId")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.errorCode").value("AGILE_BOARD_NOT_FOUND"))
    }

    // ── UPDATE-5. PATCH filterId가 타 보드 소속(서비스 404, EC5) → 404 ────────

    @Test
    fun `PATCH quick-filters filterId가 타 보드 소속이면 404`() {
        val board = sampleBoard()
        val filterId = UUID.randomUUID()
        every { boardRepository.findById(board.id) } returns board
        every {
            boardQuickFilterService.update(board.id, filterId, "긴급 버그", "label=bug")
        } throws QuickFilterNotFoundException()
        val body = mapOf("name" to "긴급 버그", "query" to "label=bug")

        mockMvc.perform(
            patch("/api/v1/boards/${board.id}/quick-filters/$filterId")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isNotFound)
    }

    // ── UPDATE-6. PATCH 이름 중복 → 409 ───────────────────────────────────────

    @Test
    fun `PATCH quick-filters 이름 중복이면 409`() {
        val board = sampleBoard()
        val filterId = UUID.randomUUID()
        every { boardRepository.findById(board.id) } returns board
        every {
            boardQuickFilterService.update(board.id, filterId, "긴급 버그", "label=bug")
        } throws QuickFilterNameConflictException()
        val body = mapOf("name" to "긴급 버그", "query" to "label=bug")

        mockMvc.perform(
            patch("/api/v1/boards/${board.id}/quick-filters/$filterId")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.detail").value("같은 이름의 퀵필터가 이미 있습니다."))
    }

    // ── DELETE-1. DELETE 정상 → 204 ───────────────────────────────────────────

    @Test
    fun `DELETE quick-filters 정상이면 204`() {
        val board = sampleBoard()
        val filterId = UUID.randomUUID()
        every { boardRepository.findById(board.id) } returns board

        mockMvc.perform(delete("/api/v1/boards/${board.id}/quick-filters/$filterId"))
            .andExpect(status().isNoContent)

        verify(exactly = 1) { boardQuickFilterService.delete(board.id, filterId) }
        assertThat(permissionGate.calls)
            .containsExactly(Triple(actorId, IssuePermission.CREATE, IssueScope.Project("BTS")))
    }

    // ── DELETE-2. DELETE CREATE 권한 미충족 → 403 ─────────────────────────────

    @Test
    fun `DELETE quick-filters CREATE 권한이 없으면 403이고 서비스가 호출되지 않는다`() {
        val board = sampleBoard()
        val filterId = UUID.randomUUID()
        every { boardRepository.findById(board.id) } returns board
        permissionGate.allowAll = false

        mockMvc.perform(delete("/api/v1/boards/${board.id}/quick-filters/$filterId"))
            .andExpect(status().isForbidden)

        verify(exactly = 0) { boardQuickFilterService.delete(any(), any()) }
    }

    // ── DELETE-3. DELETE 미인증 → 401 ─────────────────────────────────────────

    @Test
    fun `DELETE quick-filters 비인증이면 401`() {
        SecurityContextHolder.clearContext()
        val boardId = UUID.randomUUID()
        val filterId = UUID.randomUUID()

        mockMvc.perform(delete("/api/v1/boards/$boardId/quick-filters/$filterId"))
            .andExpect(status().isUnauthorized)

        verify(exactly = 0) { boardQuickFilterService.delete(any(), any()) }
    }

    // ── DELETE-4. DELETE 보드 미존재 → 404 ────────────────────────────────────

    @Test
    fun `DELETE quick-filters 보드 미존재면 404`() {
        val boardId = UUID.randomUUID()
        val filterId = UUID.randomUUID()
        every { boardRepository.findById(boardId) } returns null

        mockMvc.perform(delete("/api/v1/boards/$boardId/quick-filters/$filterId"))
            .andExpect(status().isNotFound)
    }

    // ── DELETE-5. DELETE filterId가 타 보드 소속(서비스 404, EC5) → 404 ───────

    @Test
    fun `DELETE quick-filters filterId가 타 보드 소속이면 404`() {
        val board = sampleBoard()
        val filterId = UUID.randomUUID()
        every { boardRepository.findById(board.id) } returns board
        every { boardQuickFilterService.delete(board.id, filterId) } throws QuickFilterNotFoundException()

        mockMvc.perform(delete("/api/v1/boards/${board.id}/quick-filters/$filterId"))
            .andExpect(status().isNotFound)
    }

    // ── ORDER-1. 권한 거부 시 리소스(서비스)가 호출되지 않는다 ─────────────────

    @Test
    fun `생성 권한 거부 시 actor 추출 후 권한 게이트에서 차단되어 서비스 미호출`() {
        val board = sampleBoard()
        every { boardRepository.findById(board.id) } returns board
        permissionGate.allowAll = false
        val body = mapOf("name" to "내 버그", "query" to "label=bug")

        mockMvc.perform(
            post("/api/v1/boards/${board.id}/quick-filters")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isForbidden)

        verify(exactly = 0) { boardQuickFilterService.create(any(), any(), any()) }
    }

    // ── SCOPE-1. 권한 게이트가 CREATE + Project scope 로만 판정됨(가짜그린 차단) ───

    /**
     * 일부러 검증 — 컨트롤러가 잘못된 권한코드/scope 를 넘기면 이 테스트가 실패해야 한다.
     * allow-all boolean stub 만으로는 그린이 되던 갭을 막는다(선례 BoardControllerIntegrationTest SCOPE-1).
     */
    @Test
    fun `퀵필터 생성 권한 판정은 BROWSE 나 Issue scope 가 아니라 CREATE + Project scope 여야 한다`() {
        val board = sampleBoard()
        val quickFilter = sampleQuickFilter(board.id)
        every { boardRepository.findById(board.id) } returns board
        every { boardQuickFilterService.create(board.id, "내 버그", "label=bug") } returns quickFilter
        val body = mapOf("name" to "내 버그", "query" to "label=bug")

        mockMvc.perform(
            post("/api/v1/boards/${board.id}/quick-filters")
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
}
