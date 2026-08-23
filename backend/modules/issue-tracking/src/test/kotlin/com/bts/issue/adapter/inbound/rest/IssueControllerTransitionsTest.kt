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

/**
 * IssueController GET /api/v1/issues/{key}/transitions MockMvc 슬라이스 테스트.
 *
 * `@SpringBootApplication` 없이 `@ContextConfiguration` 으로 최소 컨텍스트를 직접 구성한다.
 * [IssueApplicationService] 는 MockK stub 으로 대체한다.
 *
 * 테스트 케이스 4건.
 * - T-1. service 가 2건 반환 시 200 + `data.transitions` 배열 2건 + 각 필드(fromStateKey, toStateKey, name, key) 직렬화 확인.
 * - T-2. service 가 [IssueNotFoundException] throw → 404.
 * - T-3. service 가 [IssueWorkflowNotConfiguredException] throw → 422.
 * - T-4. GLOBAL 전환의 `key` 가 도메인 게터 규칙(`GLOBAL__<to>`)과 같다 — 응답이 재계산하지 않는다.
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
        // CurrentActor 결선(FR-PM-06 PR-B) 이후 컨트롤러가 인증 주체를 요구하므로 SecurityContext 를 주입한다.
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(
                "11111111-1111-4111-8111-111111111111",
                null,
                listOf(SimpleGrantedAuthority("ROLE_USER")),
            )
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
        SecurityContextHolder.clearContext()
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
                    key = "open__in_progress",
                ),
                AvailableTransitionView(
                    fromStateKey = "open",
                    toStateKey = "closed",
                    name = "닫기",
                    toCategory = "DONE",
                    key = "open__closed",
                ),
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

    // ── T-4: GLOBAL 전환 key 는 도메인 게터 결과 그대로 ───────────────────────

    @Test
    fun `GET transitions — GLOBAL 전환의 key 는 재계산이 아니라 도메인 게터 결과 그대로다`() {
        // 도메인 게터(project-workflow `WorkflowTransition.key`)는 종류마다 규칙이 다르다 —
        // NORMAL 은 `from__to`, GLOBAL·INITIAL 은 `KIND__to`. 엔진은 GLOBAL 의 빈 출발 상태를
        // 요청한 현재 상태(open)로 채워 넘기므로, 응답이 fromStateKey 로 key 를 재조립하면
        // 같은 전환을 도메인은 "GLOBAL__done", 응답은 "open__done" 이라 부르는 발산이 생긴다.
        // 이 key 는 `PostActionController` 의 `.../transitions/{transitionKey}/post-actions`
        // 경로 세그먼트로 소비되므로 발산하면 그 경로가 빗나간다.
        val globalView =
            AvailableTransitionView(
                fromStateKey = "open",
                toStateKey = "done",
                name = "즉시 완료",
                toCategory = "DONE",
                kind = "GLOBAL",
                // 엔진이 도메인 게터 결과를 그대로 실어 보내는 값 — fromStateKey 와 어긋난 채로 들어온다.
                key = "GLOBAL__done",
            )

        every {
            issueApplicationService.availableTransitions(any(), IssueKey("ATLAS-1"))
        } returns listOf(globalView)

        mockMvc.perform(get("/api/v1/issues/ATLAS-1/transitions"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.transitions[0].kind").value("GLOBAL"))
            .andExpect(jsonPath("$.data.transitions[0].key").value("GLOBAL__done"))
    }
}
