// 워크플로우·상태 쓰기 API 의 요청 DTO 검증이 실제로 400 을 내는지 — 장식 애노테이션 재발 방지 (WF-04-5)

package com.bts.workflow.web

import com.bts.shared.permission.WorkflowDefinitionPermissionResolver
import com.bts.workflow.application.WorkflowApplicationService
import com.bts.workflow.application.WorkflowCommandService
import com.bts.workflow.application.WorkflowStatusCompositionService
import com.bts.workflow.cache.WorkflowCache
import com.bts.workflow.globalOnlyOwnershipScope
import com.bts.workflow.status.application.StatusCommandService
import com.bts.workflow.status.web.StatusController
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.mockk.clearMocks
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.MediaType
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers
import org.springframework.security.web.SecurityFilterChain
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.junit.jupiter.SpringExtension
import org.springframework.test.context.web.WebAppConfiguration
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import org.springframework.web.servlet.config.annotation.EnableWebMvc

private const val ACTOR_ID = "11111111-2222-3333-4444-555555555555"

/**
 * 요청 DTO 의 `@NotBlank` · `@Pattern` · `@NotEmpty` 가 **읽히는지** 못박는다.
 *
 * 종전에는 `@RequestBody` 에 `@Valid` 가 없고 모듈에 검증 구현체도 없어 이 애노테이션들이 장식이었다 —
 * `key = "../../users"` 인 워크플로우가 그대로 저장됐다. 400 케이스는 서비스가 **불리지 않았음**까지 본다.
 */
@ExtendWith(SpringExtension::class)
@ContextConfiguration(classes = [RequestValidationMvcTest.TestMvcConfig::class])
@WebAppConfiguration
class RequestValidationMvcTest {
    @Configuration
    @EnableWebMvc
    @EnableWebSecurity
    open class TestMvcConfig {
        @Bean
        open fun workflowCommandService(): WorkflowCommandService = mockk(relaxed = true)

        @Bean
        open fun compositionService(): WorkflowStatusCompositionService = mockk(relaxed = true)

        @Bean
        open fun statusCommandService(): StatusCommandService = mockk(relaxed = true)

        @Bean
        open fun workflowController(commandService: WorkflowCommandService): WorkflowController =
            WorkflowController(
                mockk<WorkflowApplicationService>(relaxed = true),
                commandService,
                mockk<WorkflowCache>(relaxed = true),
                mockk<WorkflowDefinitionPermissionResolver>(relaxed = true),
                globalOnlyOwnershipScope(),
            )

        @Bean
        open fun compositionController(service: WorkflowStatusCompositionService): WorkflowStatusCompositionController =
            WorkflowStatusCompositionController(service)

        @Bean
        open fun statusController(service: StatusCommandService): StatusController = StatusController(service)

        @Bean
        open fun validationHandler(): RequestValidationExceptionHandler = RequestValidationExceptionHandler()

        @Bean
        open fun testSecurityFilterChain(http: HttpSecurity): SecurityFilterChain =
            http
                .csrf { it.disable() }
                .authorizeHttpRequests { it.anyRequest().permitAll() }
                .build()
    }

    @Autowired
    lateinit var webApplicationContext: WebApplicationContext

    @Autowired
    lateinit var workflowCommandService: WorkflowCommandService

    @Autowired
    lateinit var compositionService: WorkflowStatusCompositionService

    @Autowired
    lateinit var statusCommandService: StatusCommandService

    lateinit var mockMvc: MockMvc

    private val mapper: ObjectMapper = ObjectMapper().registerKotlinModule()

    @BeforeEach
    fun setUp() {
        clearMocks(workflowCommandService, compositionService, statusCommandService)
        mockMvc =
            MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply<DefaultMockMvcBuilder>(SecurityMockMvcConfigurers.springSecurity())
                .build()
    }

    private fun json(
        request: MockHttpServletRequestBuilder,
        body: Any,
    ) = mockMvc.perform(request.contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body)))

    private fun createBody(
        key: String,
        name: String = "새 흐름",
    ) = mapOf(
        "key" to key,
        "name" to name,
        "statuses" to listOf(mapOf("key" to "TODO", "name" to "할 일", "category" to "TODO", "displayOrder" to 1)),
    )

    @Test
    @WithMockUser(username = ACTOR_ID)
    fun `경로 조각이 든 key 로 워크플로우를 만들면 400 이고 저장하지 않는다`() {
        json(post("/api/v1/workflows"), createBody("../../users"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error.code").value("WORKFLOW_INVALID_REQUEST"))

        verify(exactly = 0) { workflowCommandService.create(any(), any()) }
    }

    @Test
    @WithMockUser(username = ACTOR_ID)
    fun `복제 화면이 만드는 key 모양은 통과한다`() {
        json(post("/api/v1/workflows"), createBody("bug_tracking-copy2"))
            .andExpect(status().isCreated)
    }

    @Test
    @WithMockUser(username = ACTOR_ID)
    fun `이름이 공백뿐이면 400 이다`() {
        json(post("/api/v1/workflows"), createBody("fine-key", name = "   "))
            .andExpect(status().isBadRequest)

        verify(exactly = 0) { workflowCommandService.create(any(), any()) }
    }

    @Test
    @WithMockUser(username = ACTOR_ID)
    fun `복제 대상 key 도 같은 형식 검사를 받는다`() {
        json(post("/api/v1/workflows/simple/duplicate"), mapOf("key" to "a/b", "name" to "사본"))
            .andExpect(status().isBadRequest)

        verify(exactly = 0) { workflowCommandService.duplicate(any(), any(), any(), any(), any()) }
    }

    @Test
    @WithMockUser(username = ACTOR_ID)
    fun `빈 순서 목록으로 편성 순서를 바꾸면 400 이다`() {
        json(put("/api/v1/workflows/simple/statuses/order"), mapOf("statusIds" to emptyList<String>()))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error.code").value("WORKFLOW_INVALID_REQUEST"))

        verify(exactly = 0) { compositionService.reorder(any(), any(), any()) }
    }

    @Test
    @WithMockUser(username = ACTOR_ID)
    fun `범주가 셋 중 하나가 아닌 상태는 만들지 않는다`() {
        json(post("/api/v1/statuses"), mapOf("key" to "REVIEW", "name" to "검토", "category" to "WAITING"))
            .andExpect(status().isBadRequest)

        verify(exactly = 0) { statusCommandService.create(any(), any()) }
    }
}
