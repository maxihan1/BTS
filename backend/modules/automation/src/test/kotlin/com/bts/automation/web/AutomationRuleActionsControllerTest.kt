// AutomationRuleController 룰 CRUD payload 확장 HTTP 통합 테스트 — actions[] + actorUserId (FR-AT-02 Task 11)

package com.bts.automation.web

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
private const val OTHER_ACTOR_UUID = "44444444-4444-4444-4444-444444444444"
private const val PROJECT_KEY = "ATLAS"

/**
 * [com.bts.automation.adapter.web.AutomationRuleController] 의 룰 CRUD payload 확장 HTTP 통합 테스트
 * (FR-AT-02 Task 11).
 *
 * Controller → Service → 실 [com.bts.automation.adapter.AutomationRuleRepository]/
 * [com.bts.automation.adapter.AutomationActionRepository] → **실 PostgreSQL(Testcontainers)** end-to-end.
 * MANAGE_AUTOMATION 판정은 [StubAutomationPermissionResolver] 로 대체한다(FR-AT-01 `AutomationRuleControllerTest`
 * 선례 동형 — 같은 `@TestConfiguration` 패턴을 이 테스트 파일 자체 범위 안에 재선언한다).
 *
 * ## 커버 시나리오 (plan Task 11 RED)
 * - 액션 포함 룰 생성 → 저장(`automation_actions` 행)·응답(`rule.actions`) 반영.
 * - `actorUserId` 미지정 시 `createdBy`(actor)로 기본값 설정.
 * - `actorUserId` 지정 시 응답에 그대로 반영(cross-BC FK 아님 — 임의 UUID 허용, V303 KDoc).
 * - 잘못된 action config(SET_FIELD 인데 field/value 누락) → 400, 룰 자체가 생성되지 않는다(부분 커밋 없음).
 * - MANAGE_AUTOMATION 미보유 → 403, 룰/액션 모두 생성되지 않는다.
 * - PATCH 로 actions 교체 — 기존 액션이 삭제되고 새 액션만 남는다(GET 재조회로 영속 확인).
 */
@SpringBootTest(
    classes = [AutomationTestBootApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.MOCK,
)
@Import(
    AutomationTestcontainersBase::class,
    AutomationRuleActionsControllerTest.TestSupportConfig::class,
)
class AutomationRuleActionsControllerTest {
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
        // automation_actions 는 automation_rules FK ON DELETE CASCADE 라 룰만 지워도 함께 정리된다(V302).
        jdbcTemplate.update("DELETE FROM automation_rules")
        permissionResolver.reset()
        permissionResolver.allow(PROJECT_KEY)
    }

    // ── POST 생성 — actions[] ───────────────────────────────────────────────────

    @Test
    @WithMockUser(username = ACTOR_UUID)
    fun `POST 액션 포함 룰 생성 - 저장 및 응답에 actions 반영`() {
        val response =
            mockMvc
                .perform(
                    post("/api/v1/projects/$PROJECT_KEY/automation/rules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            createRequestJson(
                                name = "액션 포함 룰",
                                actions =
                                    listOf(
                                        mapOf("type" to "ADD_COMMENT", "config" to """{"body":"자동 댓글"}"""),
                                        mapOf("type" to "ASSIGN", "config" to """{"assigneeId":null}"""),
                                    ),
                            ),
                        ),
                ).andExpect(status().isCreated)
                .andExpect(jsonPath("$.rule.actions.length()").value(2))
                .andExpect(jsonPath("$.rule.actions[0].type").value("ADD_COMMENT"))
                .andExpect(jsonPath("$.rule.actions[0].config.body").value("자동 댓글"))
                .andExpect(jsonPath("$.rule.actions[1].type").value("ASSIGN"))
                .andReturn()
                .response
                .contentAsString

        val ruleId = objectMapper.readTree(response).get("rule").get("id").asText()
        val storedCount =
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM automation_actions WHERE rule_id = ?::uuid",
                Int::class.java,
                ruleId,
            )
        assertThat(storedCount).isEqualTo(2)
    }

    @Test
    @WithMockUser(username = ACTOR_UUID)
    fun `POST 액션 없이 룰 생성 - actions 빈 리스트로 응답`() {
        mockMvc
            .perform(
                post("/api/v1/projects/$PROJECT_KEY/automation/rules")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(createRequestJson(name = "액션 없는 룰")),
            ).andExpect(status().isCreated)
            .andExpect(jsonPath("$.rule.actions.length()").value(0))
    }

    // ── POST 생성 — actorUserId ──────────────────────────────────────────────────

    @Test
    @WithMockUser(username = ACTOR_UUID)
    fun `POST actorUserId 미지정 - createdBy(actor) 로 기본값 설정된다`() {
        mockMvc
            .perform(
                post("/api/v1/projects/$PROJECT_KEY/automation/rules")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(createRequestJson(name = "actor 미지정")),
            ).andExpect(status().isCreated)
            .andExpect(jsonPath("$.rule.actorUserId").value(ACTOR_UUID))
            .andExpect(jsonPath("$.rule.createdBy").value(ACTOR_UUID))
    }

    @Test
    @WithMockUser(username = ACTOR_UUID)
    fun `POST actorUserId 지정 - 응답에 그대로 반영된다`() {
        mockMvc
            .perform(
                post("/api/v1/projects/$PROJECT_KEY/automation/rules")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(createRequestJson(name = "actor 지정", actorUserId = OTHER_ACTOR_UUID)),
            ).andExpect(status().isCreated)
            .andExpect(jsonPath("$.rule.actorUserId").value(OTHER_ACTOR_UUID))
            .andExpect(jsonPath("$.rule.createdBy").value(ACTOR_UUID))
    }

    // ── POST 생성 — 잘못된 action config ─────────────────────────────────────────

    @Test
    @WithMockUser(username = ACTOR_UUID)
    fun `POST 잘못된 action config - 400 - 룰이 생성되지 않는다`() {
        mockMvc
            .perform(
                post("/api/v1/projects/$PROJECT_KEY/automation/rules")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        createRequestJson(
                            name = "잘못된 액션",
                            // SET_FIELD 는 field·value 가 필수인데 빈 객체를 보낸다.
                            actions = listOf(mapOf("type" to "SET_FIELD", "config" to "{}")),
                        ),
                    ),
            ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errorCode").value("AUTOMATION_RULE_INVALID"))

        val count =
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM automation_rules WHERE project_key = ?",
                Int::class.java,
                PROJECT_KEY,
            )
        assertThat(count).isEqualTo(0)
    }

    // ── POST 생성 — 권한 없음 ───────────────────────────────────────────────────

    @Test
    @WithMockUser(username = ACTOR_UUID)
    fun `POST 권한 없음 - 403 - 룰과 액션 모두 생성되지 않는다`() {
        permissionResolver.deny(PROJECT_KEY)

        mockMvc
            .perform(
                post("/api/v1/projects/$PROJECT_KEY/automation/rules")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        createRequestJson(
                            name = "권한없음",
                            actions = listOf(mapOf("type" to "ADD_COMMENT", "config" to """{"body":"막혀야 함"}""")),
                        ),
                    ),
            ).andExpect(status().isForbidden)
            .andExpect(jsonPath("$.errorCode").value("AUTOMATION_ACCESS_DENIED"))

        val ruleCount =
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM automation_rules WHERE project_key = ?",
                Int::class.java,
                PROJECT_KEY,
            )
        assertThat(ruleCount).isEqualTo(0)
        val actionCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM automation_actions", Int::class.java)
        assertThat(actionCount).isEqualTo(0)
    }

    // ── PATCH 수정 — actions 교체 ────────────────────────────────────────────────

    @Test
    @WithMockUser(username = ACTOR_UUID)
    fun `PATCH actions 교체 - 기존 액션이 삭제되고 새 액션만 남는다`() {
        val ruleId =
            createRule(
                name = "교체 대상",
                actions = listOf(mapOf("type" to "ADD_COMMENT", "config" to """{"body":"이전 액션"}""")),
            )

        mockMvc
            .perform(
                patch("/api/v1/projects/$PROJECT_KEY/automation/rules/$ruleId")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """{"version":0,"actions":[{"type":"ASSIGN","config":"{\"assigneeId\":null}"}]}""",
                    ),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.actions.length()").value(1))
            .andExpect(jsonPath("$.actions[0].type").value("ASSIGN"))
            .andExpect(jsonPath("$.version").value(1))

        // 응답뿐 아니라 실제로 영속됐는지 재조회로 확인한다.
        mockMvc
            .perform(get("/api/v1/projects/$PROJECT_KEY/automation/rules/$ruleId"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.actions.length()").value(1))
            .andExpect(jsonPath("$.actions[0].type").value("ASSIGN"))

        val remaining =
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM automation_actions WHERE rule_id = ?::uuid",
                Int::class.java,
                ruleId,
            )
        assertThat(remaining).isEqualTo(1)
    }

    @Test
    @WithMockUser(username = ACTOR_UUID)
    fun `PATCH actions 미지정 - 기존 액션이 그대로 유지된다`() {
        val ruleId =
            createRule(
                name = "무변경 대상",
                actions = listOf(mapOf("type" to "ADD_COMMENT", "config" to """{"body":"유지되어야 함"}""")),
            )

        mockMvc
            .perform(
                patch("/api/v1/projects/$PROJECT_KEY/automation/rules/$ruleId")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"version":0,"name":"이름만 변경"}"""),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.actions.length()").value(1))
            .andExpect(jsonPath("$.actions[0].type").value("ADD_COMMENT"))
    }

    // ── PATCH 수정 — actorUserId (FR-AT-02 Task 14) ─────────────────────────────

    @Test
    @WithMockUser(username = ACTOR_UUID)
    fun `PATCH actorUserId 변경 - 저장 및 응답에 반영되고 재조회 시 새 actor 로 남는다`() {
        val ruleId = createRule(name = "actor 변경 대상")

        mockMvc
            .perform(
                patch("/api/v1/projects/$PROJECT_KEY/automation/rules/$ruleId")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"version":0,"actorUserId":"$OTHER_ACTOR_UUID"}"""),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.actorUserId").value(OTHER_ACTOR_UUID))
            .andExpect(jsonPath("$.version").value(1))

        // 응답뿐 아니라 실제로 영속됐는지 재조회로 확인한다.
        mockMvc
            .perform(get("/api/v1/projects/$PROJECT_KEY/automation/rules/$ruleId"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.actorUserId").value(OTHER_ACTOR_UUID))
    }

    @Test
    @WithMockUser(username = ACTOR_UUID)
    fun `PATCH actorUserId 미지정 - 기존 actor 가 그대로 유지된다`() {
        val ruleId = createRule(name = "actor 유지 대상")

        mockMvc
            .perform(
                patch("/api/v1/projects/$PROJECT_KEY/automation/rules/$ruleId")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"version":0,"name":"이름만 변경"}"""),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.actorUserId").value(ACTOR_UUID))
    }

    // ── PATCH 수정 — 다필드 동시 변경 OCC (코드리뷰 BLOCKER, task-16) ──────────────
    //
    // patch() 가 name→updateConfig→updateActions→changeActor 를 순차 체이닝하면 도메인 동작 호출
    // 횟수(K)만큼 version 이 인메모리에서 여러 번 +1 된다. repository 의 OCC 술어는
    // "expectedVersion = rule.version - 1"(단일 bump 전제)이므로, K≥2 면 expectedVersion 이 DB 원본
    // version(클라이언트가 보낸 version)과 어긋나 항상 409 로 실패해야 정상(수정 전) — 아래 테스트들은
    // 그 대신 "한 PATCH = version 정확히 +1"(K 무관)을 기대한다.

    @Test
    @WithMockUser(username = ACTOR_UUID)
    fun `PATCH name+actions 동시 변경 - 200 이고 version 이 정확히 1 증가한다`() {
        val ruleId =
            createRule(
                name = "다필드 대상1",
                actions = listOf(mapOf("type" to "ADD_COMMENT", "config" to """{"body":"이전 액션"}""")),
            )

        mockMvc
            .perform(
                patch("/api/v1/projects/$PROJECT_KEY/automation/rules/$ruleId")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """{"version":0,"name":"이름+액션 변경",""" +
                            """"actions":[{"type":"ASSIGN","config":"{\"assigneeId\":null}"}]}""",
                    ),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.version").value(1))
            .andExpect(jsonPath("$.name").value("이름+액션 변경"))
            .andExpect(jsonPath("$.actions.length()").value(1))
            .andExpect(jsonPath("$.actions[0].type").value("ASSIGN"))

        // 응답뿐 아니라 실제로 영속됐는지 재조회로 확인한다 — 재조회 version 도 1 이어야 다음 PATCH 가
        // 정상적으로 expectedVersion=1 을 보낼 수 있다(비수렴 회귀 방지).
        mockMvc
            .perform(get("/api/v1/projects/$PROJECT_KEY/automation/rules/$ruleId"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.version").value(1))
            .andExpect(jsonPath("$.name").value("이름+액션 변경"))
    }

    @Test
    @WithMockUser(username = ACTOR_UUID)
    fun `PATCH name+enabled 동시 변경 - 200 이고 version 이 정확히 1 증가한다`() {
        val ruleId = createRule(name = "다필드 대상2")

        mockMvc
            .perform(
                patch("/api/v1/projects/$PROJECT_KEY/automation/rules/$ruleId")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"version":0,"name":"이름+비활성","enabled":false}"""),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.version").value(1))
            .andExpect(jsonPath("$.name").value("이름+비활성"))
            .andExpect(jsonPath("$.enabled").value(false))
    }

    @Test
    @WithMockUser(username = ACTOR_UUID)
    fun `PATCH actions+actorUserId 동시 변경 - 200 이고 version 이 정확히 1 증가한다`() {
        val ruleId = createRule(name = "다필드 대상3")

        mockMvc
            .perform(
                patch("/api/v1/projects/$PROJECT_KEY/automation/rules/$ruleId")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """{"version":0,"actions":[{"type":"ASSIGN","config":"{\"assigneeId\":null}"}],""" +
                            """"actorUserId":"$OTHER_ACTOR_UUID"}""",
                    ),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.version").value(1))
            .andExpect(jsonPath("$.actions.length()").value(1))
            .andExpect(jsonPath("$.actorUserId").value(OTHER_ACTOR_UUID))
    }

    @Test
    @WithMockUser(username = ACTOR_UUID)
    fun `PATCH 3필드(name+actions+actorUserId) 동시 변경 - 200 이고 version 이 정확히 1 증가한다`() {
        val ruleId = createRule(name = "다필드 대상4")

        mockMvc
            .perform(
                patch("/api/v1/projects/$PROJECT_KEY/automation/rules/$ruleId")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """{"version":0,"name":"3필드 변경",""" +
                            """"actions":[{"type":"ADD_COMMENT","config":"{\"body\":\"3필드\"}"}],""" +
                            """"actorUserId":"$OTHER_ACTOR_UUID"}""",
                    ),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.version").value(1))
            .andExpect(jsonPath("$.name").value("3필드 변경"))
            .andExpect(jsonPath("$.actions.length()").value(1))
            .andExpect(jsonPath("$.actorUserId").value(OTHER_ACTOR_UUID))

        // 두 번째 PATCH — 첫 PATCH 후 version 이 정확히 1이었어야 expectedVersion=1 이 수락된다
        // (비수렴 없이 연속 다필드 PATCH 도 항상 정확히 1씩 증가하는지 확인).
        mockMvc
            .perform(
                patch("/api/v1/projects/$PROJECT_KEY/automation/rules/$ruleId")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"version":1,"name":"두번째 PATCH","enabled":false}"""),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.version").value(2))
    }

    // ── 헬퍼 ──────────────────────────────────────────────────────────────────────

    @Suppress("LongParameterList")
    private fun createRequestJson(
        name: String,
        triggerType: String = "ISSUE_CREATED",
        triggerConfig: String = "{}",
        actions: List<Map<String, String?>> = emptyList(),
        actorUserId: String? = null,
    ): String {
        val body =
            mutableMapOf<String, Any?>(
                "name" to name,
                "triggerType" to triggerType,
                "triggerConfig" to triggerConfig,
            )
        if (actions.isNotEmpty()) body["actions"] = actions
        if (actorUserId != null) body["actorUserId"] = actorUserId
        return objectMapper.writeValueAsString(body)
    }

    /**
     * MockMvc 로 액션 포함 룰을 생성하고 생성된 id 를 반환하는 테스트 헬퍼.
     *
     * 호출하는 테스트 메서드가 `@WithMockUser(username = ACTOR_UUID)` 를 이미 갖고 있어야 한다.
     */
    private fun createRule(
        name: String,
        actions: List<Map<String, String?>> = emptyList(),
    ): String {
        val response =
            mockMvc
                .perform(
                    post("/api/v1/projects/$PROJECT_KEY/automation/rules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createRequestJson(name = name, actions = actions)),
                ).andExpect(status().isCreated)
                .andReturn()
                .response
                .contentAsString
        return objectMapper.readTree(response).get("rule").get("id").asText()
    }

    /**
     * 테스트 전용 인가 필터체인 + [StubAutomationPermissionResolver]/[StubIssueMutationPort]/
     * [StubIssueSnapshotPort]/[StubIssuePermissionResolver] 빈 등록(FR-AT-01
     * `AutomationRuleControllerTest.TestSupportConfig` 선례 동형 — 이 테스트 파일 자체 범위 내 재선언,
     * [StubIssueSnapshotPort] 는 FR-AT-03 Task 7 추가분, [StubIssuePermissionResolver] 는 FR-AT-04
     * Task 4 추가분).
     */
    @TestConfiguration
    class TestSupportConfig {
        @Bean
        fun automationActionsTestSecurityFilterChain(http: HttpSecurity): SecurityFilterChain {
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
