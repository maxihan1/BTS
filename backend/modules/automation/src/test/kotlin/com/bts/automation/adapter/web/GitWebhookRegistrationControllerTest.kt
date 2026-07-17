// GitWebhookRegistrationController 풀스택 HTTP 통합 테스트 — 등록/목록/삭제 + 가드 순서 + 토큰 1회노출 + secret 검증 (FR-AT-07 PR-C Task 11)

package com.bts.automation.adapter.web

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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
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
 * 유효한 provider 서명 secret. 응답 본문 누출 검사([[GitWebhookRegistrationControllerTest]] §GET 누출)에서
 * 부분 문자열로 찾을 수 있도록 다른 상수와 겹치지 않는 특징적인 값을 쓴다.
 */
private const val VALID_SECRET = "hmac-shared-secret-DO-NOT-LEAK-9142"

/** 최소 길이([com.bts.automation.application.GitWebhookRegistrationService] MIN_SECRET_LENGTH=16) 미만 — 15자. */
private const val SECRET_15_CHARS = "123456789012345"

/** 최소 길이 경계 정확히 16자 — 통과해야 한다. */
private const val SECRET_16_CHARS = "1234567890123456"

/** 프로젝트에 존재하지 않는 웹훅 id — 가드 순서 실증(403 vs 404)에 쓴다. */
private const val ABSENT_WEBHOOK_ID = "99999999-9999-4999-8999-999999999999"

/**
 * [GitWebhookRegistrationController] 풀스택 HTTP 통합 테스트 (FR-AT-07 PR-C Task 11).
 *
 * Controller → [com.bts.automation.application.GitWebhookRegistrationService] → 실
 * [com.bts.automation.adapter.GitWebhookRepository] → **실 PostgreSQL(Testcontainers)** end-to-end.
 * MANAGE_AUTOMATION 판정만 [StubAutomationPermissionResolver] 로 대체한다(BC 격리 — automation
 * 클래스패스에 identity-access 구현이 없다, `AutomationRuleControllerTest` 동형).
 *
 * ## 암호화 키 주입
 * secret 은 `automationSecretEncryptor` 로 암호화해 저장하므로 이 컨텍스트는 `bts.automation-encryption.*`
 * 프로퍼티가 **반드시** 있어야 한다 — 없으면 등록 첫 호출이 500 이 된다(키 미설정은 부팅으로 드러나지 않는다,
 * [[use-time-validated-env-passes-boot-fails-on-use]]). salt 는 hex 여야 한다([SecretEncryptor] 계약).
 *
 * ## 커버 시나리오 (plan Task 11 RED)
 * - 201 + 원문 토큰 **1회 노출** + DB 에는 SHA-256 해시만(평문 토큰 미저장, DATA.md §8).
 * - GET 목록 응답 **원문(raw) JSON 문자열**에 token·secret 이 **한 조각도 없음** — 필드 누락이 아니라
 *   "값이 새지 않는가"를 단언한다.
 * - secret 검증(B4-sec) — blank·15자 거부(400), 16자 경계 통과(201).
 * - 가드 순서([[auth-extraction-before-resource-lookup]]) — 미인증 401 · 권한없음 403.
 * - **가드 순서 실증 2종** — 권한 없는 actor 에게는 (a) 없는 웹훅 DELETE 가 404 가 아니라 **403**,
 *   (b) blank secret POST 가 400 이 아니라 **403**. 즉 권한 판정이 리소스 조회·본문 검증보다 먼저다.
 * - DELETE 소프트 삭제(DATA.md §1.2) — 204 후 GET 목록에서 사라지되 행 자체는 남고 `deleted_at` 이 채워진다.
 * - 프로젝트 경계 — 타 프로젝트 소속 웹훅 DELETE 는 404(존재 숨김).
 */
@SpringBootTest(
    classes = [AutomationTestBootApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    properties = [
        "bts.automation-encryption.key=test-automation-encryption-key-value",
        "bts.automation-encryption.salt=deadbeefcafef00d",
    ],
)
@Import(
    AutomationTestcontainersBase::class,
    GitWebhookRegistrationControllerTest.TestSupportConfig::class,
)
class GitWebhookRegistrationControllerTest {
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
        jdbcTemplate.update("DELETE FROM git_webhooks")
        permissionResolver.reset()
        permissionResolver.allow(PROJECT_KEY)
        permissionResolver.allow(OTHER_PROJECT_KEY)
    }

    // ── POST 등록 ────────────────────────────────────────────────────────────────

    @Test
    @WithMockUser(username = ACTOR_UUID)
    fun `POST 등록 - 201 + 원문 토큰 1회 노출 + DB 에는 SHA-256 해시만 저장된다`() {
        val body =
            mockMvc
                .perform(
                    post("/api/v1/projects/$PROJECT_KEY/automation/git-webhooks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createRequestJson()),
                ).andExpect(status().isCreated)
                .andExpect(jsonPath("$.provider").value("GITHUB"))
                .andExpect(jsonPath("$.token").isNotEmpty)
                .andReturn()
                .response
                .contentAsString

        val rawToken = objectMapper.readTree(body).get("token").asText()

        // DATA.md §8 — 평문 토큰은 절대 저장하지 않는다. 해시만 저장된다.
        val tokenHash =
            jdbcTemplate.queryForObject(
                "SELECT token_hash FROM git_webhooks WHERE project_key = ?",
                String::class.java,
                PROJECT_KEY,
            )
        assertThat(tokenHash).isEqualTo(sha256Hex(rawToken))
        assertThat(tokenHash).isNotEqualTo(rawToken)

        // secret 도 평문으로 저장되지 않는다(암호문).
        val secretEncrypted =
            jdbcTemplate.queryForObject(
                "SELECT secret_encrypted FROM git_webhooks WHERE project_key = ?",
                String::class.java,
                PROJECT_KEY,
            )
        assertThat(secretEncrypted).isNotNull().isNotEqualTo(VALID_SECRET)
        assertThat(secretEncrypted).doesNotContain(VALID_SECRET)
    }

    @Test
    @WithMockUser(username = ACTOR_UUID)
    fun `POST 등록 - webhookUrl 은 인바운드 경로 + 원문 토큰으로 조립된다`() {
        val body =
            mockMvc
                .perform(
                    post("/api/v1/projects/$PROJECT_KEY/automation/git-webhooks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createRequestJson()),
                ).andExpect(status().isCreated)
                .andReturn()
                .response
                .contentAsString

        val json = objectMapper.readTree(body)
        val rawToken = json.get("token").asText()
        assertThat(json.get("webhookUrl").asText()).isEqualTo("/api/v1/webhooks/git/$rawToken")
    }

    @Test
    @WithMockUser(username = ACTOR_UUID)
    fun `POST 등록 - 알 수 없는 provider - 400`() {
        mockMvc
            .perform(
                post("/api/v1/projects/$PROJECT_KEY/automation/git-webhooks")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(createRequestJson(provider = "BITBUCKET")),
            ).andExpect(status().isBadRequest)
    }

    // ── secret 검증 (B4-sec) ─────────────────────────────────────────────────────

    @Test
    @WithMockUser(username = ACTOR_UUID)
    fun `POST blank secret - 400 - GITLAB 평문 비교를 빈 값으로 통과시킬 수 없어야 한다`() {
        mockMvc
            .perform(
                post("/api/v1/projects/$PROJECT_KEY/automation/git-webhooks")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(createRequestJson(secret = "")),
            ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errorCode").value("AUTOMATION_GIT_WEBHOOK_SECRET_INVALID"))

        assertThat(countWebhooks()).isZero()
    }

    @Test
    @WithMockUser(username = ACTOR_UUID)
    fun `POST 공백만 있는 secret - 400 - 길이만 채운 빈 secret 도 거부한다`() {
        mockMvc
            .perform(
                post("/api/v1/projects/$PROJECT_KEY/automation/git-webhooks")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(createRequestJson(secret = " ".repeat(20))),
            ).andExpect(status().isBadRequest)

        assertThat(countWebhooks()).isZero()
    }

    @Test
    @WithMockUser(username = ACTOR_UUID)
    fun `POST 15자 secret - 400 - 최소 길이 미만은 거부한다`() {
        mockMvc
            .perform(
                post("/api/v1/projects/$PROJECT_KEY/automation/git-webhooks")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(createRequestJson(secret = SECRET_15_CHARS)),
            ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errorCode").value("AUTOMATION_GIT_WEBHOOK_SECRET_INVALID"))

        assertThat(countWebhooks()).isZero()
    }

    @Test
    @WithMockUser(username = ACTOR_UUID)
    fun `POST 16자 secret - 201 - 최소 길이 경계는 통과한다`() {
        mockMvc
            .perform(
                post("/api/v1/projects/$PROJECT_KEY/automation/git-webhooks")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(createRequestJson(secret = SECRET_16_CHARS)),
            ).andExpect(status().isCreated)

        assertThat(countWebhooks()).isEqualTo(1)
    }

    @Test
    @WithMockUser(username = ACTOR_UUID)
    fun `POST 상한 초과 secret - 400`() {
        mockMvc
            .perform(
                post("/api/v1/projects/$PROJECT_KEY/automation/git-webhooks")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(createRequestJson(secret = "a".repeat(4097))),
            ).andExpect(status().isBadRequest)

        assertThat(countWebhooks()).isZero()
    }

    // ── GET 목록 — 누출 차단 ──────────────────────────────────────────────────────

    @Test
    @WithMockUser(username = ACTOR_UUID)
    fun `GET 목록 - 200 + 응답 원문에 token 도 secret 도 없다`() {
        val rawToken = registerWebhook()

        val body =
            mockMvc
                .perform(get("/api/v1/projects/$PROJECT_KEY/automation/git-webhooks"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].provider").value("GITHUB"))
                .andExpect(jsonPath("$[0].createdBy").value(ACTOR_UUID))
                .andExpect(jsonPath("$[0].createdAt").isNotEmpty)
                .andReturn()
                .response
                .contentAsString

        // 필드 누락이 아니라 "값이 새지 않는가" — 응답 원문 어디에도 토큰/secret 조각이 없어야 한다.
        assertThat(body).doesNotContain(rawToken)
        assertThat(body).doesNotContain(VALID_SECRET)
        assertThat(body).doesNotContain(sha256Hex(rawToken))
        assertThat(body).doesNotContain("token")
        assertThat(body).doesNotContain("secret")
    }

    @Test
    @WithMockUser(username = ACTOR_UUID)
    fun `GET 목록 - 프로젝트 경계 - 타 프로젝트 웹훅은 보이지 않는다`() {
        registerWebhook(projectKey = OTHER_PROJECT_KEY)

        mockMvc
            .perform(get("/api/v1/projects/$PROJECT_KEY/automation/git-webhooks"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(0))
    }

    // ── DELETE 소프트 삭제 ───────────────────────────────────────────────────────

    @Test
    @WithMockUser(username = ACTOR_UUID)
    fun `DELETE - 204 + GET 에서 사라지고 행은 남아 deleted_at 이 채워진다`() {
        registerWebhook()
        val id = firstWebhookId()

        mockMvc
            .perform(delete("/api/v1/projects/$PROJECT_KEY/automation/git-webhooks/$id"))
            .andExpect(status().isNoContent)

        mockMvc
            .perform(get("/api/v1/projects/$PROJECT_KEY/automation/git-webhooks"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(0))

        // 소프트 삭제(DATA.md §1.2) — 물리 삭제가 아니라 deleted_at 이 채워진 상태로 행이 남는다.
        val deletedAt =
            jdbcTemplate.queryForObject(
                "SELECT deleted_at FROM git_webhooks WHERE id = ?",
                java.sql.Timestamp::class.java,
                UUID.fromString(id),
            )
        assertThat(deletedAt).isNotNull()
    }

    @Test
    @WithMockUser(username = ACTOR_UUID)
    fun `DELETE 타 프로젝트 소속 웹훅 - 404 - 존재를 숨긴다`() {
        registerWebhook(projectKey = OTHER_PROJECT_KEY)
        val id = firstWebhookId()

        mockMvc
            .perform(delete("/api/v1/projects/$PROJECT_KEY/automation/git-webhooks/$id"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.errorCode").value("AUTOMATION_GIT_WEBHOOK_NOT_FOUND"))
    }

    @Test
    @WithMockUser(username = ACTOR_UUID)
    fun `DELETE 없는 웹훅 - 404`() {
        // errorCode 까지 단언한다 — 상태코드만 보면 "라우트 자체가 없어서 나온 404"와 구별되지 않아
        // 컨트롤러가 없어도 통과하는 vacuous 테스트가 된다([[negative-guard-needs-body-discriminator]]).
        mockMvc
            .perform(delete("/api/v1/projects/$PROJECT_KEY/automation/git-webhooks/$ABSENT_WEBHOOK_ID"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.errorCode").value("AUTOMATION_GIT_WEBHOOK_NOT_FOUND"))
    }

    // ── 인증/인가 가드 ───────────────────────────────────────────────────────────

    @Test
    fun `POST 미인증 - 401`() {
        mockMvc
            .perform(
                post("/api/v1/projects/$PROJECT_KEY/automation/git-webhooks")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(createRequestJson()),
            ).andExpect(status().isUnauthorized)

        assertThat(countWebhooks()).isZero()
    }

    @Test
    fun `GET 미인증 - 401`() {
        mockMvc
            .perform(get("/api/v1/projects/$PROJECT_KEY/automation/git-webhooks"))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `DELETE 미인증 - 401`() {
        mockMvc
            .perform(delete("/api/v1/projects/$PROJECT_KEY/automation/git-webhooks/$ABSENT_WEBHOOK_ID"))
            .andExpect(status().isUnauthorized)
    }

    @Test
    @WithMockUser(username = ACTOR_UUID)
    fun `POST 권한 없음 - 403 + 웹훅은 만들어지지 않는다`() {
        permissionResolver.deny(PROJECT_KEY)

        mockMvc
            .perform(
                post("/api/v1/projects/$PROJECT_KEY/automation/git-webhooks")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(createRequestJson()),
            ).andExpect(status().isForbidden)
            .andExpect(jsonPath("$.errorCode").value("AUTOMATION_ACCESS_DENIED"))

        assertThat(countWebhooks()).isZero()
    }

    @Test
    @WithMockUser(username = ACTOR_UUID)
    fun `GET 권한 없음 - 403`() {
        permissionResolver.deny(PROJECT_KEY)

        mockMvc
            .perform(get("/api/v1/projects/$PROJECT_KEY/automation/git-webhooks"))
            .andExpect(status().isForbidden)
    }

    @Test
    @WithMockUser(username = ACTOR_UUID)
    fun `DELETE 권한 없음 - 403`() {
        registerWebhook()
        val id = firstWebhookId()
        permissionResolver.deny(PROJECT_KEY)

        mockMvc
            .perform(delete("/api/v1/projects/$PROJECT_KEY/automation/git-webhooks/$id"))
            .andExpect(status().isForbidden)
    }

    // ── ★ 가드 순서 실증 (권한 판정이 리소스 조회·본문 검증보다 먼저) ──────────────

    @Test
    @WithMockUser(username = ACTOR_UUID)
    fun `DELETE 권한 없음 + 없는 웹훅 - 404 가 아니라 403 - 존재 probe 차단`() {
        permissionResolver.deny(PROJECT_KEY)

        // 리소스 조회가 권한 판정보다 먼저라면 여기서 404 가 나와, 권한 없는 사용자가 403/404 차이로
        // 웹훅 존재 여부를 알아낼 수 있다([[auth-extraction-before-resource-lookup]]).
        mockMvc
            .perform(delete("/api/v1/projects/$PROJECT_KEY/automation/git-webhooks/$ABSENT_WEBHOOK_ID"))
            .andExpect(status().isForbidden)
    }

    @Test
    @WithMockUser(username = ACTOR_UUID)
    fun `POST 권한 없음 + blank secret - 400 이 아니라 403 - 본문 검증보다 권한이 먼저`() {
        permissionResolver.deny(PROJECT_KEY)

        // secret 검증이 권한 판정보다 먼저면 400 이 된다. `AutomationRuleController.import` 가 확립한
        // "권한 → 본문 파싱/검증" 순서(게이트2 코드리뷰 CONCERN-2)를 이 API 도 따른다.
        mockMvc
            .perform(
                post("/api/v1/projects/$PROJECT_KEY/automation/git-webhooks")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(createRequestJson(secret = "")),
            ).andExpect(status().isForbidden)
    }

    // ── 헬퍼 ─────────────────────────────────────────────────────────────────────

    private fun createRequestJson(
        provider: String = "GITHUB",
        secret: String = VALID_SECRET,
    ): String = """{"provider":"$provider","secret":"$secret"}"""

    /** 웹훅 1건을 등록하고 응답의 원문 토큰을 반환한다. */
    private fun registerWebhook(projectKey: String = PROJECT_KEY): String {
        val body =
            mockMvc
                .perform(
                    post("/api/v1/projects/$projectKey/automation/git-webhooks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createRequestJson()),
                ).andExpect(status().isCreated)
                .andReturn()
                .response
                .contentAsString
        return objectMapper.readTree(body).get("token").asText()
    }

    private fun firstWebhookId(): String =
        jdbcTemplate
            .queryForObject("SELECT id FROM git_webhooks LIMIT 1", UUID::class.java)
            .toString()

    // 블록 body — expression body 로 두면 ktlint(한 줄로 붙이라)와 detekt(MaxLineLength 120)가 서로
    // 충돌한다([[ktlint-detekt-linelength-and-baseline-traps]]).
    private fun countWebhooks(): Int {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM git_webhooks", Int::class.java) ?: 0
    }

    private fun sha256Hex(plaintext: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(plaintext.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    /**
     * 이 테스트 컨텍스트 전용 지원 빈 (`AutomationRuleControllerTest.TestSupportConfig` 동형).
     *
     * cross-BC 포트(권한·이슈)는 automation 클래스패스에 prod 구현이 없어 fail-closed stub 으로 등록한다.
     * 필터 체인은 "전 경로 authenticated + 미인증 401" — 이 등록 API 는 인증이 필요한 관리 API 라
     * permitAll 대상이 아니다(인바운드 웹훅 경로만 permitAll, Task 12 소유).
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
