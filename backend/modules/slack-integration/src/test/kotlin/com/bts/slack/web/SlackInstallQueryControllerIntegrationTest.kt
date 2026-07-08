// Slack 연결 상태/설치 URL JSON 조회 웹 레이어 통합 테스트 — 관리자 가드·비밀값 미노출 (FR-SL-01 D6/D7 Task 3)

package com.bts.slack.web

import com.bts.slack.SlackIntegrationTestBootApplication
import com.bts.slack.SlackTestSecurityConfig
import com.bts.slack.SlackTestcontainersConfig
import com.bts.slack.StubSystemPermissionResolver
import com.bts.slack.StubUserLookupPort
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.nullValue
import org.hamcrest.Matchers.startsWith
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/** `@WithMockUser` username 은 컴파일 상수여야 하므로 top-level const 로 둔다(actor 는 principal name=UUID). */
private const val ADMIN_UUID = "11111111-1111-1111-1111-111111111111"
private const val NON_ADMIN_UUID = "22222222-2222-2222-2222-222222222222"

/**
 * Slack 연결 상태/설치 URL JSON 조회 웹 레이어 풀스택 통합 테스트 (FR-SL-01 D6/D7 Task 3).
 *
 * `SlackInstallQueryController` → `SlackInstallService` → JdbcTemplate → **실 PostgreSQL(Testcontainers)**
 * end-to-end. 302 리다이렉트 흐름([SlackInstallController])과 달리 이 두 엔드포인트는 SPA(Bearer 인증)가
 * 호출하는 **JSON view-layer** 다([SlackInstallControllerIntegrationTest][SlackInstallIntegrationTest] 미러).
 *
 * ## 커버 (spec §API — 상태 배너 + 설치 개시 버튼)
 * - `GET /api/v1/slack/installation` — 관리자 & 미설치 → 200 `connected:false`(나머지 null), 설치됨 →
 *   200 `connected:true` + 표시필드(teamId/teamName/botUserId·installedAt·updatedAt ISO-8601·설치자
 *   표시명 installerName). 비관리자 → 403, 미인증 → 401.
 * - `GET /api/v1/slack/install-url` — 관리자 → 200 `{url}`(authorize URL + `state=`), 비관리자 → 403.
 *
 * ## 비밀값 미노출 (§1.1.2)
 * 상태 조회 응답 본문에 봇 토큰(`xoxb`)·암호문·`installedBy`(설치자 UUID)가 절대 실리지 않음을 검증한다 —
 * `SlackInstallationView` projection 이 애초에 그 필드들을 로드하지 않는다(방어적 타입 경계).
 */
@SpringBootTest(
    classes = [SlackIntegrationTestBootApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.MOCK,
)
@Import(
    SlackTestcontainersConfig::class,
    SlackTestSecurityConfig::class,
)
@TestPropertySource(
    properties = [
        "bts.slack.state-key=slack-oauth-state-hmac-key-for-integration-tests-0123456789",
        "bts.slack-encryption.key=slack-bot-token-encryption-key-for-integration-tests",
        "bts.slack-encryption.salt=deadbeefcafef00d",
        // buildAuthorizeUrl 은 client-id·redirect-uri 가 설정돼 있어야 실 authorize URL 을 만든다(state 서명 실제 경로).
        "bts.slack.client-id=test-client-id",
        "bts.slack.redirect-uri=https://bts.example.com/api/v1/slack/install/callback",
    ],
)
class SlackInstallQueryControllerIntegrationTest {
    @Autowired
    private lateinit var webApplicationContext: WebApplicationContext

    @Autowired
    private lateinit var jdbc: NamedParameterJdbcTemplate

    @Autowired
    private lateinit var permissionResolver: StubSystemPermissionResolver

    @Autowired
    private lateinit var userLookupPort: StubUserLookupPort

    private lateinit var mockMvc: MockMvc

    @BeforeEach
    fun setUp() {
        mockMvc =
            MockMvcBuilders
                .webAppContextSetup(webApplicationContext)
                .apply<org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder>(springSecurity())
                .build()
        jdbc.update("DELETE FROM slack_installs", emptyMap<String, Any>())
        permissionResolver.admins.clear()
        permissionResolver.admins.add(UUID.fromString(ADMIN_UUID))
        // 공유 stub 이므로 테스트 간 표시명 등록이 새지 않도록 비운다.
        userLookupPort.displayNames.clear()
    }

    @AfterEach
    fun tearDown() {
        jdbc.update("DELETE FROM slack_installs", emptyMap<String, Any>())
        userLookupPort.displayNames.clear()
    }

    // ── GET /api/v1/slack/installation ───────────────────────────────────────────

    @Test
    @WithMockUser(username = ADMIN_UUID)
    fun `GET installation - 관리자 미설치면 200 connected false`() {
        mockMvc
            .perform(get("/api/v1/slack/installation"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.connected").value(false))
            .andExpect(jsonPath("$.teamId", nullValue()))
            .andExpect(jsonPath("$.teamName", nullValue()))
            .andExpect(jsonPath("$.installedAt", nullValue()))
    }

    @Test
    @WithMockUser(username = ADMIN_UUID)
    fun `GET installation - 관리자 설치됨이면 200 connected true 표시필드만`() {
        insertInstallation()
        // 설치자 UUID → 표시명 등록. 응답에는 해석된 표시명만 실리고 원시 UUID 는 실리지 않아야 한다.
        userLookupPort.displayNames[UUID.fromString(INSTALLER_UUID)] = INSTALLER_NAME

        val body =
            mockMvc
                .perform(get("/api/v1/slack/installation"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.connected").value(true))
                .andExpect(jsonPath("$.teamId").value(TEAM_ID))
                .andExpect(jsonPath("$.teamName").value(TEAM_NAME))
                // installedAt 은 ISO-8601 문자열(epoch 숫자가 아님).
                .andExpect(jsonPath("$.installedAt").isString)
                .andExpect(jsonPath("$.installedAt", startsWith("2026-07-08T12:34:56")))
                // botUserId — 봇 사용자 id(`U…`) 표시 메타.
                .andExpect(jsonPath("$.botUserId").value(BOT_USER_ID))
                // updatedAt 은 ISO-8601 문자열이며 installedAt 과 구분되는 값(필드 매핑 오배치 검증).
                .andExpect(jsonPath("$.updatedAt").isString)
                .andExpect(jsonPath("$.updatedAt", startsWith("2026-07-08T13:00:00")))
                // installerName — 설치자 표시명(원시 UUID 대체, 이것만이 유일한 설치자 표현).
                .andExpect(jsonPath("$.installerName").value(INSTALLER_NAME))
                .andReturn()
                .response
                .contentAsString

        // §1.1.2 — 봇 토큰·암호문·설치자 UUID·installedBy 키가 상태 응답에 절대 실리지 않는다.
        assertThat(body).doesNotContain("xoxb")
        assertThat(body).doesNotContain(CIPHERTEXT_SENTINEL)
        assertThat(body).doesNotContain(INSTALLER_UUID)
        assertThat(body).doesNotContain("installedBy")
        assertThat(body).doesNotContain("botToken")
    }

    @Test
    @WithMockUser(username = NON_ADMIN_UUID)
    fun `GET installation - 비관리자면 403`() {
        mockMvc
            .perform(get("/api/v1/slack/installation"))
            .andExpect(status().isForbidden)
    }

    @Test
    fun `GET installation - 미인증이면 401`() {
        mockMvc
            .perform(get("/api/v1/slack/installation"))
            .andExpect(status().isUnauthorized)
    }

    // ── GET /api/v1/slack/install-url ────────────────────────────────────────────

    @Test
    @WithMockUser(username = ADMIN_UUID)
    fun `GET install-url - 관리자면 200 authorize URL과 서명 state`() {
        mockMvc
            .perform(get("/api/v1/slack/install-url"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.url", startsWith("https://slack.com/oauth/v2/authorize")))
            .andExpect(jsonPath("$.url", containsString("state=")))
    }

    @Test
    @WithMockUser(username = NON_ADMIN_UUID)
    fun `GET install-url - 비관리자면 403`() {
        mockMvc
            .perform(get("/api/v1/slack/install-url"))
            .andExpect(status().isForbidden)
    }

    // ── DB 헬퍼 ──────────────────────────────────────────────────────────────────

    /**
     * 상태=connected true 를 재현할 설치 1행을 직접 삽입한다.
     *
     * 봇 토큰 암호문([CIPHERTEXT_SENTINEL])·설치자 UUID([INSTALLER_UUID])를 함께 저장해, 상태 조회 응답이
     * 이 비-표시 필드들을 실지 않음을 검증할 수 있게 한다. `installed_at`/`updated_at` 은 서로 다른 고정
     * instant 로 넣어 ISO-8601 직렬화와 필드 매핑을 결정적으로 assert 한다(updatedAt 이 installedAt 값을
     * 잘못 실지 않는지까지 검증).
     */
    private fun insertInstallation() {
        jdbc.update(
            """
            INSERT INTO slack_installs
                (team_id, team_name, bot_user_id, app_id, bot_token_encrypted, scopes,
                 is_enterprise_install, installed_by, installed_at, updated_at)
            VALUES
                (:teamId, :teamName, :botUserId, :appId, :botTokenEncrypted, :scopes,
                 false, :installedBy, :installedAt, :updatedAt)
            """,
            mapOf(
                "teamId" to TEAM_ID,
                "teamName" to TEAM_NAME,
                "botUserId" to BOT_USER_ID,
                "appId" to "A123APP",
                "botTokenEncrypted" to CIPHERTEXT_SENTINEL,
                "scopes" to "chat:write,commands",
                "installedBy" to UUID.fromString(INSTALLER_UUID),
                "installedAt" to Timestamp.from(FIXED_INSTALLED_AT),
                "updatedAt" to Timestamp.from(FIXED_UPDATED_AT),
            ),
        )
    }

    private companion object {
        const val TEAM_ID = "T123WS"
        const val TEAM_NAME = "Acme Workspace"

        /** 봇 사용자 id — 표시 메타로 응답에 실린다(비-비밀). */
        const val BOT_USER_ID = "U123BOT"

        /** 저장된 봇 토큰 암호문을 흉내 내는 sentinel — 상태 응답에 새어 나오면 안 된다. */
        const val CIPHERTEXT_SENTINEL = "CIPHERTEXT-deadbeef-should-never-leak"

        /** 설치자 UUID — 상태 응답에 새어 나오면 안 된다(비-표시 필드, 이름 해석 전용). */
        const val INSTALLER_UUID = "99999999-9999-9999-9999-999999999999"

        /** 설치자 표시명 — 응답에 실리는 유일한 설치자 표현(원시 UUID 대체). */
        const val INSTALLER_NAME = "홍길동"

        /** 결정적 ISO-8601 직렬화 검증용 고정 설치 시각. */
        val FIXED_INSTALLED_AT: Instant = Instant.parse("2026-07-08T12:34:56Z")

        /** 재설치 갱신 시각 — installedAt 과 다른 값으로 updatedAt 필드 매핑을 검증한다. */
        val FIXED_UPDATED_AT: Instant = Instant.parse("2026-07-08T13:00:00Z")
    }
}
