// AutomationRuleController import 엔드포인트 통합 테스트 — YAML upsert 200 + 예외매핑(400/413) + 커밋후 conflicts (FR-AT-06 GitOps Task 5)

package com.bts.automation.web

import com.bts.automation.AutomationTestBootApplication
import com.bts.automation.AutomationTestcontainersBase
import com.bts.automation.StubAutomationPermissionResolver
import com.bts.automation.StubIssueMutationPort
import com.bts.automation.StubIssuePermissionResolver
import com.bts.automation.StubIssueSnapshotPort
import com.bts.shared.permission.IssuePermissionResolver
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import java.util.UUID

/** `@WithMockUser` username 은 컴파일 상수여야 하므로 top-level const 로 둔다(actor 는 principal name=UUID). */
private const val ACTOR_UUID = "44444444-4444-4444-4444-444444444444"
private const val PROJECT_KEY = "GITIMPWEB"

/** [AutomationRuleController.import] 컨트롤러 상수와 값이 같아야 한다(플랜 §NFR2·EC8) — 파일 범위 밖이라 여기 재정의한다. */
private const val MAX_IMPORT_RULES = 500

/** YAML 미디어 타입 — [AutomationRuleController.import] `consumes` 목록 중 하나. */
private val YAML_MEDIA_TYPE: MediaType = MediaType.parseMediaType("application/yaml")

/**
 * `POST /api/v1/projects/{projectKey}/automation/rules/import` 통합 테스트 (FR-AT-06 GitOps Task 5).
 *
 * Controller → [com.bts.automation.application.AutomationRuleService.importRules] →
 * [com.bts.automation.application.AutomationRuleService.analyzeProjectConflicts](커밋 후) → 실
 * PostgreSQL(Testcontainers) end-to-end. [AutomationRuleExportIntegrationTest] 의 `TestSupportConfig`
 * 동형 인프라를 재사용한다(BC 격리 — automation 클래스패스에 identity-access 구현이 없어
 * [StubAutomationPermissionResolver] 로 MANAGE_AUTOMATION 판정을 대체).
 *
 * ## 커버 시나리오 (plan Task 5 RED)
 * 1. happy path — 유효 YAML 2규칙(WEBHOOK 1건 포함) POST → 200 [com.bts.automation.adapter.web.dto.AutomationImportResponse]
 *    (created/updated/total·ruleIds 입력순, webhookTokens 1건).
 * 2. malformed YAML → 400 `AUTOMATION_IMPORT_INVALID`.
 * 3. projectKey 불일치(EC2) → 400.
 * 4. 규칙 수 > [MAX_IMPORT_RULES] → 413 `AUTOMATION_IMPORT_TOO_LARGE`.
 * 5. import 후 conflicts 채워짐(같은 트리거 2규칙이 같은 필드를 다른 값으로 SET → FIELD_CONFLICT,
 *    커밋 후 `analyzeProjectConflicts` 반영).
 */
@SpringBootTest(
    classes = [AutomationTestBootApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.MOCK,
)
@Import(
    AutomationTestcontainersBase::class,
    AutomationRuleImportIntegrationTest.TestSupportConfig::class,
)
class AutomationRuleImportIntegrationTest {
    @Autowired
    @Suppress("VarCouldBeVal")
    private lateinit var webApplicationContext: WebApplicationContext

    @Autowired
    @Suppress("VarCouldBeVal")
    private lateinit var jdbcTemplate: JdbcTemplate

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
    fun `POST import - happy path 2규칙(WEBHOOK 1건 포함) - 200 created-updated-total-ruleIds 입력순 + webhookTokens 1건`() {
        val ruleAId = UUID.randomUUID()
        val ruleBId = UUID.randomUUID()
        val yaml = happyPathYaml(ruleAId, ruleBId)

        mockMvc
            .perform(
                post("/api/v1/projects/$PROJECT_KEY/automation/rules/import")
                    .contentType(YAML_MEDIA_TYPE)
                    .content(yaml),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.created").value(2))
            .andExpect(jsonPath("$.updated").value(0))
            .andExpect(jsonPath("$.total").value(2))
            .andExpect(jsonPath("$.ruleIds[0]").value(ruleAId.toString()))
            .andExpect(jsonPath("$.ruleIds[1]").value(ruleBId.toString()))
            .andExpect(jsonPath("$.webhookTokens.length()").value(1))
            .andExpect(jsonPath("$.webhookTokens[0].ruleId").value(ruleBId.toString()))
            .andExpect(jsonPath("$.webhookTokens[0].name").value("웹훅 규칙 B"))
            .andExpect(jsonPath("$.webhookTokens[0].token").isNotEmpty)

        val storedCount =
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM automation_rules WHERE project_key = ?",
                Int::class.java,
                PROJECT_KEY,
            )
        assertThat(storedCount).isEqualTo(2)
    }

    @Test
    @WithMockUser(username = ACTOR_UUID)
    fun `POST import - malformed YAML - 400 AUTOMATION_IMPORT_INVALID`() {
        mockMvc
            .perform(
                post("/api/v1/projects/$PROJECT_KEY/automation/rules/import")
                    .contentType(YAML_MEDIA_TYPE)
                    .content("not: [valid: yaml: structure"),
            ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errorCode").value("AUTOMATION_IMPORT_INVALID"))
    }

    @Test
    @WithMockUser(username = ACTOR_UUID)
    fun `POST import - YAML projectKey가 경로와 다르면(EC2) - 400`() {
        val yaml =
            """
            version: 1
            projectKey: WRONGKEY
            rules: []
            """.trimIndent()

        mockMvc
            .perform(
                post("/api/v1/projects/$PROJECT_KEY/automation/rules/import")
                    .contentType(YAML_MEDIA_TYPE)
                    .content(yaml),
            ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errorCode").value("AUTOMATION_IMPORT_INVALID"))
    }

    @Test
    @WithMockUser(username = ACTOR_UUID)
    fun `POST import - 규칙 수가 상한을 초과하면 - 413 AUTOMATION_IMPORT_TOO_LARGE`() {
        val yaml = oversizedYaml(PROJECT_KEY, MAX_IMPORT_RULES + 1)

        mockMvc
            .perform(
                post("/api/v1/projects/$PROJECT_KEY/automation/rules/import")
                    .contentType(YAML_MEDIA_TYPE)
                    .content(yaml),
            ).andExpect(status().isPayloadTooLarge)
            .andExpect(jsonPath("$.errorCode").value("AUTOMATION_IMPORT_TOO_LARGE"))

        val storedCount =
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM automation_rules WHERE project_key = ?",
                Int::class.java,
                PROJECT_KEY,
            )
        assertThat(storedCount).isEqualTo(0)
    }

    @Test
    @WithMockUser(username = ACTOR_UUID)
    fun `POST import - 같은 트리거 2규칙이 같은 필드를 다른 값으로 SET하면 - 커밋 후 conflicts에 FIELD_CONFLICT가 채워진다`() {
        val ruleCId = UUID.randomUUID()
        val ruleDId = UUID.randomUUID()
        val yaml = fieldConflictYaml(ruleCId, ruleDId)

        mockMvc
            .perform(
                post("/api/v1/projects/$PROJECT_KEY/automation/rules/import")
                    .contentType(YAML_MEDIA_TYPE)
                    .content(yaml),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.created").value(2))
            .andExpect(jsonPath("$.conflicts[?(@.type == 'FIELD_CONFLICT')]").exists())
            .andExpect(
                jsonPath("$.conflicts[?(@.type == 'FIELD_CONFLICT')].ruleIds[0]")
                    .value(org.hamcrest.Matchers.hasItems(ruleCId.toString(), ruleDId.toString())),
            )
    }

    // ── 헬퍼 ─────────────────────────────────────────────────────────────────

    /** 시나리오 1(happy path) — ISSUE_CREATED 규칙 A + WEBHOOK 규칙 B(액션 없음). */
    private fun happyPathYaml(
        ruleAId: UUID,
        ruleBId: UUID,
    ): String =
        """
        version: 1
        projectKey: $PROJECT_KEY
        rules:
          - id: $ruleAId
            name: "규칙 A"
            trigger:
              type: ISSUE_CREATED
              config: {}
            actions:
              - type: SET_FIELD
                config:
                  field: priority
                  value: High
          - id: $ruleBId
            name: "웹훅 규칙 B"
            trigger:
              type: WEBHOOK
              config: {}
            actions: []
        """.trimIndent()

    /** 시나리오 5(FIELD_CONFLICT) — 같은 트리거(ISSUE_CREATED)에서 priority를 다른 값으로 SET하는 규칙 2개. */
    private fun fieldConflictYaml(
        ruleCId: UUID,
        ruleDId: UUID,
    ): String =
        """
        version: 1
        projectKey: $PROJECT_KEY
        rules:
          - id: $ruleCId
            name: "우선순위를 1로 설정"
            trigger:
              type: ISSUE_CREATED
              config: {}
            actions:
              - type: SET_FIELD
                config:
                  field: priority
                  value: 1
          - id: $ruleDId
            name: "우선순위를 5로 설정"
            trigger:
              type: ISSUE_CREATED
              config: {}
            actions:
              - type: SET_FIELD
                config:
                  field: priority
                  value: 5
        """.trimIndent()

    /** 시나리오 4(EC8) — [count]개의 최소 유효 규칙을 담은 YAML(상한 검증은 codec 파싱 이후 곧바로 일어나므로 도메인 상세 검증까지 갈 필요가 없다). */
    private fun oversizedYaml(
        projectKey: String,
        count: Int,
    ): String {
        val rules =
            (1..count).joinToString(separator = "\n") { i ->
                """
                |  - name: "rule-$i"
                |    trigger:
                |      type: ISSUE_CREATED
                |      config: {}
                """.trimMargin()
            }
        return """
            |version: 1
            |projectKey: $projectKey
            |rules:
            |$rules
            """.trimMargin()
    }

    /**
     * 테스트 전용 인가 필터체인 + 협력자 stub 빈 등록([AutomationRuleExportIntegrationTest.TestSupportConfig] 동형).
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
