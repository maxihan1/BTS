// IssueController GET 엔드포인트 MockMvc 슬라이스 테스트 — task-14 RED

package com.bts.issue.adapter.inbound.rest

import com.bts.issue.application.IssueApplicationService
import com.bts.issue.domain.ActorId
import com.bts.issue.domain.IssueKey
import com.bts.issue.domain.IssueNotFoundException
import io.mockk.every
import io.mockk.mockk
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
 * 테스트 케이스 4건.
 * - R-1. GET /issues/{key} 정상 → 200 + IssueResponse body
 * - R-2. GET /issues/{key} 미존재 → 404 + ProblemDetail (IssueExceptionHandler 경유)
 * - R-3. GET /issues?projectKey=ATLAS&page=0&size=20 → 200 + Page 구조
 * - R-4. GET /issues projectKey 생략 → 200 + 빈 Page (projectKey=null 허용)
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

    @BeforeEach
    fun setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build()
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
            issueApplicationService.listIssues(any(), "ATLAS", any())
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

    // ── R-4: GET /issues projectKey 생략 → 200 + 빈 Page ──────────────────────

    @Test
    fun `GET 이슈 목록 조회 — projectKey 생략 시 200 + 빈 Page`() {
        val pageable = PageRequest.of(0, 20)
        val emptyPage = PageImpl(emptyList<IssueResponse>(), pageable, 0L)

        every {
            issueApplicationService.listIssues(any(), any(), any())
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
}
