// AutomationRuleController export 엔드포인트 통합 테스트 — YAML 방출 + 결정적 순서 + 토큰 미노출 + 권한가드 (FR-AT-06 GitOps Task 3)

package com.bts.automation.web

import com.bts.automation.AutomationTestBootApplication
import com.bts.automation.AutomationTestcontainersBase
import com.bts.automation.StubAutomationPermissionResolver
import com.bts.automation.StubIssueMutationPort
import com.bts.automation.StubIssuePermissionResolver
import com.bts.automation.StubIssueSnapshotPort
import com.bts.automation.gitops.AutomationYamlCodec
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
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.HttpStatusEntryPoint
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import java.util.UUID

/** `@WithMockUser` username 은 컴파일 상수여야 하므로 top-level const 로 둔다(actor 는 principal name=UUID). */
private const val ACTOR_UUID = "33333333-3333-3333-3333-333333333333"
private const val PROJECT_KEY = "GITEXP"

/** 화이트리스트 필드([com.bts.automation.domain.Condition.FIELD_WHITELIST])를 참조하는 유효 조건 표현식. */
private const val VALID_CONDITION = """{"==":[{"var":"issue.status"},"open"]}"""

/** 요청 바디 조립용 액션 1건 표현([com.bts.automation.adapter.web.dto.ActionRequest] 와 필드 대칭). */
private data class ActionSpec(val type: String, val config: String)

/**
 * `GET /api/v1/projects/{projectKey}/automation/rules/export` 통합 테스트 (FR-AT-06 GitOps Task 3).
 *
 * Controller → [com.bts.automation.application.AutomationRuleService.exportRules] → 실
 * [com.bts.automation.adapter.AutomationRuleRepository]/[com.bts.automation.adapter.AutomationActionRepository]/
 * [com.bts.automation.adapter.AutomationConditionRepository] → **실 PostgreSQL(Testcontainers)** end-to-end.
 * [AutomationRuleControllerTest] 의 `TestSupportConfig` 동형 인프라를 재사용한다(BC 격리 — automation
 * 클래스패스에 identity-access 구현이 없어 [StubAutomationPermissionResolver] 로 MANAGE_AUTOMATION 판정을
 * 대체).
 *
 * ## 커버 시나리오 (plan Task 3 RED)
 * 1. 활성 2(webhook 다중 액션 1 + 조건 포함 1) + 비활성 1, 총 3규칙 시드 → 200 `application/yaml` +
 *    `Content-Disposition: attachment; filename="automation-rules-{projectKey}.yaml"`.
 * 2. YAML 본문에 3규칙 전부(활성+비활성) 포함, webhook 원문 토큰/DB 해시 문자열 미노출(NFR3), 규칙 순서가
 *    [com.bts.automation.adapter.AutomationRuleRepository] 의 `created_at, id` 결정적 순서와 일치(FR1).
 * 3. MANAGE_AUTOMATION 권한 없음 → 403 `AUTOMATION_ACCESS_DENIED`.
 */
@SpringBootTest(
    classes = [AutomationTestBootApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.MOCK,
)
@Import(
    AutomationTestcontainersBase::class,
    AutomationRuleExportIntegrationTest.TestSupportConfig::class,
)
class AutomationRuleExportIntegrationTest {
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
    private lateinit var permissionResolver: StubAutomationPermissionResolver

    private lateinit var mockMvc: MockMvc

    @BeforeEach
    fun setUp() {
        mockMvc =
            MockMvcBuilders
                .webAppContextSetup(webApplicationContext)
                .apply<org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder>(springSecurity())
                .build()
        jdbcTemplate.update("DELETE FROM automation_rules")
        permissionResolver.reset()
        permissionResolver.allow(PROJECT_KEY)
    }

    @Test
    @WithMockUser(username = ACTOR_UUID)
    fun `GET export - 규칙 3개(활성2+비활성1) 시드 - 200 application-yaml + Content-Disposition`() {
        seedThreeRules()

        mockMvc
            .perform(get("/api/v1/projects/$PROJECT_KEY/automation/rules/export"))
            .andExpect(status().isOk)
            .andExpect(content().contentType(MediaType.parseMediaType("application/yaml")))
            .andExpect(
                header().string(
                    "Content-Disposition",
                    "attachment; filename=\"automation-rules-$PROJECT_KEY.yaml\"",
                ),
            )
    }

    @Test
    @WithMockUser(username = ACTOR_UUID)
    fun `GET export - YAML 본문에 활성+비활성 3규칙 전부, 결정적 순서, webhook 토큰-해시 미노출`() {
        val seeded = seedThreeRules()

        val yaml =
            mockMvc
                .perform(get("/api/v1/projects/$PROJECT_KEY/automation/rules/export"))
                .andExpect(status().isOk)
                .andReturn()
                .response
                .contentAsString

        // NFR3 — webhook 원문 토큰도, DB 에 저장된 해시도 YAML 본문 어디에도 나타나지 않는다.
        assertThat(yaml).doesNotContain(seeded.webhookToken)
        val storedHash =
            jdbcTemplate.queryForObject(
                "SELECT webhook_token_hash FROM automation_rules WHERE id = ?::uuid",
                String::class.java,
                seeded.webhookRuleId.toString(),
            )
        assertThat(yaml).doesNotContain(storedHash)

        val parsed = AutomationYamlCodec.fromYaml(yaml)
        assertThat(parsed.projectKey).isEqualTo(PROJECT_KEY)
        assertThat(parsed.rules).hasSize(3)
        assertThat(parsed.rules.map { it.id }).containsExactlyInAnyOrder(
            seeded.webhookRuleId,
            seeded.conditionRuleId,
            seeded.disabledRuleId,
        )

        val disabled = parsed.rules.single { it.id == seeded.disabledRuleId }
        assertThat(disabled.enabled).isFalse()
        val conditionRule = parsed.rules.single { it.id == seeded.conditionRuleId }
        assertThat(conditionRule.condition).isNotNull()
        val webhookRule = parsed.rules.single { it.id == seeded.webhookRuleId }
        assertThat(webhookRule.actions).hasSize(2)

        // FR1 결정적 순서 — YAML 규칙 순서가 repository 의 `created_at, id` 순서와 정확히 일치해야 한다.
        val dbOrder =
            jdbcTemplate.queryForList(
                "SELECT id FROM automation_rules WHERE project_key = ? ORDER BY created_at, id",
                String::class.java,
                PROJECT_KEY,
            )
        assertThat(parsed.rules.map { it.id.toString() }).isEqualTo(dbOrder)
    }

    @Test
    @WithMockUser(username = ACTOR_UUID)
    fun `GET export - MANAGE_AUTOMATION 권한 없음 - 403 AUTOMATION_ACCESS_DENIED`() {
        permissionResolver.deny(PROJECT_KEY)

        mockMvc
            .perform(get("/api/v1/projects/$PROJECT_KEY/automation/rules/export"))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.errorCode").value("AUTOMATION_ACCESS_DENIED"))
    }

    // ── 헬퍼 ─────────────────────────────────────────────────────────────────

    /** [seedThreeRules] 가 시드한 3규칙의 id/토큰 — 이후 단언에서 재사용한다. */
    private data class SeededRules(
        val webhookRuleId: UUID,
        val webhookToken: String,
        val conditionRuleId: UUID,
        val disabledRuleId: UUID,
    )

    /** 활성 2(webhook 다중 액션 + 조건 포함) + 비활성 1, 총 3규칙을 시드한다(plan Task 3 RED 시나리오). */
    private fun seedThreeRules(): SeededRules {
        val (webhookRuleId, webhookToken) = createWebhookRule()
        val conditionRuleId =
            createRule(
                name = "조건 포함 규칙",
                triggerType = "ISSUE_CREATED",
                condition = VALID_CONDITION,
                actions = listOf(ActionSpec("SET_FIELD", """{"field":"priority","value":"High"}""")),
            )
        val disabledRuleId =
            createRule(
                name = "비활성 규칙",
                triggerType = "ISSUE_CREATED",
                actions = listOf(ActionSpec("ADD_COMMENT", """{"body":"자동 처리됨"}""")),
            )
        disableRule(disabledRuleId)
        return SeededRules(
            webhookRuleId = UUID.fromString(webhookRuleId),
            webhookToken = webhookToken,
            conditionRuleId = UUID.fromString(conditionRuleId),
            disabledRuleId = UUID.fromString(disabledRuleId),
        )
    }

    /** WEBHOOK 트리거 + 액션 2개짜리 규칙을 생성하고 (id, 1회 노출 원문 토큰) 을 반환한다. */
    private fun createWebhookRule(): Pair<String, String> {
        val response =
            mockMvc
                .perform(
                    post("/api/v1/projects/$PROJECT_KEY/automation/rules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            createRequestJson(
                                name = "웹훅 다중 액션 규칙",
                                triggerType = "WEBHOOK",
                                actions =
                                    listOf(
                                        ActionSpec("SET_FIELD", """{"field":"priority","value":"High"}"""),
                                        ActionSpec("ADD_COMMENT", """{"body":"웹훅 처리됨"}"""),
                                    ),
                            ),
                        ),
                ).andExpect(status().isCreated)
                .andReturn()
                .response
                .contentAsString
        val node = objectMapper.readTree(response)
        return node.get("rule").get("id").asText() to node.get("webhookToken").asText()
    }

    private fun createRequestJson(
        name: String,
        triggerType: String,
        triggerConfig: String = "{}",
        condition: String? = null,
        actions: List<ActionSpec> = emptyList(),
    ): String {
        val body =
            mutableMapOf<String, Any?>(
                "name" to name,
                "triggerType" to triggerType,
                "triggerConfig" to triggerConfig,
                "actions" to actions.map { mapOf("type" to it.type, "config" to it.config) },
            )
        if (condition != null) body["condition"] = condition
        return objectMapper.writeValueAsString(body)
    }

    /** `POST` 로 룰을 생성하고 생성된 id 를 반환하는 테스트 헬퍼([AutomationRuleControllerTest] 동형). */
    private fun createRule(
        name: String,
        triggerType: String,
        triggerConfig: String = "{}",
        condition: String? = null,
        actions: List<ActionSpec> = emptyList(),
    ): String {
        val response =
            mockMvc
                .perform(
                    post("/api/v1/projects/$PROJECT_KEY/automation/rules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createRequestJson(name, triggerType, triggerConfig, condition, actions)),
                ).andExpect(status().isCreated)
                .andReturn()
                .response
                .contentAsString
        return objectMapper.readTree(response).get("rule").get("id").asText()
    }

    /** [ruleId] 규칙을 `enabled=false` 로 PATCH 한다(생성 직후 version=0 전제). */
    private fun disableRule(ruleId: String) {
        mockMvc
            .perform(
                patch("/api/v1/projects/$PROJECT_KEY/automation/rules/$ruleId")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"version":0,"enabled":false}"""),
            ).andExpect(status().isOk)
    }

    /**
     * 테스트 전용 인가 필터체인 + 협력자 stub 빈 등록([AutomationRuleControllerTest.TestSupportConfig] 동형).
     *
     * `AutomationTestcontainersBase`(다른 Task 산출물)는 이 Task 의 파일 범위 밖이라 `@Bean` 을 추가할 수
     * 없어 이 파일 자체의 nested `@TestConfiguration` 에서 등록한다.
     */
    @TestConfiguration
    class TestSupportConfig {
        @Bean
        fun automationTestSecurityFilterChain(http: HttpSecurity): SecurityFilterChain {
            http
                .csrf { it.disable() }
                .authorizeHttpRequests { it.anyRequest().authenticated() }
                .exceptionHandling {
                    it.authenticationEntryPoint(HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED))
                }
            return http.build()
        }

        @Bean
        fun stubAutomationPermissionResolver(): StubAutomationPermissionResolver = StubAutomationPermissionResolver()

        @Bean
        fun stubIssueMutationPort(): StubIssueMutationPort = StubIssueMutationPort()

        @Bean
        fun stubIssueSnapshotPort(): StubIssueSnapshotPort = StubIssueSnapshotPort()

        @Bean
        fun issuePermissionResolver(): IssuePermissionResolver = StubIssuePermissionResolver()
    }
}
