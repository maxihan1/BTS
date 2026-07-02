// DashboardShareController MockMvc 슬라이스 테스트 — 공유토큰 관리 API·유출 회귀가드·라우팅 회귀

package com.bts.notification.dashboard.web

import com.bts.notification.dashboard.application.DashboardForbiddenException
import com.bts.notification.dashboard.application.DashboardNotFoundException
import com.bts.notification.dashboard.application.DashboardService
import com.bts.notification.dashboard.application.IssuedShareToken
import com.bts.notification.dashboard.application.ShareTokenLimitExceededException
import com.bts.notification.dashboard.application.ShareTokenNotFoundException
import com.bts.notification.dashboard.domain.DashboardShareToken
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.MediaType
import org.springframework.http.converter.HttpMessageConverter
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.junit.jupiter.SpringExtension
import org.springframework.test.context.web.WebAppConfiguration
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import org.springframework.web.servlet.config.annotation.EnableWebMvc
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import java.time.Instant
import java.util.UUID

/**
 * DashboardShareController MockMvc 슬라이스 테스트.
 *
 * DashboardService 는 MockK stub 으로 대체한다. DashboardController 도 같은 컨텍스트에 등록해
 * literal 경로("gadget-catalog")가 신규 `/shares` 라우팅에 가려지지 않는지 함께 검증한다.
 *
 * 테스트 케이스.
 * - POST-1. POST /shares 정상(expiresAt 포함) -> 201 + {id,token,createdAt,expiresAt}
 * - POST-2. POST /shares body 없음(expiresAt 생략) -> 201, expiresAt 응답에서 생략
 * - GET-1. GET /shares -> 200 + items, token/tokenHash 필드 JSON 레벨 부재(유출 회귀가드 EC-9)
 * - DELETE-1. DELETE /shares/{shareId} -> 204
 * - ERR-1. 비소유자 -> 403
 * - ERR-2. 없는 대시보드 -> 404
 * - ERR-3. 상한 초과 -> 400
 * - AUTH-1. 미인증 -> 401
 * - ROUTE-1. GET /dashboards/gadget-catalog 기존 동작 불변
 */
@ExtendWith(SpringExtension::class)
@ContextConfiguration(classes = [DashboardShareControllerTest.TestMvcConfig::class])
@WebAppConfiguration
class DashboardShareControllerTest {
    /**
     * 테스트 전용 Spring MVC 최소 컨텍스트.
     *
     * DashboardShareController·DashboardController·DashboardExceptionHandler 를 등록한다.
     * DashboardController 를 함께 등록해 literal 경로 라우팅 회귀(ROUTE-1)를 같은 컨텍스트에서 확인한다.
     *
     * `@EnableWebMvc` 슬라이스는 Spring Boot Jackson 자동설정을 상속하지 않아 Instant 가
     * epoch 숫자로 직렬화된다. `extendMessageConverters`(교체 아님)로 기존
     * MappingJackson2HttpMessageConverter 에 JavaTimeModule + ISO 직렬화만 보강한다
     * (memory: enablewebmvc-slice-localdate-array-serialization 교훈 — 컨버터 교체 시
     * ProblemDetail errorCode 확장 프로퍼티 직렬화가 깨짐).
     */
    @Configuration
    @EnableWebMvc
    open class TestMvcConfig : WebMvcConfigurer {
        @Bean
        open fun dashboardService(): DashboardService = mockk(relaxed = true)

        @Bean
        open fun dashboardShareController(service: DashboardService) = DashboardShareController(service)

        @Bean
        open fun dashboardController(service: DashboardService) = DashboardController(service)

        @Bean
        open fun dashboardExceptionHandler() = DashboardExceptionHandler()

        override fun extendMessageConverters(converters: MutableList<HttpMessageConverter<*>>) {
            converters
                .filterIsInstance<MappingJackson2HttpMessageConverter>()
                .forEach { converter ->
                    converter.objectMapper
                        .registerModule(JavaTimeModule())
                        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                }
        }
    }

    @Autowired
    private lateinit var webApplicationContext: WebApplicationContext

    @Autowired
    private lateinit var service: DashboardService

    private lateinit var mockMvc: MockMvc
    private val mapper: ObjectMapper =
        ObjectMapper().registerKotlinModule().registerModule(JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)

    private val actorId: UUID = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001")
    private val dashboardId: UUID = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000001")
    private val shareId: UUID = UUID.fromString("cccccccc-0000-0000-0000-000000000001")
    private val fixedNow: Instant = Instant.parse("2026-07-02T12:00:00Z")

    @BeforeEach
    fun setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build()
        setAuth(actorId)
    }

    // ── POST /api/v1/dashboards/{id}/shares ──────────────────────────────────

    /** POST-1. expiresAt 포함 정상 발급 -> 201 + 원문 토큰 포함 응답. */
    @Test
    fun `POST shares 정상 요청 시 201 과 원문 토큰 반환`() {
        val expiresAt = Instant.parse("2026-08-01T00:00:00Z")
        every {
            service.issueShareToken(actorId = actorId, dashboardId = dashboardId, expiresAt = expiresAt)
        } returns buildIssuedToken(expiresAt = expiresAt)

        mockMvc.perform(
            post("/api/v1/dashboards/$dashboardId/shares")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(mapOf("expiresAt" to expiresAt.toString()))),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.data.id").value(shareId.toString()))
            .andExpect(jsonPath("$.data.token").value("plaintext-token-value"))
            .andExpect(jsonPath("$.data.expiresAt").value(expiresAt.toString()))
    }

    /** POST-2. body 없음(expiresAt 생략) -> 201, expiresAt 필드 응답에서 생략. */
    @Test
    fun `POST shares body 없이 요청해도 201 반환 — expiresAt 생략`() {
        every {
            service.issueShareToken(actorId = actorId, dashboardId = dashboardId, expiresAt = null)
        } returns buildIssuedToken(expiresAt = null)

        mockMvc.perform(post("/api/v1/dashboards/$dashboardId/shares"))
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.data.id").value(shareId.toString()))
            .andExpect(jsonPath("$.data.token").value("plaintext-token-value"))
            .andExpect(jsonPath("$.data.expiresAt").doesNotExist())
    }

    /** ERR-1. 비소유자 발급 시도 -> 403 + errorCode. */
    @Test
    fun `POST shares 비소유자 요청 시 403 반환`() {
        every {
            service.issueShareToken(actorId = actorId, dashboardId = dashboardId, expiresAt = null)
        } throws DashboardForbiddenException()

        mockMvc.perform(post("/api/v1/dashboards/$dashboardId/shares"))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.errorCode").value("NOTIF_DASHBOARD_FORBIDDEN"))
    }

    /** ERR-2. 없는 대시보드 -> 404 + errorCode. */
    @Test
    fun `POST shares 없는 대시보드 요청 시 404 반환`() {
        every {
            service.issueShareToken(actorId = actorId, dashboardId = dashboardId, expiresAt = null)
        } throws DashboardNotFoundException(dashboardId)

        mockMvc.perform(post("/api/v1/dashboards/$dashboardId/shares"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.errorCode").value("NOTIF_DASHBOARD_NOT_FOUND"))
    }

    /** ERR-3. 활성 토큰 상한 초과 -> 400 + errorCode. */
    @Test
    fun `POST shares 상한 초과 시 400 반환`() {
        every {
            service.issueShareToken(actorId = actorId, dashboardId = dashboardId, expiresAt = null)
        } throws ShareTokenLimitExceededException()

        mockMvc.perform(post("/api/v1/dashboards/$dashboardId/shares"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errorCode").value("NOTIF_DASHBOARD_SHARE_LIMIT_EXCEEDED"))
    }

    // ── GET /api/v1/dashboards/{id}/shares ───────────────────────────────────

    /** GET-1. 목록 조회 -> 200 + items, token/tokenHash 필드 JSON 레벨 부재(EC-9). */
    @Test
    fun `GET shares 목록 조회 시 200 과 token 필드 부재 응답 반환`() {
        every { service.listShareTokens(actorId, dashboardId) } returns listOf(buildToken())

        mockMvc.perform(get("/api/v1/dashboards/$dashboardId/shares"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.items").isArray)
            .andExpect(jsonPath("$.data.items[0].id").value(shareId.toString()))
            .andExpect(jsonPath("$.data.items[0].token").doesNotExist())
            .andExpect(jsonPath("$.data.items[0].tokenHash").doesNotExist())
    }

    /** ERR-4. 목록 조회 비소유자 -> 403. */
    @Test
    fun `GET shares 비소유자 요청 시 403 반환`() {
        every { service.listShareTokens(actorId, dashboardId) } throws DashboardForbiddenException()

        mockMvc.perform(get("/api/v1/dashboards/$dashboardId/shares"))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.errorCode").value("NOTIF_DASHBOARD_FORBIDDEN"))
    }

    // ── DELETE /api/v1/dashboards/{id}/shares/{shareId} ──────────────────────

    /** DELETE-1. 정상 취소 -> 204. */
    @Test
    fun `DELETE shares 정상 취소 시 204 반환`() {
        justRun { service.revokeShareToken(actorId, dashboardId, shareId) }

        mockMvc.perform(delete("/api/v1/dashboards/$dashboardId/shares/$shareId"))
            .andExpect(status().isNoContent)
    }

    /** ERR-5. 없는 공유 토큰 취소 -> 404 + errorCode. */
    @Test
    fun `DELETE shares 없는 공유 토큰 취소 시 404 반환`() {
        every {
            service.revokeShareToken(actorId, dashboardId, shareId)
        } throws ShareTokenNotFoundException()

        mockMvc.perform(delete("/api/v1/dashboards/$dashboardId/shares/$shareId"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.errorCode").value("NOTIF_DASHBOARD_SHARE_NOT_FOUND"))
    }

    /** ERR-6. 비소유자 취소 -> 403. */
    @Test
    fun `DELETE shares 비소유자 취소 시 403 반환`() {
        every {
            service.revokeShareToken(actorId, dashboardId, shareId)
        } throws DashboardForbiddenException()

        mockMvc.perform(delete("/api/v1/dashboards/$dashboardId/shares/$shareId"))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.errorCode").value("NOTIF_DASHBOARD_FORBIDDEN"))
    }

    // ── 인증 ──────────────────────────────────────────────────────────────────

    /** AUTH-1. 미인증 요청 -> 401 (actor 추출이 리소스 조회보다 앞섬). */
    @Test
    fun `미인증 요청 시 401 반환`() {
        SecurityContextHolder.clearContext()

        mockMvc.perform(get("/api/v1/dashboards/$dashboardId/shares"))
            .andExpect(status().isUnauthorized)

        // 테스트 후 인증 복원
        setAuth(actorId)
    }

    // ── 라우팅 회귀 ───────────────────────────────────────────────────────────

    /** ROUTE-1. 기존 literal 경로(gadget-catalog)가 신규 /shares 라우팅에 가려지지 않는다. */
    @Test
    fun `GET dashboards gadget-catalog 는 shares 라우팅 도입 후에도 200 반환`() {
        mockMvc.perform(get("/api/v1/dashboards/gadget-catalog"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.gadgets").isArray)
    }

    // ── 헬퍼 ──────────────────────────────────────────────────────────────────

    private fun buildToken(expiresAt: Instant? = null): DashboardShareToken =
        DashboardShareToken(
            id = shareId,
            dashboardId = dashboardId,
            tokenHash = "a".repeat(64),
            createdBy = actorId,
            createdAt = fixedNow,
            expiresAt = expiresAt,
        )

    private fun buildIssuedToken(expiresAt: Instant? = null): IssuedShareToken =
        IssuedShareToken(
            plaintext = "plaintext-token-value",
            token = buildToken(expiresAt = expiresAt),
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
