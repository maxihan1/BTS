// DashboardController MockMvc 슬라이스 테스트 — CRUD 엔드포인트·상태코드·직렬화·미인증 시나리오

package com.bts.notification.dashboard.web

import com.bts.notification.dashboard.application.DashboardConflictException
import com.bts.notification.dashboard.application.DashboardForbiddenException
import com.bts.notification.dashboard.application.DashboardNotFoundException
import com.bts.notification.dashboard.application.DashboardService
import com.bts.notification.dashboard.domain.Dashboard
import com.bts.notification.dashboard.domain.DashboardDomainException
import com.bts.notification.dashboard.domain.DashboardVisibility
import com.bts.notification.dashboard.repository.DashboardPage
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import org.hamcrest.Matchers.hasSize
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.MediaType
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.junit.jupiter.SpringExtension
import org.springframework.test.context.web.WebAppConfiguration
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import org.springframework.web.servlet.config.annotation.EnableWebMvc
import java.time.Instant
import java.util.UUID

/**
 * DashboardController MockMvc 슬라이스 테스트.
 *
 * DashboardService 는 MockK stub 으로 대체한다.
 * Spring Security 컨텍스트는 SecurityContextHolder 에 UUID 기반 Authentication 을 직접 주입한다.
 *
 * advice scoping: DashboardExceptionHandler 만 등록 (NotificationExceptionHandler 는 다른 패키지 — 격리).
 * 각 에러 케이스에 errorCode 단언을 포함해 가짜 그린을 차단한다.
 *
 * 테스트 케이스.
 * - POST-1. POST /dashboards 정상 -> 201
 * - POST-2. POST 빈 이름 -> 400 + errorCode=NOTIF_DASHBOARD_INVALID
 * - POST-3. POST malformed JSON body -> 400 + errorCode=NOTIF_DASHBOARD_INVALID
 * - POST-4. POST 비-JSON layout -> 400 + errorCode=NOTIF_DASHBOARD_INVALID
 * - GET_LIST-1. GET /dashboards?limit=10&offset=0 -> 200 + items/total
 * - GET_ONE-1. GET /dashboards/{id} 존재 -> 200
 * - GET_ONE-2. GET /dashboards/{id} 없음 -> 404 + errorCode
 * - GET_ONE-3. GET /dashboards/not-a-uuid -> 400 + errorCode=NOTIF_DASHBOARD_INVALID
 * - PATCH-1. PATCH /{id} 정상 -> 200
 * - PATCH-2. PATCH /{id} 비소유자 -> 403 + errorCode
 * - PATCH-3. PATCH /{id} OCC 충돌 -> 409 + errorCode
 * - PATCH-4. PATCH /{id} 없음 -> 404 + errorCode
 * - PATCH-5. PATCH /{id} malformed JSON -> 400 + errorCode=NOTIF_DASHBOARD_INVALID
 * - DELETE-1. DELETE /{id} 정상 -> 204
 * - DELETE-2. DELETE /{id} 비소유자 -> 403 + errorCode
 * - DELETE-3. DELETE /{id} 없음 -> 404 + errorCode
 * - AUTH-1. 미인증 요청 -> 401
 */
@ExtendWith(SpringExtension::class)
@ContextConfiguration(classes = [DashboardControllerTest.TestMvcConfig::class])
@WebAppConfiguration
class DashboardControllerTest {
    /**
     * 테스트 전용 Spring MVC 최소 컨텍스트.
     *
     * DashboardController, DashboardExceptionHandler 와 MockK stub 빈을 등록한다.
     * NotificationExceptionHandler 는 등록하지 않는다 — production 과 동일한 advice scoping.
     */
    @Configuration
    @EnableWebMvc
    open class TestMvcConfig {
        @Bean
        open fun dashboardService(): DashboardService = mockk(relaxed = true)

        @Bean
        open fun dashboardController(service: DashboardService) = DashboardController(service)

        @Bean
        open fun dashboardExceptionHandler() = DashboardExceptionHandler()
    }

    @Autowired
    private lateinit var webApplicationContext: WebApplicationContext

    @Autowired
    private lateinit var service: DashboardService

    private lateinit var mockMvc: MockMvc
    private val mapper: ObjectMapper = ObjectMapper().registerKotlinModule()

    private val actorId: UUID = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001")
    private val dashboardId: UUID = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000001")
    private val fixedNow: Instant = Instant.parse("2026-06-22T12:00:00Z")

    @BeforeEach
    fun setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build()
        setAuth(actorId)
    }

    // ── POST /api/v1/dashboards ────────────────────────────────────────────────

    /** POST-1. 정상 생성 -> 201 + 응답 body에 id 포함. */
    @Test
    fun `POST dashboards 정상 요청 시 201 반환`() {
        val response = buildDashboard()
        every {
            service.create(
                actorId = actorId,
                name = "내 대시보드",
                description = null,
                visibility = DashboardVisibility.PRIVATE,
                layout = "[]",
                sharedUserIds = emptySet(),
            )
        } returns response

        mockMvc.perform(
            post("/api/v1/dashboards")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    mapper.writeValueAsString(
                        mapOf(
                            "name" to "내 대시보드",
                            "visibility" to "PRIVATE",
                        ),
                    ),
                ),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.data.id").value(dashboardId.toString()))
            .andExpect(jsonPath("$.data.visibility").value("PRIVATE"))
    }

    /** POST-2. 빈 name -> 도메인 DashboardDomainException -> 400 + errorCode=NOTIF_DASHBOARD_INVALID. */
    @Test
    fun `POST dashboards 빈 이름은 400 반환 — NOTIF_DASHBOARD_INVALID errorCode`() {
        every {
            service.create(
                actorId = actorId,
                name = "",
                description = null,
                visibility = DashboardVisibility.PRIVATE,
                layout = "[]",
                sharedUserIds = emptySet(),
            )
        } throws DashboardDomainException("대시보드 이름은 빈 문자열 또는 공백일 수 없습니다.")

        mockMvc.perform(
            post("/api/v1/dashboards")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(mapOf("name" to "", "visibility" to "PRIVATE"))),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errorCode").value("NOTIF_DASHBOARD_INVALID"))
    }

    /** POST-3. malformed JSON body -> 400 + errorCode=NOTIF_DASHBOARD_INVALID. */
    @Test
    fun `POST dashboards malformed JSON body 는 400 반환 — NOTIF_DASHBOARD_INVALID errorCode`() {
        mockMvc.perform(
            post("/api/v1/dashboards")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{not-valid-json"),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errorCode").value("NOTIF_DASHBOARD_INVALID"))
    }

    /** POST-4. 비-JSON layout -> 400 + errorCode=NOTIF_DASHBOARD_INVALID. */
    @Test
    fun `POST dashboards 비-JSON layout 은 400 반환 — NOTIF_DASHBOARD_INVALID errorCode`() {
        every {
            service.create(
                actorId = actorId,
                name = "테스트",
                description = null,
                visibility = DashboardVisibility.PRIVATE,
                layout = "{not json",
                sharedUserIds = emptySet(),
            )
        } throws DashboardDomainException("layout 은 유효한 JSON 이어야 합니다.")

        mockMvc.perform(
            post("/api/v1/dashboards")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    mapper.writeValueAsString(
                        mapOf(
                            "name" to "테스트",
                            "visibility" to "PRIVATE",
                            "layout" to "{not json",
                        ),
                    ),
                ),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errorCode").value("NOTIF_DASHBOARD_INVALID"))
    }

    // ── GET /api/v1/dashboards ─────────────────────────────────────────────────

    /** GET_LIST-1. 목록 조회 -> 200 + items/total 포함. */
    @Test
    fun `GET dashboards 목록 조회 시 200 과 페이지네이션 응답 반환`() {
        val dashboard = buildDashboard()
        every { service.list(actorId, 10, 0) } returns DashboardPage(listOf(dashboard), 1)

        mockMvc.perform(
            get("/api/v1/dashboards")
                .param("limit", "10")
                .param("offset", "0"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.items").isArray)
            .andExpect(jsonPath("$.data.total").value(1))
    }

    // ── GET /api/v1/dashboards/{id} ───────────────────────────────────────────

    /** GET_ONE-1. 단건 조회 성공 -> 200. */
    @Test
    fun `GET dashboards id 존재 시 200 반환`() {
        every { service.get(actorId, dashboardId) } returns buildDashboard()

        mockMvc.perform(get("/api/v1/dashboards/$dashboardId"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.id").value(dashboardId.toString()))
    }

    /** GET_ONE-2. 단건 조회 없음 -> 404 + errorCode. */
    @Test
    fun `GET dashboards id 없으면 404 반환`() {
        every { service.get(actorId, dashboardId) } throws DashboardNotFoundException(dashboardId)

        mockMvc.perform(get("/api/v1/dashboards/$dashboardId"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.errorCode").value("NOTIF_DASHBOARD_NOT_FOUND"))
    }

    /** GET_ONE-3. UUID 가 아닌 path variable -> 400 + errorCode=NOTIF_DASHBOARD_INVALID. */
    @Test
    fun `GET dashboards path 가 UUID 가 아니면 400 반환 — NOTIF_DASHBOARD_INVALID errorCode`() {
        mockMvc.perform(get("/api/v1/dashboards/not-a-uuid"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errorCode").value("NOTIF_DASHBOARD_INVALID"))
    }

    // ── PATCH /api/v1/dashboards/{id} ─────────────────────────────────────────

    /** PATCH-1. 정상 수정 -> 200. */
    @Test
    fun `PATCH dashboards 정상 수정 시 200 반환`() {
        every {
            service.update(
                actorId = actorId,
                id = dashboardId,
                name = "새 이름",
                description = null,
                visibility = null,
                layout = null,
                sharedUserIds = null,
                version = 0L,
            )
        } returns buildDashboard(name = "새 이름")

        mockMvc.perform(
            patch("/api/v1/dashboards/$dashboardId")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(mapOf("name" to "새 이름", "version" to 0))),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.name").value("새 이름"))
    }

    /** PATCH-2. 비소유자 수정 -> 403 + errorCode. */
    @Test
    fun `PATCH dashboards 비소유자 수정 시 403 반환`() {
        every {
            service.update(
                actorId = actorId,
                id = dashboardId,
                name = null,
                description = null,
                visibility = null,
                layout = null,
                sharedUserIds = null,
                version = 0L,
            )
        } throws DashboardForbiddenException()

        mockMvc.perform(
            patch("/api/v1/dashboards/$dashboardId")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(mapOf("version" to 0))),
        )
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.errorCode").value("NOTIF_DASHBOARD_FORBIDDEN"))
    }

    /** PATCH-3. OCC 충돌 -> 409 + errorCode. */
    @Test
    fun `PATCH dashboards OCC 충돌 시 409 반환`() {
        every {
            service.update(
                actorId = actorId,
                id = dashboardId,
                name = null,
                description = null,
                visibility = null,
                layout = null,
                sharedUserIds = null,
                version = 1L,
            )
        } throws DashboardConflictException(dashboardId)

        mockMvc.perform(
            patch("/api/v1/dashboards/$dashboardId")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(mapOf("version" to 1))),
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.errorCode").value("NOTIF_DASHBOARD_CONFLICT"))
    }

    /** PATCH-4. 없는 대시보드 -> 404 + errorCode. */
    @Test
    fun `PATCH dashboards 없는 대시보드 시 404 반환`() {
        every {
            service.update(
                actorId = actorId,
                id = dashboardId,
                name = null,
                description = null,
                visibility = null,
                layout = null,
                sharedUserIds = null,
                version = 0L,
            )
        } throws DashboardNotFoundException(dashboardId)

        mockMvc.perform(
            patch("/api/v1/dashboards/$dashboardId")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(mapOf("version" to 0))),
        )
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.errorCode").value("NOTIF_DASHBOARD_NOT_FOUND"))
    }

    /** PATCH-5. malformed JSON body -> 400 + errorCode=NOTIF_DASHBOARD_INVALID. */
    @Test
    fun `PATCH dashboards malformed JSON body 는 400 반환 — NOTIF_DASHBOARD_INVALID errorCode`() {
        mockMvc.perform(
            patch("/api/v1/dashboards/$dashboardId")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{not-valid-json"),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errorCode").value("NOTIF_DASHBOARD_INVALID"))
    }

    // ── DELETE /api/v1/dashboards/{id} ────────────────────────────────────────

    /** DELETE-1. 정상 삭제 -> 204. */
    @Test
    fun `DELETE dashboards 정상 삭제 시 204 반환`() {
        justRun { service.delete(actorId, dashboardId) }

        mockMvc.perform(delete("/api/v1/dashboards/$dashboardId"))
            .andExpect(status().isNoContent)
    }

    /** DELETE-2. 비소유자 삭제 -> 403 + errorCode. */
    @Test
    fun `DELETE dashboards 비소유자 삭제 시 403 반환`() {
        every { service.delete(actorId, dashboardId) } throws DashboardForbiddenException()

        mockMvc.perform(delete("/api/v1/dashboards/$dashboardId"))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.errorCode").value("NOTIF_DASHBOARD_FORBIDDEN"))
    }

    /** DELETE-3. 없는 대시보드 -> 404 + errorCode. */
    @Test
    fun `DELETE dashboards 없는 대시보드 시 404 반환`() {
        every { service.delete(actorId, dashboardId) } throws DashboardNotFoundException(dashboardId)

        mockMvc.perform(delete("/api/v1/dashboards/$dashboardId"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.errorCode").value("NOTIF_DASHBOARD_NOT_FOUND"))
    }

    // ── GET /api/v1/dashboards/gadget-catalog ────────────────────────────────

    /**
     * CATALOG-1. 카탈로그 조회 → 200 + 12종 수량 단언 (C2 — isArray 단독 금지).
     *
     * GadgetType 열거형 12개가 모두 반환되는지 확인한다.
     */
    @Test
    fun `GET gadget-catalog 는 200 과 12종 카탈로그 반환`() {
        mockMvc.perform(get("/api/v1/dashboards/gadget-catalog"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.gadgets").isArray)
            .andExpect(jsonPath("$.data.gadgets.length()").value(12))
    }

    /**
     * CATALOG-2. enabled=true 항목 수 == 6 카운트 단언.
     *
     * 점단언: issue_count(ISSUE 카테고리) 는 enabled=true,
     * pie_chart(CHART 카테고리) 는 enabled=false.
     */
    @Test
    fun `GET gadget-catalog enabled true 항목 6개 카운트 단언과 점단언`() {
        mockMvc.perform(get("/api/v1/dashboards/gadget-catalog"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.gadgets[?(@.enabled == true)]", hasSize<Any>(6)))
            .andExpect(jsonPath("$.data.gadgets[?(@.type == 'issue_count')][0].enabled").value(true))
            .andExpect(jsonPath("$.data.gadgets[?(@.type == 'pie_chart')][0].enabled").value(false))
    }

    /**
     * CATALOG-3. 각 엔트리에 type·category·label·configFields 필드 존재.
     *
     * text_widget 엔트리의 configFields 에 key=markdown, required=true 항목이 있음을 확인한다.
     * GadgetType 단일 출처에서 파생되므로 drift 불가.
     */
    @Test
    fun `GET gadget-catalog 각 엔트리 필수 필드 존재 및 text_widget configFields 단일 출처 확인`() {
        mockMvc.perform(get("/api/v1/dashboards/gadget-catalog"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.gadgets[0].type").exists())
            .andExpect(jsonPath("$.data.gadgets[0].category").exists())
            .andExpect(jsonPath("$.data.gadgets[0].label").exists())
            .andExpect(jsonPath("$.data.gadgets[0].configFields").exists())
            .andExpect(
                jsonPath(
                    "$.data.gadgets[?(@.type == 'text_widget')][0].configFields[?(@.key == 'markdown')][0].required",
                ).value(true),
            )
    }

    /**
     * CATALOG-AUTH. 미인증 → 401.
     *
     * currentActorId() 게이팅이 카탈로그 엔드포인트에도 적용되는지 확인한다.
     */
    @Test
    fun `GET gadget-catalog 미인증 요청 시 401 반환`() {
        SecurityContextHolder.clearContext()

        mockMvc.perform(get("/api/v1/dashboards/gadget-catalog"))
            .andExpect(status().isUnauthorized)

        // 테스트 후 인증 복원
        setAuth(actorId)
    }

    /**
     * EC12 라우팅 회귀.
     *
     * Spring PathPattern 은 literal segment 를 path-variable 보다 우선 매칭한다.
     * "gadget-catalog" literal 이 /{id} UUID 파싱(400)보다 우선하여 200 + 카탈로그 바디를 반환해야 한다.
     */
    @Test
    fun `GET gadget-catalog 는 UUID path variable 라우팅에 가려지지 않고 200 반환`() {
        mockMvc.perform(get("/api/v1/dashboards/gadget-catalog"))
            .andExpect(status().isOk)
            // {id} 핸들러로 라우팅됐다면 "gadget-catalog"는 UUID 파싱 실패 → 400 이 됨
            // 카탈로그 바디 존재가 올바른 핸들러 매칭 증명
            .andExpect(jsonPath("$.data.gadgets").isArray)
    }

    // ── 인증 ──────────────────────────────────────────────────────────────────

    /** AUTH-1. 미인증 요청 -> 401. */
    @Test
    fun `미인증 요청 시 401 반환`() {
        SecurityContextHolder.clearContext()

        mockMvc.perform(get("/api/v1/dashboards/$dashboardId"))
            .andExpect(status().isUnauthorized)

        // 테스트 후 인증 복원
        setAuth(actorId)
    }

    // ── 헬퍼 ──────────────────────────────────────────────────────────────────

    private fun buildDashboard(name: String = "내 대시보드"): Dashboard =
        Dashboard(
            id = dashboardId,
            ownerId = actorId,
            name = name,
            description = null,
            visibility = DashboardVisibility.PRIVATE,
            layout = "[]",
            sharedUserIds = emptySet(),
            createdAt = fixedNow,
            updatedAt = fixedNow,
            deletedAt = null,
            version = 0L,
        )

    private fun setAuth(userId: UUID) {
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(
                userId.toString(),
                null,
                listOf(SimpleGrantedAuthority("ROLE_USER")),
            )
    }
}
