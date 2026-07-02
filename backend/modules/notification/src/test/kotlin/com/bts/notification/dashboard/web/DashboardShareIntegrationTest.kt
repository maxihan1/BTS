// 대시보드 공유 토큰 HTTP end-to-end 통합 테스트 — 발급→익명조회 라운드트립·취소/만료/부모삭제 404·교차차단·유출가드 (FR-DB-03)

package com.bts.notification.dashboard.web

import com.bts.notification.NotificationTestBootApplication
import com.bts.notification.NotificationTestcontainersConfig
import com.bts.notification.TestPermissionConfig
import com.bts.notification.jooq.tables.references.DASHBOARD_SHARE_TOKENS
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.not
import org.hamcrest.Matchers.notNullValue
import org.jooq.DSLContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import java.time.Instant
import java.util.UUID

/**
 * 대시보드 공유 토큰 HTTP end-to-end 통합 테스트 (FR-DB-03 PR1 Task 8).
 *
 * HTTP 요청 → DashboardShareController/PublicDashboardController → DashboardService →
 * DashboardShareTokenRepository → Testcontainers PostgreSQL 16 까지 관통 검증한다.
 *
 * 검증 시나리오.
 * - S1. 발급→익명 조회 라운드트립 — 정적 가젯(text_widget)은 config 유지, 데이터 가젯(assigned_to_me)은
 *   config 제거+requiresAuth=true, 내부 필드(ownerId/sharedUserIds/version/id) 부재.
 * - S2. 취소된 토큰 → 익명 GET 404.
 * - S3. 만료된 토큰 → 익명 GET 404.
 * - S4. 부모 대시보드 소프트 삭제 → 익명 GET 404.
 * - S5. 교차 차단 — 다른 대시보드 경로로 취소 시도 → 404, 원 토큰은 여전히 유효.
 * - S6. 목록 유출 가드 — 목록 응답 문자열에 발급 원문·해시가 등장하지 않는다.
 * - S7. 미인증 발급 요청 → 401.
 *
 * ## 인증 시뮬레이트 범위
 * 이 테스트 슬라이스는 identity-access 의 실제 SecurityFilterChain 을 부팅하지 않는다
 * (NotificationTestBootApplication 은 com.bts.notification 만 스캔). 인증은 SecurityContextHolder
 * 직접 주입/삭제로 시뮬레이트하며, currentActorId() 헬퍼가 SecurityContext 부재 시 자체적으로
 * ResponseStatusException(401) 을 던지므로 실제 필터 체인 없이도 401 시나리오(S7)를 검증할 수 있다.
 * 반대로 permitAll 라우팅 자체(익명 요청이 인증 필터를 통과하는지)의 실동작 검증은 이 슬라이스의
 * 범위 밖이며 code-review 에서 SecurityConfig wiring 확인으로 위임한다.
 */
@SpringBootTest(
    classes = [
        NotificationTestBootApplication::class,
        NotificationTestcontainersConfig::class,
        TestPermissionConfig::class,
    ],
    webEnvironment = SpringBootTest.WebEnvironment.MOCK,
)
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DashboardShareIntegrationTest {
    @Autowired
    lateinit var webApplicationContext: WebApplicationContext

    @Autowired
    lateinit var dsl: DSLContext

    private lateinit var mockMvc: MockMvc
    private val mapper: ObjectMapper =
        ObjectMapper().registerKotlinModule().registerModule(JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)

    /** TestPermissionConfig.ADMIN_ACTOR_ID 와 동일 값 유지 (해당 값은 이 테스트에서 admin 여부와 무관) */
    private val actorId: UUID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")

    private val staticPlusDataLayout =
        """[
            {"i":"g1","x":0,"y":0,"w":4,"h":3,"gadgetType":"text_widget","config":{"markdown":"## Hello World"}},
            {"i":"g2","x":4,"y":0,"w":4,"h":3,"gadgetType":"assigned_to_me","config":{}}
        ]"""

    @BeforeEach
    fun setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build()
        setAuth()
        // 테스트 간 독립성 보장 — 이전 테스트 잔여 데이터 제거 (공유 Testcontainers DB 사용)
        dsl.execute("DELETE FROM dashboard_share_tokens")
        dsl.execute("DELETE FROM dashboard_shares")
        dsl.execute("DELETE FROM dashboards")
    }

    @AfterEach
    fun tearDown() {
        SecurityContextHolder.clearContext()
    }

    // ── S1. 발급→익명 조회 라운드트립 + 내부 필드 유출 가드 ──────────────────────

    @Test
    fun `S1 발급된 공유 토큰으로 익명 조회 시 정적 가젯은 유지되고 데이터 가젯은 정화되며 내부 식별자는 노출되지 않는다`() {
        val dashboardId = createDashboard()
        val issued = issueToken(dashboardId)

        val result =
            mockMvc.perform(get("/api/v1/public/dashboards/${issued.plaintext}"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.data.ownerId").doesNotExist())
                .andExpect(jsonPath("$.data.sharedUserIds").doesNotExist())
                .andExpect(jsonPath("$.data.version").doesNotExist())
                .andExpect(jsonPath("$.data.id").doesNotExist())
                .andReturn()

        val data = mapper.readTree(result.response.contentAsString).get("data")
        val layout = mapper.readTree(data.get("layout").asText())

        val staticItem = layout.first { it.get("i").asText() == "g1" }
        assertThat(staticItem.get("gadgetType").asText(), equalTo("text_widget"))
        assertThat(staticItem.get("config").get("markdown").asText(), equalTo("## Hello World"))
        assertThat(staticItem.has("requiresAuth"), equalTo(false))

        val dataItem = layout.first { it.get("i").asText() == "g2" }
        assertThat(dataItem.get("gadgetType").asText(), equalTo("assigned_to_me"))
        assertThat(dataItem.has("config"), equalTo(false))
        assertThat(dataItem.get("requiresAuth").asBoolean(), equalTo(true))
    }

    // ── S2. 취소된 토큰 → 익명 GET 404 ────────────────────────────────────────

    @Test
    fun `S2 취소된 공유 토큰으로 GET 하면 404 를 반환한다`() {
        val dashboardId = createDashboard()
        val issued = issueToken(dashboardId)

        mockMvc.perform(delete("/api/v1/dashboards/$dashboardId/shares/${issued.id}"))
            .andExpect(status().isNoContent)

        mockMvc.perform(get("/api/v1/public/dashboards/${issued.plaintext}"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.errorCode").value("NOTIF_DASHBOARD_NOT_FOUND"))
    }

    // ── S3. 만료된 토큰 → 익명 GET 404 ────────────────────────────────────────

    @Test
    fun `S3 만료된 공유 토큰으로 GET 하면 404 를 반환한다`() {
        val dashboardId = createDashboard()
        val issued = issueToken(dashboardId, expiresAt = Instant.parse("2020-01-01T00:00:00Z"))

        mockMvc.perform(get("/api/v1/public/dashboards/${issued.plaintext}"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.errorCode").value("NOTIF_DASHBOARD_NOT_FOUND"))
    }

    // ── S4. 부모 대시보드 소프트 삭제 → 익명 GET 404 ──────────────────────────

    @Test
    fun `S4 부모 대시보드가 소프트 삭제되면 공유 토큰 GET 은 404 를 반환한다`() {
        val dashboardId = createDashboard()
        val issued = issueToken(dashboardId)

        mockMvc.perform(delete("/api/v1/dashboards/$dashboardId"))
            .andExpect(status().isNoContent)

        mockMvc.perform(get("/api/v1/public/dashboards/${issued.plaintext}"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.errorCode").value("NOTIF_DASHBOARD_NOT_FOUND"))
    }

    // ── S5. 교차 차단 ─────────────────────────────────────────────────────────

    @Test
    fun `S5 다른 대시보드 경로로 공유 토큰 취소를 시도하면 404 이고 원 토큰은 여전히 유효하다`() {
        val dashboardA = createDashboard("Dashboard A")
        val dashboardB = createDashboard("Dashboard B")
        val issuedA = issueToken(dashboardA)

        mockMvc.perform(delete("/api/v1/dashboards/$dashboardB/shares/${issuedA.id}"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.errorCode").value("NOTIF_DASHBOARD_SHARE_NOT_FOUND"))

        mockMvc.perform(get("/api/v1/public/dashboards/${issuedA.plaintext}"))
            .andExpect(status().isOk)
    }

    // ── S6. 목록 유출 가드 ────────────────────────────────────────────────────

    @Test
    fun `S6 공유 토큰 목록 응답에는 발급 원문과 해시가 노출되지 않는다`() {
        val dashboardId = createDashboard()
        val issued = issueToken(dashboardId)

        val storedHash =
            dsl.select(DASHBOARD_SHARE_TOKENS.TOKEN_HASH)
                .from(DASHBOARD_SHARE_TOKENS)
                .where(DASHBOARD_SHARE_TOKENS.ID.eq(issued.id))
                .fetchOne(DASHBOARD_SHARE_TOKENS.TOKEN_HASH)
        assertThat(storedHash, notNullValue())

        val result =
            mockMvc.perform(get("/api/v1/dashboards/$dashboardId/shares"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.data.items[0].id").value(issued.id.toString()))
                .andReturn()

        val body = result.response.contentAsString
        assertThat(body, not(containsString(issued.plaintext)))
        assertThat(body, not(containsString(storedHash!!)))
    }

    // ── S7. 미인증 발급 요청 → 401 ────────────────────────────────────────────

    @Test
    fun `S7 미인증 상태로 공유 토큰 발급을 시도하면 401 을 반환한다`() {
        val dashboardId = createDashboard()
        SecurityContextHolder.clearContext()

        mockMvc.perform(post("/api/v1/dashboards/$dashboardId/shares"))
            .andExpect(status().isUnauthorized)

        setAuth()
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun setAuth() {
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(
                actorId.toString(),
                null,
                listOf(SimpleGrantedAuthority("ROLE_USER")),
            )
    }

    private fun createDashboard(name: String = "Share E2E Dashboard"): UUID {
        val body = mapOf("name" to name, "visibility" to "PRIVATE", "layout" to staticPlusDataLayout)
        val result =
            mockMvc.perform(
                post("/api/v1/dashboards")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsString(body)),
            )
                .andExpect(status().isCreated)
                .andReturn()
        return UUID.fromString(mapper.readTree(result.response.contentAsString).get("data").get("id").asText())
    }

    private data class IssuedTokenInfo(val id: UUID, val plaintext: String)

    private fun issueToken(
        dashboardId: UUID,
        expiresAt: Instant? = null,
    ): IssuedTokenInfo {
        val body = if (expiresAt != null) mapOf("expiresAt" to expiresAt.toString()) else emptyMap()
        val result =
            mockMvc.perform(
                post("/api/v1/dashboards/$dashboardId/shares")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsString(body)),
            )
                .andExpect(status().isCreated)
                .andReturn()
        val data = mapper.readTree(result.response.contentAsString).get("data")
        return IssuedTokenInfo(id = UUID.fromString(data.get("id").asText()), plaintext = data.get("token").asText())
    }
}
