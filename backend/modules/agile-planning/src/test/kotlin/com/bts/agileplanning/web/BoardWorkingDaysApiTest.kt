// 작업일 탭 저장 API 의 400/404/403/401 과 정규화 계약을 고정하는 HTTP 슬라이스 테스트 (부채 177 Task 10)

package com.bts.agileplanning.web

import com.bts.agileplanning.application.WorkingDaysSettingsService
import com.bts.agileplanning.domain.Board
import com.bts.agileplanning.repository.BoardRepository
import com.bts.agileplanning.repository.BoardSettingsRepository
import com.bts.shared.permission.IssuePermission
import com.bts.shared.permission.IssuePermissionResolver
import com.bts.shared.permission.IssueScope
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
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
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import org.springframework.web.servlet.config.annotation.EnableWebMvc
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * `PUT /api/v1/boards/{boardId}/working-days` 계약 테스트 (부채 177 Task 10 · 스펙 R5·R6 · E1·E8).
 *
 * ### 왜 서비스를 mock 하지 않나
 * 이 테스트가 재는 것은 **검증과 정규화**이고 둘 다 [WorkingDaysSettingsService] 안에 있다.
 * 서비스를 stub 으로 바꾸면 400 판정이 사라진 채로도 전부 초록이 된다. 그래서 서비스는 실물로 쓰고
 * [BoardSettingsRepository] 만 mock 해 **무엇이 저장 계층으로 넘어갔는지**를 직접 본다.
 *
 * ### 공허 방지 — 각 축이 무엇과 무엇을 가르나
 * - **NULL ↔ 빈 배열** (WD-2 ↔ WD-1). 「0개는 400」만 두면 「NULL 도 400」인 구현이 통과하는데,
 *   그러면 **미설정 보드를 아무도 저장할 수 없다.** NULL 은 유효한 상태다(R6 — 미설정 = 달력일 전부).
 *   두 입력이 **다른 결과**를 내는지를 한 쌍으로 잰다.
 * - **타임존 유효 ↔ 무효** (WD-4). 유효값도 함께 재지 않으면 「전부 400」인 구현이 통과한다.
 * - **비근무일 기간 밖** (WD-5 · E8). 먼 미래 날짜가 **저장되는지** 재서 「스프린트 기간 안만
 *   허용」으로 좁히는 구현을 잡는다. 보드는 스프린트 기간을 알지 못한다.
 * - **중복 날짜** (WD-6). `PRIMARY KEY (board_id, date)` 가 중복을 거부하므로 정규화하지 않으면
 *   500 이 된다. 저장 계층에 넘어간 목록에 중복이 없는지 본다.
 * - **미지원 요일** (WD-7). 검증이 없는 구현은 `VARCHAR(3)[]` 에 쓰레기 값을 그대로 넣는다.
 */
@ExtendWith(SpringExtension::class)
@ContextConfiguration(classes = [BoardWorkingDaysApiTest.TestMvcConfig::class])
@WebAppConfiguration
class BoardWorkingDaysApiTest {
    /**
     * 테스트 전용 Spring MVC 최소 컨텍스트.
     *
     * [WorkingDaysSettingsService] 는 **실물**이고 [BoardSettingsRepository] 만 mock 이다.
     */
    @Configuration
    @EnableWebMvc
    open class TestMvcConfig : WebMvcConfigurer {
        /**
         * production(Spring Boot) 직렬화와 동일하게 LocalDate 를 ISO 문자열로 다룬다.
         *
         * `@EnableWebMvc` 슬라이스는 Boot 의 Jackson 자동설정을 상속하지 않아 기본 ObjectMapper 가
         * LocalDate 를 `[2026,10,3]` 배열로 직렬화한다([SprintBurndownControllerTest] 선례).
         */
        override fun extendMessageConverters(converters: MutableList<HttpMessageConverter<*>>) {
            converters.filterIsInstance<MappingJackson2HttpMessageConverter>().forEach {
                it.objectMapper
                    .registerModule(JavaTimeModule())
                    .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            }
        }

        @Bean
        open fun boardSettingsRepository(): BoardSettingsRepository = mockk(relaxed = true)

        @Bean
        open fun boardRepository(): BoardRepository = mockk(relaxed = true)

        @Bean
        open fun permissionGate(): PermissionGate = PermissionGate()

        @Bean
        open fun workingDaysSettingsService(settingsRepository: BoardSettingsRepository): WorkingDaysSettingsService =
            WorkingDaysSettingsService(settingsRepository)

        @Bean
        open fun boardWorkingDaysController(
            service: WorkingDaysSettingsService,
            boardRepository: BoardRepository,
            gate: PermissionGate,
        ): BoardWorkingDaysController = BoardWorkingDaysController(service, boardRepository, gate)

        @Bean
        open fun boardWorkingDaysExceptionHandler(): BoardWorkingDaysExceptionHandler =
            BoardWorkingDaysExceptionHandler()
    }

    /** allow/deny 토글 + 전달 인자 캡처가 가능한 [IssuePermissionResolver] stub. */
    open class PermissionGate : IssuePermissionResolver {
        /** false 면 모든 권한 판정을 거부한다. */
        var allow: Boolean = true

        /** 판정에 실제로 쓰인 (권한, 스코프) 쌍. 권한코드 축 단언에 쓴다. */
        val calls: MutableList<Pair<IssuePermission, IssueScope>> = mutableListOf()

        override fun hasPermission(
            actorId: UUID,
            permission: IssuePermission,
            scope: IssueScope,
        ): Boolean {
            calls += permission to scope
            return allow
        }
    }

    @Autowired
    lateinit var webApplicationContext: WebApplicationContext

    @Autowired
    lateinit var settingsRepository: BoardSettingsRepository

    @Autowired
    lateinit var boardRepository: BoardRepository

    @Autowired
    lateinit var permissionGate: PermissionGate

    lateinit var mockMvc: MockMvc

    private val boardId: UUID = UUID.fromString("22222222-2222-2222-2222-222222222222")
    private val actorId: UUID = UUID.fromString("11111111-1111-1111-1111-111111111111")

    @BeforeEach
    fun setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build()
        clearMocks(settingsRepository, boardRepository)
        permissionGate.allow = true
        permissionGate.calls.clear()
        every { boardRepository.findById(boardId) } returns board()
        every { settingsRepository.updateWorkingDays(any(), any(), any()) } returns true
        authenticate()
    }

    // ── WD-1. 근무일 0개는 400 이다 (스펙 E1 — ideal 선 0 나눗셈 차단) ──────────

    @Test
    fun `표준 근무일을 빈 배열로 저장하면 400 이고 아무것도 저장되지 않는다`() {
        save("""{"standardDays":[],"nonWorkingDates":[]}""")
            .andExpect(status().isBadRequest)

        // 400 인데 한쪽만 써 두면 이전 설정이 반쯤 지워진다 — 저장 계층에 아무것도 가면 안 된다.
        verify(exactly = 0) { settingsRepository.updateWorkingDays(any(), any(), any()) }
        verify(exactly = 0) { settingsRepository.replaceNonWorkingDates(any(), any()) }
    }

    // ── WD-2. ★대조군 — NULL 은 유효한 상태다 (R6 · 미설정 = 달력일 전부) ────────

    @Test
    fun `표준 근무일이 NULL 이면 200 이고 미설정으로 저장된다`() {
        // WD-1 과 한 쌍이다. 「NULL 도 400」인 구현이면 미설정 보드를 아무도 저장할 수 없다.
        save("""{"standardDays":null,"nonWorkingDates":[],"timezone":null}""")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.standardDays").value(nullValue()))

        verify(exactly = 1) { settingsRepository.updateWorkingDays(boardId, null, null) }
    }

    @Test
    fun `표준 근무일 키를 아예 생략해도 200 이고 미설정으로 저장된다`() {
        // 프론트가 타임존만 바꾸는 경우다(E7) — 근무일은 미설정으로 남고 타임존만 저장된다.
        save("""{"nonWorkingDates":[],"timezone":"Asia/Seoul"}""")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.standardDays").value(nullValue()))
            .andExpect(jsonPath("$.data.timezone").value("Asia/Seoul"))

        verify(exactly = 1) { settingsRepository.updateWorkingDays(boardId, null, "Asia/Seoul") }
    }

    // ── WD-3. 정상 저장 ────────────────────────────────────────────────────────

    @Test
    fun `월에서 금까지와 타임존을 저장하면 그대로 저장 계층으로 넘어간다`() {
        save(
            """
            {"standardDays":["MON","TUE","WED","THU","FRI"],
             "nonWorkingDates":["2026-10-03"],
             "timezone":"Asia/Seoul"}
            """.trimIndent(),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.standardDays.length()").value(5))
            .andExpect(jsonPath("$.data.standardDays[0]").value("MON"))
            .andExpect(jsonPath("$.data.nonWorkingDates[0]").value("2026-10-03"))
            .andExpect(jsonPath("$.data.timezone").value("Asia/Seoul"))

        verify(exactly = 1) {
            settingsRepository.updateWorkingDays(boardId, listOf("MON", "TUE", "WED", "THU", "FRI"), "Asia/Seoul")
        }
        verify(exactly = 1) { settingsRepository.replaceNonWorkingDates(boardId, listOf(LocalDate.of(2026, 10, 3))) }
    }

    // ── WD-4. 타임존 — 유효 ↔ 무효 대조군 ──────────────────────────────────────

    @Test
    fun `IANA 가 아닌 타임존은 400 이고 IANA 타임존은 200 이다`() {
        save("""{"standardDays":["MON"],"nonWorkingDates":[],"timezone":"Mars/Olympus"}""")
            .andExpect(status().isBadRequest)
        verify(exactly = 0) { settingsRepository.updateWorkingDays(any(), any(), any()) }

        // 대조군 — 유효값도 함께 재지 않으면 「전부 400」인 구현이 통과한다.
        save("""{"standardDays":["MON"],"nonWorkingDates":[],"timezone":"Europe/Berlin"}""")
            .andExpect(status().isOk)
        verify(exactly = 1) { settingsRepository.updateWorkingDays(boardId, listOf("MON"), "Europe/Berlin") }
    }

    // ── WD-5. E8 — 스프린트 기간 밖 비근무일도 저장된다 ─────────────────────────

    @Test
    fun `스프린트 기간 밖 비근무일도 그대로 저장된다`() {
        // 보드는 스프린트 기간을 모른다. 여기서 거르면 스프린트가 바뀔 때마다 설정이 소실된다(E8).
        val faraway = LocalDate.of(2029, 12, 31)
        val nearby = LocalDate.of(2026, 1, 1)

        save("""{"standardDays":["MON"],"nonWorkingDates":["$nearby","$faraway"]}""")
            .andExpect(status().isOk)

        val dates = slot<List<LocalDate>>()
        verify(exactly = 1) { settingsRepository.replaceNonWorkingDates(boardId, capture(dates)) }
        assertThat(dates.captured)
            .describedAs("기간 밖 날짜를 걸러 내면 안 된다 — 계산에서 자연히 무시된다(E8)")
            .containsExactly(nearby, faraway)
    }

    // ── WD-6. 중복 날짜 정규화는 서비스 몫이다 ─────────────────────────────────

    @Test
    fun `같은 비근무일이 두 번 들어와도 저장 계층에는 한 번만 넘어간다`() {
        // PRIMARY KEY (board_id, date) 가 중복을 거부한다 — 정규화 없이 넘기면 500 이다.
        save("""{"standardDays":["MON"],"nonWorkingDates":["2026-10-03","2026-10-03","2026-01-01"]}""")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.nonWorkingDates.length()").value(2))

        val dates = slot<List<LocalDate>>()
        verify(exactly = 1) { settingsRepository.replaceNonWorkingDates(boardId, capture(dates)) }
        assertThat(dates.captured)
            .describedAs("중복을 정규화해 넘긴다 — 리포지터리는 조용히 삼키지 않는다")
            .containsExactly(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 10, 3))
        assertThat(dates.captured).doesNotHaveDuplicates()
    }

    // ── WD-7. 미지원 요일 키 ───────────────────────────────────────────────────

    @Test
    fun `요일 키에 지원하지 않는 값이 섞이면 400 이다`() {
        save("""{"standardDays":["MON","FUNDAY"],"nonWorkingDates":[]}""")
            .andExpect(status().isBadRequest)

        verify(exactly = 0) { settingsRepository.updateWorkingDays(any(), any(), any()) }
    }

    // ── WD-8. 날짜 형식 오류가 500 이 아니다 ───────────────────────────────────

    @Test
    fun `비근무일 날짜 형식이 틀리면 400 이다`() {
        save("""{"standardDays":["MON"],"nonWorkingDates":["2026-13-45"]}""")
            .andExpect(status().isBadRequest)

        verify(exactly = 0) { settingsRepository.replaceNonWorkingDates(any(), any()) }
    }

    // ── WD-9. 게이트 — 401 → 404 → 403 순서 (R9) ──────────────────────────────

    @Test
    fun `미인증이면 401 이고 보드 조회조차 하지 않는다`() {
        SecurityContextHolder.clearContext()

        save("""{"standardDays":["MON"],"nonWorkingDates":[]}""")
            .andExpect(status().isUnauthorized)

        verify(exactly = 0) { boardRepository.findById(any()) }
        verify(exactly = 0) { settingsRepository.updateWorkingDays(any(), any(), any()) }
    }

    @Test
    fun `보드가 없으면 404 이고 권한 판정에 이르지 않는다`() {
        every { boardRepository.findById(boardId) } returns null

        save("""{"standardDays":["MON"],"nonWorkingDates":[]}""")
            .andExpect(status().isNotFound)

        assertThat(permissionGate.calls)
            .describedAs("존재 확인이 권한 판정보다 먼저다 — 뒤집으면 403 과 404 의 의미가 갈린다(R9)")
            .isEmpty()
    }

    @Test
    fun `권한이 없으면 403 이고 아무것도 저장되지 않는다`() {
        permissionGate.allow = false

        save("""{"standardDays":["MON"],"nonWorkingDates":[]}""")
            .andExpect(status().isForbidden)

        verify(exactly = 0) { settingsRepository.updateWorkingDays(any(), any(), any()) }
        assertThat(permissionGate.calls.map { it.first })
            .describedAs("설정 쓰기 게이트는 SOFT_DELETE 권한코드를 쓴다(계획 Task 8 REFACTOR)")
            .containsExactly(IssuePermission.SOFT_DELETE)
        assertThat(permissionGate.calls.map { it.second })
            .describedAs("스코프는 보드가 속한 프로젝트다")
            .containsExactly(IssueScope.Project("BTS"))
    }

    // ── WD-10. 저장 직전 경합 — 보드가 사라지면 404 ────────────────────────────

    @Test
    fun `저장 시점에 보드가 사라졌으면 404 이고 비근무일도 쓰지 않는다`() {
        every { settingsRepository.updateWorkingDays(any(), any(), any()) } returns false

        save("""{"standardDays":["MON"],"nonWorkingDates":["2026-10-03"]}""")
            .andExpect(status().isNotFound)

        verify(exactly = 0) { settingsRepository.replaceNonWorkingDates(any(), any()) }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun save(body: String): ResultActions =
        mockMvc.perform(
            put("/api/v1/boards/$boardId/working-days")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body),
        )

    private fun authenticate() {
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(
                actorId.toString(),
                null,
                listOf(SimpleGrantedAuthority("ROLE_USER")),
            )
    }

    private fun board(): Board =
        Board(
            id = boardId,
            projectKey = "BTS",
            name = "작업일 테스트 보드",
            columns = emptyList(),
            createdAt = Instant.parse("2026-09-01T00:00:00Z"),
            updatedAt = Instant.parse("2026-09-01T00:00:00Z"),
        )
}
