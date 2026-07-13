// lint(규칙 충돌 분석)의 참여 트랜잭션 read 예외가 저장 트랜잭션을 오염시키지 않는지 실 DB round-trip 으로 검증 (FR-AT-04 코드리뷰 BLOCKER hotfix)

package com.bts.automation.integration

import com.bts.automation.AutomationTestBootApplication
import com.bts.automation.AutomationTestcontainersBase
import com.bts.automation.StubAutomationPermissionResolver
import com.bts.automation.StubIssueMutationPort
import com.bts.automation.StubIssuePermissionResolver
import com.bts.automation.StubIssueSnapshotPort
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
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext

/** `@WithMockUser` username 은 컴파일 상수여야 하므로 top-level const 로 둔다(actor 는 principal name=UUID). */
private const val ACTOR_UUID = "33333333-3333-3333-3333-333333333333"
private const val PROJECT_KEY = "LINTTX"

/**
 * lint(규칙 충돌 분석, [com.bts.automation.application.RuleConflictAnalyzer])의 참여 트랜잭션 read 예외가
 * 저장 트랜잭션을 오염시키지 않는지 검증하는 회귀 가드(코드리뷰 BLOCKER hotfix).
 *
 * ## 재현 시나리오 — 손상된 액션 데이터로 실 DB read 예외를 유발
 * `automation_actions.action_config`(JSONB)는 DB CHECK 제약이 없다(`action_type` 만 화이트리스트
 * CHECK). 이 컬럼에 [com.bts.automation.domain.Action.fromJson] 파싱 요건을 위반하는 값(`SET_FIELD` 인데
 * `field`/`value` 가 없는 `{}`)을 [jdbcTemplate] 로 **도메인 검증을 우회해 직접** 삽입하면,
 * [com.bts.automation.adapter.AutomationActionRepository.findByRuleId](참여 `@Transactional(readOnly = true)`)
 * 가 실제로 예외([com.bts.automation.domain.ActionConfigInvalidException])를 던지는 상황을 실 DB
 * round-trip 으로 재현할 수 있다 — MockK 로는 Spring 트랜잭션 전파(participation)를 관측할 수 없어
 * ([[transaction-self-invocation-requires-new]] 참고 교훈과 동일하게) Testcontainers 통합 테스트만
 * 이 경로를 표면화한다.
 *
 * ## 수정 전 vs 수정 후
 * - **수정 전**: `create`/`patch` 자신의 `@Transactional` 안에서 lint(`analyzeConflicts`)가 호출되고,
 *   그 안의 [com.bts.automation.adapter.AutomationRuleRepository.findByProject]/
 *   `AutomationActionRepository.findByRuleId` 는 참여(REQUIRED) 전파로 같은 트랜잭션에 합류한다. 손상된
 *   액션을 읽다가 예외가 나면 Spring 이 `globalRollbackOnParticipationFailure`(기본 true)로 트랜잭션을
 *   rollback-only 로 표시한다 — 이 표시는 애플리케이션 코드의 `try/catch` 로 예외 자체를 흡수해도 지워지지
 *   않는다. `create`/`patch` 프록시가 메서드 정상 반환 시 커밋을 시도하면
 *   `UnexpectedRollbackException` 이 던져지고, 방금 저장한 새 규칙까지 롤백되며 응답은 500 이 된다.
 * - **수정 후**: lint 는 저장 트랜잭션이 커밋된 뒤 [com.bts.automation.adapter.web.AutomationRuleController]
 *   가 [com.bts.automation.application.AutomationRuleService.analyzeProjectConflicts] 를 별도(비-`@Transactional`)
 *   로 호출한다 — 이 안의 read 들은 각자 독립된 새 트랜잭션이라 예외가 나도 이미 커밋된 저장에 영향이
 *   없고, fail-safe catch 가 빈 리스트로 흡수한다.
 */
@SpringBootTest(
    classes = [AutomationTestBootApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.MOCK,
)
@Import(
    AutomationTestcontainersBase::class,
    AutomationRuleLintTransactionIsolationIntegrationTest.TestSupportConfig::class,
)
class AutomationRuleLintTransactionIsolationIntegrationTest {
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
    fun `POST - 같은 프로젝트의 다른 규칙에 손상된 액션이 있어도 저장은 성공하고 conflicts 는 빈 리스트다`() {
        val corruptedRuleId = createRuleWithoutActions(name = "손상될 규칙")
        corruptAction(corruptedRuleId)

        val response = createRuleRaw(name = "새 규칙", actions = listOf(ActionSpec("SET_FIELD", """{"field":"priority","value":"High"}""")))

        assertThat(response.get("rule").get("conflicts").toList()).isEmpty()

        // 저장이 실제로 커밋됐는지 재조회로 확인한다(롤백되지 않았음의 직접 증거).
        val newRuleId = response.get("rule").get("id").asText()
        mockMvc
            .perform(get("/api/v1/projects/$PROJECT_KEY/automation/rules/$newRuleId"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.name").value("새 규칙"))

        val savedCount =
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM automation_rules WHERE project_key = ?",
                Int::class.java,
                PROJECT_KEY,
            )
        assertThat(savedCount).isEqualTo(2)
    }

    @Test
    @WithMockUser(username = ACTOR_UUID)
    fun `PATCH - 같은 프로젝트의 다른 규칙에 손상된 액션이 있어도 수정 저장은 성공하고 conflicts 는 빈 리스트다`() {
        val corruptedRuleId = createRuleWithoutActions(name = "손상될 규칙 B")
        corruptAction(corruptedRuleId)
        val targetRuleId = createRuleWithoutActions(name = "패치 대상")

        val response =
            mockMvc
                .perform(
                    patch("/api/v1/projects/$PROJECT_KEY/automation/rules/$targetRuleId")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"version":0,"name":"패치됨"}"""),
                ).andExpect(status().isOk)
                .andReturn()
                .response
                .contentAsString
        val patched = objectMapper.readTree(response)

        assertThat(patched.get("name").asText()).isEqualTo("패치됨")
        assertThat(patched.get("version").asLong()).isEqualTo(1)
        assertThat(patched.get("conflicts").toList()).isEmpty()

        // 재조회로 실제 커밋 확인.
        mockMvc
            .perform(get("/api/v1/projects/$PROJECT_KEY/automation/rules/$targetRuleId"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.name").value("패치됨"))
            .andExpect(jsonPath("$.version").value(1))
    }

    // ── 헬퍼 ─────────────────────────────────────────────────────────────────

    private data class ActionSpec(val type: String, val config: String)

    private fun createRequestJson(
        name: String,
        actions: List<ActionSpec>,
    ): String =
        objectMapper.writeValueAsString(
            mapOf(
                "name" to name,
                "triggerType" to "ISSUE_CREATED",
                "triggerConfig" to "{}",
                "actions" to actions.map { mapOf("type" to it.type, "config" to it.config) },
            ),
        )

    private fun createRuleRaw(
        name: String,
        actions: List<ActionSpec>,
    ) = mockMvc
        .perform(
            post("/api/v1/projects/$PROJECT_KEY/automation/rules")
                .contentType(MediaType.APPLICATION_JSON)
                .content(createRequestJson(name, actions)),
        ).andExpect(status().isCreated)
        .andReturn()
        .response
        .contentAsString
        .let(objectMapper::readTree)

    /** 액션 없는 유효한 룰을 생성하고 id 를 반환한다(픽스처 준비용). */
    private fun createRuleWithoutActions(name: String): String =
        createRuleRaw(name, emptyList()).get("rule").get("id").asText()

    /**
     * [ruleId] 에 [com.bts.automation.domain.Action.fromJson] 파싱 요건을 위반하는 `SET_FIELD` 액션
     * 행을 도메인 검증을 우회해 직접 삽입한다 — `field`/`value` 가 없는 빈 객체는 DB CHECK(action_type
     * 화이트리스트만 강제, action_config 구조는 미강제)를 통과하지만
     * [com.bts.automation.adapter.AutomationActionRepository.findByRuleId] 의 역직렬화 시 예외를 던진다
     * (클래스 KDoc §재현 시나리오 참고).
     */
    private fun corruptAction(ruleId: String) {
        jdbcTemplate.update(
            "INSERT INTO automation_actions (rule_id, position, action_type, action_config) " +
                "VALUES (?::uuid, 0, 'SET_FIELD', '{}'::jsonb)",
            ruleId,
        )
    }

    /**
     * 테스트 전용 인가 필터체인 + 협력자 stub 빈 등록([RuleConflictAnalysisIntegrationTest] 동형).
     * `AutomationTestcontainersBase`(Task 1 산출물)는 이 파일 범위 밖이라 `@Bean` 을 추가할 수 없어
     * 이 파일 자체의 nested `@TestConfiguration` 에서 등록한다.
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
