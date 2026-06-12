// NotificationPolicyController MockMvc 슬라이스 테스트 — 정책 REST API (FR-NT-01 Task 6)

package com.bts.notification.web

import com.bts.notification.application.NotificationPolicyDuplicateException
import com.bts.notification.application.NotificationPolicyForbiddenException
import com.bts.notification.application.NotificationPolicyNotFoundException
import com.bts.notification.application.NotificationPolicyService
import com.bts.notification.domain.Channel
import com.bts.notification.domain.NotificationEventType
import com.bts.notification.domain.NotificationPolicy
import com.bts.notification.domain.RecipientRole
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
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
 * NotificationPolicyController MockMvc 슬라이스 테스트.
 *
 * [NotificationPolicyService] 는 MockK stub 으로 대체한다.
 * Spring Security 컨텍스트는 [SecurityContextHolder] 에 직접 UUID 기반 Authentication 을 주입해 사용한다.
 *
 * ### 테스트 케이스
 * - CAT-1. GET /catalog — 10 eventTypes + 9 recipientRoles + 5 channels 반환 → 200
 * - CAT-2. GET /catalog — publishable 메타 포함 확인
 * - LIST-1. GET ?projectKey=ATLAS — service.list 에 actorId 가 전달됨 → 200
 * - LIST-2. GET (projectKey 없음) — 전역 정책 목록 조회 → 200
 * - CREATE-1. POST 정상 → 201
 * - CREATE-2. POST 잘못된 eventType enum → 400
 * - CREATE-3. POST 잘못된 recipientRole enum → 400
 * - CREATE-4. POST 잘못된 channel enum → 400
 * - CREATE-5. POST service가 ForbiddenException → 403
 * - CREATE-6. POST service가 DuplicateException → 409
 * - TOGGLE-1. PATCH /{id} 정상 → 204
 * - TOGGLE-2. PATCH /{id} service가 NotFoundException → 404
 * - DELETE-1. DELETE /{id} 정상 → 204
 * - DELETE-2. DELETE /{id} service가 NotFoundException → 404
 * - ACTOR-1. 비인증 요청 → 401
 */
@ExtendWith(SpringExtension::class)
@ContextConfiguration(classes = [NotificationPolicyControllerTest.TestMvcConfig::class])
@WebAppConfiguration
class NotificationPolicyControllerTest {
    /**
     * 테스트 전용 Spring MVC 최소 컨텍스트.
     *
     * [NotificationPolicyController], [NotificationExceptionHandler] 와 MockK stub Bean 을 등록한다.
     */
    @Configuration
    @EnableWebMvc
    open class TestMvcConfig {
        @Bean
        open fun notificationPolicyService(): NotificationPolicyService = mockk(relaxed = true)

        @Bean
        open fun notificationPolicyController(service: NotificationPolicyService): NotificationPolicyController =
            NotificationPolicyController(service)

        @Bean
        open fun notificationExceptionHandler(): NotificationExceptionHandler = NotificationExceptionHandler()
    }

    @Autowired
    lateinit var webApplicationContext: WebApplicationContext

    @Autowired
    lateinit var notificationPolicyService: NotificationPolicyService

    lateinit var mockMvc: MockMvc

    private val mapper: ObjectMapper = ObjectMapper().registerKotlinModule()

    private val actorId: UUID = UUID.fromString("11111111-1111-1111-1111-111111111111")
    private val fixedNow: Instant = Instant.parse("2026-06-11T00:00:00Z")

    @BeforeEach
    fun setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build()
        // actor 추출을 위한 SecurityContext 주입 — UUID 문자열이 authentication.name 이 된다
        val auth =
            UsernamePasswordAuthenticationToken(
                actorId.toString(),
                null,
                listOf(SimpleGrantedAuthority("ROLE_USER")),
            )
        SecurityContextHolder.getContext().authentication = auth
    }

    // ── CAT-1. GET /catalog — 10 eventTypes + 9 roles + 5 channels ───────────

    @Test
    fun `GET catalog — eventTypes 10개 recipientRoles 9개 channels 5개 반환`() {
        mockMvc.perform(get("/api/v1/notification-policies/catalog").accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.eventTypes.length()").value(NotificationEventType.entries.size))
            .andExpect(jsonPath("$.data.recipientRoles.length()").value(RecipientRole.entries.size))
            .andExpect(jsonPath("$.data.channels.length()").value(Channel.entries.size))
    }

    // ── CAT-2. GET /catalog — publishable 메타 포함 ──────────────────────────

    @Test
    fun `GET catalog — eventTypes 항목에 value와 publishable 필드 포함`() {
        mockMvc.perform(get("/api/v1/notification-policies/catalog").accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.eventTypes[0].value").exists())
            .andExpect(jsonPath("$.data.eventTypes[0].publishable").exists())
    }

    // ── LIST-1. GET ?projectKey=ATLAS — actorId 가 service에 전달됨 ──────────

    @Test
    fun `GET notification-policies projectKey=ATLAS — service에 actorId가 전달되고 200 반환`() {
        every { notificationPolicyService.list(actorId, "ATLAS") } returns listOf(samplePolicy())

        mockMvc.perform(
            get("/api/v1/notification-policies")
                .param("projectKey", "ATLAS")
                .accept(MediaType.APPLICATION_JSON),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data").isArray)

        verify { notificationPolicyService.list(actorId, "ATLAS") }
    }

    // ── LIST-2. GET (projectKey 없음) — 전역 정책 목록 ───────────────────────

    @Test
    fun `GET notification-policies projectKey 없음 — 전역 정책 목록 조회 200`() {
        every { notificationPolicyService.list(actorId, null) } returns emptyList()

        mockMvc.perform(
            get("/api/v1/notification-policies").accept(MediaType.APPLICATION_JSON),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data").isArray)

        verify { notificationPolicyService.list(actorId, null) }
    }

    // ── CREATE-1. POST 정상 → 201 ────────────────────────────────────────────

    @Test
    fun `POST notification-policies 정상 입력이면 201 + response body`() {
        val created = samplePolicy()
        every {
            notificationPolicyService.create(
                actorId,
                null,
                NotificationEventType.ISSUE_CREATED,
                RecipientRole.REPORTER,
                Channel.EMAIL,
                true,
            )
        } returns created

        val body =
            mapOf(
                "eventType" to "issue.created",
                "recipientRole" to "REPORTER",
                "channel" to "EMAIL",
                "enabled" to true,
            )

        mockMvc.perform(
            post("/api/v1/notification-policies")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.data.id").exists())
            .andExpect(jsonPath("$.data.eventType").value("issue.created"))
            .andExpect(jsonPath("$.data.enabled").value(true))
    }

    // ── CREATE-2. POST 잘못된 eventType enum → 400 ───────────────────────────

    @Test
    fun `POST notification-policies 잘못된 eventType이면 400`() {
        val body =
            mapOf(
                "eventType" to "not.a.valid.event",
                "recipientRole" to "REPORTER",
                "channel" to "EMAIL",
                "enabled" to true,
            )

        mockMvc.perform(
            post("/api/v1/notification-policies")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isBadRequest)
    }

    // ── CREATE-3. POST 잘못된 recipientRole enum → 400 ───────────────────────

    @Test
    fun `POST notification-policies 잘못된 recipientRole이면 400`() {
        val body =
            mapOf(
                "eventType" to "issue.created",
                "recipientRole" to "INVALID_ROLE",
                "channel" to "EMAIL",
                "enabled" to true,
            )

        mockMvc.perform(
            post("/api/v1/notification-policies")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isBadRequest)
    }

    // ── CREATE-4. POST 잘못된 channel enum → 400 ─────────────────────────────

    @Test
    fun `POST notification-policies 잘못된 channel이면 400`() {
        val body =
            mapOf(
                "eventType" to "issue.created",
                "recipientRole" to "REPORTER",
                "channel" to "INVALID_CHANNEL",
                "enabled" to true,
            )

        mockMvc.perform(
            post("/api/v1/notification-policies")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isBadRequest)
    }

    // ── CREATE-5. POST service ForbiddenException → 403 ─────────────────────

    @Test
    fun `POST notification-policies service가 ForbiddenException이면 403`() {
        every {
            notificationPolicyService.create(any(), any(), any(), any(), any(), any())
        } throws NotificationPolicyForbiddenException("알림 정책 관리 권한이 없습니다")

        val body =
            mapOf(
                "eventType" to "issue.created",
                "recipientRole" to "REPORTER",
                "channel" to "EMAIL",
                "enabled" to true,
            )

        mockMvc.perform(
            post("/api/v1/notification-policies")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.errorCode").value("NOTIF_FORBIDDEN"))
    }

    // ── CREATE-6. POST service DuplicateException → 409 ─────────────────────

    @Test
    fun `POST notification-policies service가 DuplicateException이면 409`() {
        every {
            notificationPolicyService.create(any(), any(), any(), any(), any(), any())
        } throws NotificationPolicyDuplicateException("동일한 알림 정책이 이미 존재합니다")

        val body =
            mapOf(
                "eventType" to "issue.created",
                "recipientRole" to "REPORTER",
                "channel" to "EMAIL",
                "enabled" to true,
            )

        mockMvc.perform(
            post("/api/v1/notification-policies")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.errorCode").value("NOTIF_POLICY_DUPLICATE"))
    }

    // ── TOGGLE-1. PATCH /{id} 정상 → 204 ─────────────────────────────────────

    @Test
    fun `PATCH notification-policies id 정상이면 204`() {
        val policyId = UUID.randomUUID()

        val body = mapOf("enabled" to false)

        mockMvc.perform(
            patch("/api/v1/notification-policies/$policyId")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isNoContent)
    }

    // ── TOGGLE-2. PATCH /{id} service NotFoundException → 404 ───────────────

    @Test
    fun `PATCH notification-policies id service가 NotFoundException이면 404`() {
        val policyId = UUID.randomUUID()
        every {
            notificationPolicyService.toggle(actorId, policyId, any())
        } throws NotificationPolicyNotFoundException(policyId)

        val body = mapOf("enabled" to false)

        mockMvc.perform(
            patch("/api/v1/notification-policies/$policyId")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.errorCode").value("NOTIF_POLICY_NOT_FOUND"))
    }

    // ── DELETE-1. DELETE /{id} 정상 → 204 ────────────────────────────────────

    @Test
    fun `DELETE notification-policies id 정상이면 204`() {
        val policyId = UUID.randomUUID()

        mockMvc.perform(delete("/api/v1/notification-policies/$policyId"))
            .andExpect(status().isNoContent)
    }

    // ── DELETE-2. DELETE /{id} service NotFoundException → 404 ──────────────

    @Test
    fun `DELETE notification-policies id service가 NotFoundException이면 404`() {
        val policyId = UUID.randomUUID()
        every {
            notificationPolicyService.delete(actorId, policyId)
        } throws NotificationPolicyNotFoundException(policyId)

        mockMvc.perform(delete("/api/v1/notification-policies/$policyId"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.errorCode").value("NOTIF_POLICY_NOT_FOUND"))
    }

    // ── ACTOR-1. 비인증 요청 → 401 ───────────────────────────────────────────

    @Test
    fun `GET notification-policies 비인증이면 401`() {
        SecurityContextHolder.clearContext()

        mockMvc.perform(get("/api/v1/notification-policies").accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isUnauthorized)
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun samplePolicy(): NotificationPolicy =
        NotificationPolicy(
            id = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
            projectKey = null,
            eventType = NotificationEventType.ISSUE_CREATED,
            recipientRole = RecipientRole.REPORTER,
            channel = Channel.EMAIL,
            enabled = true,
            createdBy = actorId,
            createdAt = fixedNow,
            updatedAt = fixedNow,
        )
}
