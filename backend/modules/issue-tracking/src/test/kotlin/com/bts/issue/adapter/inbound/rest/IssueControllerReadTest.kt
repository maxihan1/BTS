// IssueController GET 엔드포인트 MockMvc 슬라이스 테스트 — task-14 RED

package com.bts.issue.adapter.inbound.rest

import com.bts.issue.application.IssueApplicationService
import com.bts.issue.domain.ActorId
import com.bts.issue.domain.IssueKey
import com.bts.issue.domain.IssueNotFoundException
import com.bts.shared.board.BoardCardFilter
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest
import org.springframework.data.web.config.EnableSpringDataWebSupport
import org.springframework.http.MediaType
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.junit.jupiter.SpringExtension
import org.springframework.test.context.web.WebAppConfiguration
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import org.springframework.web.servlet.config.annotation.EnableWebMvc
import java.time.Instant
import java.util.UUID

/**
 * IssueController GET /api/v1/issues/{key} 및 GET /api/v1/issues MockMvc 슬라이스 테스트.
 *
 * `@SpringBootApplication` 없이 최소 컨텍스트로 구성한다.
 * [IssueApplicationService] 는 MockK stub 으로 대체한다.
 * [IssueExceptionHandler] 를 컨텍스트에 등록하여 IssueNotFoundException → 404 변환을 검증한다.
 *
 * 테스트 케이스.
 * - R-1. GET /issues/{key} 정상 → 200 + IssueResponse body
 * - R-2. GET /issues/{key} 미존재 → 404 + ProblemDetail (IssueExceptionHandler 경유)
 * - R-3. GET /issues?projectKey=ATLAS&page=0&size=20 → 200 + Page 구조
 * - R-4. GET /issues projectKey 생략 → 200 + 빈 Page (projectKey=null 허용)
 * - R-5. GET /issues?status=open&assignee=&label=bug → listIssues 에 filter 전달
 * - R-6. GET /issues?assignee=not-a-uuid → 400 + errorCode=VALIDATION_FAILED (B2 교정 검증)
 */
@ExtendWith(SpringExtension::class)
@ContextConfiguration(classes = [IssueControllerReadTest.TestMvcConfig::class])
@WebAppConfiguration
class IssueControllerReadTest {
    /**
     * 테스트 전용 Spring MVC 최소 컨텍스트.
     *
     * [IssueController], [IssueExceptionHandler], MockK stub Bean 을 등록한다.
     */
    @Configuration
    @EnableWebMvc
    @EnableSpringDataWebSupport
    open class TestMvcConfig {
        @Bean
        open fun issueApplicationService(): IssueApplicationService = mockk(relaxed = true)

        @Bean
        open fun issueController(service: IssueApplicationService): IssueController = IssueController(service)

        @Bean
        open fun issueExceptionHandler(): IssueExceptionHandler = IssueExceptionHandler()
    }

    @Autowired
    lateinit var webApplicationContext: WebApplicationContext

    @Autowired
    lateinit var issueApplicationService: IssueApplicationService

    lateinit var mockMvc: MockMvc

    private val fixedNow: Instant = Instant.parse("2026-05-26T00:00:00Z")
    private val actorId = ActorId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
    private val issueId = UUID.fromString("00000000-0000-0000-0000-000000000002")

    private val sampleResponse =
        IssueResponse(
            key = "ATLAS-1",
            id = issueId,
            projectKey = "ATLAS",
            summary = "샘플 이슈 요약",
            currentStateKey = "open",
            reporterId = actorId.value,
            version = 1L,
            createdAt = fixedNow,
            updatedAt = fixedNow,
            typeId = 3L,
            typeKey = "task",
            typeName = "Task",
        )

    /**
     * 매 테스트 후 이 테스트의 mock 만 초기화한다.
     *
     * [clearMocks] 는 지정한 mock 만 초기화하므로 다른 Spring ApplicationContext 의 Bean mock 을 오염시키지 않는다.
     * [io.mockk.clearAllMocks] (JVM 전역 초기화) 대신 이 방식을 사용해야 [IssueControllerTransitionIntegrationTest]
     * 의 [com.bts.workflow.engine.WorkflowDefinitionRepository] mock stub 이 지워지는 것을 방지한다 (BLOCKER 2 fix).
     *
     * PRE_EXISTING context: value class + relaxed mock 안티패턴으로 MockK 전역 누수가 발생했던 이슈는
     * clearMocks(issueApplicationService) 로도 동일하게 차단된다 — 이 mock 이 등록한 타입 정보를 지우기 때문.
     */
    @AfterEach
    fun tearDown() {
        clearMocks(issueApplicationService)
        SecurityContextHolder.clearContext()
    }

    @BeforeEach
    fun setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build()
        // CurrentActor 결선(FR-PM-06 PR-B) 이후 컨트롤러가 인증 주체를 요구하므로 SecurityContext 를 주입한다.
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(
                "11111111-1111-4111-8111-111111111111",
                null,
                listOf(SimpleGrantedAuthority("ROLE_USER")),
            )
    }

    // ── R-1: GET /issues/{key} 정상 → 200 + IssueResponse ────────────────────

    @Test
    fun `GET 이슈 단건 조회 — 존재하는 키이면 200 + IssueResponse`() {
        every {
            issueApplicationService.findByKey(any(), IssueKey("ATLAS-1"))
        } returns sampleResponse

        mockMvc.perform(
            get("/api/v1/issues/ATLAS-1").accept(MediaType.APPLICATION_JSON),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.key").value("ATLAS-1"))
            .andExpect(jsonPath("$.data.summary").value("샘플 이슈 요약"))
            .andExpect(jsonPath("$.data.currentStateKey").value("open"))
            .andExpect(jsonPath("$.data.projectKey").value("ATLAS"))
    }

    // ── R-2: GET /issues/{key} 미존재 → 404 ProblemDetail ────────────────────

    @Test
    fun `GET 이슈 단건 조회 — 미존재 키이면 404 ProblemDetail`() {
        every {
            issueApplicationService.findByKey(any(), IssueKey("ATLAS-99"))
        } throws IssueNotFoundException(IssueKey("ATLAS-99"))

        mockMvc.perform(
            get("/api/v1/issues/ATLAS-99").accept(MediaType.APPLICATION_JSON),
        )
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.status").value(404))
            .andExpect(jsonPath("$.errorCode").value("ISSUE_NOT_FOUND"))
    }

    // ── R-3: GET /issues?projectKey=ATLAS&page=0&size=20 → 200 + Page 구조 ──

    @Test
    fun `GET 이슈 목록 조회 — projectKey 지정 시 200 + Page 구조`() {
        val pageable = PageRequest.of(0, 20)
        val pageResult = PageImpl(listOf(sampleResponse), pageable, 1L)

        every {
            issueApplicationService.listIssues(any(), "ATLAS", any(), any())
        } returns pageResult

        mockMvc.perform(
            get("/api/v1/issues")
                .param("projectKey", "ATLAS")
                .param("page", "0")
                .param("size", "20")
                .accept(MediaType.APPLICATION_JSON),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.content").isArray)
            .andExpect(jsonPath("$.content[0].key").value("ATLAS-1"))
            .andExpect(jsonPath("$.totalElements").value(1))
            .andExpect(jsonPath("$.totalPages").value(1))
            .andExpect(jsonPath("$.number").value(0))
            .andExpect(jsonPath("$.size").value(20))
    }

    // ── R-5: 미인증 호출 → 401 (ResponseStatusException 변질 차단) ─────────────

    @Test
    fun `GET 이슈 단건 조회 — 미인증(SecurityContext 비움)이면 401`() {
        // CurrentActor.current() 가 던지는 ResponseStatusException(401) 이 catch-all 500 으로
        // 변질되지 않고 401 로 전파되는지 검증한다 (FR-PM-06 PR-B B1).
        SecurityContextHolder.clearContext()

        mockMvc.perform(
            get("/api/v1/issues/ATLAS-1").accept(MediaType.APPLICATION_JSON),
        )
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `GET 이슈 목록 조회 — 미인증(SecurityContext 비움)이면 401`() {
        SecurityContextHolder.clearContext()

        mockMvc.perform(
            get("/api/v1/issues")
                .param("projectKey", "ATLAS")
                .accept(MediaType.APPLICATION_JSON),
        )
            .andExpect(status().isUnauthorized)
    }

    // ── R-4: GET /issues projectKey 생략 → 200 + 빈 Page ──────────────────────

    @Test
    fun `GET 이슈 목록 조회 — projectKey 생략 시 200 + 빈 Page`() {
        val pageable = PageRequest.of(0, 20)
        val emptyPage = PageImpl(emptyList<IssueResponse>(), pageable, 0L)

        every {
            issueApplicationService.listIssues(any(), any(), any(), any())
        } returns emptyPage

        mockMvc.perform(
            get("/api/v1/issues")
                .param("page", "0")
                .param("size", "20")
                .accept(MediaType.APPLICATION_JSON),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.content").isArray)
            .andExpect(jsonPath("$.totalElements").value(0))
    }

    // ── R-5: 필터 파라미터 → service.listIssues(filter) 전달 ───────────────────

    @Test
    fun `GET 이슈 목록 조회 — status와 label 필터 파라미터가 listIssues 에 filter 로 전달된다`() {
        val pageable = PageRequest.of(0, 20)
        val pageResult = PageImpl(listOf(sampleResponse), pageable, 1L)

        every {
            issueApplicationService.listIssues(any(), "ATLAS", any(), any())
        } returns pageResult

        mockMvc.perform(
            get("/api/v1/issues")
                .param("projectKey", "ATLAS")
                .param("status", "open")
                .param("label", "bug")
                .param("page", "0")
                .param("size", "20")
                .accept(MediaType.APPLICATION_JSON),
        )
            .andExpect(status().isOk)

        verify {
            issueApplicationService.listIssues(
                any(),
                "ATLAS",
                any(),
                match { filter ->
                    filter.statusKeys.contains("open") && filter.labels.contains("bug")
                },
            )
        }
    }

    // ── R-6: 잘못된 UUID → 400 + errorCode=VALIDATION_FAILED (B2 교정 검증) ───

    @Test
    fun `GET 이슈 목록 조회 — assignee 에 잘못된 UUID 이면 400 이고 errorCode 는 VALIDATION_FAILED 이다`() {
        mockMvc.perform(
            get("/api/v1/issues")
                .param("projectKey", "ATLAS")
                .param("assignee", "not-a-uuid")
                .accept(MediaType.APPLICATION_JSON),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.status").value(400))
            .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
    }
}
