// GitOps YAML round-trip + 멱등 + 크기상한 실서블릿 end-to-end 계약 검증 (FR-AT-06 GitOps Task 6)

package com.bts.automation.web

import com.bts.automation.AutomationTestBootApplication
import com.bts.automation.AutomationTestcontainersBase
import com.bts.automation.StubAutomationPermissionResolver
import com.bts.automation.StubIssueMutationPort
import com.bts.automation.StubIssuePermissionResolver
import com.bts.automation.StubIssueSnapshotPort
import com.bts.automation.gitops.AutomationYamlCodec
import com.bts.automation.gitops.ImportRuleCommand
import com.bts.shared.permission.IssuePermissionResolver
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.config.Customizer
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.core.userdetails.User
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.provisioning.InMemoryUserDetailsManager
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.HttpStatusEntryPoint
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import java.util.UUID

/** `@WithMockUser` username 은 컴파일 상수여야 하므로 top-level const 로 둔다(actor 는 principal name=UUID). */
private const val ACTOR_UUID = "66666666-6666-6666-6666-666666666666"

/** round-trip 원본 프로젝트(export 대상). */
private const val PROJECT_A = "GITRTA"

/** round-trip 대상 프로젝트(import 대상, A와 무관한 빈 프로젝트). */
private const val PROJECT_B = "GITRTB"

/** 화이트리스트 필드([com.bts.automation.domain.Condition.FIELD_WHITELIST])를 참조하는 유효 조건 표현식. */
private const val VALID_CONDITION = """{"==":[{"var":"issue.status"},"open"]}"""

/** ASSIGN 액션 시드용 고정 담당자 id(존재 검증은 실행 시점 책임이라 임의 UUID로 충분). */
private val ASSIGNEE_ID: UUID = UUID.fromString("77777777-7777-7777-7777-777777777777")

/**
 * import/export 양쪽이 공유하는 YAML 미디어 타입([com.bts.automation.adapter.web.AutomationRuleController.import]
 * `consumes` 목록 중 하나).
 */
private val YAML_MEDIA_TYPE: MediaType = MediaType.parseMediaType("application/yaml")

/** [com.bts.automation.adapter.web.AutomationRuleController.import] 의 상한과 동일(spec NFR2·EC8) — 파일 범위 밖이라 재정의한다. */
private const val MAX_IMPORT_RULES = 500

/**
 * [com.bts.automation.adapter.web.AutomationRuleController.import] 의 본문 바이트 상한과 동일(spec NFR2,
 * 게이트2 코드리뷰 CONCERN-1 수정) — 파일 범위 밖이라 재정의한다.
 */
private const val MAX_IMPORT_BYTES = 1_048_576

/** 실서블릿(RANDOM_PORT) 크기상한 테스트 전용 actor — round-trip 테스트와 다른 Spring 컨텍스트라 별도 상수로 둔다. */
private const val SIZE_LIMIT_ACTOR_UUID = "99999999-9999-9999-9999-999999999999"

/** 실서블릿 크기상한 테스트 전용 프로젝트 키. */
private const val SIZE_LIMIT_PROJECT_KEY = "GITRTSIZE"

/** [SIZE_LIMIT_ACTOR_UUID] 의 HTTP Basic 인증 비밀번호(테스트 전용, prod 인증 방식과 무관). */
private const val SIZE_LIMIT_TEST_PASSWORD = "gitops-roundtrip-test-pw"

/** 요청 바디 조립용 액션 1건 표현([com.bts.automation.adapter.web.dto.ActionRequest] 와 필드 대칭). */
private data class RoundTripActionSpec(val type: String, val config: String)

/**
 * GitOps YAML round-trip + 멱등 end-to-end 계약 검증 (FR-AT-06 GitOps Task 6, spec 완료기준 3·4·시나리오
 * S2/S3).
 *
 * T3([com.bts.automation.adapter.web.AutomationRuleController.export])·T5
 * ([com.bts.automation.adapter.web.AutomationRuleController.import])가 이미 GREEN이므로, 이 테스트는 새
 * prod 코드를 요구하지 않는다 — "프로젝트 A에서 export한 YAML을 B로 import하면 A와 동등하다" +
 * "같은 YAML을 재적용해도 안전하다(GitOps apply)"는 end-to-end 계약이 실제로 성립하는지를 검증한다.
 *
 * [AutomationRuleExportIntegrationTest]/[AutomationRuleImportIntegrationTest] 의 `TestSupportConfig` 동형
 * 인프라를 재사용한다(BC 격리 — automation 클래스패스에 identity-access 구현이 없어
 * [StubAutomationPermissionResolver] 로 MANAGE_AUTOMATION 판정을 대체).
 *
 * ## 커버 시나리오 (plan Task 6 RED)
 * 1. round-trip(D5, 완료기준 3) — A에 활성 2(조건 포함 1 + 웹훅 1)·비활성 1, SET_FIELD/ADD_COMMENT/ASSIGN
 *    액션 조합으로 시드 → export한 YAML의 `projectKey`만 B로 치환해 import → B를 다시 export해 id로 짝지어
 *    A와 name·enabled·actorUserId·triggerType·triggerConfig·condition·actions 동등성을 확인한다.
 * 2. 멱등(S3, 완료기준 4) — 같은 YAML(projectKey=B)을 B에 2회 import → 1회차 created=N/updated=0, 2회차
 *    created=0/updated=N, DB 규칙 수 불변.
 *
 * 크기상한 실서블릿 검증([[multipart-default-limit-app-policy-false-green]] 회피)은 이 클래스가 아니라
 * 파일 하단의 별도 top-level 클래스 [AutomationGitOpsImportSizeLimitIntegrationTest] 가 담당한다 —
 * `webEnvironment` 가 이 클래스는 MOCK(MockMvc), 그쪽은 RANDOM_PORT([TestRestTemplate])로 서로 달라 같은
 * 클래스에 둘 수 없다(`ModuleBootTest.kt` 의 파일당 복수 top-level 클래스 선례 동형).
 */
@SpringBootTest(
    classes = [AutomationTestBootApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.MOCK,
)
@Import(
    AutomationTestcontainersBase::class,
    AutomationGitOpsRoundTripTest.TestSupportConfig::class,
)
class AutomationGitOpsRoundTripTest {
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
        permissionResolver.allow(PROJECT_A)
        permissionResolver.allow(PROJECT_B)
    }

    @Test
    @WithMockUser(username = ACTOR_UUID)
    fun `round-trip - A(활성2+비활성1, 조건+웹훅+SET_FIELD-ADD_COMMENT-ASSIGN) export→projectKey만 B로 치환→import → id별 A와 동등`() {
        seedRoundTripRules()
        val exportedYamlA = exportYaml(PROJECT_A)
        retireSourceProject()
        val yamlForB = replaceProjectKey(exportedYamlA, from = PROJECT_A, to = PROJECT_B)

        importYaml(PROJECT_B, yamlForB)

        val exportedYamlB = exportYaml(PROJECT_B)
        val rulesA = AutomationYamlCodec.fromYaml(exportedYamlA).rules
        val rulesB = AutomationYamlCodec.fromYaml(exportedYamlB).rules
        assertEquivalentRules(expected = rulesA, actual = rulesB)
    }

    @Test
    @WithMockUser(username = ACTOR_UUID)
    fun `멱등 - 같은 YAML을 B에 2회 import - 1회차 created=N-updated=0, 2회차 created=0-updated=N, 규칙 수 불변`() {
        seedRoundTripRules()
        val exportedYamlA = exportYaml(PROJECT_A)
        retireSourceProject()
        val yamlForB = replaceProjectKey(exportedYamlA, from = PROJECT_A, to = PROJECT_B)

        val first = objectMapper.readTree(importYaml(PROJECT_B, yamlForB))
        val ruleCount = first.get("total").asInt()
        assertThat(first.get("created").asInt()).isEqualTo(ruleCount)
        assertThat(first.get("updated").asInt()).isEqualTo(0)

        val second = objectMapper.readTree(importYaml(PROJECT_B, yamlForB))
        assertThat(second.get("created").asInt()).isEqualTo(0)
        assertThat(second.get("updated").asInt()).isEqualTo(ruleCount)

        val storedCount =
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM automation_rules WHERE project_key = ?",
                Int::class.java,
                PROJECT_B,
            )
        assertThat(storedCount).isEqualTo(ruleCount)
    }

    // ── 헬퍼 — 시드 ──────────────────────────────────────────────────────────

    /**
     * A의 행을 물리 삭제해 A가 방금 export한 id들을 "어디에도 없는" 상태로 되돌린다.
     *
     * [com.bts.automation.application.AutomationRuleService.importRules] 의 id 해석은 **전역**(프로젝트
     * 무관) `findById` 로 기존 소유를 판정한다(FR3 두 번째 분기 — id가 다른 프로젝트에 **살아있는 채로**
     * 소유돼 있으면 PK 전역 유일성 보호 차원에서 EC4 400으로 거부한다, spec FR3·EC4). S2가 그리는
     * "PROJ에서 export한 YAML을 빈 프로젝트 PROJ2로 옮긴다"는 GitOps 백업/이전 시나리오는 원본 PROJ의
     * 행이 그 시점엔 더 이상 살아있지 않다는 전제다 — export **직후** A를 정리해 그 전제를 재현한다
     * (export한 YAML 텍스트 자체는 이미 캡처됐으므로 이후 단언에는 영향이 없다).
     */
    private fun retireSourceProject() {
        jdbcTemplate.update("DELETE FROM automation_rules WHERE project_key = ?", PROJECT_A)
    }

    /** 활성 2(조건 포함 1 + 웹훅 1) + 비활성 1, SET_FIELD/ADD_COMMENT/ASSIGN 조합으로 A를 시드한다(plan Task 6 RED). */
    private fun seedRoundTripRules() {
        createRule(
            name = "조건부 우선순위 변경",
            triggerType = "ISSUE_CREATED",
            condition = VALID_CONDITION,
            actions =
                listOf(
                    RoundTripActionSpec("SET_FIELD", """{"field":"priority","value":"High"}"""),
                    RoundTripActionSpec("ADD_COMMENT", """{"body":"자동 처리됨"}"""),
                ),
        )
        createRule(
            name = "웹훅 담당자 배정",
            triggerType = "WEBHOOK",
            actions = listOf(RoundTripActionSpec("ASSIGN", """{"assigneeId":"$ASSIGNEE_ID"}""")),
        )
        val disabledId =
            createRule(
                name = "비활성 갱신 알림",
                triggerType = "ISSUE_UPDATED",
                actions = listOf(RoundTripActionSpec("ADD_COMMENT", """{"body":"비활성 규칙 코멘트"}""")),
            )
        disableRule(disabledId)
    }

    private fun createRequestJson(
        name: String,
        triggerType: String,
        triggerConfig: String = "{}",
        condition: String? = null,
        actions: List<RoundTripActionSpec> = emptyList(),
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

    /** `POST` 로 A에 룰을 생성하고 생성된 id 를 반환한다([AutomationRuleExportIntegrationTest.createRule] 동형). */
    private fun createRule(
        name: String,
        triggerType: String,
        triggerConfig: String = "{}",
        condition: String? = null,
        actions: List<RoundTripActionSpec> = emptyList(),
    ): String {
        val response =
            mockMvc
                .perform(
                    post("/api/v1/projects/$PROJECT_A/automation/rules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createRequestJson(name, triggerType, triggerConfig, condition, actions)),
                ).andExpect(status().isCreated)
                .andReturn()
                .response
                .contentAsString
        return objectMapper.readTree(response).get("rule").get("id").asText()
    }

    /** A 소속 [ruleId] 규칙을 `enabled=false` 로 PATCH 한다(생성 직후 version=0 전제). */
    private fun disableRule(ruleId: String) {
        mockMvc
            .perform(
                patch("/api/v1/projects/$PROJECT_A/automation/rules/$ruleId")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"version":0,"enabled":false}"""),
            ).andExpect(status().isOk)
    }

    // ── 헬퍼 — export/import 호출 ────────────────────────────────────────────

    private fun exportYaml(projectKey: String): String =
        mockMvc
            .perform(get("/api/v1/projects/$projectKey/automation/rules/export"))
            .andExpect(status().isOk)
            .andReturn()
            .response
            .contentAsString

    private fun importYaml(
        projectKey: String,
        yaml: String,
    ): String =
        mockMvc
            .perform(
                post("/api/v1/projects/$projectKey/automation/rules/import")
                    .contentType(YAML_MEDIA_TYPE)
                    .content(yaml),
            ).andExpect(status().isOk)
            .andReturn()
            .response
            .contentAsString

    /**
     * export한 YAML 텍스트의 `projectKey: "{from}"` 줄을 `projectKey: "{to}"` 로 문자열 치환한다(plan Task 6
     * "YAML의 projectKey를 B로 치환" 요구, 재직렬화 대신 최소 변경).
     *
     * [from] 리터럴이 존재하지 않으면 즉시 실패한다 — [AutomationYamlCodec] 의 YAML 방출 형식(문자열 스칼라를
     * 항상 큰따옴표로 감싸는 [com.fasterxml.jackson.dataformat.yaml.YAMLFactory] 기본값) 가정이 깨졌을 때
     * 이후 단언에서 원인 불명한 실패로 번지지 않게 방어한다.
     */
    private fun replaceProjectKey(
        yaml: String,
        from: String,
        to: String,
    ): String {
        // AutomationYamlCodec 의 yamlMapper(YAMLFactory 기본값)는 문자열 스칼라를 항상 큰따옴표로 감싼다
        // (MINIMIZE_QUOTES 미설정) — plain scalar 를 가정하지 않고 실제 방출 형식을 그대로 치환 대상으로 쓴다.
        val fromLine = "projectKey: \"$from\""
        val toLine = "projectKey: \"$to\""
        require(yaml.contains(fromLine)) { "export YAML에 '$fromLine' 줄이 없습니다(직렬화 형식 가정이 깨졌습니다)." }
        return yaml.replace(fromLine, toLine)
    }

    // ── 헬퍼 — 동등성 단언 ────────────────────────────────────────────────────

    /**
     * [expected](A)/[actual](B) 를 id로 짝지어 name·enabled·actorUserId·triggerType·triggerConfig·
     * condition·actions 동등성을 확인한다(spec 완료기준 3). id 집합이 같다는 것 자체가 "id 보존"의 단언이다.
     */
    private fun assertEquivalentRules(
        expected: List<ImportRuleCommand>,
        actual: List<ImportRuleCommand>,
    ) {
        assertThat(actual).hasSameSizeAs(expected)
        val expectedById = expected.associateBy { requireNotNull(it.id) { "export는 항상 id를 채운다." } }
        val actualById = actual.associateBy { requireNotNull(it.id) { "export는 항상 id를 채운다." } }
        assertThat(actualById.keys).isEqualTo(expectedById.keys)
        expectedById.forEach { (id, expectedRule) -> assertRuleFieldsEqual(expectedRule, actualById.getValue(id)) }
    }

    /**
     * 트리거 config·조건·액션 config 는 JSON 문자열이라 [JsonNode] 구조적 동등성으로 비교한다 — 문자열
     * 바이트 비교는 두 차례의 YAML↔JSON 왕복을 거치며 키 순서가 달라질 가능성에 취약하다.
     */
    private fun assertRuleFieldsEqual(
        expected: ImportRuleCommand,
        actual: ImportRuleCommand,
    ) {
        assertThat(actual.name).isEqualTo(expected.name)
        assertThat(actual.enabled).isEqualTo(expected.enabled)
        assertThat(actual.actorUserId).isEqualTo(expected.actorUserId)
        assertThat(actual.triggerType).isEqualTo(expected.triggerType)
        assertThat(jsonNodeOf(actual.triggerConfig)).isEqualTo(jsonNodeOf(expected.triggerConfig))
        assertThat(actual.condition?.let(::jsonNodeOf)).isEqualTo(expected.condition?.let(::jsonNodeOf))
        assertThat(actual.actions.map { it.type }).isEqualTo(expected.actions.map { it.type })
        actual.actions.zip(expected.actions).forEach { (a, e) ->
            assertThat(jsonNodeOf(a.config)).isEqualTo(jsonNodeOf(e.config))
        }
    }

    private fun jsonNodeOf(raw: String): JsonNode = objectMapper.readTree(raw)

    /**
     * 테스트 전용 인가 필터체인 + 협력자 stub 빈 등록([AutomationRuleExportIntegrationTest.TestSupportConfig]
     * 동형).
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

/**
 * `POST /api/v1/projects/{projectKey}/automation/rules/import` 규칙 수 상한(spec NFR2·EC8) 실서블릿 통합
 * 테스트 (FR-AT-06 GitOps Task 6).
 *
 * [AutomationGitOpsRoundTripTest] 와 달리 `webEnvironment = RANDOM_PORT` + [TestRestTemplate] 실 HTTP로
 * 검증한다 — MockMvc는 `DispatcherServlet`으로 직접 디스패치해 서블릿 컨테이너 경계를 우회하므로, 본문/카운트
 * 검증 경로가 실제로 실 서블릿을 통과하는지는 MockMvc로 보장할 수 없다
 * ([[multipart-default-limit-app-policy-false-green]] 반면교사, plan Task 6 RED 명시 요구 — MockMvc 금지).
 *
 * ## 인증 — httpBasic (JWT 인프라 부재, BC 격리)
 * automation 모듈에는 identity-access의 JWT 발급 인프라가 없다(BC 격리). `@WithMockUser`는 스레드 로컬
 * `SecurityContext`라 별도 스레드(임베디드 Tomcat)로 도는 실 HTTP 요청에 전파되지 않으므로, 이 클래스
 * 전용 [TestSupportConfig] 가 `httpBasic()` + [InMemoryUserDetailsManager] 로 실 인증 경로를 재현한다.
 *
 * ## 권한 — 파싱/크기검증보다 먼저 판정한다(게이트2 코드리뷰 CONCERN-2 수정)
 * [com.bts.automation.adapter.web.AutomationRuleController.import] 는 actor 추출(401) 다음으로
 * [com.bts.automation.application.AutomationRuleService.assertManageAutomationPermission] 을 호출해
 * 바이트 상한/규칙 수 상한/YAML 파싱보다 **먼저** MANAGE_AUTOMATION 권한을 판정한다 — 이 클래스의
 * [SIZE_LIMIT_ACTOR_UUID] 가 [SIZE_LIMIT_PROJECT_KEY] 에서 크기 상한(413) 응답을 실제로 받으려면
 * [cleanUp] 이 [StubAutomationPermissionResolver] 에 권한을 명시 allow 해야 한다(과거에는 순서가
 * 반대라 allow 가 필요 없었다).
 */
@SpringBootTest(
    classes = [AutomationTestBootApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
@Import(
    AutomationTestcontainersBase::class,
    AutomationGitOpsImportSizeLimitIntegrationTest.TestSupportConfig::class,
)
class AutomationGitOpsImportSizeLimitIntegrationTest {
    @Autowired
    @Suppress("VarCouldBeVal")
    private lateinit var restTemplate: TestRestTemplate

    @Autowired
    @Suppress("VarCouldBeVal")
    private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    @Suppress("VarCouldBeVal")
    private lateinit var permissionResolver: StubAutomationPermissionResolver

    @BeforeEach
    fun cleanUp() {
        jdbcTemplate.update("DELETE FROM automation_rules WHERE project_key = ?", SIZE_LIMIT_PROJECT_KEY)
        // 권한 판정이 크기 상한보다 먼저 실행되므로(클래스 KDoc §권한 참고) 이 프로젝트 키를 명시 allow
        // 해야 아래 테스트들이 실제로 413(크기 상한)까지 도달한다.
        permissionResolver.reset()
        permissionResolver.allow(SIZE_LIMIT_PROJECT_KEY)
    }

    @Test
    fun `POST import 실서블릿 - 규칙 수가 상한을 초과하면 - 413 AUTOMATION_IMPORT_TOO_LARGE, 저장 없음`() {
        val yaml = oversizedYaml(SIZE_LIMIT_PROJECT_KEY, MAX_IMPORT_RULES + 1)
        val entity = HttpEntity(yaml, yamlHeaders())

        val response =
            restTemplate
                .withBasicAuth(SIZE_LIMIT_ACTOR_UUID, SIZE_LIMIT_TEST_PASSWORD)
                .exchange(
                    "/api/v1/projects/$SIZE_LIMIT_PROJECT_KEY/automation/rules/import",
                    HttpMethod.POST,
                    entity,
                    String::class.java,
                )

        assertThat(response.statusCode).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE)
        assertThat(response.body).contains("AUTOMATION_IMPORT_TOO_LARGE")
        val storedCount =
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM automation_rules WHERE project_key = ?",
                Int::class.java,
                SIZE_LIMIT_PROJECT_KEY,
            )
        assertThat(storedCount).isEqualTo(0)
    }

    @Test
    fun `POST import 실서블릿 - 규칙 수는 상한 이하지만 본문 바이트가 상한을 초과하면 - 413 AUTOMATION_IMPORT_TOO_LARGE, 저장 없음`() {
        val yaml = oversizedByBytesYaml(SIZE_LIMIT_PROJECT_KEY)
        val entity = HttpEntity(yaml, yamlHeaders())

        val response =
            restTemplate
                .withBasicAuth(SIZE_LIMIT_ACTOR_UUID, SIZE_LIMIT_TEST_PASSWORD)
                .exchange(
                    "/api/v1/projects/$SIZE_LIMIT_PROJECT_KEY/automation/rules/import",
                    HttpMethod.POST,
                    entity,
                    String::class.java,
                )

        assertThat(response.statusCode).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE)
        assertThat(response.body).contains("AUTOMATION_IMPORT_TOO_LARGE")
        val storedCount =
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM automation_rules WHERE project_key = ?",
                Int::class.java,
                SIZE_LIMIT_PROJECT_KEY,
            )
        assertThat(storedCount).isEqualTo(0)
    }

    private fun yamlHeaders(): HttpHeaders = HttpHeaders().apply { contentType = YAML_MEDIA_TYPE }

    /**
     * [count]개의 최소 유효 규칙을 담은 YAML을 만든다(상한 검증은 codec 파싱 이후 곧바로 일어나므로 도메인
     * 상세 검증까지 갈 필요가 없다, [AutomationRuleImportIntegrationTest.oversizedYaml] 동형).
     */
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
     * 규칙 수는 1개([MAX_IMPORT_RULES] 이하)뿐이지만 `name` 필드에 [MAX_IMPORT_BYTES] 를 넘는 패딩을 담아
     * 원문 바이트 크기 자체를 상한 초과로 만든다(게이트2 코드리뷰 CONCERN-1 수정, 규칙 수 상한(EC8)과 별개인
     * 본문 바이트 상한(spec NFR2)을 단독으로 재현한다). 바이트 상한 검증은 YAML 파싱 **이전**에 일어나므로
     * 패딩이 실제로 파싱 가능한 값일 필요는 없다.
     */
    private fun oversizedByBytesYaml(projectKey: String): String {
        val padding = "x".repeat(MAX_IMPORT_BYTES + 1)
        return """
            |version: 1
            |projectKey: $projectKey
            |rules:
            |  - name: "$padding"
            |    trigger:
            |      type: ISSUE_CREATED
            |      config: {}
            """.trimMargin()
    }

    /**
     * httpBasic 인증 필터체인 + [SIZE_LIMIT_ACTOR_UUID] 단일 사용자 + 컨텍스트 부팅에 필요한 협력자 stub
     * 빈 등록([AutomationGitOpsRoundTripTest.TestSupportConfig] 동형, csrf는 세션 기반이 아닌 stateless
     * Basic 인증이라 비활성화한다).
     */
    @TestConfiguration
    class TestSupportConfig {
        @Bean
        fun automationImportSizeLimitSecurityFilterChain(http: HttpSecurity): SecurityFilterChain {
            http
                .csrf { it.disable() }
                .authorizeHttpRequests { it.anyRequest().authenticated() }
                .httpBasic(Customizer.withDefaults())
                .exceptionHandling {
                    it.authenticationEntryPoint(HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED))
                }
            return http.build()
        }

        @Bean
        fun userDetailsService(): UserDetailsService {
            val user =
                User
                    .withUsername(SIZE_LIMIT_ACTOR_UUID)
                    .password("{noop}$SIZE_LIMIT_TEST_PASSWORD")
                    .roles("USER")
                    .build()
            return InMemoryUserDetailsManager(user)
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
