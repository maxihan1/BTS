// 보드 설정 4탭이 같은 오류(없는 보드 404)에 같은 RFC 7807 봉투를 내는지 재는 HTTP 슬라이스 테스트 (부채 177 Task 29)

package com.bts.agileplanning.web

import com.bts.agileplanning.application.CardLayoutSettingsService
import com.bts.agileplanning.application.DetailViewSettingsService
import com.bts.agileplanning.application.EstimationSettingsService
import com.bts.agileplanning.application.WorkingDaysSettingsService
import com.bts.agileplanning.domain.Board
import com.bts.agileplanning.domain.BoardColumn
import com.bts.agileplanning.repository.BoardRepository
import com.bts.agileplanning.repository.BoardSettingsRepository
import com.bts.shared.permission.IssuePermission
import com.bts.shared.permission.IssuePermissionResolver
import com.bts.shared.permission.IssueScope
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.mockk.every
import io.mockk.mockk
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
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import org.springframework.web.servlet.config.annotation.EnableWebMvc
import java.time.Instant
import java.util.UUID

/** 없는 보드에 대해 네 탭이 내야 하는 RFC 7807 `type` — [BoardExceptionHandler.handleBoardNotFound] 가 만든다. */
private const val NOT_FOUND_TYPE = "https://bts.example.com/problems/agile-board-not-found"

/** 네 탭 공통 404 에러 코드. 프론트가 파싱하는 필드다. */
private const val NOT_FOUND_CODE = "AGILE_BOARD_NOT_FOUND"

/**
 * 보드 설정 4탭이 **같은 오류에 같은 봉투**를 내는지 재는 교차 탭 테스트 (부채 177 Task 29).
 *
 * ## 무엇이 문제였나
 * [BoardExceptionHandler] 의 `assignableTypes` 가 탭 컨트롤러 넷을 덮지 않아 넷이 **서로 다르게
 * 우회**했다. [BoardCardLayoutController] · [BoardDetailViewController] 는
 * `ResponseStatusException` 계열만 던져 상태 코드만 맞췄고(본문은 빈 채로 나간다),
 * [BoardWorkingDaysController] 는 자기 파일 안에 별도 advice 를 뒀으며(Task 29 가 걷어냈다),
 * [BoardEstimationController] 도 `ResponseStatusException` 계열이다. **같은 설정 화면의 네 탭이
 * 서로 다른 오류 본문을 낸다** — 프론트가 탭마다 다르게 파싱해야 하고 한 탭만 고치면 나머지가
 * 조용히 어긋난다.
 *
 * ## ★ 상태 코드만 재면 이 축은 공허하다
 * Spring 의 `ResponseStatusExceptionResolver` 와 `DefaultHandlerExceptionResolver` 가
 * **핸들러 없이도 같은 상태 코드를 낸다.** 그래서 「404 인가」만 재는 단언은 [BoardExceptionHandler]
 * 를 통째로 지워도 초록이다(아래 뮤테이션 표의 X2 가 실측이다). 이 파일은 상태 코드가 아니라
 * **본문 봉투**(`type` · `title` · `detail` · `errorCode`)를 잰다.
 *
 * ## 축
 *
 * | 축 | 무엇 | 느슨한 구현 ↔ 올바른 구현 |
 * |---|---|---|
 * | ①~④ 탭별 봉투 | 각 탭의 404 본문이 `agile-board-not-found` 봉투다 | 상태만 맞고 본문이 빈 응답 ↔ RFC 7807 봉투 |
 * | ⑤ 교차 동일성 | 네 탭의 본문이 `timestamp`·`instance` 를 빼면 **서로 같다** | 탭마다 다른 봉투 ↔ 한 봉투 |
 *
 * ★**①~④ 와 ⑤ 는 짝으로만 산다.** ①~④ 만 두면 「넷이 각자 같은 문자열을 낸다」를 넷이 따로
 * 주장할 뿐 서로를 검사하지 않고, ⑤ 만 두면 「넷이 똑같이 틀린 봉투」를 통과시킨다.
 *
 * ## 뮤테이션 실측 (2026-09-06 · 5건) — 재현 가능한 증거
 *
 * 구현을 한 군데씩 되돌려 심고 아래 명령을 돌린 결과다. 재현은
 * `./gradlew :modules:agile-planning:test --rerun --no-build-cache --tests '*BoardSettingsTabErrorEnvelopeTest'
 * --tests '*BoardCardLayoutApiTest' --tests '*BoardEstimationApiTest' --tests '*BoardWorkingDaysApiTest'
 * --tests '*BoardDetailViewApiTest'`(전체 57건) 로 한다.
 *
 * | # | 뮤테이션 | red 가 된 테스트 | 그래도 통과 |
 * |---|---|---|---|
 * | X1 | `assignableTypes` 에서 [BoardDetailViewController] 제거 | 5건 ★a | 나머지 세 탭의 봉투 단언 |
 * | X5 | `assignableTypes` 에서 [BoardWorkingDaysController] 제거 | 9건 ★b | 나머지 세 탭의 봉투 단언 |
 * | **X2** | `@RestControllerAdvice` 를 떼어 advice 무력화 | 18건 — 이 파일 5건 **전부** | 상태만 재는 단언 ★★c |
 * | X3 | [BoardWorkingDaysController] 권한코드를 `SOFT_DELETE` 로 | 1건 ★d | 상태는 여전히 403 |
 * | X4 | [BoardDetailViewController] PATCH 권한코드를 `SOFT_DELETE` 로 | 1건 ★e | 상태는 여전히 403 |
 *
 * - **★a** 「상세 보기 탭은 …」 · 「네 탭의 … 서로 같다」 · [BoardDetailViewApiTest] 의 403/404 3건.
 *   탭 하나가 목록에서 빠지면 **그 탭만** 죽는다 — 단언이 뭉뚱그려져 있지 않다는 증거다.
 * - **★b** 「작업일 탭은 …」 · 「네 탭의 …」 · [BoardWorkingDaysApiTest] 의 400/403/404 7건. 같은 성질이다.
 * - **★★c 이 표의 존재 이유다.** 넷 중 셋의 오류 경로가 [ResponseStatusException] 계열을 지나므로
 *   advice 를 통째로 지워도 **상태 코드는 그대로 나온다.** 실측으로 초록으로 남은 것 —
 *   [BoardEstimationApiTest] 의 「없는 보드는 404 다 — 409 가 아니다」·「소프트 삭제된 스크럼 보드는
 *   404 다」, [BoardCardLayoutApiTest] 의 「미인증이면 존재하는 보드라도 401 이다」.
 *   「404 인가」만 재는 테스트는 핸들러가 있는지 없는지를 **전혀 재지 않는다.**
 * - **★d** [BoardWorkingDaysApiTest] 「권한이 없으면 403 이고 아무것도 저장되지 않는다」.
 * - **★e** [BoardDetailViewApiTest] 「PATCH 는 CREATE 를 프로젝트 스코프로 판정하고 …」.
 *   ★d·★e 둘 다 상태는 **여전히 403** 이라 권한코드 캡처 단언만이 가른다.
 *
 * ## 이 파일이 재지 **않는** 것
 * - 401/403/400 봉투 — 이 task 가 닫는 것은 404 한 자리다. 나머지 상태는 탭별 API 테스트가 진다.
 * - 저장 동작 — 게이트에서 404 로 끝나므로 서비스는 호출되지 않는다.
 */
@ExtendWith(SpringExtension::class)
@ContextConfiguration(classes = [BoardSettingsTabErrorEnvelopeTest.TestMvcConfig::class])
@WebAppConfiguration
class BoardSettingsTabErrorEnvelopeTest {
    /**
     * 탭 컨트롤러 넷 + [BoardExceptionHandler] 를 한 컨텍스트에 올린 최소 MVC 슬라이스.
     *
     * 리포지터리만 mock 이고 서비스는 실물이다 — 게이트가 404 를 내기까지의 경로를 그대로 태운다.
     */
    @Configuration
    @EnableWebMvc
    open class TestMvcConfig {
        @Bean
        open fun boardRepository(): BoardRepository = mockk()

        @Bean
        open fun boardSettingsRepository(): BoardSettingsRepository = mockk(relaxed = true)

        @Bean
        open fun permissionStub(): PermissionStub = PermissionStub()

        @Bean
        open fun cardLayoutSettingsService(
            boardRepository: BoardRepository,
            settingsRepository: BoardSettingsRepository,
        ): CardLayoutSettingsService = CardLayoutSettingsService(boardRepository, settingsRepository)

        @Bean
        open fun estimationSettingsService(
            boardRepository: BoardRepository,
            settingsRepository: BoardSettingsRepository,
        ): EstimationSettingsService = EstimationSettingsService(boardRepository, settingsRepository)

        @Bean
        open fun workingDaysSettingsService(settingsRepository: BoardSettingsRepository): WorkingDaysSettingsService =
            WorkingDaysSettingsService(settingsRepository)

        @Bean
        open fun detailViewSettingsService(settingsRepository: BoardSettingsRepository): DetailViewSettingsService =
            DetailViewSettingsService(settingsRepository)

        @Bean
        open fun boardCardLayoutController(
            service: CardLayoutSettingsService,
            boardRepository: BoardRepository,
            gate: PermissionStub,
        ): BoardCardLayoutController = BoardCardLayoutController(service, boardRepository, gate)

        @Bean
        open fun boardEstimationController(
            service: EstimationSettingsService,
            boardRepository: BoardRepository,
            gate: PermissionStub,
        ): BoardEstimationController = BoardEstimationController(service, boardRepository, gate)

        @Bean
        open fun boardWorkingDaysController(
            service: WorkingDaysSettingsService,
            boardRepository: BoardRepository,
            gate: PermissionStub,
        ): BoardWorkingDaysController = BoardWorkingDaysController(service, boardRepository, gate)

        @Bean
        open fun boardDetailViewController(
            service: DetailViewSettingsService,
            boardRepository: BoardRepository,
            gate: PermissionStub,
        ): BoardDetailViewController = BoardDetailViewController(service, boardRepository, gate)

        @Bean
        open fun boardExceptionHandler(): BoardExceptionHandler = BoardExceptionHandler()
    }

    /**
     * allow/deny 토글 + 전달 인자 캡처가 가능한 [IssuePermissionResolver] stub.
     *
     * 형제 [BoardCardLayoutApiTest.PermissionStub] · [BoardEstimationApiTest.PermissionStub] 과
     * **같은 필드 이름**이다 — 다섯 번째 탭이 생겨도 복제할 본이 하나로 남는다.
     */
    open class PermissionStub : IssuePermissionResolver {
        var allowAll: Boolean = true
        var lastPermission: IssuePermission? = null
        var lastScope: IssueScope? = null
        var lastActorId: UUID? = null
        var callCount: Int = 0

        override fun hasPermission(
            actorId: UUID,
            permission: IssuePermission,
            scope: IssueScope,
        ): Boolean {
            lastPermission = permission
            lastScope = scope
            lastActorId = actorId
            callCount += 1
            return allowAll
        }
    }

    @Autowired
    lateinit var webApplicationContext: WebApplicationContext

    @Autowired
    lateinit var boardRepository: BoardRepository

    @Autowired
    lateinit var permissionStub: PermissionStub

    lateinit var mockMvc: MockMvc

    private val mapper: ObjectMapper = ObjectMapper().registerKotlinModule()
    private val actorId: UUID = UUID.fromString("11111111-1111-1111-1111-111111111111")
    private val missingBoardId: UUID = UUID.fromString("33333333-3333-3333-3333-333333333333")

    @BeforeEach
    fun setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build()
        // 보드가 없으므로 네 탭 모두 게이트에서 404 로 끝난다 — 권한 판정에는 이르지 않는다.
        every { boardRepository.findById(any()) } returns null
        permissionStub.allowAll = true
        permissionStub.lastPermission = null
        permissionStub.lastScope = null
        permissionStub.lastActorId = null
        permissionStub.callCount = 0
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(
                actorId.toString(),
                null,
                listOf(SimpleGrantedAuthority("ROLE_USER")),
            )
    }

    // ── ①~④ 탭별 봉투 ────────────────────────────────────────────────────────

    @Test
    fun `카드 레이아웃 탭은 없는 보드에 AGILE_BOARD_NOT_FOUND 봉투를 낸다`() {
        assertBoardNotFoundEnvelope("card-layout", patchCardLayout())
    }

    @Test
    fun `추정 탭은 없는 보드에 AGILE_BOARD_NOT_FOUND 봉투를 낸다`() {
        assertBoardNotFoundEnvelope("estimation", patchEstimation())
    }

    @Test
    fun `작업일 탭은 없는 보드에 AGILE_BOARD_NOT_FOUND 봉투를 낸다`() {
        assertBoardNotFoundEnvelope("working-days", putWorkingDays())
    }

    @Test
    fun `상세 보기 탭은 없는 보드에 AGILE_BOARD_NOT_FOUND 봉투를 낸다`() {
        assertBoardNotFoundEnvelope("detail-view-fields", patchDetailView())
    }

    // ── ⑤ 교차 동일성 ────────────────────────────────────────────────────────

    @Test
    fun `네 탭의 없는 보드 응답은 timestamp 와 instance 를 빼면 본문이 서로 같다`() {
        val envelopes = tabRequests().mapValues { (_, request) -> envelopeOf(request()) }

        assertThat(envelopes.values.toSet())
            .describedAs("같은 설정 화면의 네 탭은 같은 오류에 같은 본문을 내야 한다 — 탭별 실제 본문: %s", envelopes)
            .hasSize(1)
    }

    // ── ⑥ 게이트 actor ───────────────────────────────────────────────────────

    @Test
    fun `네 탭 모두 인증된 주체를 그대로 권한 게이트에 넘긴다`() {
        // 이 파일의 다른 테스트와 달리 **보드가 있다** — 없으면 404 로 끝나 게이트에 닿지 못한다.
        every { boardRepository.findById(any()) } returns sampleBoard()
        // 거부로 고정한다. 게이트까지만 가고 서비스는 돌지 않아 이 축이 탭별 저장 경로에 얽히지 않는다.
        permissionStub.allowAll = false

        tabRequests().forEach { (tab, request) ->
            // 앞 탭이 남긴 값으로 초록이 되지 않게 매 탭마다 비운다 — 게이트를 아예 안 부른 탭은 null 로 걸린다.
            permissionStub.lastActorId = null

            val result = request()

            assertThat(result.response.status).describedAs("%s 상태 코드", tab).isEqualTo(403)
            assertThat(permissionStub.lastActorId)
                .describedAs("%s — 게이트가 받은 주체는 인증된 주체와 같아야 한다", tab)
                .isEqualTo(actorId)
        }
    }

    // ── 헬퍼 ─────────────────────────────────────────────────────────────────

    /**
     * 네 탭의 요청을 담은 **하나의 표**. 교차 축(⑤ 봉투 동일성 · ⑥ 게이트 actor)은 이 표만 돈다.
     *
     * ★다섯 번째 탭은 여기 한 줄이면 두 축이 함께 는다. 축마다 탭 목록을 따로 적으면
     * 「두 목록이 서로를 검사하지 않는」 양식이 되어, 새 탭이 한쪽 목록에만 들어가도 아무도 못 잡는다.
     */
    private fun tabRequests(): Map<String, () -> MvcResult> =
        linkedMapOf(
            "card-layout" to ::patchCardLayout,
            "estimation" to ::patchEstimation,
            "working-days" to ::putWorkingDays,
            "detail-view-fields" to ::patchDetailView,
        )

    /**
     * ⑥ 전용 보드. 네 컨트롤러가 게이트에서 읽는 것은 `projectKey` 뿐이라 나머지 필드는 최소값이다.
     *
     * `findById(any())` 로 돌려주므로 경로의 [missingBoardId] 와 `id` 가 달라도 상관없다 —
     * ⑥ 은 「어떤 보드인가」가 아니라 「누구로 물었는가」를 잰다.
     */
    private fun sampleBoard(): Board =
        Board(
            id = UUID.fromString("44444444-4444-4444-4444-444444444444"),
            projectKey = "BTS",
            name = "BTS 개발 보드",
            columns = listOf(BoardColumn(UUID.randomUUID(), listOf("open"), "열림", "TODO", 0)),
            createdAt = Instant.parse("2026-06-20T00:00:00Z"),
            updatedAt = Instant.parse("2026-06-20T00:00:00Z"),
        )

    /**
     * 404 응답 본문이 [BoardExceptionHandler] 의 보드 미존재 봉투인지 잰다.
     *
     * ★상태 코드를 **먼저** 재고 본문을 **따로** 잰다. 상태 코드만으로는 Spring 기본 리졸버의
     * 빈 본문 404 와 갈리지 않기 때문이다.
     */
    private fun assertBoardNotFoundEnvelope(
        tab: String,
        result: MvcResult,
    ) {
        assertThat(result.response.status).describedAs("%s 상태 코드", tab).isEqualTo(404)

        val body = envelopeOf(result)
        assertThat(body["errorCode"]).describedAs("%s errorCode — 프론트가 파싱하는 필드다", tab).isEqualTo(NOT_FOUND_CODE)
        assertThat(body["type"]).describedAs("%s problem type", tab).isEqualTo(NOT_FOUND_TYPE)
        assertThat(body["title"]).describedAs("%s title", tab).isEqualTo("Board Not Found")
        assertThat(body["detail"]).describedAs("%s detail", tab).isEqualTo("보드를 찾을 수 없습니다.")
    }

    /**
     * 응답 본문을 `timestamp` · `instance` 를 뺀 맵으로 꺼낸다.
     *
     * 둘은 **탭마다 다른 것이 옳다** — `timestamp` 는 호출 시각이고 `instance` 는 Spring 이 채우는
     * 요청 경로다. 나머지 필드(`type` · `title` · `status` · `detail` · `errorCode`)만 동일성 비교 대상이다.
     * 본문이 비어 있으면(핸들러가 안 붙어 Spring 이 `sendError` 로 끝낸 경우) 그 사실 자체를 값으로
     * 만들어 실패 메시지에 드러낸다.
     *
     * ★`contentAsString` 이 아니라 **바이트를 UTF-8 로 직접 읽는다.** `application/problem+json` 에는
     * charset 파라미터가 없어 `MockHttpServletResponse` 의 문자 인코딩이 ISO-8859-1 로 남고, 그러면
     * `detail` 의 한글이 깨져 「본문이 다르다」가 아니라 「인코딩이 다르다」를 재게 된다.
     * 실제 컨테이너는 JSON 을 UTF-8 로 내보내므로 이 깨짐은 슬라이스 하네스의 성질이다.
     */
    private fun envelopeOf(result: MvcResult): Map<String, Any?> {
        val raw = String(result.response.contentAsByteArray, Charsets.UTF_8)
        if (raw.isBlank()) return mapOf("본문없음" to "status=${result.response.status}")
        val parsed: Map<String, Any?> = mapper.readValue(raw, Map::class.java).mapKeys { it.key.toString() }
        return parsed.filterKeys { it != "timestamp" && it != "instance" }
    }

    private fun patchCardLayout(): MvcResult =
        mockMvc.perform(
            patch("/api/v1/boards/{boardId}/card-layout", missingBoardId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"cardLayout":{"BOARD":["EPIC"]}}"""),
        ).andReturn()

    private fun patchEstimation(): MvcResult =
        mockMvc.perform(
            patch("/api/v1/boards/{boardId}/estimation", missingBoardId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"timeTracking":"NONE"}"""),
        ).andReturn()

    private fun putWorkingDays(): MvcResult =
        mockMvc.perform(
            put("/api/v1/boards/{boardId}/working-days", missingBoardId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"standardDays":["MON"],"nonWorkingDates":[]}"""),
        ).andReturn()

    private fun patchDetailView(): MvcResult =
        mockMvc.perform(
            patch("/api/v1/boards/{boardId}/detail-view-fields", missingBoardId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"groups":{"GENERAL":["summary"]}}"""),
        ).andReturn()
}
