// AutomationRuleController 풀스택 HTTP 통합 테스트 — CRUD 5종 + 권한가드 + 웹훅 토큰 1회노출 + OCC 409 + config 400 (FR-AT-01 Task 6)

package com.bts.automation.web

import com.bts.automation.AutomationTestBootApplication
import com.bts.automation.AutomationTestcontainersBase
import com.bts.automation.StubAutomationPermissionResolver
import com.bts.automation.StubIssueMutationPort
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import java.security.MessageDigest
import java.util.UUID

/** `@WithMockUser` username 은 컴파일 상수여야 하므로 top-level const 로 둔다(actor 는 principal name=UUID). */
private const val ACTOR_UUID = "11111111-1111-1111-1111-111111111111"
private const val PROJECT_KEY = "ATLAS"
private const val OTHER_PROJECT_KEY = "OTHER"

/**
 * [com.bts.automation.adapter.web.AutomationRuleController] 풀스택 HTTP 통합 테스트 (FR-AT-01 Task 6).
 *
 * Controller → Service → 실 [com.bts.automation.adapter.AutomationRuleRepository] → **실 PostgreSQL
 * (Testcontainers)** end-to-end. MANAGE_AUTOMATION 판정만 [StubAutomationPermissionResolver] 로 대체한다
 * (BC 격리 — automation 클래스패스에 identity-access 구현이 없음, plan-eng-review E4).
 *
 * ## 커버 시나리오 (plan Task 6 RED)
 * - CRUD 5종(POST/GET 목록/GET 단건/PATCH/DELETE) 정상 경로.
 * - MANAGE_AUTOMATION 가드 — 미인증 401 · 권한 없음 403(일반 메시지, [[fr-pm-04-guard-exception-message-http-leak]]).
 * - WEBHOOK 룰 생성 시 `webhookToken` 1회 노출 + 단건 조회 응답에는 원문/해시 어느 것도 없음(타입 경계로
 *   [com.bts.automation.adapter.web.dto.AutomationRuleResponse] 자체에 토큰 필드가 존재하지 않는다) +
 *   DB 에는 평문이 아닌 SHA-256 해시만 저장된다.
 * - PATCH version 불일치 → 409(OCC).
 * - SCHEDULED/ISSUE_UPDATED triggerConfig 형식 오류 → 400.
 * - 프로젝트 경계 — 다른 프로젝트 소속 룰 id 로 조회/patch/delete 시도 시 404(존재 숨김).
 */
@SpringBootTest(
    classes = [AutomationTestBootApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.MOCK,
)
@Import(
    AutomationTestcontainersBase::class,
    AutomationRuleControllerTest.TestSupportConfig::class,
)
class AutomationRuleControllerTest {
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
        permissionResolver.allow(OTHER_PROJECT_KEY)
    }

    // ── POST 생성 ────────────────────────────────────────────────────────────────

    @Test
    @WithMockUser(username = ACTOR_UUID)
    fun `POST 룰 생성 - ISSUE_CREATED - 201 + enabled true version 0`() {
        mockMvc
            .perform(
                post("/api/v1/projects/$PROJECT_KEY/automation/rules")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(createRequestJson(name = "이슈 생성 알림", triggerType = "ISSUE_CREATED")),
            ).andExpect(status().isCreated)
            .andExpect(jsonPath("$.rule.name").value("이슈 생성 알림"))
            .andExpect(jsonPath("$.rule.enabled").value(true))
            .andExpect(jsonPath("$.rule.version").value(0))
            .andExpect(jsonPath("$.rule.projectKey").value(PROJECT_KEY))
            .andExpect(jsonPath("$.rule.hasWebhookToken").value(false))
            .andExpect(jsonPath("$.webhookToken").doesNotExist())
    }

    @Test
    @WithMockUser(username = ACTOR_UUID)
    fun `POST WEBHOOK 룰 생성 - webhookToken 1회 노출 + DB엔 해시만 저장`() {
        val response =
            mockMvc
                .perform(
                    post("/api/v1/projects/$PROJECT_KEY/automation/rules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createRequestJson(name = "인바운드 웹훅", triggerType = "WEBHOOK")),
                ).andExpect(status().isCreated)
                .andExpect(jsonPath("$.rule.hasWebhookToken").value(true))
                .andExpect(jsonPath("$.webhookToken").isNotEmpty())
                .andReturn()
                .response
                .contentAsString

        val webhookToken = objectMapper.readTree(response).get("webhookToken").asText()
        val ruleId = objectMapper.readTree(response).get("rule").get("id").asText()

        // DB 에는 SHA-256 해시만 저장되고, 평문 토큰과 일치하지 않는다.
        val storedHash =
            jdbcTemplate.queryForObject(
                "SELECT webhook_token_hash FROM automation_rules WHERE id = ?::uuid",
                String::class.java,
                ruleId,
            )
        assertThat(storedHash).isNotEqualTo(webhookToken)
        assertThat(storedHash).isEqualTo(sha256Hex(webhookToken))

        // 단건 조회 응답에는 webhookToken 필드 자체가 없다(타입 경계 미노출).
        mockMvc
            .perform(get("/api/v1/projects/$PROJECT_KEY/automation/rules/$ruleId"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.hasWebhookToken").value(true))
            .andExpect(jsonPath("$.webhookToken").doesNotExist())
            .andExpect(jsonPath("$.webhookTokenHash").doesNotExist())
    }

    @Test
    @WithMockUser(username = ACTOR_UUID)
    fun `POST SCHEDULED 룰 생성 - cron 유효 - nextFireAt 계산됨`() {
        mockMvc
            .perform(
                post("/api/v1/projects/$PROJECT_KEY/automation/rules")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        createRequestJson(
                            name = "매일 9시 알림",
                            triggerType = "SCHEDULED",
                            // Spring CronExpression 은 6필드(초 분 시 일 월 요일).
                            triggerConfig = """{"cron":"0 0 9 * * *"}""",
                        ),
                    ),
            ).andExpect(status().isCreated)
            .andExpect(jsonPath("$.rule.nextFireAt").isNotEmpty())
    }

    @Test
    @WithMockUser(username = ACTOR_UUID)
    fun `POST SCHEDULED 룰 생성 - cron 필드 누락 - 400 AUTOMATION_RULE_INVALID`() {
        mockMvc
            .perform(
                post("/api/v1/projects/$PROJECT_KEY/automation/rules")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(createRequestJson(name = "잘못된 스케줄", triggerType = "SCHEDULED", triggerConfig = "{}")),
            ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errorCode").value("AUTOMATION_RULE_INVALID"))
    }

    @Test
    fun `POST 미인증 - 401`() {
        mockMvc
            .perform(
                post("/api/v1/projects/$PROJECT_KEY/automation/rules")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(createRequestJson(name = "미인증", triggerType = "ISSUE_CREATED")),
            ).andExpect(status().isUnauthorized)
    }

    @Test
    @WithMockUser(username = ACTOR_UUID)
    fun `POST 권한 없음 - 403 AUTOMATION_ACCESS_DENIED - 룰은 만들어지지 않는다`() {
        permissionResolver.deny(PROJECT_KEY)

        mockMvc
            .perform(
                post("/api/v1/projects/$PROJECT_KEY/automation/rules")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(createRequestJson(name = "권한없음", triggerType = "ISSUE_CREATED")),
            ).andExpect(status().isForbidden)
            .andExpect(jsonPath("$.errorCode").value("AUTOMATION_ACCESS_DENIED"))

        val count =
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM automation_rules WHERE project_key = ?",
                Int::class.java,
                PROJECT_KEY,
            )
        assertThat(count).isEqualTo(0)
    }

    // ── GET 목록/단건 ────────────────────────────────────────────────────────────

    @Test
    @WithMockUser(username = ACTOR_UUID)
    fun `GET 목록 - 같은 프로젝트 룰만 반환한다`() {
        val ruleId = createRule(name = "룰 A")
        createRule(name = "룰 B", projectKey = OTHER_PROJECT_KEY)

        mockMvc
            .perform(get("/api/v1/projects/$PROJECT_KEY/automation/rules"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].id").value(ruleId))
    }

    @Test
    @WithMockUser(username = ACTOR_UUID)
    fun `GET 단건 - 존재하지 않는 id - 404`() {
        mockMvc
            .perform(get("/api/v1/projects/$PROJECT_KEY/automation/rules/${UUID.randomUUID()}"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.errorCode").value("AUTOMATION_RULE_NOT_FOUND"))
    }

    @Test
    @WithMockUser(username = ACTOR_UUID)
    fun `GET 단건 - 다른 프로젝트 소속 룰 id - 404(존재 숨김)`() {
        val ruleId = createRule(name = "타 프로젝트 룰", projectKey = OTHER_PROJECT_KEY)

        mockMvc
            .perform(get("/api/v1/projects/$PROJECT_KEY/automation/rules/$ruleId"))
            .andExpect(status().isNotFound)
    }

    @Test
    fun `GET 목록 - 미인증 - 401`() {
        mockMvc
            .perform(get("/api/v1/projects/$PROJECT_KEY/automation/rules"))
            .andExpect(status().isUnauthorized)
    }

    // ── PATCH 수정 ───────────────────────────────────────────────────────────────

    @Test
    @WithMockUser(username = ACTOR_UUID)
    fun `PATCH name 변경 - 200 + version bump`() {
        val ruleId = createRule(name = "원래 이름")

        mockMvc
            .perform(
                patch("/api/v1/projects/$PROJECT_KEY/automation/rules/$ruleId")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"version":0,"name":"바뀐 이름"}"""),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.name").value("바뀐 이름"))
            .andExpect(jsonPath("$.version").value(1))
    }

    @Test
    @WithMockUser(username = ACTOR_UUID)
    fun `PATCH enabled false 변경 - 200`() {
        val ruleId = createRule(name = "비활성화 대상")

        mockMvc
            .perform(
                patch("/api/v1/projects/$PROJECT_KEY/automation/rules/$ruleId")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"version":0,"enabled":false}"""),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.enabled").value(false))
    }

    @Test
    @WithMockUser(username = ACTOR_UUID)
    fun `PATCH version 불일치 - 409 AUTOMATION_RULE_VERSION_CONFLICT`() {
        val ruleId = createRule(name = "원래 이름")

        mockMvc
            .perform(
                patch("/api/v1/projects/$PROJECT_KEY/automation/rules/$ruleId")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"version":99,"name":"충돌 시도"}"""),
            ).andExpect(status().isConflict)
            .andExpect(jsonPath("$.errorCode").value("AUTOMATION_RULE_VERSION_CONFLICT"))
    }

    @Test
    @WithMockUser(username = ACTOR_UUID)
    fun `PATCH triggerConfig 형식 오류 - 400`() {
        val ruleId =
            createRule(
                name = "스케줄 룰",
                triggerType = "SCHEDULED",
                triggerConfig = """{"cron":"0 0 9 * * *"}""",
            )

        mockMvc
            .perform(
                patch("/api/v1/projects/$PROJECT_KEY/automation/rules/$ruleId")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"version":0,"triggerConfig":"{\"cron\":\"not-a-cron\"}"}"""),
            ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errorCode").value("AUTOMATION_RULE_INVALID"))
    }

    @Test
    @WithMockUser(username = ACTOR_UUID)
    fun `PATCH 권한 없음 - 403`() {
        val ruleId = createRule(name = "권한 테스트")
        permissionResolver.deny(PROJECT_KEY)

        mockMvc
            .perform(
                patch("/api/v1/projects/$PROJECT_KEY/automation/rules/$ruleId")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"version":0,"name":"거부되어야 함"}"""),
            ).andExpect(status().isForbidden)
    }

    @Test
    @WithMockUser(username = ACTOR_UUID)
    fun `PATCH 무변경 (version만 전송) - 200 version 유지`() {
        val ruleId = createRule(name = "무변경 대상")

        mockMvc
            .perform(
                patch("/api/v1/projects/$PROJECT_KEY/automation/rules/$ruleId")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"version":0}"""),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.version").value(0))
    }

    @Test
    @WithMockUser(username = ACTOR_UUID)
    fun `PATCH 동일값 enabled=true (이미 활성) - 200 무변경 version 유지`() {
        val ruleId = createRule(name = "이미 활성")

        mockMvc
            .perform(
                patch("/api/v1/projects/$PROJECT_KEY/automation/rules/$ruleId")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"version":0,"enabled":true}"""),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.enabled").value(true))
            .andExpect(jsonPath("$.version").value(0))
    }

    @Test
    @WithMockUser(username = ACTOR_UUID)
    fun `PATCH version 타입 불일치 - 400 (잘못된 바디)`() {
        val ruleId = createRule(name = "바디 검증")

        mockMvc
            .perform(
                patch("/api/v1/projects/$PROJECT_KEY/automation/rules/$ruleId")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"version":"숫자아님","name":"타입 불일치"}"""),
            ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errorCode").value("AUTOMATION_MALFORMED_REQUEST"))
    }

    @Test
    @WithMockUser(username = ACTOR_UUID)
    fun `PATCH 손상된 JSON 바디 - 400`() {
        val ruleId = createRule(name = "손상 바디")

        mockMvc
            .perform(
                patch("/api/v1/projects/$PROJECT_KEY/automation/rules/$ruleId")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{not-json"""),
            ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errorCode").value("AUTOMATION_MALFORMED_REQUEST"))
    }

    // ── DELETE 삭제 ──────────────────────────────────────────────────────────────

    @Test
    @WithMockUser(username = ACTOR_UUID)
    fun `DELETE - 204 + 이후 조회 404`() {
        val ruleId = createRule(name = "삭제 대상")

        mockMvc
            .perform(delete("/api/v1/projects/$PROJECT_KEY/automation/rules/$ruleId"))
            .andExpect(status().isNoContent)

        mockMvc
            .perform(get("/api/v1/projects/$PROJECT_KEY/automation/rules/$ruleId"))
            .andExpect(status().isNotFound)
    }

    @Test
    @WithMockUser(username = ACTOR_UUID)
    fun `DELETE 다른 프로젝트 소속 룰 - 404(삭제되지 않는다)`() {
        val ruleId = createRule(name = "타 프로젝트 룰", projectKey = OTHER_PROJECT_KEY)

        mockMvc
            .perform(delete("/api/v1/projects/$PROJECT_KEY/automation/rules/$ruleId"))
            .andExpect(status().isNotFound)

        // deleted_at 컬럼이 NULL 이면 JdbcTemplate.queryForObject(..., Class) 는 Kotlin 플랫폼 타입
        // null-check 로 NPE 를 던지므로(값 자체가 NULL), COUNT 로 우회해 소프트 삭제 미발생을 확인한다.
        val deletedCount =
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM automation_rules WHERE id = ?::uuid AND deleted_at IS NOT NULL",
                Int::class.java,
                ruleId,
            )
        assertThat(deletedCount).isEqualTo(0)
    }

    // ── 헬퍼 ──────────────────────────────────────────────────────────────────────

    private fun createRequestJson(
        name: String,
        triggerType: String,
        triggerConfig: String = "{}",
    ): String =
        objectMapper.writeValueAsString(
            mapOf("name" to name, "triggerType" to triggerType, "triggerConfig" to triggerConfig),
        )

    /**
     * MockMvc 로 룰을 생성하고 생성된 id 를 반환하는 테스트 헬퍼(다른 시나리오의 픽스처 준비용).
     *
     * 호출하는 테스트 메서드가 `@WithMockUser(username = ACTOR_UUID)` 를 이미 갖고 있어야 한다 — 같은
     * 테스트 메서드 안의 모든 [mockMvc] 호출은 그 인증 컨텍스트를 공유한다.
     */
    private fun createRule(
        name: String,
        triggerType: String = "ISSUE_CREATED",
        triggerConfig: String = "{}",
        projectKey: String = PROJECT_KEY,
    ): String {
        val response =
            mockMvc
                .perform(
                    post("/api/v1/projects/$projectKey/automation/rules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createRequestJson(name, triggerType, triggerConfig)),
                ).andExpect(status().isCreated)
                .andReturn()
                .response
                .contentAsString
        return objectMapper.readTree(response).get("rule").get("id").asText()
    }

    private fun sha256Hex(plaintext: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(plaintext.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    /**
     * 테스트 전용 인가 필터체인 + [StubAutomationPermissionResolver]/[StubIssueMutationPort] 빈 등록.
     *
     * `AutomationTestcontainersBase`(Task 1 산출물)는 이 Task 의 파일 범위 밖이라 여기(테스트 파일 자체)에서
     * nested `@TestConfiguration` 으로 등록한다. csrf 는 JSON API 테스트 편의상 비활성화한다(prod 정책 아님,
     * slack `SlackTestSecurityConfig` 동형). [StubIssueMutationPort] 는 `ActionExecutor`(FR-AT-02 Task 9)가
     * non-null 로 요구하는 [com.bts.shared.issue.IssueMutationPort] 를 컨텍스트 로드용으로 대신 등록한다.
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
    }
}
