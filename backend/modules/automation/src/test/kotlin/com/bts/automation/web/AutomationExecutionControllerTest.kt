// AutomationExecutionController 풀스택 HTTP 통합 테스트 — 룰별 이력 목록 + 단건 trace + 권한가드 (FR-AT-05 Task 4)

package com.bts.automation.web

import com.bts.automation.AutomationTestBootApplication
import com.bts.automation.AutomationTestSecurityConfig
import com.bts.automation.AutomationTestcontainersBase
import com.bts.automation.StubAutomationPermissionResolver
import com.bts.automation.StubIssueMutationPort
import com.bts.automation.StubIssuePermissionResolver
import com.bts.automation.StubIssueSnapshotPort
import com.bts.automation.adapter.RuleExecutionRepository
import com.bts.automation.application.ActionExecutionStatus
import com.bts.automation.application.ActionOutcome
import com.bts.automation.application.RuleExecution
import com.bts.automation.domain.ActionType
import com.bts.automation.domain.TriggerType
import com.bts.shared.issue.IssueMutationPort
import com.bts.shared.issue.IssueSnapshotPort
import com.bts.shared.permission.IssuePermissionResolver
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import java.time.Instant
import java.util.UUID

private const val ACTOR_UUID = "22222222-2222-2222-2222-222222222222"
private const val PROJECT_KEY = "ATLAS"
private const val OTHER_PROJECT_KEY = "OTHER"

/**
 * [com.bts.automation.adapter.web.AutomationExecutionController] 풀스택 HTTP 통합 테스트 (FR-AT-05 Task 4).
 *
 * Controller → Service → 실 [RuleExecutionRepository] → **실 PostgreSQL(Testcontainers)** end-to-end.
 * MANAGE_AUTOMATION 판정만 [StubAutomationPermissionResolver] 로 대체한다([AutomationRuleControllerTest] 동형).
 * [AutomationTestSecurityConfig] 를 재사용해 웹훅 permitAll 외 나머지 경로(이 컨트롤러 포함)가 실제
 * authenticated 필터 체인으로 검증되게 한다.
 *
 * ## 커버 시나리오 (plan Task 4 RED)
 * - GET 목록 — 최신순 요약 반환·issueKey 필터·limit 200 초과 clamp(에러 아님)·미인증 401·권한 없음 403·
 *   before 형식 오류 400.
 * - GET 단건 — trace(outcomes+triggerEvent) 200·존재하지 않는 id 404·타 프로젝트 소속(권한 없음) 404
 *   (존재 숨김, 403 아님)·미인증 401.
 */
@SpringBootTest(
    classes = [AutomationTestBootApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.MOCK,
)
@Import(
    AutomationTestcontainersBase::class,
    AutomationTestSecurityConfig::class,
    AutomationExecutionControllerTest.PermissionResolverStubConfig::class,
)
class AutomationExecutionControllerTest {
    /**
     * `com.bts.automation` 전체 스캔 시 함께 로드되는 [com.bts.automation.application.AutomationRuleService]/
     * `ActionExecutor`/`RuleConflictAnalyzer` 가 non-null 로 요구하는 cross-BC 포트를 test-boot 용 stub 으로
     * 등록한다(이 컨트롤러 자신은 쓰지 않지만 컨텍스트 로드에 필요, [AutomationWebhookControllerTest] 동형).
     */
    @TestConfiguration
    class PermissionResolverStubConfig {
        @Bean
        fun automationPermissionResolver(): StubAutomationPermissionResolver = StubAutomationPermissionResolver()

        @Bean
        fun issueMutationPort(): IssueMutationPort = StubIssueMutationPort()

        @Bean
        fun issueSnapshotPort(): IssueSnapshotPort = StubIssueSnapshotPort()

        @Bean
        fun issuePermissionResolver(): IssuePermissionResolver = StubIssuePermissionResolver()
    }

    @Autowired
    @Suppress("VarCouldBeVal")
    private lateinit var webApplicationContext: WebApplicationContext

    @Autowired
    @Suppress("VarCouldBeVal")
    private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    @Suppress("VarCouldBeVal")
    private lateinit var objectMapper: ObjectMapper

    @Autowired
    @Suppress("VarCouldBeVal")
    private lateinit var repository: RuleExecutionRepository

    @Autowired
    @Suppress("VarCouldBeVal")
    private lateinit var permissionResolver: StubAutomationPermissionResolver

    private lateinit var mockMvc: MockMvc

    private val ruleId: UUID = UUID.randomUUID()

    @BeforeEach
    fun setUp() {
        mockMvc =
            MockMvcBuilders
                .webAppContextSetup(webApplicationContext)
                .apply<DefaultMockMvcBuilder>(springSecurity())
                .build()
        jdbcTemplate.update("DELETE FROM rule_executions")
        permissionResolver.reset()
        permissionResolver.allow(PROJECT_KEY)
        permissionResolver.allow(OTHER_PROJECT_KEY)
    }

    // ── 헬퍼 ──────────────────────────────────────────────────────────────────────

    private fun saveExecution(
        id: UUID = UUID.randomUUID(),
        ruleId: UUID = this.ruleId,
        projectKey: String = PROJECT_KEY,
        issueKey: String? = "ATLAS-1",
        startedAt: Instant = Instant.parse("2026-07-12T00:00:00Z"),
    ): RuleExecution {
        val execution =
            RuleExecution(
                id = id,
                ruleId = ruleId,
                projectKey = projectKey,
                triggerType = TriggerType.ISSUE_CREATED,
                triggerEvent = objectMapper.readTree("""{"issueKey":"${issueKey ?: "ATLAS-1"}"}"""),
                issueKey = issueKey,
                status = ActionExecutionStatus.SUCCESS,
                outcomes =
                    listOf(
                        ActionOutcome(position = 0, actionType = ActionType.SET_FIELD, success = true, error = null),
                    ),
                replayedFrom = null,
                startedAt = startedAt,
                finishedAt = startedAt.plusSeconds(1),
            )
        repository.save(execution)
        return execution
    }

    private fun listUrl(targetRuleId: UUID = ruleId): String =
        "/api/v1/projects/$PROJECT_KEY/automation/rules/$targetRuleId/executions"

    // ── GET 목록 ─────────────────────────────────────────────────────────────────

    @Test
    @WithMockUser(username = ACTOR_UUID)
    fun `GET 목록 - 200 최신순 요약 반환`() {
        val execution = saveExecution()

        mockMvc
            .perform(get(listUrl()))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].id").value(execution.id.toString()))
            .andExpect(jsonPath("$[0].ruleId").value(ruleId.toString()))
            .andExpect(jsonPath("$[0].triggerType").value("ISSUE_CREATED"))
            .andExpect(jsonPath("$[0].status").value("SUCCESS"))
            .andExpect(jsonPath("$[0].actionCount").value(1))
            .andExpect(jsonPath("$[0].successCount").value(1))
    }

    @Test
    @WithMockUser(username = ACTOR_UUID)
    fun `GET 목록 - issueKey 필터를 지정하면 해당 이슈 실행만 반환한다`() {
        saveExecution(issueKey = "ATLAS-1")
        val match = saveExecution(issueKey = "ATLAS-2")

        mockMvc
            .perform(get(listUrl()).param("issueKey", "ATLAS-2"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].id").value(match.id.toString()))
    }

    @Test
    @WithMockUser(username = ACTOR_UUID)
    fun `GET 목록 - limit 이 200 을 초과해도 400 이 아니라 정상 처리된다(clamp)`() {
        saveExecution()

        mockMvc
            .perform(get(listUrl()).param("limit", "99999"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(1))
    }

    @Test
    fun `GET 목록 - 미인증 - 401`() {
        mockMvc
            .perform(get(listUrl()))
            .andExpect(status().isUnauthorized)
    }

    @Test
    @WithMockUser(username = ACTOR_UUID)
    fun `GET 목록 - 권한 없음 - 403 AUTOMATION_ACCESS_DENIED`() {
        permissionResolver.deny(PROJECT_KEY)

        mockMvc
            .perform(get(listUrl()))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.errorCode").value("AUTOMATION_ACCESS_DENIED"))
    }

    @Test
    @WithMockUser(username = ACTOR_UUID)
    fun `GET 목록 - before 가 ISO Instant 형식이 아니면 400`() {
        mockMvc
            .perform(get(listUrl()).param("before", "not-an-instant"))
            .andExpect(status().isBadRequest)
    }

    // ── GET 단건 ─────────────────────────────────────────────────────────────────

    @Test
    @WithMockUser(username = ACTOR_UUID)
    fun `GET 단건 - 200 trace 상세(outcomes+triggerEvent) 반환`() {
        val execution = saveExecution()

        mockMvc
            .perform(get("/api/v1/automation/executions/${execution.id}"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id").value(execution.id.toString()))
            .andExpect(jsonPath("$.projectKey").value(PROJECT_KEY))
            .andExpect(jsonPath("$.outcomes.length()").value(1))
            .andExpect(jsonPath("$.outcomes[0].actionType").value("SET_FIELD"))
            .andExpect(jsonPath("$.outcomes[0].success").value(true))
            .andExpect(jsonPath("$.triggerEvent.issueKey").value("ATLAS-1"))
    }

    @Test
    @WithMockUser(username = ACTOR_UUID)
    fun `GET 단건 - 존재하지 않는 id - 404 AUTOMATION_EXECUTION_NOT_FOUND`() {
        mockMvc
            .perform(get("/api/v1/automation/executions/${UUID.randomUUID()}"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.errorCode").value("AUTOMATION_EXECUTION_NOT_FOUND"))
    }

    @Test
    @WithMockUser(username = ACTOR_UUID)
    fun `GET 단건 - 타 프로젝트 소속(권한 없음) - 404(존재 숨김, 403 아님)`() {
        val execution = saveExecution(projectKey = OTHER_PROJECT_KEY)
        permissionResolver.deny(OTHER_PROJECT_KEY)

        mockMvc
            .perform(get("/api/v1/automation/executions/${execution.id}"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.errorCode").value("AUTOMATION_EXECUTION_NOT_FOUND"))

        val stillExists =
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM rule_executions WHERE id = ?::uuid",
                Int::class.java,
                execution.id,
            )
        assertThat(stillExists).isEqualTo(1)
    }

    @Test
    fun `GET 단건 - 미인증 - 401`() {
        mockMvc
            .perform(get("/api/v1/automation/executions/${UUID.randomUUID()}"))
            .andExpect(status().isUnauthorized)
    }
}
