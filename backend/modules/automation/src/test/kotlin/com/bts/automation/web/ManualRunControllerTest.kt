// ManualRunController 통합 테스트 — MANUAL 룰 목록(BROWSE) · 이슈로 실행 202+enqueue · 존재 숨김 404 · 401 (R9)

package com.bts.automation.web

import com.bts.automation.AutomationTestBootApplication
import com.bts.automation.AutomationTestSecurityConfig
import com.bts.automation.AutomationTestcontainersBase
import com.bts.automation.StubAutomationPermissionResolver
import com.bts.automation.StubIssueMutationPort
import com.bts.automation.StubIssueSnapshotPort
import com.bts.automation.adapter.AutomationRuleRepository
import com.bts.automation.domain.AutomationRule
import com.bts.automation.domain.TriggerType
import com.bts.shared.issue.IssueMutationPort
import com.bts.shared.issue.IssueSnapshot
import com.bts.shared.permission.IssuePermission
import com.bts.shared.permission.IssuePermissionResolver
import com.bts.shared.permission.IssueScope
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@SpringBootTest(
    classes = [AutomationTestBootApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.MOCK,
)
@Import(
    AutomationTestcontainersBase::class,
    AutomationTestSecurityConfig::class,
    ManualRunControllerTest.StubConfig::class,
)
class ManualRunControllerTest {
    /** BROWSE 허용 프로젝트를 테스트가 정하는 [IssuePermissionResolver] — 기본 stub 은 AlwaysAllow 라 403 을 못 본다. */
    class BrowsePermissionResolver : IssuePermissionResolver {
        val browsable: MutableSet<String> = ConcurrentHashMap.newKeySet()

        override fun hasPermission(
            actorId: UUID,
            permission: IssuePermission,
            scope: IssueScope,
        ): Boolean = permission != IssuePermission.BROWSE || (scope is IssueScope.Project && scope.key in browsable)
    }

    @TestConfiguration
    class StubConfig {
        @Bean
        fun automationPermissionResolver(): StubAutomationPermissionResolver = StubAutomationPermissionResolver()

        @Bean
        fun issueMutationPort(): IssueMutationPort = StubIssueMutationPort()

        @Bean
        fun issueSnapshotPort(): StubIssueSnapshotPort = StubIssueSnapshotPort()

        @Bean
        fun issuePermissionResolver(): BrowsePermissionResolver = BrowsePermissionResolver()
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
    private lateinit var snapshotPort: StubIssueSnapshotPort

    @Autowired
    @Suppress("VarCouldBeVal")
    private lateinit var permissionResolver: BrowsePermissionResolver

    private lateinit var mockMvc: MockMvc

    private val now: Instant = Instant.parse("2026-10-28T00:00:00Z")

    @BeforeEach
    fun setUp() {
        mockMvc =
            MockMvcBuilders
                .webAppContextSetup(webApplicationContext)
                .apply<DefaultMockMvcBuilder>(springSecurity())
                .build()
        jdbcTemplate.update("DELETE FROM automation_rules")
        jdbcTemplate.execute("SELECT pgmq.purge_queue('q_automation_execution')")
        snapshotPort.reset()
        permissionResolver.browsable.clear()
    }

    private fun saveRule(
        triggerType: TriggerType = TriggerType.MANUAL,
        projectKey: String = PROJECT_KEY,
        name: String = "수동 룰",
        enabled: Boolean = true,
    ): AutomationRule {
        val rule =
            AutomationRule.create(
                projectKey = projectKey,
                name = name,
                triggerType = triggerType,
                triggerConfig = if (triggerType == TriggerType.SCHEDULED) """{"cron":"0 0 9 * * *"}""" else "{}",
                createdBy = UUID.randomUUID(),
                now = now,
            )
        ruleRepository.save(rule)
        if (!enabled) ruleRepository.update(rule.disable(now))
        return rule
    }

    private fun seedVisible(issueKey: String) {
        val snapshot =
            IssueSnapshot(
                key = issueKey,
                projectKey = issueKey.substringBefore('-'),
                type = null,
                status = "open",
                priority = null,
                assigneeId = null,
                reporterId = null,
                labels = emptyList(),
                summary = "제목",
            )
        snapshotPort.seed(ACTOR, issueKey, snapshot)
    }

    private fun runUrl(ruleId: UUID) = "/api/v1/projects/$PROJECT_KEY/automation/manual-rules/$ruleId/run"

    private fun runBody(issueKey: String) = """{"issueKey":"$issueKey"}"""

    private fun executionMessages() =
        jdbcTemplate
            .queryForList("SELECT message::text FROM pgmq.read('q_automation_execution', 1, 50)", String::class.java)
            .map { objectMapper.readTree(it) }

    // ── 목록 ────────────────────────────────────────────────────────────────────

    @Test
    @WithMockUser(username = ACTOR_UUID)
    fun `GET 목록 - BROWSE 권한이면 켜진 MANUAL 룰만 준다`() {
        permissionResolver.browsable += PROJECT_KEY
        val manual = saveRule(name = "릴리스 노트 달기")
        saveRule(name = "꺼진 수동 룰", enabled = false)
        saveRule(triggerType = TriggerType.ISSUE_CREATED, name = "생성 룰")
        saveRule(projectKey = "OTHER", name = "다른 프로젝트 수동 룰")

        mockMvc
            .perform(get("/api/v1/projects/$PROJECT_KEY/automation/manual-rules"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].id").value(manual.id.toString()))
            .andExpect(jsonPath("$[0].name").value("릴리스 노트 달기"))
    }

    @Test
    @WithMockUser(username = ACTOR_UUID)
    fun `GET 목록 - BROWSE 권한이 없으면 403`() {
        saveRule()

        mockMvc
            .perform(get("/api/v1/projects/$PROJECT_KEY/automation/manual-rules"))
            .andExpect(status().isForbidden)
    }

    @Test
    fun `GET 목록 - 미인증 401`() {
        mockMvc
            .perform(get("/api/v1/projects/$PROJECT_KEY/automation/manual-rules"))
            .andExpect(status().isUnauthorized)
    }

    // ── 실행 ────────────────────────────────────────────────────────────────────

    @Test
    @WithMockUser(username = ACTOR_UUID)
    fun `POST run - 볼 수 있는 이슈면 202 와 서버가 만든 triggerEvent 로 실행 큐에 들어간다`() {
        val rule = saveRule()
        seedVisible("ATLAS-1")

        mockMvc
            .perform(
                post(runUrl(rule.id))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"issueKey":"ATLAS-1","projectKey":"OTHER","extra":"x"}"""),
            ).andExpect(status().isAccepted)

        val messages = executionMessages()
        assertThat(messages).hasSize(1)
        assertThat(messages[0].get("ruleId").asText()).isEqualTo(rule.id.toString())
        assertThat(messages[0].get("triggerType").asText()).isEqualTo("MANUAL")
        val event = messages[0].get("triggerEvent")
        assertThat(event.get("type").asText()).isEqualTo("manual")
        assertThat(event.get("issueKey").asText()).isEqualTo("ATLAS-1")
        assertThat(event.get("projectKey").asText()).isEqualTo(PROJECT_KEY)
        assertThat(event.get("actorId").asText()).isEqualTo(ACTOR_UUID)
        assertThat(event.has("extra")).isFalse()
    }

    @Test
    @WithMockUser(username = ACTOR_UUID)
    fun `POST run - 볼 수 없는 이슈는 404 이고 큐에 안 들어간다`() {
        val rule = saveRule()

        mockMvc
            .perform(post(runUrl(rule.id)).contentType(MediaType.APPLICATION_JSON).content(runBody("ATLAS-2")))
            .andExpect(status().isNotFound)

        assertThat(executionMessages()).isEmpty()
    }

    @Test
    @WithMockUser(username = ACTOR_UUID)
    fun `POST run - 다른 프로젝트 이슈 키는 404`() {
        val rule = saveRule()
        seedVisible("OTHER-1")

        mockMvc
            .perform(post(runUrl(rule.id)).contentType(MediaType.APPLICATION_JSON).content(runBody("OTHER-1")))
            .andExpect(status().isNotFound)

        assertThat(executionMessages()).isEmpty()
    }

    @Test
    @WithMockUser(username = ACTOR_UUID)
    fun `POST run - MANUAL 아님 · 꺼짐 · 다른 프로젝트 · 없는 룰은 모두 404`() {
        seedVisible("ATLAS-3")
        val targets =
            listOf(
                saveRule(triggerType = TriggerType.ISSUE_CREATED).id,
                saveRule(enabled = false).id,
                saveRule(projectKey = "OTHER").id,
                UUID.randomUUID(),
            )

        targets.forEach { ruleId ->
            mockMvc
                .perform(post(runUrl(ruleId)).contentType(MediaType.APPLICATION_JSON).content(runBody("ATLAS-3")))
                .andExpect(status().isNotFound)
        }

        assertThat(executionMessages()).isEmpty()
    }

    @Test
    @WithMockUser(username = ACTOR_UUID)
    fun `POST run - issueKey 없는 본문은 400`() {
        val rule = saveRule()

        mockMvc
            .perform(post(runUrl(rule.id)).contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `POST run - 미인증 401`() {
        mockMvc
            .perform(
                post(runUrl(UUID.randomUUID())).contentType(MediaType.APPLICATION_JSON).content(runBody("ATLAS-1")),
            ).andExpect(status().isUnauthorized)
    }

    private companion object {
        const val PROJECT_KEY = "ATLAS"
        const val ACTOR_UUID = "aaaaaaaa-1111-2222-3333-444444444444"
        val ACTOR: UUID = UUID.fromString(ACTOR_UUID)
    }
}
