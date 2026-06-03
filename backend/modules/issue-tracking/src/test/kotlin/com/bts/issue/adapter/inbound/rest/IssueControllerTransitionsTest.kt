// GET /api/v1/issues/{key}/transitions MockMvc 슬라이스 테스트 — task-4 RED

package com.bts.issue.adapter.inbound.rest

import com.bts.issue.application.IssueApplicationService
import com.bts.issue.domain.IssueKey
import com.bts.issue.domain.IssueNotFoundException
import com.bts.issue.domain.IssueWorkflowNotConfiguredException
import com.bts.shared.workflow.AvailableTransitionView
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
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

/**
 * IssueController GET /api/v1/issues/{key}/transitions MockMvc 슬라이스 테스트.
 *
 * `@SpringBootApplication` 없이 `@ContextConfiguration` 으로 최소 컨텍스트를 직접 구성한다.
 * [IssueApplicationService] 는 MockK stub 으로 대체한다.
 *
 * 테스트 케이스 3건.
 * - T-1. service 가 2건 반환 시 200 + `data.transitions` 배열 2건 + 각 필드(fromStateKey, toStateKey, name, key) 직렬화 확인.
 * - T-2. service 가 [IssueNotFoundException] throw → 404.
 * - T-3. service 가 [IssueWorkflowNotConfiguredException] throw → 422.
 */
@ExtendWith(SpringExtension::class)
@ContextConfiguration(classes = [IssueControllerTransitionsTest.TestMvcConfig::class])
@WebAppConfiguration
class IssueControllerTransitionsTest {
    /**
     * 테스트 전용 Spring MVC 최소 컨텍스트.
     *
     * [IssueController], [IssueExceptionHandler] 와 MockK stub Bean 을 등록한다.
     */
    @Configuration
    @EnableWebMvc
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

    @BeforeEach
    fun setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build()
    }

    /**
     * 매 테스트 후 이 테스트의 mock 만 초기화한다.
     *
     * [clearMocks] 는 지정한 mock 만 초기화하므로 다른 Spring ApplicationContext 의 Bean mock 을 오염시키지 않는다.
     * [io.mockk.clearAllMocks] (JVM 전역 초기화) 대신 이 방식을 사용해야 [IssueControllerTransitionIntegrationTest]
     * 의 [com.bts.workflow.engine.WorkflowDefinitionRepository] mock stub 이 지워지는 것을 방지한다 (P2 fix).
     */
    @AfterEach
    fun tearDown() {
        clearMocks(issueApplicationService)
    }

    // ── T-1: 정상 — 2건 반환 + 필드 직렬화 ────────────────────────────────────

    @Test
    fun `GET transitions — service 가 2건 반환하면 200 과 transitions 배열 2건을 반환한다`() {
        val stubTransitions =
            listOf(
                AvailableTransitionView(
                    fromStateKey = "open",
                    toStateKey = "in_progress",
                    name = "시작",
                    toCategory = "IN_PROGRESS",
                ),
                AvailableTransitionView(fromStateKey = "open", toStateKey = "closed", name = "닫기", toCategory = "DONE"),
            )

        every {
            issueApplicationService.availableTransitions(any(), IssueKey("ATLAS-1"))
        } returns stubTransitions

        mockMvc.perform(get("/api/v1/issues/ATLAS-1/transitions"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.transitions.length()").value(2))
            .andExpect(jsonPath("$.data.transitions[0].fromStateKey").value("open"))
            .andExpect(jsonPath("$.data.transitions[0].toStateKey").value("in_progress"))
            .andExpect(jsonPath("$.data.transitions[0].name").value("시작"))
            .andExpect(jsonPath("$.data.transitions[0].key").value("open__in_progress"))
            .andExpect(jsonPath("$.data.transitions[0].toCategory").value("IN_PROGRESS"))
            .andExpect(jsonPath("$.data.transitions[1].fromStateKey").value("open"))
            .andExpect(jsonPath("$.data.transitions[1].toStateKey").value("closed"))
            .andExpect(jsonPath("$.data.transitions[1].name").value("닫기"))
            .andExpect(jsonPath("$.data.transitions[1].key").value("open__closed"))
            .andExpect(jsonPath("$.data.transitions[1].toCategory").value("DONE"))
    }

    // ── T-2: IssueNotFoundException → 404 ────────────────────────────────────

    @Test
    fun `GET transitions — IssueNotFoundException 이면 404 를 반환한다`() {
        every {
            issueApplicationService.availableTransitions(any(), IssueKey("ATLAS-999"))
        } throws IssueNotFoundException(IssueKey("ATLAS-999"))

        mockMvc.perform(get("/api/v1/issues/ATLAS-999/transitions"))
            .andExpect(status().isNotFound)
    }

    // ── T-3: IssueWorkflowNotConfiguredException → 422 ───────────────────────

    @Test
    fun `GET transitions — IssueWorkflowNotConfiguredException 이면 422 를 반환한다`() {
        every {
            issueApplicationService.availableTransitions(any(), IssueKey("ATLAS-1"))
        } throws IssueWorkflowNotConfiguredException(projectKey = "ATLAS", issueTypeKey = "default")

        mockMvc.perform(get("/api/v1/issues/ATLAS-1/transitions"))
            .andExpect(status().isUnprocessableEntity)
    }
}
