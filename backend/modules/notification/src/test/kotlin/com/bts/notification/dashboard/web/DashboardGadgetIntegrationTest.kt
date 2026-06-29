// 대시보드 가젯 레이아웃 HTTP end-to-end 통합 테스트 — JSONB 왕복·가젯 검증·카탈로그·EC12 라우팅 검증

package com.bts.notification.dashboard.web

import com.bts.notification.NotificationTestBootApplication
import com.bts.notification.NotificationTestcontainersConfig
import com.bts.notification.TestPermissionConfig
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.hasSize
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import java.util.UUID

/**
 * 대시보드 가젯 레이아웃 HTTP end-to-end 통합 테스트.
 *
 * HTTP 요청 → DashboardController → DashboardService → DashboardRepository →
 * Testcontainers PostgreSQL 16 까지 관통 검증.
 *
 * Task 1~3 의 production 코드(GadgetType, validateLayout 확장, 카탈로그 API) 위에서
 * 실동작을 못박는 검증 테스트다. 기존 production 코드가 통과해야 GREEN 이 된다.
 *
 * 검증 시나리오.
 * - G1. POST gadget layout → 201 → GET 재조회 → JSONB gadgetType·config 보존 (round-trip).
 * - G1b. PATCH gadget 추가 → 200 + version+1 → GET 재조회 → 변경 영속.
 * - G2a. 알 수 없는 gadgetType → 400 NOTIF_DASHBOARD_INVALID.
 * - G2b. enabled=false 타입(pie_chart) → 400 NOTIF_DASHBOARD_INVALID.
 * - G2c. text_widget config markdown 누락 → 400 NOTIF_DASHBOARD_INVALID.
 * - G3. GET /gadget-catalog → 200 + gadgets 12종 + enabled=true 6개.
 * - G4. EC12 라우팅 — gadget-catalog literal segment 우선 매칭, UUID 파싱 400 비유출.
 * - G5. legacy 타일(gadgetType 없음) → 201 (회귀가드).
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
class DashboardGadgetIntegrationTest {
    @Autowired
    lateinit var webApplicationContext: WebApplicationContext

    @Autowired
    lateinit var dsl: DSLContext

    private lateinit var mockMvc: MockMvc
    private val mapper: ObjectMapper = ObjectMapper().registerKotlinModule()

    /** TestPermissionConfig.ADMIN_ACTOR_ID 와 동일 값 유지 */
    private val actorId: UUID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")

    @BeforeEach
    fun setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build()
        setAuth()
        // 테스트 간 독립성 보장 — 이전 테스트 잔여 대시보드 제거 (공유 Testcontainers DB 사용)
        dsl.execute("DELETE FROM dashboard_shares")
        dsl.execute("DELETE FROM dashboards")
    }

    @AfterEach
    fun tearDown() {
        SecurityContextHolder.clearContext()
    }

    // ── G1. round-trip — gadget layout JSONB 왕복 보존 ──────────────────────────

    @Test
    fun `G1 gadget layout POST 후 GET 재조회 시 gadgetType과 config가 JSONB에서 그대로 반환된다`() {
        val layout =
            """[
                {"i":"g1","x":0,"y":0,"w":4,"h":3,"gadgetType":"text_widget","config":{"markdown":"## Hello World"}},
                {"i":"g2","x":4,"y":0,"w":4,"h":3,"gadgetType":"assigned_to_me","config":{}}
            ]"""
        val body =
            mapOf(
                "name" to "Gadget Round-Trip Dashboard",
                "visibility" to "PRIVATE",
                "layout" to layout,
            )

        val createResult =
            mockMvc.perform(
                post("/api/v1/dashboards")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsString(body)),
            )
                .andExpect(status().isCreated)
                .andExpect(jsonPath("$.data.id").exists())
                .andExpect(jsonPath("$.data.layout").value(containsString("text_widget")))
                .andExpect(jsonPath("$.data.layout").value(containsString("assigned_to_me")))
                .andExpect(jsonPath("$.data.version").value(0))
                .andReturn()

        val createdId =
            mapper.readTree(createResult.response.contentAsString)
                .get("data").get("id").asText()

        // GET 단건 재조회 — DB JSONB 왕복 후에도 gadgetType·config 보존
        mockMvc.perform(get("/api/v1/dashboards/$createdId"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.id").value(createdId))
            .andExpect(jsonPath("$.data.layout").value(containsString("text_widget")))
            .andExpect(jsonPath("$.data.layout").value(containsString("Hello World")))
            .andExpect(jsonPath("$.data.layout").value(containsString("assigned_to_me")))
            .andExpect(jsonPath("$.data.version").value(0))
    }

    // ── G1b. PATCH gadget 추가 → version+1 → GET 재조회 영속 ────────────────────

    @Test
    fun `G1b PATCH로 gadget 추가 시 version이 1 증가하고 변경이 DB에 영속된다`() {
        val initLayout =
            """[{"i":"g1","x":0,"y":0,"w":4,"h":3,"gadgetType":"text_widget","config":{"markdown":"초기 내용"}}]"""
        val createBody =
            mapOf("name" to "PATCH Test Dashboard", "visibility" to "PRIVATE", "layout" to initLayout)

        val createResult =
            mockMvc.perform(
                post("/api/v1/dashboards")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsString(createBody)),
            )
                .andExpect(status().isCreated)
                .andReturn()

        val createTree = mapper.readTree(createResult.response.contentAsString)
        val dashboardId = createTree.get("data").get("id").asText()
        val version = createTree.get("data").get("version").asLong()

        val newLayout =
            """[
                {"i":"g1","x":0,"y":0,"w":4,"h":3,"gadgetType":"text_widget","config":{"markdown":"초기 내용"}},
                {"i":"g2","x":4,"y":0,"w":4,"h":3,"gadgetType":"assigned_to_me","config":{}}
            ]"""
        val patchBody = mapOf("layout" to newLayout, "version" to version)

        mockMvc.perform(
            patch("/api/v1/dashboards/$dashboardId")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(patchBody)),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.version").value(version + 1))
            .andExpect(jsonPath("$.data.layout").value(containsString("assigned_to_me")))

        // GET 재조회 — PATCH 결과가 DB에 실제로 영속됐는지 확인
        mockMvc.perform(get("/api/v1/dashboards/$dashboardId"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.version").value(version + 1))
            .andExpect(jsonPath("$.data.layout").value(containsString("assigned_to_me")))
    }

    // ── G2a. 알 수 없는 gadgetType → 400 NOTIF_DASHBOARD_INVALID ────────────────

    @Test
    fun `G2a 알 수 없는 gadgetType으로 POST하면 400 NOTIF_DASHBOARD_INVALID를 반환한다`() {
        val layout = """[{"i":"g1","x":0,"y":0,"w":4,"h":3,"gadgetType":"unknown_gadget"}]"""
        val body = mapOf("name" to "Bad Gadget Dashboard", "visibility" to "PRIVATE", "layout" to layout)

        mockMvc.perform(
            post("/api/v1/dashboards")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errorCode").value("NOTIF_DASHBOARD_INVALID"))
    }

    // ── G2b. enabled=false 타입(pie_chart) → 400 NOTIF_DASHBOARD_INVALID ────────

    @Test
    fun `G2b enabled=false 타입인 pie_chart로 POST하면 400 NOTIF_DASHBOARD_INVALID를 반환한다`() {
        val layout =
            """[{"i":"g1","x":0,"y":0,"w":4,"h":3,"gadgetType":"pie_chart","config":{"field":"status"}}]"""
        val body =
            mapOf("name" to "Disabled Gadget Dashboard", "visibility" to "PRIVATE", "layout" to layout)

        mockMvc.perform(
            post("/api/v1/dashboards")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errorCode").value("NOTIF_DASHBOARD_INVALID"))
    }

    // ── G2c. text_widget config markdown 누락 → 400 NOTIF_DASHBOARD_INVALID ──────

    @Test
    fun `G2c text_widget의 필수 config 필드 markdown이 누락되면 400 NOTIF_DASHBOARD_INVALID를 반환한다`() {
        // text_widget 은 markdown 필드가 required=true — 빈 config 로 검증 실패를 유도
        val layout = """[{"i":"g1","x":0,"y":0,"w":4,"h":3,"gadgetType":"text_widget","config":{}}]"""
        val body = mapOf("name" to "Missing Markdown Dashboard", "visibility" to "PRIVATE", "layout" to layout)

        mockMvc.perform(
            post("/api/v1/dashboards")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errorCode").value("NOTIF_DASHBOARD_INVALID"))
    }

    // ── G3. 카탈로그 12종 + enabled=true 6개 ────────────────────────────────────

    @Test
    fun `G3 gadget-catalog 조회 시 12종 전체가 반환되고 enabled=true는 6개다`() {
        mockMvc.perform(
            get("/api/v1/dashboards/gadget-catalog")
                .accept(MediaType.APPLICATION_JSON),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.gadgets.length()").value(12))
            .andExpect(jsonPath("$.data.gadgets[?(@.enabled==true)]", hasSize<Any>(6)))
    }

    // ── G4. EC12 라우팅 — literal segment 우선, UUID 파싱 400 비유출 ──────────────

    @Test
    fun `G4 EC12 GET gadget-catalog는 UUID 파싱 400이 아닌 200 카탈로그 응답을 반환한다`() {
        // Spring PathPattern 은 literal segment 를 path-variable 보다 우선 매칭한다.
        // "gadget-catalog" 가 UUID 로 파싱 시도돼 400 이 반환되면 이 테스트가 실패한다.
        mockMvc.perform(
            get("/api/v1/dashboards/gadget-catalog")
                .accept(MediaType.APPLICATION_JSON),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.gadgets").isArray)
            .andExpect(jsonPath("$.data.gadgets.length()").value(12))
    }

    // ── G5. legacy 타일 호환(gadgetType 없음) → 201 ──────────────────────────────

    @Test
    fun `G5 gadgetType 없는 legacy 타일 layout으로 POST하면 201이 반환된다`() {
        // gadgetType 필드 없는 타일 = legacy title-타일. validateLayout 은 gadgetType 미존재 시 config 검증을 건너뛴다.
        val layout = """[{"i":"tile1","x":0,"y":0,"w":4,"h":3,"title":"My Widget"}]"""
        val body = mapOf("name" to "Legacy Dashboard", "visibility" to "PRIVATE", "layout" to layout)

        mockMvc.perform(
            post("/api/v1/dashboards")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.data.id").exists())
            .andExpect(jsonPath("$.data.layout").value(containsString("tile1")))
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
}
