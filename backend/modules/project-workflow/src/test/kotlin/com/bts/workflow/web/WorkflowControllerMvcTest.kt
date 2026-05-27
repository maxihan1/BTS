// WorkflowController MockMvc 슬라이스 테스트 — 4 케이스 (목록/단건/전이/캐시무효화+권한)

package com.bts.workflow.web

import com.bts.shared.workflow.DomainEvent
import com.bts.shared.workflow.FieldChange
import com.bts.shared.workflow.TransitionPlan
import com.bts.workflow.application.WorkflowApplicationService
import com.bts.workflow.cache.WorkflowCache
import com.bts.workflow.domain.StateCategory
import com.bts.workflow.domain.Workflow
import com.bts.workflow.domain.WorkflowState
import com.bts.workflow.domain.WorkflowTransition
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.MediaType
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers
import org.springframework.security.web.SecurityFilterChain
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
import org.springframework.web.servlet.config.annotation.EnableWebMvc

/**
 * WorkflowController REST API 슬라이스 테스트.
 *
 * `@SpringBootApplication` 없이 `@ContextConfiguration` 으로 최소 컨텍스트를 직접 구성한다.
 * Spring Security method security (@PreAuthorize) 포함.
 *
 * 테스트 케이스 4건 + 권한 실패 1건.
 * - Case 1. GET /api/v1/workflows — 200 + 4 워크플로우 목록
 * - Case 2. GET /api/v1/workflows/{key} — 200 + 계층 구조 (states + transitions)
 * - Case 3. POST /api/v1/workflows/{key}/transitions — 200 + TransitionPlan 반환
 * - Case 4a. POST /api/v1/workflows/cache/invalidate — WORKFLOW_MANAGE 권한 있으면 200
 * - Case 4b. POST /api/v1/workflows/cache/invalidate — WORKFLOW_MANAGE 권한 없으면 403
 */
@ExtendWith(SpringExtension::class)
@ContextConfiguration(classes = [WorkflowControllerMvcTest.TestMvcConfig::class])
@WebAppConfiguration
class WorkflowControllerMvcTest {
    /**
     * 테스트 전용 Spring MVC + Security 최소 컨텍스트.
     *
     * [WorkflowController], [WorkflowExceptionHandler] 와 MockK stub Bean 을 등록한다.
     * @EnableMethodSecurity 로 @PreAuthorize 가 동작하도록 활성화한다.
     */
    @Configuration
    @EnableWebMvc
    @EnableWebSecurity
    @EnableMethodSecurity
    open class TestMvcConfig {
        @Bean
        open fun workflowCache(): WorkflowCache = mockk(relaxed = true)

        @Bean
        open fun workflowApplicationService(): WorkflowApplicationService = mockk(relaxed = true)

        @Bean
        open fun workflowController(
            service: WorkflowApplicationService,
            cache: WorkflowCache,
        ): WorkflowController = WorkflowController(service, cache)

        @Bean
        open fun workflowExceptionHandler(): WorkflowExceptionHandler = WorkflowExceptionHandler()

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
    lateinit var workflowCache: WorkflowCache

    @Autowired
    lateinit var workflowApplicationService: WorkflowApplicationService

    lateinit var mockMvc: MockMvc

    private val mapper: ObjectMapper = ObjectMapper().registerKotlinModule()

    @BeforeEach
    fun setUp() {
        val securityConfigurer = SecurityMockMvcConfigurers.springSecurity()
        @Suppress("MaxLineLength")
        mockMvc =
            MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply<org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder>(securityConfigurer)
                .build()
    }

    // ── Case 1: GET /api/v1/workflows — 목록 200 ─────────────────────────────

    @Test
    @WithMockUser
    fun `GET 워크플로우 목록 — 4건 반환 200`() {
        val workflows = buildFourWorkflows()
        every { workflowApplicationService.listWorkflows() } returns workflows

        mockMvc.perform(get("/api/v1/workflows").accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data").isArray)
            .andExpect(jsonPath("$.data.length()").value(4))
    }

    // ── Case 2: GET /api/v1/workflows/{key} — 단건 200 ───────────────────────

    @Test
    @WithMockUser
    fun `GET 워크플로우 단건 — 계층 구조 반환 200`() {
        val workflow = buildWorkflow("software-default", "소프트웨어 기본", stateCount = 3, transitionCount = 2)
        every { workflowApplicationService.getWorkflow("software-default") } returns workflow

        mockMvc.perform(get("/api/v1/workflows/software-default").accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.key").value("software-default"))
            .andExpect(jsonPath("$.data.states.length()").value(3))
            .andExpect(jsonPath("$.data.transitions.length()").value(2))
    }

    // ── Case 3: POST /api/v1/workflows/{key}/transitions — TransitionPlan 200 ─

    @Test
    @WithMockUser
    fun `POST 전이 계획 — TransitionPlan 반환 200`() {
        val plan =
            TransitionPlan(
                toStateKey = "IN_PROGRESS",
                fieldChanges = listOf(FieldChange(field = "status", oldValue = "TODO", newValue = "IN_PROGRESS")),
                emitEvents = listOf(DomainEvent(type = "ISSUE_TRANSITIONED", payload = mapOf("issueKey" to "BTS-1"))),
            )
        every { workflowApplicationService.planTransition(any()) } returns plan

        val body =
            mapOf(
                "toStateKey" to "IN_PROGRESS",
                "transitionName" to "시작",
                "fields" to mapOf("priority" to "HIGH"),
                "version" to 1,
                "issueKey" to "BTS-1",
                "fromStateKey" to "TODO",
                "actorId" to "user-1",
                "actorRoles" to listOf("MEMBER"),
            )

        mockMvc.perform(
            post("/api/v1/workflows/software-default/transitions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.toStateKey").value("IN_PROGRESS"))
            .andExpect(jsonPath("$.data.fieldChanges[0].field").value("status"))
            .andExpect(jsonPath("$.data.events[0].type").value("ISSUE_TRANSITIONED"))
    }

    // ── Case 4: POST /api/v1/workflows/cache/invalidate — 권한 검증 ───────────

    @Test
    @WithMockUser(authorities = ["WORKFLOW_MANAGE"])
    fun `POST 캐시 무효화 — WORKFLOW_MANAGE 권한 있으면 200`() {
        val body = mapOf("key" to "software-default")

        mockMvc.perform(
            post("/api/v1/workflows/cache/invalidate")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isOk)
    }

    @Test
    @WithMockUser
    fun `POST 캐시 무효화 — WORKFLOW_MANAGE 권한 없으면 403`() {
        val body = mapOf("key" to "software-default")

        mockMvc.perform(
            post("/api/v1/workflows/cache/invalidate")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isForbidden)
    }

    // ── 테스트 픽스처 ─────────────────────────────────────────────────────────

    private fun buildFourWorkflows(): List<Workflow> =
        listOf("software-default", "service-desk", "bug-fix", "simple")
            .mapIndexed { idx, key -> buildWorkflow(key, "워크플로우 $idx", stateCount = 3, transitionCount = 2) }

    private fun buildWorkflow(
        key: String,
        name: String,
        stateCount: Int,
        transitionCount: Int,
    ): Workflow {
        val states =
            (0 until stateCount).map { i ->
                WorkflowState(
                    key = "STATE_$i",
                    name = "상태 $i",
                    category =
                        when (i) {
                            0 -> StateCategory.TODO
                            stateCount - 1 -> StateCategory.DONE
                            else -> StateCategory.IN_PROGRESS
                        },
                    displayOrder = i,
                )
            }
        val transitions =
            (0 until transitionCount).map { i ->
                WorkflowTransition(
                    fromStateKey = "STATE_$i",
                    toStateKey = "STATE_${i + 1}",
                    name = "전이_$i",
                )
            }
        return Workflow.of(key = key, name = name, states = states, transitions = transitions)
    }
}
