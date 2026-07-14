// RuleExecutionService.replay 통합 테스트 — 실 HTTP POST→서비스→실 ActionExecutor(StubIssueMutationPort 관측)→실 PostgreSQL 저장 end-to-end (FR-AT-05 Task 5)

package com.bts.automation.integration

import com.bts.automation.AutomationTestBootApplication
import com.bts.automation.AutomationTestSecurityConfig
import com.bts.automation.AutomationTestcontainersBase
import com.bts.automation.StubAutomationPermissionResolver
import com.bts.automation.StubIssueMutationPort
import com.bts.automation.StubIssuePermissionResolver
import com.bts.automation.StubIssueSnapshotPort
import com.bts.automation.adapter.AutomationRuleRepository
import com.bts.automation.adapter.RuleExecutionRepository
import com.bts.automation.application.ActionExecutionStatus
import com.bts.automation.application.ActionOutcome
import com.bts.automation.application.RuleExecution
import com.bts.automation.domain.Action
import com.bts.automation.domain.ActionType
import com.bts.automation.domain.AutomationRule
import com.bts.automation.domain.TriggerType
import com.bts.shared.issue.IssueSnapshotPort
import com.bts.shared.permission.IssuePermissionResolver
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.TextNode
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import java.time.Instant
import java.util.UUID

private const val ACTOR_UUID = "33333333-3333-3333-3333-333333333333"
private const val PROJECT_KEY = "REPLAY"

/**
 * [com.bts.automation.application.RuleExecutionService.replay] end-to-end 통합 테스트 (FR-AT-05 Task 5).
 *
 * `POST /api/v1/automation/executions/{id}/replay` → 컨트롤러 → 서비스 → 실 [AutomationRuleRepository]/
 * [RuleExecutionRepository](Testcontainers PostgreSQL) → 실 `ActionExecutor` → [StubIssueMutationPort]
 * (consumer-owns-stub — prod `IssueMutationPort` 어댑터는 issue-tracking 소속이라 automation 클래스패스
 * 밖, [com.bts.automation.ActionExecutionEndToEndIntegrationTest] 동형 사유) 까지 전체 배선을 검증한다.
 *
 * [com.bts.automation.web.AutomationExecutionControllerTest] 가 이미 HTTP 상태/에러 코드 계약(200/409/
 * 401/404)을 검증했으므로, 이 테스트는 **replay 가 실제로 무엇을 하는지**에 집중한다 — 저장된 원본
 * trigger 로 dryRun=false 실제 mutation 을 위임하는지, `rule_executions` 에 `replayed_from` 이 채워진
 * 새 행이 저장되는지.
 */
@SpringBootTest(
    classes = [AutomationTestBootApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.MOCK,
)
@Import(
    AutomationTestcontainersBase::class,
    AutomationTestSecurityConfig::class,
    RuleExecutionReplayIntegrationTest.TestSupportConfig::class,
)
class RuleExecutionReplayIntegrationTest {
    /** [com.bts.automation.web.AutomationExecutionControllerTest.PermissionResolverStubConfig] 동형 stub 등록. */
    @TestConfiguration
    class TestSupportConfig {
        @Bean
        fun automationPermissionResolver(): StubAutomationPermissionResolver = StubAutomationPermissionResolver()

        @Bean
        fun issueMutationPort(): StubIssueMutationPort = StubIssueMutationPort()

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
    private lateinit var ruleRepository: AutomationRuleRepository

    @Autowired
    @Suppress("VarCouldBeVal")
    private lateinit var executionRepository: RuleExecutionRepository

    @Autowired
    @Suppress("VarCouldBeVal")
    private lateinit var issueMutationPort: StubIssueMutationPort

    @Autowired
    @Suppress("VarCouldBeVal")
    private lateinit var permissionResolver: StubAutomationPermissionResolver

    private lateinit var mockMvc: MockMvc

    @BeforeEach
    fun setUp() {
        mockMvc =
            MockMvcBuilders
                .webAppContextSetup(webApplicationContext)
                .apply<DefaultMockMvcBuilder>(springSecurity())
                .build()
        jdbcTemplate.update("DELETE FROM rule_executions")
        jdbcTemplate.update("DELETE FROM automation_rules")
        issueMutationPort.reset()
        permissionResolver.reset()
        permissionResolver.allow(PROJECT_KEY)
    }

    // ── 헬퍼 ──────────────────────────────────────────────────────────────────

    private fun saveRule(actorUserId: UUID = UUID.randomUUID()): AutomationRule {
        val rule =
            AutomationRule.create(
                projectKey = PROJECT_KEY,
                name = "replay 통합테스트 룰",
                triggerType = TriggerType.ISSUE_CREATED,
                createdBy = UUID.randomUUID(),
                actorUserId = actorUserId,
                actions = listOf(Action.SetFieldAction(field = "priority", value = TextNode("High"))),
                now = Instant.parse("2026-07-12T00:00:00Z"),
            )
        ruleRepository.save(rule)
        return rule
    }

    private fun saveOriginalExecution(ruleId: UUID): RuleExecution {
        val execution =
            RuleExecution(
                id = UUID.randomUUID(),
                ruleId = ruleId,
                projectKey = PROJECT_KEY,
                triggerType = TriggerType.ISSUE_CREATED,
                triggerEvent = objectMapper.readTree("""{"issueKey":"REPLAY-1"}"""),
                issueKey = "REPLAY-1",
                status = ActionExecutionStatus.SUCCESS,
                outcomes =
                    listOf(ActionOutcome(position = 0, actionType = ActionType.SET_FIELD, success = true, error = null)),
                replayedFrom = null,
                startedAt = Instant.parse("2026-07-12T00:00:00Z"),
                finishedAt = Instant.parse("2026-07-12T00:00:01Z"),
            )
        executionRepository.save(execution)
        return execution
    }

    // ── replay ────────────────────────────────────────────────────────────────

    @Test
    @WithMockUser(username = ACTOR_UUID)
    fun `replay 는 저장된 원본 trigger 로 dryRun=false 실제 mutation 을 위임하고 replayed_from 이 채워진 새 실행을 rule_executions 에 저장한다`() {
        val actorUserId = UUID.randomUUID()
        val rule = saveRule(actorUserId = actorUserId)
        val original = saveOriginalExecution(rule.id)

        val response =
            mockMvc
                .perform(post("/api/v1/automation/executions/${original.id}/replay"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.replayedFrom").value(original.id.toString()))
                .andReturn()
                .response
                .contentAsString
        val newExecutionId = UUID.fromString(objectMapper.readTree(response).get("id").asText())

        // 실제 mutation 위임 — 룰 actor(actorUserId)로 dryRun=false 호출됨을 관측.
        val cmd = issueMutationPort.setFieldCalls.single()
        assertThat(cmd.issueKey).isEqualTo("REPLAY-1")
        assertThat(cmd.actorUserId).isEqualTo(actorUserId)
        assertThat(cmd.dryRun).isFalse()

        // rule_executions 신규 행 — replayed_from 이 원본을 가리키고, 원본은 그대로 남는다.
        val persisted = executionRepository.findById(newExecutionId)
        assertThat(persisted).isNotNull()
        assertThat(persisted!!.replayedFrom).isEqualTo(original.id)
        assertThat(persisted.ruleId).isEqualTo(rule.id)
        assertThat(persisted.triggerType).isEqualTo(original.triggerType)
        assertThat(persisted.issueKey).isEqualTo(original.issueKey)
        assertThat(persisted.status).isEqualTo(ActionExecutionStatus.SUCCESS)

        assertThat(executionRepository.findById(original.id)).isNotNull()
    }
}
