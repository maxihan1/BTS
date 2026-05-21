// WorkflowController MockMvc 슬라이스 테스트 — 4 케이스 (목록/단건/전이/캐시무효화+권한)

package com.bts.workflow.web

import com.bts.workflow.cache.WorkflowCache
import com.bts.workflow.domain.StateCategory
import com.bts.workflow.domain.Workflow
import com.bts.workflow.domain.WorkflowState
import com.bts.workflow.domain.WorkflowTransition
import com.bts.workflow.domain.dto.DomainEvent
import com.bts.workflow.domain.dto.FieldChange
import com.bts.workflow.domain.dto.TransitionPlan
import com.bts.workflow.engine.WorkflowEngine
import com.bts.workflow.repository.WorkflowRepository
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.security.web.SecurityFilterChain
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * WorkflowController REST API 슬라이스 테스트.
 *
 * 테스트 케이스 4건.
 * - Case 1. GET /api/v1/workflows — 200 + 4 워크플로우 목록
 * - Case 2. GET /api/v1/workflows/{key} — 200 + 계층 구조 (states + transitions)
 * - Case 3. POST /api/v1/workflows/{key}/transitions — 200 + TransitionPlan 반환
 * - Case 4. POST /api/v1/workflows/cache/invalidate — WORKFLOW_MANAGE 권한 있으면 200, 없으면 403
 *
 * MockMvc 슬라이스 (@WebMvcTest) 에서 WorkflowCache / WorkflowEngine / WorkflowRepository 를
 * MockK stub 으로 주입한다.
 */
@WebMvcTest(controllers = [WorkflowController::class])
@Import(WorkflowControllerMvcTest.TestSecurityConfig::class, WorkflowControllerMvcTest.MockBeansConfig::class)
class WorkflowControllerMvcTest {

    /**
     * 테스트 전용 SecurityFilterChain — CSRF 비활성화, 모든 요청 허용.
     * 권한 검사는 @PreAuthorize (method security) 로만 수행한다.
     */
    @TestConfiguration
    @EnableWebSecurity
    @EnableMethodSecurity
    class TestSecurityConfig {
        @Bean
        fun testSecurityFilterChain(http: HttpSecurity): SecurityFilterChain =
            http
                .csrf { it.disable() }
                .authorizeHttpRequests { it.anyRequest().permitAll() }
                .build()
    }

    /**
     * WorkflowController 가 의존하는 Bean 을 MockK stub 으로 제공한다.
     */
    @TestConfiguration
    class MockBeansConfig {

        @Bean
        fun workflowCache(): WorkflowCache = mockk(relaxed = true)

        @Bean
        fun workflowEngine(): WorkflowEngine = mockk(relaxed = true)

        @Bean
        fun workflowRepository(): WorkflowRepository = mockk(relaxed = true)
    }

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var workflowCache: WorkflowCache

    @Autowired
    lateinit var workflowEngine: WorkflowEngine

    @Autowired
    lateinit var workflowRepository: WorkflowRepository

    private val mapper: ObjectMapper = ObjectMapper().registerKotlinModule()

    // ── Case 1: GET /api/v1/workflows — 목록 200 ─────────────────────────────

    @Test
    @WithMockUser
    fun `GET 워크플로우 목록 — 4건 반환 200`() {
        val workflows = buildFourWorkflows()
        every { workflowRepository.findAll() } returns workflows

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
        every { workflowRepository.findByKey("software-default") } returns workflow

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
        val plan = TransitionPlan(
            toStateKey = "IN_PROGRESS",
            fieldChanges = listOf(FieldChange(field = "status", oldValue = "TODO", newValue = "IN_PROGRESS")),
            emitEvents = listOf(DomainEvent(type = "ISSUE_TRANSITIONED", payload = mapOf("issueKey" to "BTS-1"))),
        )
        every { workflowEngine.plan(any()) } returns plan

        val body = mapOf(
            "toStateKey" to "IN_PROGRESS",
            "transitionName" to "시작",
            "fields" to mapOf("priority" to "HIGH"),
            "version" to 1,
            "workflowKey" to "software-default",
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

    private fun buildWorkflow(key: String, name: String, stateCount: Int, transitionCount: Int): Workflow {
        val states = (0 until stateCount).map { i ->
            WorkflowState(
                key = "STATE_$i",
                name = "상태 $i",
                category = when (i) {
                    0 -> StateCategory.TODO
                    stateCount - 1 -> StateCategory.DONE
                    else -> StateCategory.IN_PROGRESS
                },
                displayOrder = i,
            )
        }
        val transitions = (0 until transitionCount).map { i ->
            WorkflowTransition(
                fromStateKey = "STATE_$i",
                toStateKey = "STATE_${i + 1}",
                name = "전이_$i",
            )
        }
        return Workflow.of(key = key, name = name, states = states, transitions = transitions)
    }
}
