// 규칙 충돌 분석 end-to-end 통합 테스트 — 실 DB 저장 경로로 CYCLE·FIELD_CONFLICT·PRIORITY_AMBIGUITY·PERMISSION_MISSING soft 저장 + GET 미노출 + 성능 스모크 (FR-AT-04 Task 6)

package com.bts.automation.integration

import com.bts.automation.AutomationTestBootApplication
import com.bts.automation.AutomationTestcontainersBase
import com.bts.automation.StubAutomationPermissionResolver
import com.bts.automation.StubIssueMutationPort
import com.bts.automation.StubIssueSnapshotPort
import com.bts.automation.adapter.AutomationRuleRepository
import com.bts.automation.domain.AutomationRule
import com.bts.automation.domain.TriggerType
import com.bts.shared.permission.IssuePermissionResolver
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import java.time.Instant
import java.util.UUID

/** `@WithMockUser` username 은 컴파일 상수여야 하므로 top-level const 로 둔다(actor 는 principal name=UUID). */
private const val ACTOR_UUID = "22222222-2222-2222-2222-222222222222"
private const val PROJECT_KEY = "CONFLICT"

/** 성능 스모크 시나리오의 시드 규칙 수(spec NFR 1s 안전 마진 확인용, 정밀 p95 측정 아님). */
private const val SEED_RULE_COUNT = 100

/** 성능 스모크 타임아웃(ms) — NFR 1s 대비 3~5배 여유를 둔 소프트 상한(정밀 벤치마크가 아니라 "완료됨" 확인용). */
private const val PERFORMANCE_TIMEOUT_MS = 5_000L

private const val NANOS_PER_MILLI = 1_000_000L

/** 요청 바디 조립용 액션 1건 표현([com.bts.automation.adapter.web.dto.ActionRequest] 와 필드 대칭). */
private data class ActionSpec(val type: String, val config: String)

/**
 * [com.bts.automation.application.RuleConflictAnalyzer] 가 실 HTTP(POST) → 실 서비스
 * ([com.bts.automation.application.AutomationRuleService]) → 실 PostgreSQL(Testcontainers) 저장 경로에서
 * 정상적으로 배선되는지 검증하는 end-to-end 통합 테스트(FR-AT-04 Task 6).
 *
 * [com.bts.automation.application.RuleConflictAnalyzerCycleTest]/
 * [com.bts.automation.application.RuleConflictAnalyzerFieldPriorityTest]/
 * [com.bts.automation.application.RuleConflictAnalyzerPermissionTest] 는 분석기 자체의 판정 로직을 순수
 * 인메모리로 이미 촘촘히 검증했고, [com.bts.automation.application.AutomationRuleServiceConflictTest] 는
 * `AutomationRuleService` 의 저장 후 오케스트레이션을 MockK 로 검증했다 — 이 테스트는 그 위에 **실제
 * 저장 API 를 한 번이라도 왕복해도 배선이 끊어지지 않는지**를 회귀 가드로 확인하는 성격이다
 * ([[concurrent-testcontainers-suite-flaky]] 교훈에 따라 이 클래스는 항상 단독 실행으로 판정한다).
 *
 * ## PERMISSION_MISSING 을 검출하려면 `IssuePermissionResolver` 를 mock 으로 교체해야 하는 이유
 * automation test-boot 컨텍스트의 기본 `IssuePermissionResolver` 는
 * [com.bts.automation.StubIssuePermissionResolver](AlwaysAllow, non-prod fail-safe)다 — 이 stub 을 그대로
 * 쓰면 PERMISSION_MISSING 은 절대 검출되지 않는다. 이 클래스는 [TestSupportConfig] 에서 그 stub 대신
 * MockK 인스턴스를 `IssuePermissionResolver` 빈으로 등록해([ActionExecutionEndToEndIntegrationTest] 의
 * `@Primary mockWebhookActionClient` 와 동형 패턴), 기본값은 `true`(다른 시나리오를 오염시키지 않음)로
 * 두고 PERMISSION_MISSING 테스트에서만 특정 actor 에 대해 `false` 를 재정의한다.
 *
 * ## 저장 성공(soft 저장) 확인
 * 4종 시나리오 전부 `POST` 응답이 201 Created 인지를 [createRuleRaw] 의 `andExpect(status().isCreated)` 로
 * 매 호출마다 확인한다 — 충돌이 검출돼도 저장 자체는 절대 차단되지 않는다(스펙 "soft warning").
 */
@SpringBootTest(
    classes = [AutomationTestBootApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.MOCK,
)
@Import(
    AutomationTestcontainersBase::class,
    RuleConflictAnalysisIntegrationTest.TestSupportConfig::class,
)
class RuleConflictAnalysisIntegrationTest {
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
    private lateinit var automationPermissionResolver: StubAutomationPermissionResolver

    @Autowired
    @Suppress("VarCouldBeVal")
    private lateinit var issuePermissionResolver: IssuePermissionResolver

    @Autowired
    @Suppress("VarCouldBeVal")
    private lateinit var ruleRepository: AutomationRuleRepository

    private lateinit var mockMvc: MockMvc

    @BeforeEach
    fun setUp() {
        mockMvc =
            MockMvcBuilders
                .webAppContextSetup(webApplicationContext)
                .apply<org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder>(springSecurity())
                .build()
        jdbcTemplate.update("DELETE FROM automation_rules")
        automationPermissionResolver.reset()
        automationPermissionResolver.allow(PROJECT_KEY)
        // 매 테스트 시작 시 mock 을 초기화하고 기본값(항상 허용)으로 재설정한다 — PERMISSION_MISSING
        // 테스트에서만 특정 actor 를 거부로 재정의하므로, 이전 테스트의 재정의가 다음 테스트로 새는 것을
        // 막는다(클래스 KDoc §PERMISSION_MISSING 참고).
        clearMocks(issuePermissionResolver)
        every { issuePermissionResolver.hasPermission(any(), any(), any()) } returns true
    }

    // ── CYCLE ────────────────────────────────────────────────────────────────

    @Test
    @WithMockUser(username = ACTOR_UUID)
    fun `CYCLE - 서로 트리거를 유발하는 두 규칙을 저장하면 두 번째 저장 응답에 CYCLE 충돌이 담긴다`() {
        val ruleAId =
            createRule(
                name = "우선순위 변경 시 상태 변경",
                triggerType = "ISSUE_UPDATED",
                triggerConfig = """{"fields":["priority"]}""",
                actions = listOf(ActionSpec("SET_FIELD", """{"field":"status","value":"done"}""")),
            )

        val secondResponse =
            createRuleRaw(
                name = "상태 변경 시 우선순위 변경",
                triggerType = "ISSUE_UPDATED",
                triggerConfig = """{"fields":["status"]}""",
                actions = listOf(ActionSpec("SET_FIELD", """{"field":"priority","value":"high"}""")),
            )
        val ruleBId = secondResponse.get("rule").get("id").asText()

        val cycleConflict = conflictsOf(secondResponse).singleOrNull { it.get("type").asText() == "CYCLE" }
        assertThat(cycleConflict).isNotNull
        assertThat(cycleConflict!!.get("ruleIds").map { it.asText() }).containsExactlyInAnyOrder(ruleAId, ruleBId)
    }

    // ── FIELD_CONFLICT ───────────────────────────────────────────────────────

    @Test
    @WithMockUser(username = ACTOR_UUID)
    fun `FIELD_CONFLICT - 같은 트리거에서 같은 필드를 다른 값으로 설정하면 두 번째 저장 응답에 FIELD_CONFLICT 충돌이 담긴다`() {
        val ruleCId =
            createRule(
                name = "우선순위를 1로 설정",
                triggerType = "ISSUE_CREATED",
                actions = listOf(ActionSpec("SET_FIELD", """{"field":"priority","value":1}""")),
            )

        val secondResponse =
            createRuleRaw(
                name = "우선순위를 5로 설정",
                triggerType = "ISSUE_CREATED",
                actions = listOf(ActionSpec("SET_FIELD", """{"field":"priority","value":5}""")),
            )
        val ruleDId = secondResponse.get("rule").get("id").asText()

        val fieldConflict = conflictsOf(secondResponse).singleOrNull { it.get("type").asText() == "FIELD_CONFLICT" }
        assertThat(fieldConflict).isNotNull
        assertThat(fieldConflict!!.get("ruleIds").map { it.asText() }).containsExactlyInAnyOrder(ruleCId, ruleDId)
    }

    // ── PRIORITY_AMBIGUITY ───────────────────────────────────────────────────

    @Test
    @WithMockUser(username = ACTOR_UUID)
    fun `PRIORITY_AMBIGUITY - 같은 트리거에 동시 매칭되는 서로 다른 부수효과 규칙 두 개를 저장하면 PRIORITY_AMBIGUITY 충돌이 담긴다`() {
        val assigneeOne = UUID.randomUUID()
        val assigneeTwo = UUID.randomUUID()
        val ruleEId =
            createRule(
                name = "이슈 생성 시 담당자 A 배정",
                triggerType = "ISSUE_CREATED",
                actions = listOf(ActionSpec("ASSIGN", """{"assigneeId":"$assigneeOne"}""")),
            )

        val secondResponse =
            createRuleRaw(
                name = "이슈 생성 시 담당자 B 배정",
                triggerType = "ISSUE_CREATED",
                actions = listOf(ActionSpec("ASSIGN", """{"assigneeId":"$assigneeTwo"}""")),
            )
        val ruleFId = secondResponse.get("rule").get("id").asText()

        val conflicts = conflictsOf(secondResponse)
        val priorityConflict = conflicts.singleOrNull { it.get("type").asText() == "PRIORITY_AMBIGUITY" }
        assertThat(priorityConflict).isNotNull
        assertThat(priorityConflict!!.get("ruleIds").map { it.asText() }).containsExactlyInAnyOrder(ruleEId, ruleFId)
        // ASSIGN 은 SetFieldAction 이 아니므로 FIELD_CONFLICT 로 억제되지 않는다(순수 PRIORITY_AMBIGUITY).
        assertThat(conflicts.none { it.get("type").asText() == "FIELD_CONFLICT" }).isTrue()
    }

    // ── PERMISSION_MISSING ───────────────────────────────────────────────────

    @Test
    @WithMockUser(username = ACTOR_UUID)
    fun `PERMISSION_MISSING - 실행 주체가 UPDATE 권한이 없으면 SetField 규칙 저장 응답에 PERMISSION_MISSING 충돌이 담긴다`() {
        val deniedActor = UUID.randomUUID()
        every { issuePermissionResolver.hasPermission(deniedActor, any(), any()) } returns false

        val response =
            createRuleRaw(
                name = "권한 없는 실행 주체의 필드 변경",
                triggerType = "ISSUE_CREATED",
                actions = listOf(ActionSpec("SET_FIELD", """{"field":"priority","value":"High"}""")),
                actorUserId = deniedActor,
            )
        val ruleId = response.get("rule").get("id").asText()

        val permissionConflict =
            conflictsOf(response).singleOrNull { it.get("type").asText() == "PERMISSION_MISSING" }
        assertThat(permissionConflict).isNotNull
        assertThat(permissionConflict!!.get("ruleIds").map { it.asText() }).containsExactly(ruleId)
    }

    // ── GET 미노출 ───────────────────────────────────────────────────────────

    @Test
    @WithMockUser(username = ACTOR_UUID)
    fun `GET 단건-목록 응답에는 conflicts 키가 없다 - 저장 시 충돌이 검출된 규칙이어도 동일하다`() {
        val ruleAId =
            createRule(
                name = "GET 확인용 A",
                triggerType = "ISSUE_UPDATED",
                triggerConfig = """{"fields":["priority"]}""",
                actions = listOf(ActionSpec("SET_FIELD", """{"field":"status","value":"done"}""")),
            )
        createRule(
            name = "GET 확인용 B",
            triggerType = "ISSUE_UPDATED",
            triggerConfig = """{"fields":["status"]}""",
            actions = listOf(ActionSpec("SET_FIELD", """{"field":"priority","value":"high"}""")),
        )

        mockMvc
            .perform(get("/api/v1/projects/$PROJECT_KEY/automation/rules/$ruleAId"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.conflicts").doesNotExist())

        mockMvc
            .perform(get("/api/v1/projects/$PROJECT_KEY/automation/rules"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].conflicts").doesNotExist())
            .andExpect(jsonPath("$[1].conflicts").doesNotExist())
    }

    // ── 성능 스모크 ──────────────────────────────────────────────────────────

    @Test
    @WithMockUser(username = ACTOR_UUID)
    fun `성능 스모크 - 규칙 100개가 시드된 상태에서 저장 1회가 타임아웃 내 완료된다`() {
        repeat(SEED_RULE_COUNT) { index ->
            ruleRepository.save(
                AutomationRule.create(
                    projectKey = PROJECT_KEY,
                    name = "성능 시드 룰 $index",
                    triggerType = TriggerType.ISSUE_CREATED,
                    createdBy = UUID.randomUUID(),
                    now = Instant.now(),
                ),
            )
        }

        val start = System.nanoTime()
        createRuleRaw(
            name = "성능 스모크 마지막 규칙",
            triggerType = "ISSUE_CREATED",
            actions = listOf(ActionSpec("SET_FIELD", """{"field":"priority","value":"High"}""")),
        )
        val elapsedMs = (System.nanoTime() - start) / NANOS_PER_MILLI

        assertThat(elapsedMs).isLessThan(PERFORMANCE_TIMEOUT_MS)
    }

    // ── 헬퍼 ─────────────────────────────────────────────────────────────────

    private fun createRequestJson(
        name: String,
        triggerType: String,
        triggerConfig: String = "{}",
        actions: List<ActionSpec> = emptyList(),
        actorUserId: UUID? = null,
    ): String {
        val body =
            mutableMapOf<String, Any?>(
                "name" to name,
                "triggerType" to triggerType,
                "triggerConfig" to triggerConfig,
                "actions" to actions.map { mapOf("type" to it.type, "config" to it.config) },
            )
        if (actorUserId != null) body["actorUserId"] = actorUserId.toString()
        return objectMapper.writeValueAsString(body)
    }

    /** `POST` 를 호출해 201 을 확인하고 파싱된 응답 바디([CreateAutomationRuleResponse] 대응)를 반환한다. */
    private fun createRuleRaw(
        name: String,
        triggerType: String,
        triggerConfig: String = "{}",
        actions: List<ActionSpec> = emptyList(),
        actorUserId: UUID? = null,
    ): JsonNode {
        val response =
            mockMvc
                .perform(
                    post("/api/v1/projects/$PROJECT_KEY/automation/rules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createRequestJson(name, triggerType, triggerConfig, actions, actorUserId)),
                ).andExpect(status().isCreated)
                .andReturn()
                .response
                .contentAsString
        return objectMapper.readTree(response)
    }

    /** [createRuleRaw] 를 호출하고 생성된 룰의 id 만 반환하는 편의 헬퍼(다른 시나리오의 픽스처 준비용). */
    private fun createRule(
        name: String,
        triggerType: String,
        triggerConfig: String = "{}",
        actions: List<ActionSpec> = emptyList(),
        actorUserId: UUID? = null,
    ): String = createRuleRaw(name, triggerType, triggerConfig, actions, actorUserId).get("rule").get("id").asText()

    /** 생성 응답의 `rule.conflicts` 배열을 [JsonNode] 목록으로 반환한다(create 응답은 항상 이 키를 갖는다). */
    private fun conflictsOf(response: JsonNode): List<JsonNode> = response.get("rule").get("conflicts").toList()

    /**
     * 테스트 전용 인가 필터체인 + 협력자 stub 빈 등록([com.bts.automation.web.AutomationRuleControllerTest]
     * 의 동명 `TestSupportConfig` 동형이나, `IssuePermissionResolver` 만 MockK 로 교체한다(클래스 KDoc
     * §PERMISSION_MISSING 참고). `AutomationTestcontainersBase`(Task 1 산출물)는 이 Task 의 파일 범위 밖이라
     * `@Bean` 을 추가할 수 없어 이 파일 자체의 nested `@TestConfiguration` 에서 등록한다.
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

        /** `StubIssuePermissionResolver`(AlwaysAllow) 대신 MockK 로 등록 — 기본값 `true`, 테스트별로 재정의. */
        @Bean
        fun issuePermissionResolver(): IssuePermissionResolver {
            val resolver = mockk<IssuePermissionResolver>()
            every { resolver.hasPermission(any(), any(), any()) } returns true
            return resolver
        }
    }
}
