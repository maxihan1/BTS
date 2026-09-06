// 보드 설정 4탭이 같은 오류(없는 보드 404)에 같은 RFC 7807 봉투를 내는지 재는 HTTP 슬라이스 테스트 (부채 177 Task 29)

package com.bts.agileplanning.web

import com.bts.agileplanning.application.CardLayoutSettingsService
import com.bts.agileplanning.application.DetailViewSettingsService
import com.bts.agileplanning.application.EstimationSettingsService
import com.bts.agileplanning.application.WorkingDaysSettingsService
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

        override fun hasPermission(
            actorId: UUID,
            permission: IssuePermission,
            scope: IssueScope,
        ): Boolean {
            lastPermission = permission
            lastScope = scope
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
        val envelopes =
            linkedMapOf(
                "card-layout" to envelopeOf(patchCardLayout()),
                "estimation" to envelopeOf(patchEstimation()),
                "working-days" to envelopeOf(putWorkingDays()),
                "detail-view-fields" to envelopeOf(patchDetailView()),
            )

        assertThat(envelopes.values.toSet())
            .describedAs("같은 설정 화면의 네 탭은 같은 오류에 같은 본문을 내야 한다 — 탭별 실제 본문: %s", envelopes)
            .hasSize(1)
    }

    // ── 헬퍼 ─────────────────────────────────────────────────────────────────

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
