// WorkflowController MockMvc 슬라이스 테스트 — 4 케이스 (목록/단건/전환/캐시무효화+권한)

package com.bts.workflow.web

import com.bts.shared.permission.WorkflowDefinitionAccessDeniedException
import com.bts.shared.permission.WorkflowDefinitionPermission
import com.bts.shared.permission.WorkflowDefinitionPermissionResolver
import com.bts.shared.workflow.DomainEvent
import com.bts.shared.workflow.FieldChange
import com.bts.shared.workflow.TransitionPlan
import com.bts.workflow.application.WorkflowApplicationService
import com.bts.workflow.application.WorkflowCommandService
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

/** 테스트 행위자. `CurrentActor` 가 UUID 형식을 요구하므로 실제 UUID 를 쓴다. */
private const val ACTOR_ID = "11111111-2222-3333-4444-555555555555"

/**
 * 권한 판정기 목. 테스트마다 `every { ... }` 로 허용·거부를 바꾼다.
 *
 * authority 를 손수 심지 않는 것이 요점이다 — 그 방식이 발급 경로 없는 권한을 요구하는
 * 죽은 게이트를 가려 왔다.
 */
private val permissionResolver: WorkflowDefinitionPermissionResolver = mockk()

/**
 * WorkflowController REST API 슬라이스 테스트.
 *
 * `@SpringBootApplication` 없이 `@ContextConfiguration` 으로 최소 컨텍스트를 직접 구성한다.
 * Spring Security method security (@PreAuthorize) 포함.
 *
 * 테스트 케이스 4건 + 권한 실패 1건.
 * - Case 1. GET /api/v1/workflows — 200 + 4 워크플로우 목록
 * - Case 2. GET /api/v1/workflows/{key} — 200 + 계층 구조 (states + transitions)
 * - Case 3. POST /api/v1/workflows/{key}/transitions/plan — 200 + TransitionPlan 반환
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
        open fun workflowCommandService(): WorkflowCommandService = mockk(relaxed = true)

        @Bean
        open fun workflowDefinitionPermissionResolver(): WorkflowDefinitionPermissionResolver = permissionResolver

        @Bean
        open fun workflowController(
            service: WorkflowApplicationService,
            commandService: WorkflowCommandService,
            cache: WorkflowCache,
            resolver: WorkflowDefinitionPermissionResolver,
        ): WorkflowController = WorkflowController(service, commandService, cache, resolver)

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

    // ── Case 3: POST /api/v1/workflows/{key}/transitions/plan — TransitionPlan 200 ─
    //
    // ★ 경로가 옮겨졌다 (spec FR-WF-05 결정 D-1). 종전 `/{key}/transitions` 는 이제 **전환 정의
    //   컬렉션**이다 — 같은 method+path 를 두 번 매핑하면 Spring 이 기동에 실패하므로 계산 경로인
    //   `plan` 이 하위 동사 경로로 내려갔다. 자원이 아니라 계산이므로 REST 의미상으로도 이쪽이 맞다.

    @Test
    @WithMockUser
    fun `전환 계획은 POST 워크플로우 transitions plan 으로 옮겨졌다`() {
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
                "fields" to mapOf("priority" to "HIGH"),
                "version" to 1,
                "issueKey" to "BTS-1",
                "fromStateKey" to "TODO",
                "actorId" to "user-1",
                "actorRoles" to listOf("MEMBER"),
            )

        mockMvc.perform(
            post("/api/v1/workflows/software-default/transitions/plan")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.toStateKey").value("IN_PROGRESS"))
            .andExpect(jsonPath("$.data.fieldChanges[0].field").value("status"))
            .andExpect(jsonPath("$.data.events[0].type").value("ISSUE_TRANSITIONED"))
    }

    // ── Case 4: POST /api/v1/workflows/cache/invalidate — 권한 검증 ───────────
    //
    // ★ 이 두 테스트는 **반전된 것**이다. 종전에는 @WithMockUser(authorities = ["WORKFLOW_MANAGE"])
    //   로 authority 를 손수 심어 200 을 받았다. 그런데 그 authority 를 **발급하는 경로가
    //   저장소 어디에도 없었다** — 정본 권한 코드는 철자가 뒤집힌 MANAGE_WORKFLOW(V013 시드)이고,
    //   authority 를 만드는 곳 2군데(SidRevokeJwtConverter:115 · PatAuthenticationFilter:48)는
    //   둘 다 ROLE_ 접두어를 붙인다. 즉 이 엔드포인트는 **어떤 실제 요청으로도 통과할 수 없었고**,
    //   테스트만 초록이었다(unreachable-state-fixture-is-fake-green).
    //
    //   지금은 WorkflowDefinitionPermissionResolver 가 판정한다. 테스트도 authority 를 심지 않고
    //   **resolver 의 판정 결과**로 갈린다 — prod 에서 실제로 일어나는 경로와 같은 모양이다.

    @Test
    @WithMockUser(username = ACTOR_ID)
    fun `POST 캐시 무효화 — 권한 판정을 통과하면 200`() {
        every { permissionResolver.requirePermission(any(), any()) } returns Unit
        val body = mapOf("key" to "software-default")

        mockMvc.perform(
            post("/api/v1/workflows/cache/invalidate")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isOk)
    }

    @Test
    @WithMockUser(username = ACTOR_ID)
    fun `POST 캐시 무효화 — 권한 판정이 거부하면 403`() {
        every { permissionResolver.requirePermission(any(), any()) } throws
            WorkflowDefinitionAccessDeniedException(
                java.util.UUID.fromString(ACTOR_ID),
                WorkflowDefinitionPermission.UPDATE,
            )
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
                    name = "전환_$i",
                )
            }
        return Workflow.of(key = key, name = name, states = states, transitions = transitions)
    }
}
